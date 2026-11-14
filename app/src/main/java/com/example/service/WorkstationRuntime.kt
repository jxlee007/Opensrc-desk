package com.example.service

import android.content.Context
import android.os.Build
import com.example.core.DisplayProfiler
import com.example.core.DisplaySynthesizer
import com.example.core.KeystoreCrypto
import com.example.core.TrustedPeerStore
import com.example.display.DisplaySessionEngine
import com.example.display.DisplaySessionState
import com.example.host.DesktopShellBridge
import com.example.host.HostSessionEngine
import com.example.host.HostTelemetry
import com.example.host.ObservedTouchEvent
import com.example.host.ShizukuController
import com.example.model.DeviceRole
import com.example.model.DiscoveredPeer
import com.example.model.DisplayProfile
import com.example.model.InputBackendStatus
import com.example.model.InstalledAppInfo
import com.example.model.PendingSasConfirmation
import com.example.model.PendingTrustRequest
import com.example.model.ResolutionCeiling
import com.example.model.ShizukuStatus
import com.example.model.TransportMode
import com.example.model.TrustedPeer
import com.example.model.TrustedPeerRole
import com.example.network.MdnsManager
import com.example.network.MdnsResolvedPeer
import com.example.network.NetworkMonitor
import com.example.network.QrPairingCodec
import com.example.network.QrPairingPayload
import com.example.network.WifiDirectManager
import com.example.network.WifiDirectPeerInfo
import com.example.protocol.PairingCrypto
import com.example.ui.HardwareDiagnosticsState
import com.example.ui.WorkstationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Service-anchored singleton runtime holding the active Host/Display streaming engines,
 * VirtualDisplay, H264 encoder, and network managers so closing or swiping MainActivity
 * never terminates a live projection session.
 */
class WorkstationRuntime private constructor(appContext: Context) {
    val applicationContext: Context = appContext.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val crypto = KeystoreCrypto(applicationContext)
    val peerStore = TrustedPeerStore(applicationContext, crypto)
    val profiler = DisplayProfiler(applicationContext)
    val networkMonitor = NetworkMonitor(applicationContext)
    val mdnsManager = MdnsManager(applicationContext)
    val wifiDirectManager = WifiDirectManager(applicationContext)
    val shizukuController = ShizukuController(applicationContext)
    val hostEngine = HostSessionEngine(applicationContext, peerStore, shizukuController, crypto)
    val displayEngine = DisplaySessionEngine(applicationContext, peerStore, crypto)

