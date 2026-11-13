package com.example.host

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import com.example.core.DisplaySynthesizer
import com.example.core.KeystoreCrypto
import com.example.core.TrustedPeerStore
import com.example.display.DisplaySessionEngine
import com.example.model.DiscoveredPeer
import com.example.model.DisplayProfile
import com.example.model.InputBackendStatus
import com.example.model.PendingSasConfirmation
import com.example.model.PerformanceAdvisory
import com.example.model.ResolutionCeiling
import com.example.model.TrustedPeerRole
import com.example.model.VirtualDisplayConfig
import com.example.protocol.ControlCodec
import com.example.protocol.ControlMessage
import com.example.protocol.PairingCrypto
import com.example.protocol.TouchSample
import com.example.service.ExtendedDesktopService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class HostTelemetry(
    val connected: Boolean = false,
    val peerName: String = "",
    val config: VirtualDisplayConfig? = null,
    val displayProfile: DisplayProfile? = null,
    val virtualDisplayId: Int? = null,
    val fps: Int = 0,
    val kbps: Int = 0,
    val statusNote: String = "Idle",
    val activeDesktopApps: Set<String> = emptySet(),
    val advisory: PerformanceAdvisory = PerformanceAdvisory(),
    val inputBackendStatus: InputBackendStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY,
    val sessionOnlyTrustMode: Boolean = false,
    val securityNotice: String? = null
)

