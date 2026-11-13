package com.example.model

import kotlinx.serialization.Serializable

enum class DeviceRole {
    HOST,
    DISPLAY
}

@Serializable
enum class TrustedPeerRole {
    HOST_SAVED_DISPLAY,
    DISPLAY_INBOUND_HOST
}

@Serializable
enum class ResolutionCeiling(val shortEdgeCap: Int, val label: String) {
    P720(720, "720p"),
    P800(800, "800p (Tab 2)"),
    P1080(1080, "1080p"),
    P1200(1200, "1200p")
}

enum class TransportMode(val label: String) {
    LOCAL_WIFI("Same Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct"),
    USB_SOCKET("USB Socket")
}

enum class LimitTier(val label: String, val suggestedMaxApps: Int) {
    LIGHT("Light", 3),
    COMFORTABLE("Comfortable", 2),
    AT_LIMIT("At limit", 1)
}

data class PerformanceAdvisory(
    val displayAppsCount: Int = 0,
    val displayAppsLaunchedHereOnly: Boolean = false,
    val freeRamPercent: Int = 100,
    val thermalStatus: Int = 0,
    val thermalLabel: String = "normal",
    val batteryPercent: Int = 100,
    val suggestedLimitTier: LimitTier = LimitTier.LIGHT,
    val showCloseAppWarning: Boolean = false
) {
    fun notificationLine1(): String = if (displayAppsLaunchedHereOnly) {
        "Display apps: $displayAppsCount running (launched here)"
    } else {
        "Display apps: $displayAppsCount running"
    }

    fun notificationLine2(): String =
        "Suggested limit: ${suggestedLimitTier.suggestedMaxApps} (${suggestedLimitTier.label}) · RAM $freeRamPercent% free · Thermal $thermalLabel"
}

@Serializable
data class DisplayProfile(
    val deviceId: String,
    val deviceName: String,
    val sdkInt: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float,
    val aspectRatioLabel: String,
    val aspectRatioValue: Float,
    val physicalDiagonalInches: Float,
    val smallestWidthDp: Int,
    val supports5GhzWifi: Boolean,
    val isTabletClass: Boolean
) {
    fun summaryTag(): String =
        "${widthPixels}x$heightPixels • $aspectRatioLabel • ${"%.1f".format(physicalDiagonalInches)}\""
}

@Serializable
data class VirtualDisplayConfig(
    val targetWidth: Int,
    val targetHeight: Int,
    val targetDpi: Int,
    val forceTabletLayout: Boolean,
    val resolutionCeiling: ResolutionCeiling,
    val refreshRateFps: Int = 60,
    val bitrateMbps: Int = 4,
    val is24GhzAdapted: Boolean = true
)

@Serializable
data class TrustedPeer(
    val peerId: String,
    val deviceName: String,
    val encryptedPairingToken: String,
    val tokenVersion: Int,
    val peerRole: TrustedPeerRole,
    val lastKnownIp: String,
    val lastKnownPort: Int? = null,
    val cachedDisplayProfile: DisplayProfile? = null,
    val lastConnectedAtMs: Long = 0L,
    val isOnline: Boolean = false,
    val pendingCommitFinal: Boolean = false
)

data class DiscoveredPeer(
    val peerId: String,
    val deviceName: String,
    val ipAddress: String,
    val controlPort: Int,
    val displaySummary: String,
    val isTrusted: Boolean,
    val isOnline: Boolean,
    val transportMode: TransportMode,
    val profile: DisplayProfile? = null
)

sealed interface ShizukuStatus {
    data object NotInstalled : ShizukuStatus
    data object NeedsUsbAdbOnApi29 : ShizukuStatus
    data object NeedsStartOnApi30Plus : ShizukuStatus
    data object PermissionRequired : ShizukuStatus
    data object Authorized : ShizukuStatus
    data object LayoutPreviewFallback : ShizukuStatus
    data class RecoveredToPreviewAfterBinderLoss(val lostAtMs: Long) : ShizukuStatus
}

enum class InputBackendStatus(val label: String) {
    BINDER_IINPUTMANAGER("IInputManager Binder (Full Touch & Drag)"),
    SHELL_INPUT_D("Shell input -d (Tap + Coarse Drag)"),
    PREVIEW_CURSOR_ONLY("Layout Preview Cursor Only")
}

sealed interface DisplayStreamOverlayState {
    data object Active : DisplayStreamOverlayState
    data class SwitchingMode(
        val message: String = "Host switching to Layout Preview"
    ) : DisplayStreamOverlayState

    data class ReconnectingVideo(
        val message: String = "Reconnecting video…"
    ) : DisplayStreamOverlayState

    data class DecoderError(
        val message: String
    ) : DisplayStreamOverlayState

    data class Disconnected(val reason: String) : DisplayStreamOverlayState
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isActiveOnHost: Boolean = false,
    val isActiveOnVirtual: Boolean = false
)

data class LatencyProbeStats(
    val lastTouchToFrameMs: Long = 0L,
    val medianTouchToFrameMs: Long = 0L,
    val sampleCount: Int = 0,
    val targetFrameIntervalMs: Float = 16.6f,
    val gateThresholdMs: Long = 100L
) {
    val passesTab2Gate: Boolean
        get() = sampleCount > 0 && medianTouchToFrameMs < gateThresholdMs
}

data class PendingTrustRequest(
    val hostId: String,
    val hostName: String,
    val sasCode: String? = null,
    val isSessionOnlyMode: Boolean = false,
    val transportLabel: String = "Same Wi-Fi",
    val queuePositionLabel: String? = null,
    val mismatchWarning: String? = null,
    val sasDeadlineMs: Long = 0L,
    val sasTimeoutMs: Long = 120_000L,
    val onApprove: () -> Unit,
    val onReject: () -> Unit
)

data class PendingSasConfirmation(
    val peerId: String,
    val peerName: String,
    val sasCode: String,
    val isSessionOnlyMode: Boolean = false,
    val mismatchWarning: String? = null,
    val sasDeadlineMs: Long = 0L,
    val sasTimeoutMs: Long = 120_000L,
    val onConfirmMatch: () -> Unit,
    val onRejectMismatch: () -> Unit
)
