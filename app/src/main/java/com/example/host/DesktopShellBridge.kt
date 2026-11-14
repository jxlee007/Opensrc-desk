package com.example.host

import com.example.model.InstalledAppInfo
import com.example.model.ResolutionCeiling
import com.example.model.VirtualDisplayConfig

data class VerificationTargetBounds(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val centerXPx: Float,
    val centerYPx: Float
)

/**
 * Coordinates lifecycle, input routing, Web Workspace trimming, and verification bounds between
 * the service-owned [HostSessionEngine] and [DesktopShellActivity] running on Display 1.
 */
object DesktopShellBridge {

    interface ShellCallbacks {
        fun onLaunchAppFromTaskbar(packageName: String)
        fun onWorkspaceLayerChanged(webLayerActive: Boolean)
        fun onDrawerVisibilityChanged(visible: Boolean)
        fun onShellDisplayVerified(displayId: Int)
        fun onShellDisplayRejected(expectedDisplayId: Int, actualDisplayId: Int?)
        fun onVerificationTouchObserved(event: ObservedTouchEvent)
    }

    interface AttachedShell {
        fun updateConfig(config: VirtualDisplayConfig, modeLabel: String)
        fun updateCursor(normX: Float, normY: Float, actionLabel: String)
        fun appendImeText(textInput: String?, keyCode: Int)
        fun injectInProcessTouch(action: Byte, normX: Float, normY: Float)
        fun updateLaunchableApps(apps: List<InstalledAppInfo>)
        fun setWebLayerActive(active: Boolean)
        fun trimWebMemoryToColdBundle()
        fun releaseWebResourcesAndFinish()
        fun getVerificationTargetBounds(): VerificationTargetBounds?
    }

    private val lock = Any()

    @Volatile
    private var attachedShell: AttachedShell? = null

    @Volatile
    private var callbacks: ShellCallbacks? = null

    @Volatile
    var currentConfig: VirtualDisplayConfig = defaultInitialConfig()
        private set

    @Volatile
    var currentModeLabel: String = "LAYOUT PREVIEW"
        private set

    @Volatile
    var shizukuAuthorized: Boolean = false
        private set

    @Volatile
    var availableApps: List<InstalledAppInfo> = emptyList()
        private set

    @Volatile
    var webLayerActive: Boolean = false
        private set

    @Volatile
    var appDrawerVisible: Boolean = false
        private set

    @Volatile
    var verifiedDisplayId: Int? = null
        private set

    @Volatile
    var launchStatusNote: String = "Idle"
        private set

    fun prepareSession(
        expectedDisplayId: Int,
        config: VirtualDisplayConfig,
        modeLabel: String,
        shizukuAuthorized: Boolean,
        apps: List<InstalledAppInfo>,
        diagnosticOnly: Boolean,
        shellCallbacks: ShellCallbacks
    ) {
        synchronized(lock) {
            this.currentConfig = config
            this.currentModeLabel = modeLabel
            this.shizukuAuthorized = shizukuAuthorized
            this.availableApps = apps
            this.webLayerActive = !shizukuAuthorized && !diagnosticOnly
            this.appDrawerVisible = false
            this.verifiedDisplayId = null
            this.launchStatusNote = "Launching Desktop Shell on Display $expectedDisplayId…"
            this.callbacks = shellCallbacks
        }
    }

    fun attachShell(shell: AttachedShell, actualDisplayId: Int) {
        val (appsCopy, cfgCopy, labelCopy, webCopy) = synchronized(lock) {
            attachedShell = shell
            verifiedDisplayId = actualDisplayId
            launchStatusNote = "Desktop Shell active on Display $actualDisplayId"
            Quad(availableApps, currentConfig, currentModeLabel, webLayerActive)
        }
        shell.updateConfig(cfgCopy, labelCopy)
        shell.updateLaunchableApps(appsCopy)
        shell.setWebLayerActive(webCopy)
        callbacks?.onShellDisplayVerified(actualDisplayId)
    }

    fun detachShell(shell: AttachedShell) {
        synchronized(lock) {
            if (attachedShell === shell) {
                attachedShell = null
            }
        }
    }

