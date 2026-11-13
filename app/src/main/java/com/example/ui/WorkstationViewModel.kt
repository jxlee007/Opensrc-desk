package com.example.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.DisplayProfiler
import com.example.core.DisplaySynthesizer
import com.example.core.KeystoreCrypto
import com.example.core.TrustedPeerStore
import com.example.display.DisplaySessionEngine
import com.example.display.DisplaySessionState
import com.example.host.HostSessionEngine
import com.example.host.HostTelemetry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.example.host.HardwareVerificationRunner
import com.example.host.InputReceiptEvaluation
import com.example.host.ObservedTouchEvent
import com.example.service.ExtendedDesktopService
import com.example.protocol.PairingCrypto

data class HardwareDiagnosticsState(
    val cryptoSelfCheckSummary: String = "Not run",
    val relocationSummary: String = "Not run",
    val stackSnapshot: String = "",
    val lastInputReceipt: InputReceiptEvaluation? = null
)

data class WorkstationUiState(
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val recommendedRole: DeviceRole = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DeviceRole.HOST else DeviceRole.DISPLAY,
    val activeRole: DeviceRole = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DeviceRole.HOST else DeviceRole.DISPLAY,
    val localDeviceId: String = "",
    val localDeviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val localIp: String = "127.0.0.1",
    val localProfile: DisplayProfile? = null,
    val keystoreHealth: KeystoreCrypto.KeystoreHealth = KeystoreCrypto.KeystoreHealth.KEYSTORE_AES_GCM_API23,
    val upgradeNoticePending: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.LayoutPreviewFallback,
    val inputBackendStatus: InputBackendStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY,
    val selectedTransport: TransportMode = TransportMode.LOCAL_WIFI,
    val savedPeers: List<TrustedPeer> = emptyList(),
    val nearbyUntrustedPeers: List<DiscoveredPeer> = emptyList(),
    val wifiDirectPeers: List<WifiDirectPeerInfo> = emptyList(),
    val wifiDirectStatus: String = "Idle",
    val isMdnsBroadcasting: Boolean = false,
    val hostTelemetry: HostTelemetry = HostTelemetry(),
    val displaySession: DisplaySessionState = DisplaySessionState(),
    val pendingTrustRequest: PendingTrustRequest? = null,
    val pendingSasConfirmation: PendingSasConfirmation? = null,
    val launchableApps: List<InstalledAppInfo> = emptyList(),
    val resolutionCeiling: ResolutionCeiling = ResolutionCeiling.P800,
    val forceTabletLayout: Boolean = true,
    val manualDpi: Int = 160,
    val prefer24GhzBitrate: Boolean = true,
    val bannerMessage: String? = null,
    val hardwareDiagnostics: HardwareDiagnosticsState = HardwareDiagnosticsState()
)

class WorkstationViewModel(application: Application) : AndroidViewModel(application) {

    private val crypto = KeystoreCrypto(application)
    private val peerStore = TrustedPeerStore(application, crypto)
    private val profiler = DisplayProfiler(application)
    private val networkMonitor = NetworkMonitor(application)
    private val mdnsManager = MdnsManager(application)
    private val wifiDirectManager = WifiDirectManager(application)
    private val shizukuController = ShizukuController(application)
    val hostEngine = HostSessionEngine(application, peerStore, shizukuController, crypto)
    val displayEngine = DisplaySessionEngine(application, peerStore, crypto)

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
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = WorkstationUiState()
    )

    init {
        networkMonitor.start()
        shizukuController.startMonitoring()
        hostEngine.onHostForegroundPackagesUpdated = { pkgs ->
            activeHostPackages.value = pkgs
        }
        viewModelScope.launch {
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
                launchableApps.value = shizukuController.listLaunchableApps(emptySet(), emptySet())
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

    fun setTestingRoleOverride(useDisplayMode: Boolean) {
        selectRole(if (useDisplayMode) DeviceRole.DISPLAY else DeviceRole.HOST)
    }

    fun selectTransport(mode: TransportMode) {
        selectedTransport.value = mode
        if (mode == TransportMode.LOCAL_WIFI) {
            restartRoleNetworkServices()
        }
    }

    fun startWifiDirectScan() {
        wifiDirectManager.startDiscovery()
    }

    fun connectWifiDirectPeer(address: String) {
        wifiDirectManager.connectToPeer(address)
    }

    fun refreshDiscovery() {
        networkMonitor.refreshNow()
        shizukuController.refreshStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && shizukuController.isAuthorized()) {
            activeHostPackages.value = shizukuController.queryActiveHostPackages()
        }
    }

    fun requestShizukuPermission() {
        shizukuController.requestPermission()
    }

    fun continueWithLayoutPreview() {
        shizukuController.enterLayoutPreviewFallback()
    }

    /**
     * Resolves Display endpoint by peerId via mDNS FIRST; falls back to stored lastKnownIp / lastKnownPort
     * (default 57321) only when mDNS has no live match. Identity is still verified via mutual HMAC on connect.
     */
    fun connectToTrustedPeer(peer: TrustedPeer) {
        val (targetIp, targetPort) = resolveReconnectEndpoint(peer, mdnsManager.resolvedPeers.value)
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

    fun ensureDisplayStandbyForegroundService(port: Int) {
        if (port > 0 && activeRole.value == DeviceRole.DISPLAY) {
            ExtendedDesktopService.startDisplayStandby(getApplication(), port)
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
            ?: getApplication<Application>().packageName
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    override fun onCleared() {
        super.onCleared()
        networkMonitor.stop()
        mdnsManager.stopDiscovery()
        mdnsManager.stopAdvertising()
        wifiDirectManager.stop()
        shizukuController.stopMonitoring()
        hostEngine.disconnect("App closed")
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
        fun resolveReconnectEndpoint(
            savedPeer: TrustedPeer,
            mdnsPeers: List<MdnsResolvedPeer>
        ): Pair<String, Int> {
            val mdnsMatch = mdnsPeers.firstOrNull { it.peerId == savedPeer.peerId }
            if (mdnsMatch != null && mdnsMatch.port > 0) {
                return mdnsMatch.ipAddress to mdnsMatch.port
            }
            val fallbackPort = savedPeer.lastKnownPort?.takeIf { it > 0 }
                ?: DisplaySessionEngine.DEFAULT_CONTROL_PORT
            return savedPeer.lastKnownIp to fallbackPort
        }
    }
}

