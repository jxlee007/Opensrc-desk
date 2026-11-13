package com.example.display

import android.content.Context
import android.view.Surface
import com.example.core.KeystoreCrypto
import com.example.core.TrustedPeerStore
import com.example.model.DisplayProfile
import com.example.model.DisplayStreamOverlayState
import com.example.model.LatencyProbeStats
import com.example.model.PendingTrustRequest
import com.example.model.TrustedPeerRole
import com.example.model.VirtualDisplayConfig
import com.example.protocol.ControlCodec
import com.example.protocol.ControlMessage
import com.example.protocol.PairingCrypto
import com.example.service.ExtendedDesktopService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class DisplaySessionState(
    val listeningPort: Int = 0,
    val isConnected: Boolean = false,
    val hostName: String = "",
    val hostIp: String = "",
    val videoPort: Int = 0,
    val activeConfig: VirtualDisplayConfig? = null,
    val overlayState: DisplayStreamOverlayState = DisplayStreamOverlayState.Active,
    val fps: Int = 0,
    val latencyStats: LatencyProbeStats = LatencyProbeStats(),
    val sessionOnlyTrustMode: Boolean = false,
    val securityNotice: String? = null
)

class DisplaySessionEngine(
    private val context: Context,
    private val peerStore: TrustedPeerStore,
    private val crypto: KeystoreCrypto
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow(DisplaySessionState())
    val sessionState: StateFlow<DisplaySessionState> = _sessionState.asStateFlow()

    private val _pendingTrust = MutableStateFlow<PendingTrustRequest?>(null)
    val pendingTrust: StateFlow<PendingTrustRequest?> = _pendingTrust.asStateFlow()

    val touchDispatcher = TouchCoalescingDispatcher()
    private val decoder = H264StreamDecoder(
        onFirstOrResumedFrame = {
            if (_sessionState.value.overlayState !is DisplayStreamOverlayState.Active) {
                touchDispatcher.setTransitionPaused(false)
                _sessionState.value = _sessionState.value.copy(overlayState = DisplayStreamOverlayState.Active)
            }
        },
        onLatencyStats = { stats ->
            _sessionState.value = _sessionState.value.copy(latencyStats = stats)
        },
        onStreamMetrics = { fps ->
            _sessionState.value = _sessionState.value.copy(fps = fps)
        }
    )

    private val preAuthMutex = Mutex()
    private var globalPreAuthCount = 0
    private val perIpPreAuthCounts = mutableMapOf<String, Int>()
    private val trustQueue = ArrayDeque<QueuedTrustEntry>()
    private var nextQueueSeq = 1L

    private data class QueuedTrustEntry(
        val entryId: Long,
        val hostIp: String,
        val request: PendingTrustRequest,
        val decision: CompletableDeferred<Boolean>,
        val onDequeued: () -> Unit
    )

    internal var sasTimeoutOverrideMs: Long? = null

    internal suspend fun preAuthCountsSnapshot(): Pair<Int, Map<String, Int>> = preAuthMutex.withLock {
        globalPreAuthCount to perIpPreAuthCounts.toMap()
    }

    internal suspend fun trustQueueSizeSnapshot(): Int = preAuthMutex.withLock {
        trustQueue.size
    }

    private val sessionStateLock = Any()
    private var activeSessionActive = false
    private var activeSessionToken = 0L
    private var nextSessionToken = 1L
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeControlSocket: Socket? = null
    private var activeControlOut: DataOutputStream? = null
    private var lastHeartbeatAckMs = 0L
    private var outputSurface: Surface? = null
    private var localProfile: DisplayProfile? = null

    fun startListening(profile: DisplayProfile): Int {
        localProfile = profile
        synchronized(sessionStateLock) {
            val existing = serverSocket
            if (existing != null && !existing.isClosed) {
                return existing.localPort
            }
        }
        val srv = bindStableControlSocket()
        synchronized(sessionStateLock) {
            serverSocket = srv
        }
        val port = srv.localPort
        _sessionState.value = _sessionState.value.copy(
            listeningPort = port,
            sessionOnlyTrustMode = crypto.isSessionOnlyDegraded()
        )

        acceptJob = scope.launch {
            while (isActive && !srv.isClosed) {
                try {
                    val sock = srv.accept().apply { tcpNoDelay = true }
                    launch {
                        handleIncomingHost(sock, profile)
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return port
    }

    private fun bindStableControlSocket(): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(DEFAULT_CONTROL_PORT))
            }
        } catch (_: Throwable) {
            ServerSocket(0)
        }
    }

    fun attachSurface(surface: Surface?) {
        outputSurface = surface
        val state = _sessionState.value
        val cfg = state.activeConfig
        if (surface != null && state.isConnected && cfg != null && state.videoPort > 0) {
            decoder.start(state.hostIp, state.videoPort, cfg.targetWidth, cfg.targetHeight, surface)
        }
    }

    fun disconnectSession(
        reason: String = "Disconnected",
        stopStandbyService: Boolean = false
    ) {
        watchdogJob?.cancel()
        watchdogJob = null
        touchDispatcher.stop()
        decoder.stop()

        val (sockToClose, outToClose, port) = synchronized(sessionStateLock) {
            activeSessionActive = false
            activeSessionToken = 0L
            val s = activeControlSocket
            val o = activeControlOut
            activeControlSocket = null
            activeControlOut = null
            Triple(s, o, serverSocket?.localPort ?: 0)
        }
        _pendingTrust.value = null

        if (stopStandbyService) {
            ExtendedDesktopService.stop(context)
        } else if (port > 0) {
            ExtendedDesktopService.updateDisplayNotification(
                context = context,
                line1 = "Display listening on port $port",
                line2 = "Long-press screen for Info & QR",
                title = ExtendedDesktopService.TITLE_STANDBY
            )
        }

        _sessionState.value = DisplaySessionState(
            listeningPort = port,
            overlayState = DisplayStreamOverlayState.Disconnected(reason),
            sessionOnlyTrustMode = crypto.isSessionOnlyDegraded(),
            securityNotice = _sessionState.value.securityNotice
        )

        scope.launch(Dispatchers.IO) {
            preAuthMutex.withLock {
                while (trustQueue.isNotEmpty()) {
                    val item = trustQueue.removeFirst()
                    item.decision.complete(false)
                    item.onDequeued()
                }
                _pendingTrust.value = null
            }
            if (sockToClose != null && outToClose != null) {
                ControlCodec.writeJsonFrameBounded(
                    sockToClose,
                    outToClose,
                    ControlMessage.DisconnectMsg(reason),
                    PairingCrypto.COMMIT_WINDOW_MS
                )
            }
            runCatching { outToClose?.close() }
            runCatching { sockToClose?.close() }
        }
    }

    fun stopAll() {
        val srvToClose = synchronized(sessionStateLock) {
            val srv = serverSocket
            serverSocket = null
            srv
        }
        acceptJob?.cancel()
        acceptJob = null
        disconnectSession("Stopped", stopStandbyService = true)
        scope.launch(Dispatchers.IO) {
            runCatching { srvToClose?.close() }
        }
    }

    private suspend fun tryAcquirePreAuthSlot(hostIp: String): Boolean = preAuthMutex.withLock {
        val currentForIp = perIpPreAuthCounts[hostIp] ?: 0
        if (globalPreAuthCount >= MAX_GLOBAL_PRE_AUTH || currentForIp >= MAX_PER_IP_PRE_AUTH) {
            false
        } else {
            globalPreAuthCount++
            perIpPreAuthCounts[hostIp] = currentForIp + 1
            true
        }
    }

    private suspend fun releasePreAuthSlot(hostIp: String) = preAuthMutex.withLock {
        if (globalPreAuthCount > 0) globalPreAuthCount--
        val currentForIp = perIpPreAuthCounts[hostIp] ?: 0
        if (currentForIp <= 1) {
            perIpPreAuthCounts.remove(hostIp)
        } else {
            perIpPreAuthCounts[hostIp] = currentForIp - 1
        }
    }

    private suspend fun enqueueTrustEntry(
        hostIp: String,
        request: PendingTrustRequest,
        decision: CompletableDeferred<Boolean>,
        onDequeued: () -> Unit
    ): Long? = preAuthMutex.withLock {
        if (trustQueue.size >= MAX_TRUST_QUEUE || trustQueue.any { it.hostIp == hostIp }) {
            return@withLock null
        }
        val id = nextQueueSeq++
        trustQueue.addLast(
            QueuedTrustEntry(
                entryId = id,
                hostIp = hostIp,
                request = request,
                decision = decision,
                onDequeued = onDequeued
            )
        )
        publishHeadTrustRequestLocked()
        id
    }

    private suspend fun removeTrustEntryAndRefresh(entryId: Long) = preAuthMutex.withLock {
        val iterator = trustQueue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.entryId == entryId) {
                iterator.remove()
                item.onDequeued()
                break
            }
        }
        publishHeadTrustRequestLocked()
    }

    private fun publishHeadTrustRequestLocked() {
        val head = trustQueue.firstOrNull()
        _pendingTrust.value = if (head == null) {
            null
        } else {
            val label = if (trustQueue.size > 1) "Pending 1 of ${trustQueue.size}" else null
            head.request.copy(queuePositionLabel = label)
        }
    }

    private suspend fun handleIncomingHost(sock: Socket, profile: DisplayProfile) {
        val inp = DataInputStream(sock.getInputStream())
        val out = DataOutputStream(sock.getOutputStream())
        val hostIp = sock.inetAddress?.hostAddress ?: "unknown"

        if (synchronized(sessionStateLock) { activeSessionActive }) {
            ControlCodec.writeJsonFrameBounded(
                sock,
                out,
                ControlMessage.TrustApprovalResult(
                    approved = false,
                    reason = "Display busy — already connected"
                ),
                PairingCrypto.COMMIT_WINDOW_MS
            )
            try { sock.close() } catch (_: Throwable) {}
            return
        }

        if (!tryAcquirePreAuthSlot(hostIp)) {
            ControlCodec.writeJsonFrameBounded(
                sock,
                out,
                ControlMessage.TrustApprovalResult(
                    approved = false,
                    reason = "Display busy — another connection attempt from this IP or queue is active."
                ),
                PairingCrypto.COMMIT_WINDOW_MS
            )
            try { sock.close() } catch (_: Throwable) {}
            return
        }

        var slotReleased = false
        var promotedSessionToken = 0L
        suspend fun releaseSlotOnce() {
            if (!slotReleased) {
                slotReleased = true
                releasePreAuthSlot(hostIp)
            }
        }

        var queuedEntryId: Long? = null
        suspend fun cleanupQueueEntry() {
            val id = queuedEntryId
            if (id != null) {
                queuedEntryId = null
                removeTrustEntryAndRefresh(id)
            }
        }

        try {
            sock.soTimeout = PairingCrypto.PRE_AUTH_IO_TIMEOUT_MS.toInt()
            val localDisplayId = profile.deviceId.ifBlank { peerStore.getOrCreateDeviceId() }

            val frame = ControlCodec.readNextFrame(inp)
            val req = (frame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeRequest
            if (req == null) {
                releaseSlotOnce()
                sock.close()
                return
            }

            // Protocol version negotiation (v1 intentionally retired prior to release)
            val versionResult = PairingCrypto.negotiateForDisplay(
                reqProtocolVersion = req.protocolVersion,
                reqMinSupported = req.minSupportedProtocolVersion,
                hostName = req.hostName
            )
            if (versionResult is PairingCrypto.ProtocolNegotiation.Incompatible) {
                _sessionState.value = _sessionState.value.copy(securityNotice = versionResult.reason)
                ControlCodec.writeJsonFrameBounded(
                    sock,
                    out,
                    ControlMessage.TrustApprovalResult(approved = false, reason = versionResult.reason),
                    PairingCrypto.COMMIT_WINDOW_MS
                )
                releaseSlotOnce()
                sock.close()
                return
            }
            val negotiatedVersion = (versionResult as PairingCrypto.ProtocolNegotiation.Agreed).version

            // Evaluate Keystore & fallback gate atomically under Mutex BEFORE sending HandshakeChallenge
            val preChallengeGate = peerStore.evaluateDisplayPreChallengeGate()
            val savedToken = if (!preChallengeGate.sessionOnly) {
                peerStore.getDecryptedToken(req.hostId, TrustedPeerRole.DISPLAY_INBOUND_HOST)
            } else {
                null
            }
            val displayNonce = PairingCrypto.generateNonce()
            val displayEcdh = PairingCrypto.generateEcdhKeyPair()
            val displayPubB64 = PairingCrypto.encodePublicKey(displayEcdh)

            val isSilentReconnect = savedToken != null && !preChallengeGate.sessionOnly
            var pendingRawToken: String? = null

            if (isSilentReconnect && savedToken != null) {
                // Silent trusted reconnect via bidirectional HMAC-SHA-256 challenge-response
                val displayProof = PairingCrypto.computeDisplayProofHmac(
                    sharedToken = savedToken,
                    negotiatedVersion = negotiatedVersion,
                    hostNonce = req.hostNonce,
                    displayNonce = displayNonce,
                    hostId = req.hostId,
                    displayPeerId = localDisplayId
                )
                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeChallenge(
                            negotiatedProtocolVersion = negotiatedVersion,
                            peerId = localDisplayId,
                            displayNonce = displayNonce,
                            displayProofHmac = displayProof,
                            requiresUserApproval = false,
                            displayEcdhPubKey = displayPubB64,
                            sasTimeoutMs = PairingCrypto.DEFAULT_SAS_TIMEOUT_MS,
                            sessionOnly = false,
                            previousPersistFailed = preChallengeGate.previousPersistFailed
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                val authFrame = ControlCodec.readNextFrame(inp)
                val authResp = (authFrame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeAuthResponse
                val hostValid = authResp != null && PairingCrypto.verifyHostProofHmac(
                    sharedToken = savedToken,
                    candidateHmac = authResp.hostProofHmac,
                    negotiatedVersion = negotiatedVersion,
                    displayNonce = displayNonce,
                    hostNonce = req.hostNonce,
                    hostId = req.hostId,
                    displayPeerId = localDisplayId
                )
                if (!hostValid) {
                    val err = "Host ${req.hostName} failed HMAC identity verification."
                    _sessionState.value = _sessionState.value.copy(securityNotice = err)
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(approved = false, reason = err),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                peerStore.updatePeerEndpoint(
                    peerId = req.hostId,
                    peerRole = TrustedPeerRole.DISPLAY_INBOUND_HOST,
                    ip = hostIp,
                    port = null
                )
                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = true,
                            profile = profile
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    releaseSlotOnce()
                    sock.close()
                    return
                }
            } else {
                // Initial pairing or SAS fallback (e.g. Display cleared storage / Keystore unavailable / commit lost)
                val hostPubB64 = req.hostEcdhPubKey
                if (hostPubB64.isNullOrBlank()) {
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = false,
                            reason = "Missing ECDH public key from Host."
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                val effectiveSasTimeoutMs = sasTimeoutOverrideMs ?: PairingCrypto.DEFAULT_SAS_TIMEOUT_MS
                val ecdhSecret = PairingCrypto.deriveEcdhSharedSecret(displayEcdh.private, hostPubB64)
                val sasCode = PairingCrypto.deriveSasCode(
                    ecdhSharedSecret = ecdhSecret,
                    negotiatedVersion = negotiatedVersion,
                    hostNonce = req.hostNonce,
                    displayNonce = displayNonce,
                    hostPubKeyB64 = hostPubB64,
                    displayPubKeyB64 = displayPubB64
                )

                val mismatchNotice = if (req.expectSavedTrust) {
                    "Host attempted saved reconnect, but Display requires 6-digit code re-verification."
                } else {
                    null
                }
                val sasDeadlineMs = System.currentTimeMillis() + effectiveSasTimeoutMs

                val decision = CompletableDeferred<Boolean>()
                val request = PendingTrustRequest(
                    hostId = req.hostId,
                    hostName = req.hostName,
                    sasCode = sasCode,
                    isSessionOnlyMode = preChallengeGate.sessionOnly,
                    transportLabel = req.transportMode,
                    mismatchWarning = mismatchNotice,
                    sasDeadlineMs = sasDeadlineMs,
                    sasTimeoutMs = effectiveSasTimeoutMs,
                    onApprove = { decision.complete(true) },
                    onReject = { decision.complete(false) }
                )

                val entryId = enqueueTrustEntry(
                    hostIp = hostIp,
                    request = request,
                    decision = decision,
                    onDequeued = { }
                )
                if (entryId == null) {
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = false,
                            reason = "Display pairing prompt queue is full — try again shortly."
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    releaseSlotOnce()
                    sock.close()
                    return
                }
                queuedEntryId = entryId

                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeChallenge(
                            negotiatedProtocolVersion = negotiatedVersion,
                            peerId = localDisplayId,
                            displayNonce = displayNonce,
                            displayProofHmac = null,
                            requiresUserApproval = true,
                            displayEcdhPubKey = displayPubB64,
                            sasTimeoutMs = effectiveSasTimeoutMs,
                            sessionOnly = preChallengeGate.sessionOnly,
                            previousPersistFailed = preChallengeGate.previousPersistFailed
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    cleanupQueueEntry()
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                // Match socket timeout to shared SAS deadline window
                sock.soTimeout = effectiveSasTimeoutMs.coerceAtLeast(1_000L).toInt()
                val hostAuthDeferred = scope.async {
                    try {
                        val f = ControlCodec.readNextFrame(inp)
                        val msg = (f as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeAuthResponse
                        if (msg?.sasConfirmed != true) {
                            decision.complete(false)
                        }
                        msg
                    } catch (_: Throwable) {
                        decision.complete(false)
                        null
                    }
                }

                val remainingBeforeDeadline = (sasDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1L)
                val localApproved = withTimeoutOrNull(remainingBeforeDeadline) { decision.await() } ?: false

                // Release queue entry immediately on user decision or expiry
                cleanupQueueEntry()

                if (!localApproved) {
                    hostAuthDeferred.cancel()
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = false,
                            reason = "Rejected or timed out on Display"
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                val remainingForHostAuth = (sasDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1L)
                val hostAuth = withTimeoutOrNull(remainingForHostAuth) { hostAuthDeferred.await() }
                if (hostAuth?.sasConfirmed != true) {
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = false,
                            reason = "Host declined or timed out SAS code verification ($sasCode)"
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                    releaseSlotOnce()
                    sock.close()
                    return
                }

                // Hold generated pairing token strictly in RAM until 3-step commit finalizes
                val generatedToken = crypto.generateSecureToken()
                pendingRawToken = generatedToken
                val sealed = PairingCrypto.sealInitialToken(
                    ecdhSharedSecret = ecdhSecret,
                    hostNonce = req.hostNonce,
                    displayNonce = displayNonce,
                    rawToken = generatedToken
                )
                _sessionState.value = _sessionState.value.copy(
                    sessionOnlyTrustMode = preChallengeGate.sessionOnly,
                    securityNotice = if (preChallengeGate.sessionOnly) SESSION_ONLY_WARNING else null
                )

                if (!ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.TrustApprovalResult(
                            approved = true,
                            sealedPairingToken = sealed.ciphertextB64,
                            sealedPairingTag = sealed.macTagB64,
                            profile = profile
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                ) {
                    pendingRawToken = null
                    releaseSlotOnce()
                    sock.close()
                    return
                }
            }

            // Commit Step 1: Receive Host HandshakeAck within PRE_AUTH_IO_TIMEOUT_MS
            sock.soTimeout = PairingCrypto.PRE_AUTH_IO_TIMEOUT_MS.toInt()
            val ackFrame = ControlCodec.readNextFrame(inp)
            val ack = (ackFrame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeAck
            if (ack == null) {
                pendingRawToken = null
                _sessionState.value = _sessionState.value.copy(
                    securityNotice = "Host did not send stream acknowledgement — trust was not saved; please re-pair via 6-digit code."
                )
                releaseSlotOnce()
                sock.close()
                return
            }

            // Commit Step 2: Send bounded HandshakeCommitAck (still without writing to DataStore)
            if (!ControlCodec.writeJsonFrameBounded(
                    sock,
                    out,
                    ControlMessage.HandshakeCommitAck(
                        committed = true,
                        sessionOnly = preChallengeGate.sessionOnly
                    ),
                    PairingCrypto.COMMIT_WINDOW_MS
                )
            ) {
                pendingRawToken = null
                _sessionState.value = _sessionState.value.copy(
                    securityNotice = "Display timed out sending commit acknowledgement — trust was not saved; please re-pair via 6-digit code."
                )
                releaseSlotOnce()
                sock.close()
                return
            }

            // Commit Step 3: Await Host's authoritative HandshakeCommitFinal within COMMIT_WINDOW_MS
            sock.soTimeout = PairingCrypto.COMMIT_WINDOW_MS.toInt()
            val finalFrame = runCatching { ControlCodec.readNextFrame(inp) }.getOrNull()
            val commitFinal = (finalFrame as? ControlCodec.DecodedFrame.JsonControl)?.message as? ControlMessage.HandshakeCommitFinal
            if (commitFinal == null || !commitFinal.accepted) {
                pendingRawToken = null
                _sessionState.value = _sessionState.value.copy(
                    securityNotice = commitFinal?.reason
                        ?: "Host did not confirm pairing commit — trust was not saved; please re-pair via 6-digit code."
                )
                releaseSlotOnce()
                sock.close()
                return
            }

            // Host confirmed commit: persist token only now (if SAS pairing & Keystore permitted)
            if (!isSilentReconnect && !preChallengeGate.sessionOnly && !pendingRawToken.isNullOrBlank()) {
                val saved = peerStore.trustPeer(
                    peerId = req.hostId,
                    deviceName = req.hostName,
                    rawToken = pendingRawToken,
                    peerRole = TrustedPeerRole.DISPLAY_INBOUND_HOST,
                    ip = hostIp,
                    port = null,
                    profile = null,
                    pendingCommitFinal = false,
                    clearSessionOnlyFallbackOnSuccess = true
                )
                if (saved == null) {
                    peerStore.recordSessionOnlyFallback(true)
                    _sessionState.value = _sessionState.value.copy(
                        sessionOnlyTrustMode = true,
                        securityNotice = SESSION_ONLY_WARNING
                    )
                    ControlCodec.writeJsonFrameBounded(
                        sock,
                        out,
                        ControlMessage.HandshakeCommitPersistError(
                            reason = "Display Keystore write failed during pairing commit — using session-only trust. Re-pair after Keystore recovers.",
                            sessionOnlyFallback = true
                        ),
                        PairingCrypto.COMMIT_WINDOW_MS
                    )
                } else {
                    _sessionState.value = _sessionState.value.copy(
                        sessionOnlyTrustMode = false,
                        securityNotice = null
                    )
                }
            } else if (!isSilentReconnect && preChallengeGate.sessionOnly) {
                _sessionState.value = _sessionState.value.copy(
                    sessionOnlyTrustMode = true,
                    securityNotice = SESSION_ONLY_WARNING
                )
            }
            pendingRawToken = null

            // Transition to active session (re-verify single-session lock atomically before promotion)
            val acquiredToken = synchronized(sessionStateLock) {
                if (activeSessionActive) {
                    null
                } else {
                    val token = nextSessionToken++
                    activeSessionActive = true
                    activeSessionToken = token
                    activeControlSocket = sock
                    activeControlOut = out
                    token
                }
            }
            if (acquiredToken == null) {
                releaseSlotOnce()
                ControlCodec.writeJsonFrameBounded(
                    sock,
                    out,
                    ControlMessage.TrustApprovalResult(
                        approved = false,
                        reason = "Display busy — already connected"
                    ),
                    PairingCrypto.COMMIT_WINDOW_MS
                )
                try { sock.close() } catch (_: Throwable) {}
                return
            }
            promotedSessionToken = acquiredToken
            releaseSlotOnce()
            sock.soTimeout = PairingCrypto.STREAM_SO_TIMEOUT_MS.toInt()

            lastHeartbeatAckMs = System.currentTimeMillis()
            touchDispatcher.start(out)
            ExtendedDesktopService.startDisplay(context, req.hostName)

            _sessionState.value = _sessionState.value.copy(
                isConnected = true,
                hostName = req.hostName,
                hostIp = hostIp,
                videoPort = ack.videoPort,
                activeConfig = ack.config,
                overlayState = DisplayStreamOverlayState.Active,
                sessionOnlyTrustMode = preChallengeGate.sessionOnly || crypto.isSessionOnlyDegraded()
            )

            outputSurface?.let { surface ->
                decoder.start(hostIp, ack.videoPort, ack.config.targetWidth, ack.config.targetHeight, surface)
            }

            startWatchdog(out, acquiredToken)
            readHostControlLoop(inp, acquiredToken)
        } catch (_: Throwable) {
            cleanupQueueEntry()
            releaseSlotOnce()
            val isOwnedActiveSession = promotedSessionToken != 0L && synchronized(sessionStateLock) {
                activeSessionActive && activeSessionToken == promotedSessionToken
            }
            if (isOwnedActiveSession) {
                disconnectSession("Host link closed")
            } else {
                try { sock.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun startWatchdog(out: DataOutputStream, sessionToken: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _sessionState.value.isConnected &&
                synchronized(sessionStateLock) { activeSessionToken == sessionToken }
            ) {
                delay(1000L)
                if (!synchronized(sessionStateLock) { activeSessionToken == sessionToken }) break
                val now = System.currentTimeMillis()
                try {
                    ControlCodec.writeJsonFrame(out, ControlMessage.HeartbeatPing(now))
                } catch (_: Throwable) {
                }

                // Escalate at ~3 seconds without new decoded frames
                val frameStaleMs = now - decoder.lastFrameDecodedMs.get()
                if (frameStaleMs >= 3000L && _sessionState.value.overlayState is DisplayStreamOverlayState.SwitchingMode) {
                    _sessionState.value = _sessionState.value.copy(
                        overlayState = DisplayStreamOverlayState.ReconnectingVideo()
                    )
                }

                // Disconnect only after ~10-second heartbeat timeout
                if (now - lastHeartbeatAckMs >= 10_000L) {
                    if (synchronized(sessionStateLock) { activeSessionToken == sessionToken }) {
                        disconnectSession("Heartbeat timeout (10s)")
                    }
                    break
                }
            }
        }
    }

    private fun readHostControlLoop(inp: DataInputStream, sessionToken: Long) {
        try {
            while (_sessionState.value.isConnected &&
                synchronized(sessionStateLock) { activeSessionToken == sessionToken }
            ) {
                val frame = ControlCodec.readNextFrame(inp)
                val msg = (frame as? ControlCodec.DecodedFrame.JsonControl)?.message ?: continue
                when (msg) {
                    is ControlMessage.ModeChange -> {
                        touchDispatcher.setTransitionPaused(true)
                        _sessionState.value = _sessionState.value.copy(
                            overlayState = DisplayStreamOverlayState.SwitchingMode(
                                message = "Host switching to Layout Preview"
                            )
                        )
                    }
                    is ControlMessage.DisplayOverride -> {
                        _sessionState.value = _sessionState.value.copy(activeConfig = msg.config)
                    }
                    is ControlMessage.HeartbeatAck -> {
                        lastHeartbeatAckMs = System.currentTimeMillis()
                    }
                    is ControlMessage.DisconnectMsg -> {
                        if (synchronized(sessionStateLock) { activeSessionToken == sessionToken }) {
                            disconnectSession(msg.reason)
                        }
                        break
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            val isOwnedActiveSession = synchronized(sessionStateLock) {
                activeSessionActive && activeSessionToken == sessionToken
            }
            if (isOwnedActiveSession && _sessionState.value.isConnected) {
                disconnectSession("Host link closed")
            }
        }
    }

    companion object {
        const val DEFAULT_CONTROL_PORT = 57321
        const val MAX_PER_IP_PRE_AUTH = 1
        const val MAX_GLOBAL_PRE_AUTH = 8
        const val MAX_TRUST_QUEUE = 4
        const val MAX_QUEUED_SAS_PER_IP = 1
        const val SESSION_ONLY_WARNING =
            "Keystore unavailable — approve every session, no silent reconnect"
    }
}