    private val activeRole = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DeviceRole.HOST else DeviceRole.DISPLAY
    )
    private val localDeviceId = MutableStateFlow("")
    private val localProfile = MutableStateFlow<DisplayProfile?>(null)
    private val selectedTransport = MutableStateFlow(TransportMode.LOCAL_WIFI)
    private val resolutionCeiling = MutableStateFlow(ResolutionCeiling.P800)
    private val forceTabletLayout = MutableStateFlow(true)
    private val manualDpi = MutableStateFlow(160)
    private val prefer24GhzBitrate = MutableStateFlow(true)
    private val launchableApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val bannerMessage = MutableStateFlow<String?>(null)
    private val activeHostPackages = MutableStateFlow<Set<String>>(emptySet())
    private val hardwareDiagnostics = MutableStateFlow(HardwareDiagnosticsState())

    @Volatile
    private var started = false

    private val networkBundle = combine(
        networkMonitor.localIpAddress,
        mdnsManager.resolvedPeers,
        mdnsManager.isBroadcasting,
        wifiDirectManager.peers,
        wifiDirectManager.statusMessage
    ) { ip, mdns, broadcasting, p2pPeers, p2pStatus ->
        NetworkSnapshot(ip, mdns, broadcasting, p2pPeers, p2pStatus)
    }

    private val sessionBundle = combine(
        hostEngine.telemetry,
        displayEngine.sessionState,
        displayEngine.pendingTrust,
        hostEngine.pendingSasConfirmation,
        shizukuController.status
    ) { hostTel, dispSes, trustReq, sasReq, shizuku ->
        SessionSnapshot(hostTel, dispSes, trustReq, sasReq, shizuku)
    }

    private val trustBundle = combine(
        peerStore.trustedPeersFlow,
        peerStore.upgradeNoticePendingFlow,
        crypto.healthStatusFlow,
        shizukuController.inputBackendStatus,
        activeHostPackages
    ) { saved, upgradeNotice, ksHealth, inputBackend, hostPkgs ->
        TrustAndSystemSnapshot(saved, upgradeNotice, ksHealth, inputBackend, hostPkgs)
    }

    private val configBundle = combine(
        activeRole,
        localDeviceId,
        localProfile,
        selectedTransport,
        resolutionCeiling,
        forceTabletLayout,
        manualDpi,
        prefer24GhzBitrate,
        launchableApps,
        bannerMessage,
        hardwareDiagnostics
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ConfigSnapshot(
            role = values[0] as DeviceRole,
            deviceId = values[1] as String,
            profile = values[2] as DisplayProfile?,
            transport = values[3] as TransportMode,
            ceiling = values[4] as ResolutionCeiling,
            forceTablet = values[5] as Boolean,
            dpi = values[6] as Int,
            prefer24 = values[7] as Boolean,
            apps = values[8] as List<InstalledAppInfo>,
            banner = values[9] as String?,
            diagnostics = values[10] as HardwareDiagnosticsState
        )
    }

    val uiState: StateFlow<WorkstationUiState> = combine(
        networkBundle,
        sessionBundle,
        trustBundle,
        configBundle
    ) { net, ses, trust, cfg ->
        val expectedPeerRole = if (cfg.role == DeviceRole.HOST) {
            TrustedPeerRole.HOST_SAVED_DISPLAY
        } else {
            TrustedPeerRole.DISPLAY_INBOUND_HOST
        }
        val roleScopedPeers = trust.savedPeers.filter { it.peerRole == expectedPeerRole }

        val mdnsByPeerId = net.mdnsPeers.associateBy { it.peerId }
        val savedWithPresence = roleScopedPeers.map { saved ->
            val onlinePeer = mdnsByPeerId[saved.peerId]
            saved.copy(
                isOnline = onlinePeer != null,
                lastKnownIp = onlinePeer?.ipAddress ?: saved.lastKnownIp,
                lastKnownPort = when (saved.peerRole) {
                    TrustedPeerRole.DISPLAY_INBOUND_HOST -> null
                    TrustedPeerRole.HOST_SAVED_DISPLAY -> onlinePeer?.port ?: saved.lastKnownPort
                }
            )
        }.sortedByDescending { it.isOnline }

        val savedIds = roleScopedPeers.map { it.peerId }.toSet()
        val untrustedNearby = net.mdnsPeers
            .filterNot { savedIds.contains(it.peerId) }
            .map {
                DiscoveredPeer(
                    peerId = it.peerId,
                    deviceName = it.deviceName,
                    ipAddress = it.ipAddress,
                    controlPort = it.port,
                    displaySummary = it.displaySummary,
                    isTrusted = false,
                    isOnline = true,
                    transportMode = TransportMode.LOCAL_WIFI
                )
            }

        val annotatedApps = cfg.apps.map { app ->
            app.copy(
                isActiveOnHost = trust.activeHostPackages.contains(app.packageName),
                isActiveOnVirtual = ses.hostTelemetry.activeDesktopApps.contains(app.packageName)
            )
        }

        WorkstationUiState(
            localDeviceId = cfg.deviceId,
            localIp = net.localIp,
            localProfile = cfg.profile,
            keystoreHealth = trust.keystoreHealth,
            upgradeNoticePending = trust.upgradeNoticePending,
            shizukuStatus = ses.shizukuStatus,
            inputBackendStatus = trust.inputBackendStatus,
            activeRole = cfg.role,
            selectedTransport = cfg.transport,
            savedPeers = savedWithPresence,
            nearbyUntrustedPeers = untrustedNearby,
            wifiDirectPeers = net.wifiDirectPeers,
            wifiDirectStatus = net.wifiDirectStatus,
            isMdnsBroadcasting = net.isBroadcasting,
            hostTelemetry = ses.hostTelemetry,
            displaySession = ses.displaySession,
            pendingTrustRequest = ses.pendingTrustRequest,
            pendingSasConfirmation = ses.pendingSasConfirmation,
            launchableApps = annotatedApps,
            resolutionCeiling = cfg.ceiling,
            forceTabletLayout = cfg.forceTablet,
            manualDpi = cfg.dpi,
            prefer24GhzBitrate = cfg.prefer24,
            bannerMessage = cfg.banner,
            hardwareDiagnostics = cfg.diagnostics
        )
    }.stateIn(
        scope = runtimeScope,
        started = SharingStarted.Eagerly,
        initialValue = WorkstationUiState()
    )

    fun ensureStarted() {
        synchronized(this) {
            if (started) return
            started = true
        }
        networkMonitor.start()
        shizukuController.startMonitoring()
        hostEngine.onHostForegroundPackagesUpdated = { pkgs ->
            activeHostPackages.value = pkgs
        }
        runtimeScope.launch {
            peerStore.ensureSchemaV2Migrated()
            val id = peerStore.getOrCreateDeviceId()
            localDeviceId.value = id
            val cached = peerStore.cachedProfileFlow.first()
            val profile = cached ?: profiler.captureRealMetrics(id).also {
                peerStore.saveDisplayProfile(it)
            }
            localProfile.value = profile
            resolutionCeiling.value = DisplaySynthesizer.defaultCeilingForProfile(profile)
            prefer24GhzBitrate.value = !profile.supports5GhzWifi

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val apps = shizukuController.listLaunchableApps(emptySet(), emptySet())
                launchableApps.value = apps
                DesktopShellBridge.updateLaunchableApps(apps)
                if (shizukuController.isAuthorized()) {
                    activeHostPackages.value = shizukuController.queryActiveHostPackages()
                }
            }

            // Re-run discovery/advertising automatically on Wi-Fi network transitions
            networkMonitor.wifiEpoch.collect {
                restartRoleNetworkServices()
            }
        }
    }

    fun selectRole(role: DeviceRole) {
        if (role == DeviceRole.HOST && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            bannerMessage.value = "Host Mode requires Android 10+ (API 29+). Display Mode active."
            return
        }
        activeRole.value = role
        restartRoleNetworkServices()
    }

    fun selectTransport(mode: TransportMode) {
        selectedTransport.value = mode
        if (mode == TransportMode.LOCAL_WIFI) {
            restartRoleNetworkServices()
        }
    }

    fun refreshDiscovery() {
        networkMonitor.refreshNow()
        shizukuController.refreshStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val apps = shizukuController.listLaunchableApps(emptySet(), emptySet())
            launchableApps.value = apps
            DesktopShellBridge.updateLaunchableApps(apps)
            if (shizukuController.isAuthorized()) {
                activeHostPackages.value = shizukuController.queryActiveHostPackages()
            }
        }
    }

    fun connectToTrustedPeer(
        peer: TrustedPeer,
        resolveEndpoint: (TrustedPeer, List<MdnsResolvedPeer>) -> Pair<String, Int>
    ) {
        val (targetIp, targetPort) = resolveEndpoint(peer, mdnsManager.resolvedPeers.value)
        connectToPeer(
            DiscoveredPeer(
                peerId = peer.peerId,
                deviceName = peer.deviceName,
                ipAddress = targetIp,
                controlPort = targetPort,
                displaySummary = peer.cachedDisplayProfile?.summaryTag() ?: "Saved Display",
                isTrusted = true,
                isOnline = peer.isOnline,
                transportMode = selectedTransport.value,
                profile = peer.cachedDisplayProfile
            )
        )
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        val profileHint = peer.profile
        if (profileHint != null) {
            resolutionCeiling.value = DisplaySynthesizer.defaultCeilingForProfile(profileHint)
            prefer24GhzBitrate.value = !profileHint.supports5GhzWifi
        }
        hostEngine.connectToPeer(
            peer = peer,
            hostName = "${Build.MANUFACTURER} ${Build.MODEL}",
            preferredCeiling = resolutionCeiling.value,
            forceTablet = forceTabletLayout.value,
            dpiOverride = manualDpi.value,
            prefer24GhzBitrate = prefer24GhzBitrate.value
        )
    }

    fun connectManual(ip: String, portText: String) {
        val port = portText.toIntOrNull() ?: return
        if (ip.isBlank()) return
        connectToPeer(
            DiscoveredPeer(
                peerId = "manual-${ip.replace('.', '-')}",
                deviceName = "Display ($ip)",
                ipAddress = ip.trim(),
                controlPort = port,
                displaySummary = "Manual IP Link",
                isTrusted = false,
                isOnline = true,
                transportMode = selectedTransport.value
            )
        )
    }

    fun connectFromQr(rawQr: String): Boolean {
        val payload: QrPairingPayload = QrPairingCodec.decodePayload(rawQr) ?: return false
        connectToPeer(
            DiscoveredPeer(
                peerId = payload.peerId,
                deviceName = payload.deviceName,
                ipAddress = payload.ipAddress,
                controlPort = payload.controlPort,
                displaySummary = payload.displaySummary,
                isTrusted = false,
                isOnline = true,
                transportMode = TransportMode.LOCAL_WIFI
            )
        )
        return true
    }

    fun updateDisplayOverrides(
        ceiling: ResolutionCeiling = resolutionCeiling.value,
        forceTablet: Boolean = forceTabletLayout.value,
        dpi: Int = manualDpi.value,
        prefer24Ghz: Boolean = prefer24GhzBitrate.value
    ) {
        resolutionCeiling.value = ceiling
        forceTabletLayout.value = forceTablet
        manualDpi.value = dpi
        prefer24GhzBitrate.value = prefer24Ghz
        hostEngine.updateDisplaySynthesis(ceiling, forceTablet, dpi, prefer24Ghz)
    }

    fun launchAppOnExtendedDisplay(packageName: String) {
        val wasOnHost = activeHostPackages.value.contains(packageName)
        val result = hostEngine.launchAppExclusive(packageName)
        if (wasOnHost) {
            activeHostPackages.value = activeHostPackages.value - packageName
        }
        bannerMessage.value = result
    }

    fun moveAppBackToHost(packageName: String) {
        val report = shizukuController.moveAppBackToHost(packageName)
        hostEngine.markAppMovedBackToHost(packageName)
        activeHostPackages.value = activeHostPackages.value + packageName
        hardwareDiagnostics.value = hardwareDiagnostics.value.copy(
            relocationSummary = report,
            stackSnapshot = shizukuController.queryDisplayStackSnapshot()
        )
        bannerMessage.value = report
    }

    fun focusDesktopShell() {
        hostEngine.focusDesktopShell()
    }

    fun ensureDisplayStandbyForegroundService(port: Int) {
        if (port > 0 && activeRole.value == DeviceRole.DISPLAY) {
            ExtendedDesktopService.startDisplayStandby(applicationContext, port)
        }
    }

    fun runCryptoAndLatencySelfCheck() {
        val ecdhOk = runCatching {
            val a = PairingCrypto.generateEcdhKeyPair()
            val b = PairingCrypto.generateEcdhKeyPair()
            val sA = PairingCrypto.deriveEcdhSharedSecret(a.private, PairingCrypto.encodePublicKey(b))
            val sB = PairingCrypto.deriveEcdhSharedSecret(b.private, PairingCrypto.encodePublicKey(a))
            val token = PairingCrypto.generateToken()
            val sealed = PairingCrypto.sealInitialToken(sA, "h", "d", token)
            PairingCrypto.unsealInitialToken(sB, "h", "d", sealed.ciphertextB64, sealed.macTagB64) == token
        }.getOrDefault(false)
        val ksOk = crypto.isAvailable()
        val latency = displayEngine.sessionState.value.latencyStats
        val probeText = if (latency.sampleCount > 0) {
            "median=${latency.medianTouchToFrameMs}ms (<${latency.gateThresholdMs}ms=${latency.passesTab2Gate})"
        } else {
            "No live frames yet (${hostEngine.telemetry.value.inputBackendStatus.label})"
        }
        val snapshot = shizukuController.queryDisplayStackSnapshot()
        hardwareDiagnostics.value = hardwareDiagnostics.value.copy(
            cryptoSelfCheckSummary = "API ${Build.VERSION.SDK_INT} · ECDH=$ecdhOk · Keystore=$ksOk (${crypto.healthStatus}) · LatencyProbe: $probeText",
            stackSnapshot = snapshot
        )
    }

    fun runExperimentalRelocationProbe() {
        val targetPkg = launchableApps.value.firstOrNull { it.isActiveOnVirtual }?.packageName
            ?: launchableApps.value.firstOrNull()?.packageName
            ?: applicationContext.packageName
        val report = shizukuController.moveAppBackToHost(targetPkg)
        val snapshot = shizukuController.queryDisplayStackSnapshot()
        hardwareDiagnostics.value = hardwareDiagnostics.value.copy(
            relocationSummary = "Experimental [$targetPkg]: $report",
            stackSnapshot = snapshot
        )
    }

    suspend fun runInputReceiptGateDiagnostic(
        collectBaselineDisplay0: () -> List<ObservedTouchEvent>,
        clearDisplay0Buffer: () -> Unit,
        collectPostDisplay0: (Long) -> List<ObservedTouchEvent>
    ) {
        val eval = hostEngine.runInputReceiptGateDiagnostic(
            collectBaselineDisplay0 = collectBaselineDisplay0,
            clearDisplay0Buffer = clearDisplay0Buffer,
            collectPostDisplay0 = collectPostDisplay0
        )
        hardwareDiagnostics.value = hardwareDiagnostics.value.copy(lastInputReceipt = eval)
    }

    fun revokeTrustedPeer(peerId: String) {
        runtimeScope.launch {
            val peerRole = if (activeRole.value == DeviceRole.HOST) {
                TrustedPeerRole.HOST_SAVED_DISPLAY
            } else {
                TrustedPeerRole.DISPLAY_INBOUND_HOST
            }
            peerStore.revokePeerAndRotate(peerId, peerRole)
            bannerMessage.value = "Trust revoked and pairing keys rotated"
        }
    }

    fun dismissUpgradeNotice() {
        runtimeScope.launch {
            peerStore.dismissUpgradeNotice()
        }
    }

    fun clearBanner() {
        bannerMessage.value = null
    }

    fun buildDisplayQrString(): String? {
        val profile = localProfile.value ?: return null
        val port = displayEngine.sessionState.value.listeningPort
        if (port <= 0) return null
        return QrPairingCodec.encodePayload(
            QrPairingPayload(
                peerId = localDeviceId.value,
                deviceName = profile.deviceName,
                ipAddress = networkMonitor.localIpAddress.value,
                controlPort = port,
                displaySummary = profile.summaryTag()
            )
        )
    }

    private fun restartRoleNetworkServices() {
        val id = localDeviceId.value
        val profile = localProfile.value ?: return
        if (id.isBlank()) return

        if (activeRole.value == DeviceRole.HOST) {
            displayEngine.stopAll()
            mdnsManager.stopAdvertising()
            mdnsManager.startDiscovery(id)
        } else {
            mdnsManager.stopDiscovery()
            val port = displayEngine.startListening(profile)
            mdnsManager.startAdvertising(
                peerId = id,
                deviceName = profile.deviceName,
                displaySummary = profile.summaryTag(),
                port = port
            )
        }
    }

    fun stopAllFromServiceNotification() {
        hostEngine.disconnect("Stopped from notification")
        displayEngine.stopAll()
    }

    private data class NetworkSnapshot(
        val localIp: String,
        val mdnsPeers: List<MdnsResolvedPeer>,
        val isBroadcasting: Boolean,
        val wifiDirectPeers: List<WifiDirectPeerInfo>,
        val wifiDirectStatus: String
    )

    private data class SessionSnapshot(
        val hostTelemetry: HostTelemetry,
        val displaySession: DisplaySessionState,
        val pendingTrustRequest: PendingTrustRequest?,
        val pendingSasConfirmation: PendingSasConfirmation?,
        val shizukuStatus: ShizukuStatus
    )

    private data class TrustAndSystemSnapshot(
        val savedPeers: List<TrustedPeer>,
        val upgradeNoticePending: Boolean,
        val keystoreHealth: KeystoreCrypto.KeystoreHealth,
        val inputBackendStatus: InputBackendStatus,
        val activeHostPackages: Set<String>
    )

    private data class ConfigSnapshot(
        val role: DeviceRole,
        val deviceId: String,
        val profile: DisplayProfile?,
        val transport: TransportMode,
        val ceiling: ResolutionCeiling,
        val forceTablet: Boolean,
        val dpi: Int,
        val prefer24: Boolean,
        val apps: List<InstalledAppInfo>,
        val banner: String?,
        val diagnostics: HardwareDiagnosticsState
    )

    companion object {
        @Volatile
        private var instance: WorkstationRuntime? = null

        fun getInstance(context: Context): WorkstationRuntime {
            return instance ?: synchronized(this) {
                instance ?: WorkstationRuntime(context).also { instance = it }
            }
        }

        fun getIfInitialized(): WorkstationRuntime? = instance
    }
}