class HostSessionEngine(
    private val context: Context,
    private val peerStore: TrustedPeerStore,
    private val shizukuController: ShizukuController,
    private val crypto: KeystoreCrypto
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val advisor = HostPerformanceAdvisor(context)
    private var advisorJob: Job? = null

    private val _telemetry = MutableStateFlow(
        HostTelemetry(sessionOnlyTrustMode = crypto.isSessionOnlyDegraded())
    )
    val telemetry: StateFlow<HostTelemetry> = _telemetry.asStateFlow()

    private val _pendingSasConfirmation = MutableStateFlow<PendingSasConfirmation?>(null)
    val pendingSasConfirmation: StateFlow<PendingSasConfirmation?> = _pendingSasConfirmation.asStateFlow()

    var onHostForegroundPackagesUpdated: ((Set<String>) -> Unit)? = null

    private val socketLock = Any()
    private var teardownJob: Job? = null
    private var activeSessionToken = 0L
    private var nextSessionToken = 1L

    private var controlSocket: Socket? = null
    private var controlOut: DataOutputStream? = null
    private var videoServerSocket: ServerSocket? = null
    private var videoSocket: Socket? = null
    private var videoOut: DataOutputStream? = null

    private var encoder: H264StreamEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: VirtualDisplayPresentation? = null

    private val observedVirtualDisplayTouches = Collections.synchronizedList(mutableListOf<ObservedTouchEvent>())
    private val pendingProbeTimestamp = AtomicLong(0L)
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var currentConfig: VirtualDisplayConfig? = null
    private var currentProfile: DisplayProfile? = null

    init {
        shizukuController.onBinderLostMidStream = {
            handleShizukuLostMidStream()
        }
    }

    fun connectToPeer(
        peer: DiscoveredPeer,
        hostName: String,
        preferredCeiling: ResolutionCeiling? = null,
        forceTablet: Boolean? = null,
        dpiOverride: Int? = null,
        prefer24GhzBitrate: Boolean? = null
    ) {
        val myToken = synchronized(socketLock) { nextSessionToken++ }
        val priorTeardown = disconnectInternal("Restarting session", newActiveToken = myToken)
        scope.launch {
            priorTeardown?.join()
            if (synchronized(socketLock) { activeSessionToken != myToken }) {
                return@launch
            }
            try {
                _telemetry.value = HostTelemetry(
                    statusNote = "Connecting to ${peer.deviceName}…",
                    sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
                    inputBackendStatus = shizukuController.inputBackendStatus.value
                )
                val sock = Socket().apply {
                    tcpNoDelay = true
                    trafficClass = 0x10 // IPTOS_LOWDELAY
                    soTimeout = PairingCrypto.PRE_AUTH_IO_TIMEOUT_MS.toInt()
                    connect(InetSocketAddress(peer.ipAddress, peer.controlPort), 5000)
                }
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                val stillValid = synchronized(socketLock) {
                    if (activeSessionToken == myToken) {
                        controlSocket = sock
                        controlOut = out
                        true
                    } else {
                        false
                    }
                }
                if (!stillValid) {
                    runCatching { out.close() }
                    runCatching { sock.close() }
                    return@launch
                }

                val localId = peerStore.getOrCreateDeviceId()
                val savedPeer = peerStore.getPeer(peer.peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                val savedToken = if (!crypto.isSessionOnlyDegraded()) {
                    peerStore.getDecryptedToken(peer.peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                } else {
                    null
                }
                val expectSavedTrust = savedToken != null

                if (peer.isTrusted && savedToken == null) {
                    _telemetry.value = HostTelemetry(
                        statusNote = "Saved token unavailable — require fresh pairing verification",
                        sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
                        securityNotice = if (crypto.isSessionOnlyDegraded()) DisplaySessionEngine.SESSION_ONLY_WARNING else null
                    )
                }

                val hostNonce = PairingCrypto.generateNonce()
                val hostEcdh = PairingCrypto.generateEcdhKeyPair()
                val hostPubB64 = PairingCrypto.encodePublicKey(hostEcdh)

                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeRequest(
                            protocolVersion = PairingCrypto.PROTOCOL_VERSION,
                            minSupportedProtocolVersion = PairingCrypto.MIN_PROTOCOL_VERSION,
                            hostId = localId,
                            hostName = hostName,
                            hostNonce = hostNonce,
                            hostEcdhPubKey = hostPubB64,
                            expectSavedTrust = expectSavedTrust,
                            transportMode = peer.transportMode.name
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    disconnect("Handshake request write timed out")
                    return@launch
                }

                _telemetry.value = _telemetry.value.copy(
                    statusNote = if (expectSavedTrust) {
                        "Authenticating ${peer.deviceName} via HMAC…"
                    } else {
                        "Verify pairing code on ${peer.deviceName}…"
                    }
                )

                val firstFrame = ControlCodec.readNextFrame(inp)
                val firstMsg = (firstFrame as? ControlCodec.DecodedFrame.JsonControl)?.message
                if (firstMsg is ControlMessage.TrustApprovalResult) {
                    val neg = PairingCrypto.negotiateForHost(null, peer.deviceName, firstMsg.reason)
                    val reason = (neg as? PairingCrypto.ProtocolNegotiation.Incompatible)?.reason
                        ?: firstMsg.reason
                        ?: "Connection declined by Display"
                    _telemetry.value = HostTelemetry(
                        statusNote = reason,
                        sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
                        securityNotice = reason
                    )
                    disconnect(reason)
                    return@launch
                }

                val challenge = firstMsg as? ControlMessage.HandshakeChallenge
                if (challenge == null) {
                    disconnect("Unexpected handshake response from Display")
                    return@launch
                }

                val versionCheck = PairingCrypto.negotiateForHost(
                    negotiatedVersion = challenge.negotiatedProtocolVersion,
                    displayName = peer.deviceName,
                    rejectionReason = challenge.rejectionReason
                )
                if (versionCheck is PairingCrypto.ProtocolNegotiation.Incompatible) {
                    _telemetry.value = HostTelemetry(
                        statusNote = versionCheck.reason,
                        sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
                        securityNotice = versionCheck.reason
                    )
                    disconnect(versionCheck.reason)
                    return@launch
                }
                val agreedVersion = (versionCheck as PairingCrypto.ProtocolNegotiation.Agreed).version

                var ecdhSecret: ByteArray? = null
                var hostDegraded = crypto.isSessionOnlyDegraded() || challenge.sessionOnly
                var hostSecurityNotice: String? = if (hostDegraded) DisplaySessionEngine.SESSION_ONLY_WARNING else null

                val shouldAttemptSilentHmac = expectSavedTrust &&
                    savedToken != null &&
                    !challenge.requiresUserApproval &&
                    !challenge.sessionOnly

                var usedSilentHmac = false
                if (shouldAttemptSilentHmac && savedToken != null) {
                    // Anti-spoof verification: saved Display MUST prove possession of stored pairingToken
                    val displayIdentityMatches = challenge.peerId == peer.peerId &&
                        PairingCrypto.verifyDisplayProofHmac(
                            sharedToken = savedToken,
                            candidateHmac = challenge.displayProofHmac,
                            negotiatedVersion = agreedVersion,
                            hostNonce = hostNonce,
                            displayNonce = challenge.displayNonce,
                            hostId = localId,
                            displayPeerId = peer.peerId
                        )

                    if (!displayIdentityMatches) {
                        val spoofMsg =
                            "Peer failed HMAC identity check — possible mDNS spoof or tampered Display"
                        _telemetry.value = HostTelemetry(
                            statusNote = spoofMsg,
                            sessionOnlyTrustMode = hostDegraded,
                            securityNotice = spoofMsg
                        )
                        ControlCodec.writeJsonFrameBounded(
                            sock,
                            out,
                            ControlMessage.HandshakeAuthResponse(hostProofHmac = null, sasConfirmed = false),
                            PairingCrypto.COMMIT_WINDOW_MS
                        )
                        disconnect(spoofMsg)
                        return@launch
                    }

                    val hostProof = PairingCrypto.computeHostProofHmac(
                        sharedToken = savedToken,
                        negotiatedVersion = agreedVersion,
                        displayNonce = challenge.displayNonce,
                        hostNonce = hostNonce,
                        hostId = localId,
                        displayPeerId = peer.peerId
                    )
                    if (!ControlCodec.writeJsonFrameBounded(
                            sock,
                            out,
                            ControlMessage.HandshakeAuthResponse(
                                hostProofHmac = hostProof,
                                sasConfirmed = false
                            ),
                            PairingCrypto.COMMIT_WINDOW_MS
                        )
                    ) {
                        disconnect("Auth response timed out")
                        return@launch
                    }
                    usedSilentHmac = true
                } else {
                    // Initial pairing OR Trust-Mismatch SAS Fallback
                    val displayPubB64 = challenge.displayEcdhPubKey
                    if (displayPubB64.isNullOrBlank()) {
                        disconnect("Display did not provide an ECDH public key")
                        return@launch
                    }

                    // If prior or current Display persistence failed, clear unbacked Host record under Mutex
                    if (challenge.sessionOnly || challenge.previousPersistFailed) {
                        peerStore.removePeer(peer.peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                    }

                    val mismatchWarning = when {
                        (challenge.sessionOnly || challenge.previousPersistFailed) && expectSavedTrust ->
                            "Display could not save Keystore trust on a previous or current session — verify this 6-digit code matches Display to pair."
                        expectSavedTrust && savedPeer?.pendingCommitFinal == true ->
                            "Previous final confirmation did not reach Display — Display did not save trust. Verify this 6-digit code matches Display to re-pair."
                        expectSavedTrust ->
                            "Display no longer has your saved key (storage may have been cleared) — verify this 6-digit code matches Display to re-pair."
                        else -> null
                    }
                    if (mismatchWarning != null) {
                        hostSecurityNotice = mismatchWarning
                    }

                    val sasTimeoutMs = PairingCrypto.clampSasTimeoutMs(challenge.sasTimeoutMs)
                    val sasDeadlineMs = System.currentTimeMillis() + sasTimeoutMs
                    sock.soTimeout = sasTimeoutMs.toInt()

                    val derived = PairingCrypto.deriveEcdhSharedSecret(hostEcdh.private, displayPubB64)
                    ecdhSecret = derived
                    val sasCode = PairingCrypto.deriveSasCode(
                        ecdhSharedSecret = derived,
                        negotiatedVersion = agreedVersion,
                        hostNonce = hostNonce,
                        displayNonce = challenge.displayNonce,
                        hostPubKeyB64 = hostPubB64,
                        displayPubKeyB64 = displayPubB64
                    )

                    val sasDecision = CompletableDeferred<Boolean>()
                    _pendingSasConfirmation.value = PendingSasConfirmation(
                        peerId = challenge.peerId,
                        peerName = peer.deviceName,
                        sasCode = sasCode,
                        isSessionOnlyMode = hostDegraded,
                        mismatchWarning = mismatchWarning,
                        sasDeadlineMs = sasDeadlineMs,
                        sasTimeoutMs = sasTimeoutMs,
                        onConfirmMatch = { sasDecision.complete(true) },
                        onRejectMismatch = { sasDecision.complete(false) }
                    )
                    _telemetry.value = _telemetry.value.copy(
                        statusNote = "Confirm code $sasCode matches ${peer.deviceName}",
                        securityNotice = mismatchWarning
                    )

                    val remainingMs = (sasDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1L)
                    val sasConfirmed = withTimeoutOrNull(remainingMs) { sasDecision.await() } ?: false
                    _pendingSasConfirmation.value = null

                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeAuthResponse(
                            hostProofHmac = null,
                            sasConfirmed = sasConfirmed
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    if (!sasConfirmed) {
                        val abortMsg = "SAS verification declined or timed out — pairing aborted"
                        _telemetry.value = HostTelemetry(
                            statusNote = abortMsg,
                            sessionOnlyTrustMode = hostDegraded,
                            securityNotice = abortMsg
                        )
                        disconnect(abortMsg)
                        return@launch
                    }
                }

                // Read Display's TrustApprovalResult within remaining SAS / pre-auth timeout
                val approvalFrame = ControlCodec.readNextFrame(inp)
                val approval = (approvalFrame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.TrustApprovalResult
                if (approval == null || !approval.approved || approval.profile == null) {
                    val reason = approval?.reason ?: "Connection declined by Display"
                    _telemetry.value = HostTelemetry(
                        statusNote = reason,
                        sessionOnlyTrustMode = hostDegraded,
                        securityNotice = reason
                    )
                    disconnect(reason)
                    return@launch
                }

                // Unseal new token strictly into RAM (do NOT persist until Display confirms commit)
                val unsealedToken: String? = if (!usedSilentHmac) {
                    val sharedSecret = ecdhSecret
                    if (sharedSecret != null) {
                        PairingCrypto.unsealInitialToken(
                            ecdhSharedSecret = sharedSecret,
                            hostNonce = hostNonce,
                            displayNonce = challenge.displayNonce,
                            sealedCipherB64 = approval.sealedPairingToken,
                            macTagB64 = approval.sealedPairingTag
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }

                val profile = approval.profile
                currentProfile = profile
                val config = DisplaySynthesizer.synthesize(
                    profile = profile,
                    ceiling = preferredCeiling ?: DisplaySynthesizer.defaultCeilingForProfile(profile),
                    forceTabletLayout = forceTablet ?: profile.isTabletClass,
                    manualDpiOverride = dpiOverride,
                    prefer24GhzBitrate = prefer24GhzBitrate ?: !profile.supports5GhzWifi
                )
                currentConfig = config

                val vServer = ServerSocket(0).apply {
                    soTimeout = PairingCrypto.VIDEO_ACCEPT_TIMEOUT_MS.toInt()
                }
                val videoPort = vServer.localPort
                val vServerAccepted = synchronized(socketLock) {
                    if (activeSessionToken == myToken) {
                        videoServerSocket = vServer
                        true
                    } else {
                        false
                    }
                }
                if (!vServerAccepted) {
                    runCatching { vServer.close() }
                    return@launch
                }
                val desktopMode = if (shizukuController.isAuthorized()) "SHIZUKU_FULL" else "LAYOUT_PREVIEW"

                // Commit Step 1: Host sends HandshakeAck (bounded write)
                sock.soTimeout = PairingCrypto.COMMIT_WINDOW_MS.toInt()
                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeAck(
                            config = config,
                            videoPort = videoPort,
                            desktopMode = desktopMode
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    val notice = "Pairing commit stalled sending stream ACK — trust was not saved; please re-pair via 6-digit code."
                    _telemetry.value = HostTelemetry(statusNote = notice, securityNotice = notice)
                    disconnect(notice)
                    return@launch
                }

                // Commit Step 2: Await Display's HandshakeCommitAck within COMMIT_WINDOW_MS
                val commitAckFrame = runCatching { ControlCodec.readNextFrame(inp) }.getOrNull()
                val commitAck = (commitAckFrame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeCommitAck
                if (commitAck == null) {
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeCommitFinal(
                            accepted = false,
                            reason = "Commit acknowledgement timed out — please re-pair via 6-digit code."
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    val notice = "Pairing commit timed out awaiting Display ACK — trust was not saved; please re-pair via 6-digit code."
                    _telemetry.value = HostTelemetry(statusNote = notice, securityNotice = notice)
                    disconnect(notice)
                    return@launch
                }

                if (!commitAck.committed) {
                    peerStore.clearPendingCommitFinal(challenge.peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeCommitFinal(
                            accepted = false,
                            reason = "Display canceled pairing before commit — trust was not saved."
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    val notice = "Display canceled pairing before commit — trust was not saved."
                    _telemetry.value = HostTelemetry(statusNote = notice, securityNotice = notice)
                    disconnect(notice)
                    return@launch
                }

                // Commit Step 3: Persist or confirm peer state atomically under Mutex BEFORE sending HandshakeCommitFinal
                if (usedSilentHmac) {
                    // Silent mutual HMAC proved Display already holds valid trust; safe to clear pendingCommitFinal
                    peerStore.clearPendingCommitFinal(challenge.peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                    peerStore.updatePeerEndpoint(
                        peerId = challenge.peerId,
                        peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
                        ip = peer.ipAddress,
                        port = peer.controlPort
                    )
                } else if (!commitAck.sessionOnly && !unsealedToken.isNullOrBlank()) {
                    val saved = peerStore.trustPeer(
                        peerId = challenge.peerId,
                        deviceName = peer.deviceName,
                        rawToken = unsealedToken,
                        peerRole = TrustedPeerRole.HOST_SAVED_DISPLAY,
                        ip = peer.ipAddress,
                        port = peer.controlPort,
                        profile = profile,
                        pendingCommitFinal = true
                    )
                    if (saved == null || crypto.isSessionOnlyDegraded()) {
                        hostDegraded = true
                        hostSecurityNotice = DisplaySessionEngine.SESSION_ONLY_WARNING
                    }
                }

                // Send final authoritative confirmation via bounded write.
                // Intentionally KEEP pendingCommitFinal = true even if write returns true; only confirmed clear on next session proof or error.
                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeCommitFinal(accepted = true),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    val notice = "Final confirmation did not reach Display — Display did not save trust; confirm the 6-digit code on next connect to finish re-pairing."
                    _telemetry.value = HostTelemetry(
                        statusNote = notice,
                        sessionOnlyTrustMode = hostDegraded,
                        securityNotice = notice
                    )
                    disconnect(notice)
                    return@launch
                }

                sock.soTimeout = PairingCrypto.STREAM_SO_TIMEOUT_MS.toInt()

                val initialAdvisory = advisor.sample(
                    displayAppsCount = 0,
                    shizukuAuthorized = shizukuController.isAuthorized()
                )
                ExtendedDesktopService.startHost(context, initialAdvisory)

                val vSock = vServer.accept().apply {
                    tcpNoDelay = true
                }
                val vOut = DataOutputStream(vSock.getOutputStream())
                val streamStillActive = synchronized(socketLock) {
                    if (activeSessionToken == myToken) {
                        videoSocket = vSock
                        videoOut = vOut
                        true
                    } else {
                        false
                    }
                }
                if (!streamStillActive) {
                    runCatching { vOut.close() }
                    runCatching { vSock.close() }
                    return@launch
                }

                startVirtualDisplayAndEncoder(config, desktopMode)

                _telemetry.value = HostTelemetry(
                    connected = true,
                    peerName = peer.deviceName,
                    config = config,
                    displayProfile = profile,
                    virtualDisplayId = virtualDisplay?.display?.displayId,
                    statusNote = if (desktopMode == "SHIZUKU_FULL") "Active • Shizuku Multi-Display" else "Active • Layout Preview",
                    advisory = initialAdvisory,
                    inputBackendStatus = shizukuController.inputBackendStatus.value,
                    sessionOnlyTrustMode = hostDegraded,
                    securityNotice = hostSecurityNotice
                )
                startAdvisorLoop()

                readControlLoop(inp, out, challenge.peerId, myToken)
            } catch (t: Throwable) {
                if (synchronized(socketLock) { activeSessionToken == myToken }) {
                    val msg = "Connection error: ${t.message ?: "unreachable"}"
                    _telemetry.value = HostTelemetry(
                        statusNote = msg,
                        sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
                        securityNotice = _telemetry.value.securityNotice ?: msg
                    )
                    disconnect(msg)
                }
            }
        }
    }

    fun updateDisplaySynthesis(
        ceiling: ResolutionCeiling,
        forceTabletLayout: Boolean,
        manualDpiOverride: Int?,
        prefer24GhzBitrate: Boolean
    ) {
        val profile = currentProfile ?: return
        val newConfig = DisplaySynthesizer.synthesize(
            profile = profile,
            ceiling = ceiling,
            forceTabletLayout = forceTabletLayout,
            manualDpiOverride = manualDpiOverride,
            prefer24GhzBitrate = prefer24GhzBitrate
        )
        currentConfig = newConfig
        _telemetry.value = _telemetry.value.copy(config = newConfig)

        val modeLabel = if (shizukuController.isAuthorized()) "SHIZUKU FULL" else "LAYOUT PREVIEW"
        mainHandler.post {
            virtualDisplay?.resize(newConfig.targetWidth, newConfig.targetHeight, newConfig.targetDpi)
            presentation?.updateConfig(newConfig, modeLabel)
        }
        val out = controlOut ?: return
        scope.launch {
            try {
                ControlCodec.writeJsonFrame(out, ControlMessage.DisplayOverride(newConfig))
            } catch (_: Throwable) {
            }
        }
    }

    fun sendTrackpadDelta(dxNorm: Float, dyNorm: Float, action: Byte) {
        cursorX = (cursorX + dxNorm).coerceIn(0f, 1f)
        cursorY = (cursorY + dyNorm).coerceIn(0f, 1f)
        val label = when (action) {
            TouchSample.ACTION_DOWN -> "Click"
            TouchSample.ACTION_UP -> "Release"
            TouchSample.ACTION_RIGHT_CLICK -> "Right Click"
            else -> "Trackpad"
        }
        presentation?.updateCursor(cursorX, cursorY, label)
        val displayId = virtualDisplay?.display?.displayId
        val cfg = currentConfig
        if (displayId != null && cfg != null && shizukuController.isAuthorized()) {
            shizukuController.injectTouchOnDisplay(
                displayId = displayId,
                action = action,
                xPx = cursorX * cfg.targetWidth,
                yPx = cursorY * cfg.targetHeight
            )
            _telemetry.value = _telemetry.value.copy(
                inputBackendStatus = shizukuController.inputBackendStatus.value
            )
        }
        encoder?.requestSyncFrameBurst()
    }

    fun sendImeInput(textInput: String?, keyCode: Int) {
        presentation?.appendImeText(textInput, keyCode)
        val displayId = virtualDisplay?.display?.displayId
        if (displayId != null && shizukuController.isAuthorized()) {
            shizukuController.injectKeyOrTextOnDisplay(displayId, textInput, keyCode)
        }
        encoder?.requestSyncFrameBurst()
    }

    fun launchAppExclusive(packageName: String): String {
        val displayId = virtualDisplay?.display?.displayId ?: return "No virtual display active"
        if (!shizukuController.isAuthorized()) {
            return "Third-party app launch requires Shizuku. Use Layout Preview panels or activate Shizuku."
        }
        val launched = shizukuController.launchAppOnDisplay(packageName, displayId)
        return if (launched) {
            val updated = _telemetry.value.activeDesktopApps + packageName
            val nextAdvisory = advisor.sample(
                displayAppsCount = updated.size,
                shizukuAuthorized = shizukuController.isAuthorized()
            )
            _telemetry.value = _telemetry.value.copy(
                activeDesktopApps = updated,
                statusNote = "Moved $packageName to Display $displayId",
                advisory = nextAdvisory,
                inputBackendStatus = shizukuController.inputBackendStatus.value
            )
            ExtendedDesktopService.updateHostNotification(context, nextAdvisory)
            encoder?.requestSyncFrameBurst()
            "Moved $packageName to Extended Display"
        } else {
            "Unable to launch $packageName on Display $displayId"
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun seedActiveDesktopAppsForTest(pkgs: Set<String>, connected: Boolean) {
        _telemetry.value = _telemetry.value.copy(
            connected = connected,
            activeDesktopApps = pkgs,
            advisory = advisor.sample(displayAppsCount = pkgs.size, shizukuAuthorized = false)
        )
    }

    fun markAppMovedBackToHost(packageName: String) {
        val current = _telemetry.value
        if (!current.activeDesktopApps.contains(packageName)) return
        val updated = current.activeDesktopApps - packageName
        val nextAdvisory = advisor.sample(
            displayAppsCount = updated.size,
            shizukuAuthorized = shizukuController.isAuthorized()
        )
        _telemetry.value = current.copy(
            activeDesktopApps = updated,
            statusNote = if (current.connected) "Moved $packageName back to Host" else current.statusNote,
            advisory = nextAdvisory,
            inputBackendStatus = shizukuController.inputBackendStatus.value
        )
        if (current.connected) {
            ExtendedDesktopService.updateHostNotification(context, nextAdvisory)
        }
    }

    /**
     * Runs the 2s baseline + VirtualDisplay target receipt gate using REAL observed MotionEvents
     * from VirtualDisplayPresentation (`displayId != 0`). If no active peer VirtualDisplay exists,
     * creates a temporary local diagnostic VirtualDisplay + Presentation and tears it down
     * immediately after the 1.5s receipt window. Never synthesizes mock virtual-display hits.
     */
    suspend fun runInputReceiptGateDiagnostic(
        collectBaselineDisplay0: () -> List<ObservedTouchEvent> = { emptyList() },
        clearDisplay0Buffer: () -> Unit = {},
        collectPostDisplay0: (startMs: Long) -> List<ObservedTouchEvent> = { emptyList() }
    ): InputReceiptEvaluation {
        var tempReader: ImageReader? = null
        var tempVd: VirtualDisplay? = null
        var tempPres: VirtualDisplayPresentation? = null
        val createdTemporaryDisplay: Boolean

        val activeVd = virtualDisplay
        val activePres = presentation
        val diagPresentation: VirtualDisplayPresentation?
        val diagDisplayId: Int

        if (activeVd != null && activePres != null) {
            createdTemporaryDisplay = false
            diagPresentation = activePres
            diagDisplayId = activeVd.display.displayId
        } else {
            createdTemporaryDisplay = true
            val created = createTemporaryDiagnosticDisplay()
            tempReader = created.reader
            tempVd = created.virtualDisplay
            tempPres = created.presentation
            diagPresentation = created.presentation
            diagDisplayId = created.virtualDisplay?.display?.displayId ?: 1
        }

        try {
            observedVirtualDisplayTouches.clear()
            clearDisplay0Buffer()
            val tStart = System.currentTimeMillis()
            delay(HardwareVerificationRunner.BASELINE_WINDOW_MS)
            val baselineDisplay0 = collectBaselineDisplay0()
            clearDisplay0Buffer()

            val bounds = diagPresentation?.getVerificationTargetBounds()
            val targetWidthPx = bounds?.widthPx ?: 144
            val targetHeightPx = bounds?.heightPx ?: 144
            val injectXPx = bounds?.centerXPx ?: 400f
            val injectYPx = bounds?.centerYPx ?: 300f
            val targetDisplayId = bounds?.displayId ?: diagDisplayId

            val tInject = System.currentTimeMillis()
            if (diagVdReady(diagPresentation, createdTemporaryDisplay, tempVd) && shizukuController.isAuthorized()) {
                shizukuController.injectTouchOnDisplay(
                    displayId = targetDisplayId,
                    action = TouchSample.ACTION_DOWN,
                    xPx = injectXPx,
                    yPx = injectYPx
                )
                shizukuController.injectTouchOnDisplay(
                    displayId = targetDisplayId,
                    action = TouchSample.ACTION_UP,
                    xPx = injectXPx,
                    yPx = injectYPx
                )
            }

            delay(HardwareVerificationRunner.RECEIPT_WINDOW_MS)
            val postDisplay0 = collectPostDisplay0(tStart)
            val realVdEvents = synchronized(observedVirtualDisplayTouches) {
                observedVirtualDisplayTouches.filter { it.displayId == targetDisplayId }
            }

            return HardwareVerificationRunner.evaluateInputReceiptGate(
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
                injectedXPx = injectXPx,
                injectedYPx = injectYPx,
                tInjectMs = tInject,
                baselineDisplay0Events = baselineDisplay0,
                virtualDisplayEvents = realVdEvents,
                display0Events = postDisplay0
            )
        } finally {
            if (createdTemporaryDisplay) {
                releaseTemporaryDiagnosticDisplay(tempPres, tempVd, tempReader)
            }
        }
    }

    private fun diagVdReady(
        pres: VirtualDisplayPresentation?,
        isTemp: Boolean,
        tempVd: VirtualDisplay?
    ): Boolean = if (isTemp) (tempVd != null && pres != null) else (virtualDisplay != null && pres != null)

    private data class TempDiagnosticDisplay(
        val reader: ImageReader?,
        val virtualDisplay: VirtualDisplay?,
        val presentation: VirtualDisplayPresentation?
    )

    private suspend fun createTemporaryDiagnosticDisplay(): TempDiagnosticDisplay {
        val deferred = CompletableDeferred<TempDiagnosticDisplay>()
        val spawn = Runnable {
            try {
                val width = 800
                val height = 600
                val dpi = 160
                val cfg = VirtualDisplayConfig(
                    targetWidth = width,
                    targetHeight = height,
                    targetDpi = dpi,
                    refreshRateFps = 60,
                    bitrateMbps = 4,
                    forceTabletLayout = true,
                    resolutionCeiling = ResolutionCeiling.P800,
                    is24GhzAdapted = false
                )
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val vd = dm.createVirtualDisplay(
                    "ExtDeskDiagVD",
                    width,
                    height,
                    dpi,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
                val pres = VirtualDisplayPresentation(
                    context = context,
                    display = vd.display,
                    config = cfg,
                    modeLabel = "DIAGNOSTIC PROBE",
                    onVerificationTouchObserved = { ev -> observedVirtualDisplayTouches.add(ev) }
                )
                pres.show()
                deferred.complete(TempDiagnosticDisplay(reader, vd, pres))
            } catch (_: Throwable) {
                deferred.complete(TempDiagnosticDisplay(null, null, null))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            spawn.run()
        } else {
            mainHandler.post(spawn)
        }
        return withTimeoutOrNull(2_000L) { deferred.await() } ?: TempDiagnosticDisplay(null, null, null)
    }

    private fun releaseTemporaryDiagnosticDisplay(
        pres: VirtualDisplayPresentation?,
        vd: VirtualDisplay?,
        reader: ImageReader?
    ) {
        val cleanup = Runnable {
            runCatching { pres?.dismiss() }
            runCatching { vd?.release() }
            runCatching { reader?.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    private fun startAdvisorLoop() {
        advisorJob?.cancel()
        advisorJob = scope.launch {
            while (isActive && _telemetry.value.connected) {
                val appCount = _telemetry.value.activeDesktopApps.size
                val sample = advisor.sample(
                    displayAppsCount = appCount,
                    shizukuAuthorized = shizukuController.isAuthorized()
                )
                val hostForeground = if (shizukuController.isAuthorized()) {
                    shizukuController.queryActiveHostPackages()
                } else {
                    emptySet()
                }
                onHostForegroundPackagesUpdated?.invoke(hostForeground)
                _telemetry.value = _telemetry.value.copy(
                    advisory = sample,
                    inputBackendStatus = shizukuController.inputBackendStatus.value
                )
                ExtendedDesktopService.updateHostNotification(context, sample)
                delay(3000L)
            }
        }
    }

    fun handleShizukuLostMidStream() {
        if (!_telemetry.value.connected) return
        val cfg = currentConfig ?: return
        mainHandler.post {
            presentation?.updateConfig(cfg, "LAYOUT PREVIEW (RECOVERED)")
        }
        _telemetry.value = _telemetry.value.copy(
            statusNote = "Shizuku stopped — seamlessly switched to Layout Preview",
            inputBackendStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY
        )
        val out = synchronized(socketLock) { controlOut } ?: return
        scope.launch {
            try {
                ControlCodec.writeJsonFrame(
                    out,
                    ControlMessage.ModeChange(
                        reason = "SHIZUKU_LOST",
                        newMode = "LAYOUT_PREVIEW"
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun disconnect(reason: String = "User disconnected") {
        disconnectInternal(reason, newActiveToken = 0L)
    }

    private fun disconnectInternal(reason: String, newActiveToken: Long): Job? {
        advisorJob?.cancel()
        advisorJob = null
        _pendingSasConfirmation.value = null

        val oldCtrlOut: DataOutputStream?
        val oldCtrlSock: Socket?
        val oldVidOut: DataOutputStream?
        val oldVidSock: Socket?
        val oldVidSrv: ServerSocket?
        val oldEncoder: H264StreamEncoder?
        val oldPres: VirtualDisplayPresentation?
        val oldVd: VirtualDisplay?

        synchronized(socketLock) {
            activeSessionToken = newActiveToken
            oldCtrlOut = controlOut
            oldCtrlSock = controlSocket
            oldVidOut = videoOut
            oldVidSock = videoSocket
            oldVidSrv = videoServerSocket
            oldEncoder = encoder
            oldPres = presentation
            oldVd = virtualDisplay
            controlOut = null
            controlSocket = null
            videoOut = null
            videoSocket = null
            videoServerSocket = null
            encoder = null
            presentation = null
            virtualDisplay = null
        }

        mainHandler.post {
            runCatching { oldPres?.dismiss() }
            runCatching { oldVd?.release() }
        }

        ExtendedDesktopService.stop(context)
        _telemetry.value = HostTelemetry(
            statusNote = reason,
            inputBackendStatus = shizukuController.inputBackendStatus.value,
            sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
            securityNotice = _telemetry.value.securityNotice
        )

        val hasResourcesToClose = oldEncoder != null ||
            oldCtrlOut != null ||
            oldCtrlSock != null ||
            oldVidOut != null ||
            oldVidSock != null ||
            oldVidSrv != null

        if (!hasResourcesToClose) {
            return synchronized(socketLock) { teardownJob }
        }

        val job = scope.launch(Dispatchers.IO) {
            runCatching { oldEncoder?.stop() }
            if (oldCtrlSock != null && oldCtrlOut != null) {
                ControlCodec.writeJsonFrameBounded(
                    oldCtrlSock,
                    oldCtrlOut,
                    ControlMessage.DisconnectMsg(reason),
                    PairingCrypto.COMMIT_WINDOW_MS
                )
            }
            runCatching { oldVidOut?.close() }
            runCatching { oldVidSock?.close() }
            runCatching { oldVidSrv?.close() }
            runCatching { oldCtrlOut?.close() }
            runCatching { oldCtrlSock?.close() }
        }
        synchronized(socketLock) {
            teardownJob = job
        }
        return job
    }

    private fun startVirtualDisplayAndEncoder(config: VirtualDisplayConfig, modeLabel: String) {
        val enc = H264StreamEncoder(
            width = config.targetWidth,
            height = config.targetHeight,
            bitrateMbps = config.bitrateMbps,
            fps = config.refreshRateFps,
            onEncodedFrame = { nalBytes, _ ->
                val vOut = synchronized(socketLock) { videoOut } ?: return@H264StreamEncoder
                val probeTs = pendingProbeTimestamp.getAndSet(0L)
                try {
                    H264StreamEncoder.writeLengthPrefixedFrame(vOut, nalBytes, probeTs)
                } catch (_: Throwable) {
                }
            },
            onMetrics = { fps, kbps ->
                _telemetry.value = _telemetry.value.copy(fps = fps, kbps = kbps)
            }
        )
        encoder = enc
        val surface = enc.start()

        mainHandler.post {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(
                "ExtendedDesktopDisplay",
                config.targetWidth,
                config.targetHeight,
                config.targetDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd
            val pres = VirtualDisplayPresentation(
                context = context,
                display = vd.display,
                config = config,
                modeLabel = modeLabel,
                onVerificationTouchObserved = { ev -> observedVirtualDisplayTouches.add(ev) }
            )
            pres.show()
            presentation = pres
            _telemetry.value = _telemetry.value.copy(virtualDisplayId = vd.display.displayId)
        }
    }

    private fun readControlLoop(
        inp: DataInputStream,
        out: DataOutputStream,
        peerId: String,
        sessionToken: Long
    ) {
        val reusableSample = TouchSample()
        while (_telemetry.value.connected &&
            synchronized(socketLock) { activeSessionToken == sessionToken }
        ) {
            when (val frame = ControlCodec.readNextFrame(inp)) {
                is ControlCodec.DecodedFrame.BinaryTouch -> {
                    if (TouchSample.readFromBytes(frame.bytes, reusableSample)) {
                        cursorX = reusableSample.normX.coerceIn(0f, 1f)
                        cursorY = reusableSample.normY.coerceIn(0f, 1f)
                        val actionStr = when (reusableSample.action) {
                            TouchSample.ACTION_DOWN -> "Down"
                            TouchSample.ACTION_UP -> "Up"
                            else -> "Move"
                        }
                        presentation?.updateCursor(cursorX, cursorY, actionStr)
                        val displayId = virtualDisplay?.display?.displayId
                        val cfg = currentConfig
                        if (displayId != null && cfg != null && shizukuController.isAuthorized()) {
                            shizukuController.injectTouchOnDisplay(
                                displayId = displayId,
                                action = reusableSample.action,
                                xPx = cursorX * cfg.targetWidth,
                                yPx = cursorY * cfg.targetHeight
                            )
                        }
                        encoder?.requestSyncFrameBurst()
                    }
                }
                is ControlCodec.DecodedFrame.JsonControl -> {
                    when (val msg = frame.message) {
                        is ControlMessage.LatencyProbe -> {
                            pendingProbeTimestamp.set(msg.timestampMs)
                            encoder?.requestSyncFrameBurst()
                        }
                        is ControlMessage.HeartbeatPing -> {
                            ControlCodec.writeJsonFrame(out, ControlMessage.HeartbeatAck(msg.timestampMs))
                        }
                        is ControlMessage.HandshakeCommitPersistError -> {
                            scope.launch {
                                peerStore.removePeer(peerId, TrustedPeerRole.HOST_SAVED_DISPLAY)
                            }
                            _telemetry.value = _telemetry.value.copy(
                                sessionOnlyTrustMode = msg.sessionOnlyFallback,
                                securityNotice = msg.reason
                            )
                        }
                        is ControlMessage.DisconnectMsg -> {
                            if (synchronized(socketLock) { activeSessionToken == sessionToken }) {
                                disconnect(msg.reason)
                            }
                            break
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
