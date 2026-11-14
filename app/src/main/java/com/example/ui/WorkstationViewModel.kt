package com.example.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.example.core.KeystoreCrypto
import com.example.display.DisplaySessionEngine
import com.example.display.DisplaySessionState
import com.example.host.HostSessionEngine
import com.example.host.HostTelemetry
import com.example.host.InputReceiptEvaluation
import com.example.host.ObservedTouchEvent
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
import com.example.network.MdnsResolvedPeer
import com.example.network.WifiDirectPeerInfo
import com.example.service.WorkstationRuntime
import kotlinx.coroutines.flow.StateFlow

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

    private val runtime: WorkstationRuntime = WorkstationRuntime.getInstance(application).apply {
        ensureStarted()
    }

    val hostEngine: HostSessionEngine
        get() = runtime.hostEngine

    val displayEngine: DisplaySessionEngine
        get() = runtime.displayEngine

    val uiState: StateFlow<WorkstationUiState>
        get() = runtime.uiState

    fun selectRole(role: DeviceRole) = runtime.selectRole(role)

    fun setTestingRoleOverride(useDisplayMode: Boolean) {
        selectRole(if (useDisplayMode) DeviceRole.DISPLAY else DeviceRole.HOST)
    }

    fun selectTransport(mode: TransportMode) = runtime.selectTransport(mode)

    fun startWifiDirectScan() = runtime.wifiDirectManager.startDiscovery()

    fun connectWifiDirectPeer(address: String) = runtime.wifiDirectManager.connectToPeer(address)

    fun refreshDiscovery() = runtime.refreshDiscovery()

    fun requestShizukuPermission() = runtime.shizukuController.requestPermission()

    fun continueWithLayoutPreview() = runtime.shizukuController.enterLayoutPreviewFallback()

    /**
     * Resolves Display endpoint by peerId via mDNS FIRST; falls back to stored lastKnownIp / lastKnownPort
     * (default 57321) only when mDNS has no live match. Identity is still verified via mutual HMAC on connect.
     */
    fun connectToTrustedPeer(peer: TrustedPeer) =
        runtime.connectToTrustedPeer(peer, Companion::resolveReconnectEndpoint)

    fun connectToPeer(peer: DiscoveredPeer) = runtime.connectToPeer(peer)

    fun connectManual(ip: String, portText: String) = runtime.connectManual(ip, portText)

    fun connectFromQr(rawQr: String): Boolean = runtime.connectFromQr(rawQr)

    fun updateDisplayOverrides(
        ceiling: ResolutionCeiling = uiState.value.resolutionCeiling,
        forceTablet: Boolean = uiState.value.forceTabletLayout,
        dpi: Int = uiState.value.manualDpi,
        prefer24Ghz: Boolean = uiState.value.prefer24GhzBitrate
    ) = runtime.updateDisplayOverrides(ceiling, forceTablet, dpi, prefer24Ghz)

    fun launchAppOnExtendedDisplay(packageName: String) =
        runtime.launchAppOnExtendedDisplay(packageName)

    fun moveAppBackToHost(packageName: String) =
        runtime.moveAppBackToHost(packageName)

    fun focusDesktopShell() = runtime.focusDesktopShell()

    fun startHostMirroring(resultCode: Int, data: android.content.Intent) =
        runtime.startHostMirroring(resultCode, data)

    fun stopHostMirroring() = runtime.stopHostMirroring()

    fun setMirrorRightClickAsBack(enabled: Boolean) = runtime.setMirrorRightClickAsBack(enabled)

    fun setMirrorAspectFallback(enabled: Boolean) = runtime.setMirrorAspectFallback(enabled)

    fun showBanner(message: String) = runtime.showBanner(message)

    fun ensureDisplayStandbyForegroundService(port: Int) =
        runtime.ensureDisplayStandbyForegroundService(port)

    fun runCryptoAndLatencySelfCheck() = runtime.runCryptoAndLatencySelfCheck()

    fun runExperimentalRelocationProbe() = runtime.runExperimentalRelocationProbe()

    suspend fun runInputReceiptGateDiagnostic(
        collectBaselineDisplay0: () -> List<ObservedTouchEvent>,
        clearDisplay0Buffer: () -> Unit,
        collectPostDisplay0: (Long) -> List<ObservedTouchEvent>
    ) = runtime.runInputReceiptGateDiagnostic(
        collectBaselineDisplay0 = collectBaselineDisplay0,
        clearDisplay0Buffer = clearDisplay0Buffer,
        collectPostDisplay0 = collectPostDisplay0
    )

    fun revokeTrustedPeer(peerId: String) = runtime.revokeTrustedPeer(peerId)

    fun dismissUpgradeNotice() = runtime.dismissUpgradeNotice()

    fun clearBanner() = runtime.clearBanner()

    fun buildDisplayQrString(): String? = runtime.buildDisplayQrString()

    override fun onCleared() {
        super.onCleared()
        // Intentionally do NOT disconnect HostSessionEngine / DisplaySessionEngine or stop
        // VirtualDisplay here: closing or swiping MainActivity must keep active projection alive
        // under ExtendedDesktopService until explicit Disconnect or Notification Stop.
    }

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