    fun reportDisplayMismatch(expectedDisplayId: Int, actualDisplayId: Int?) {
        synchronized(lock) {
            verifiedDisplayId = null
            launchStatusNote =
                "OEM blocked Display $expectedDisplayId launch (landed on Display ${actualDisplayId ?: -1})"
        }
        callbacks?.onShellDisplayRejected(expectedDisplayId, actualDisplayId)
    }

    fun reportLaunchException(expectedDisplayId: Int, error: Throwable) {
        synchronized(lock) {
            verifiedDisplayId = null
            launchStatusNote =
                "Display $expectedDisplayId launch failed: ${error.message ?: error::class.java.simpleName}"
        }
        callbacks?.onShellDisplayRejected(expectedDisplayId, null)
    }

    fun notifyAppLaunchedFromTaskbar(packageName: String) {
        callbacks?.onLaunchAppFromTaskbar(packageName)
    }

    fun notifyWorkspaceLayerChanged(active: Boolean) {
        webLayerActive = active
        callbacks?.onWorkspaceLayerChanged(active)
    }

    fun notifyDrawerVisibilityChanged(visible: Boolean) {
        appDrawerVisible = visible
        callbacks?.onDrawerVisibilityChanged(visible)
    }

    fun notifyVerificationTouchObserved(event: ObservedTouchEvent) {
        callbacks?.onVerificationTouchObserved(event)
    }

    fun updateConfig(config: VirtualDisplayConfig, modeLabel: String) {
        currentConfig = config
        currentModeLabel = modeLabel
        attachedShell?.updateConfig(config, modeLabel)
    }

    fun updateLaunchableApps(apps: List<InstalledAppInfo>) {
        availableApps = apps
        attachedShell?.updateLaunchableApps(apps)
    }

    fun updateCursor(normX: Float, normY: Float, actionLabel: String) {
        attachedShell?.updateCursor(normX, normY, actionLabel)
    }

    fun appendImeText(textInput: String?, keyCode: Int) {
        attachedShell?.appendImeText(textInput, keyCode)
    }

    fun injectInProcessTouch(action: Byte, normX: Float, normY: Float) {
        attachedShell?.injectInProcessTouch(action, normX, normY)
    }

    fun trimWebMemoryToColdBundle() {
        attachedShell?.trimWebMemoryToColdBundle()
    }

    fun getVerificationTargetBounds(): VerificationTargetBounds? =
        attachedShell?.getVerificationTargetBounds()

    fun releaseAndCloseShell() {
        val shell = synchronized(lock) {
            val s = attachedShell
            attachedShell = null
            callbacks = null
            verifiedDisplayId = null
            webLayerActive = false
            appDrawerVisible = false
            launchStatusNote = "Idle"
            s
        }
        shell?.releaseWebResourcesAndFinish()
    }

    /**
     * Pure helper: verifies Display 1 launches actually landed on the target VirtualDisplay.
     */
    fun isExpectedDisplayMatch(expectedDisplayId: Int, actualDisplayId: Int?): Boolean =
        expectedDisplayId <= 0 || (actualDisplayId != null && actualDisplayId == expectedDisplayId)

    /**
     * Pure helper: filters ALL launchable apps without truncation for the searchable Display drawer.
     */
    fun filterLaunchableApps(
        apps: List<InstalledAppInfo>,
        query: String
    ): List<InstalledAppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter { app ->
            app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)
        }
    }

    /**
     * Pure helper: routes touch in-process to DesktopShellActivity when Shizuku is inactive,
     * the Web Workspace or App Drawer is visible, or the tap lands in the bottom 64px taskbar zone.
     */
    fun shouldRouteTouchInProcess(
        webLayerActive: Boolean,
        appDrawerVisible: Boolean,
        normY: Float,
        shizukuAuthorized: Boolean,
        taskbarHeightFraction: Float = 0.085f
    ): Boolean {
        if (!shizukuAuthorized || webLayerActive || appDrawerVisible) return true
        return normY >= (1f - taskbarHeightFraction)
    }

    private fun defaultInitialConfig(): VirtualDisplayConfig = VirtualDisplayConfig(
        targetWidth = 1280,
        targetHeight = 800,
        targetDpi = 160,
        refreshRateFps = 60,
        bitrateMbps = 4,
        forceTabletLayout = true,
        resolutionCeiling = ResolutionCeiling.P800,
        is24GhzAdapted = true
    )

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
