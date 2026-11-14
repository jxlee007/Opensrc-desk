package com.example.host

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.example.model.InputBackendStatus
import com.example.model.InstalledAppInfo
import com.example.model.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class ShizukuController(private val context: Context) {

    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.LayoutPreviewFallback)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _lastBinderLostAtMs = MutableStateFlow<Long?>(null)
    val lastBinderLostAtMs: StateFlow<Long?> = _lastBinderLostAtMs.asStateFlow()

    private val _inputBackendStatus = MutableStateFlow(InputBackendStatus.PREVIEW_CURSOR_ONLY)
    val inputBackendStatus: StateFlow<InputBackendStatus> = _inputBackendStatus.asStateFlow()

    private val inputBridge = ShizukuInputBridge()
    private var desktopUserService: IShizukuDesktopService? = null
    private var userServiceBound = false

    var onBinderLostMidStream: (() -> Unit)? = null

    private var listenersRegistered = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuDesktopUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("extdesk")
            .tag("extdesk_desktop_svc")
            .version(1)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            desktopUserService = IShizukuDesktopService.Stub.asInterface(service)
            userServiceBound = desktopUserService != null
            _inputBackendStatus.value = inputBridge.probeAndSelectBackend(desktopUserService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            desktopUserService = null
            userServiceBound = false
            inputBridge.resetProbe()
            _inputBackendStatus.value = InputBackendStatus.PREVIEW_CURSOR_ONLY
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val now = System.currentTimeMillis()
        _lastBinderLostAtMs.value = now
        desktopUserService = null
        userServiceBound = false
        inputBridge.resetProbe()
        _inputBackendStatus.value = InputBackendStatus.PREVIEW_CURSOR_ONLY
        _status.value = ShizukuStatus.RecoveredToPreviewAfterBinderLoss(now)
        onBinderLostMidStream?.invoke()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    _status.value = ShizukuStatus.Authorized
                    bindUserServiceIfReady()
                } else {
                    _status.value = ShizukuStatus.PermissionRequired
                }
            }
        }

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _status.value = ShizukuStatus.LayoutPreviewFallback
            return
        }
        if (!listenersRegistered) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                listenersRegistered = true
            } catch (_: Throwable) {
            }
        }
        refreshStatus()
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (userServiceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
            userServiceBound = false
            desktopUserService = null
        }
        if (!listenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
        listenersRegistered = false
    }

    fun refreshStatus(): ShizukuStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _status.value = ShizukuStatus.LayoutPreviewFallback
            return _status.value
        }
        if (!isShizukuInstalled()) {
            _status.value = ShizukuStatus.NotInstalled
            return _status.value
        }
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            _status.value = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                ShizukuStatus.NeedsUsbAdbOnApi29
            } else {
                ShizukuStatus.NeedsStartOnApi30Plus
            }
            return _status.value
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        _status.value = if (granted) ShizukuStatus.Authorized else ShizukuStatus.PermissionRequired
        if (granted) {
            bindUserServiceIfReady()
        }
        return _status.value
    }

    private fun bindUserServiceIfReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || userServiceBound) return
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            _inputBackendStatus.value = inputBridge.probeAndSelectBackend(desktopUserService)
        } catch (_: Throwable) {
        }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
            } else {
                refreshStatus()
            }
        } catch (_: Throwable) {
            refreshStatus()
        }
    }

    fun enterLayoutPreviewFallback() {
        _status.value = ShizukuStatus.LayoutPreviewFallback
        _inputBackendStatus.value = InputBackendStatus.PREVIEW_CURSOR_ONLY
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isAuthorized(): Boolean = _status.value is ShizukuStatus.Authorized

    fun listLaunchableApps(
        activeHostPackages: Set<String>,
        activeVirtualPackages: Set<String>
    ): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList = pm.queryIntentActivities(intent, 0)
        return resolveList
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val pkg = info.activityInfo.packageName
                InstalledAppInfo(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    isActiveOnHost = activeHostPackages.contains(pkg),
                    isActiveOnVirtual = activeVirtualPackages.contains(pkg)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun queryActiveHostPackages(): Set<String> {
        val raw = runCatching { desktopUserService?.queryHostForegroundPackages(0) }.getOrNull()
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun enableFreeformDesktopSupport(): Boolean {
        if (!isAuthorized()) return false
        return runCatching { desktopUserService?.enableFreeformDesktopSupport() == true }
            .getOrDefault(false)
    }

    fun launchAppOnDisplay(packageName: String, displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        val comp = launchIntent.component?.flattenToShortString()

        // Prefer privileged UserService `am start --display <displayId> --windowingMode 5` when bound
        val launchedByUserService = runCatching {
            desktopUserService?.launchAppOnDisplay(packageName, comp, displayId) == true
        }.getOrDefault(false)
        if (launchedByUserService) return true

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(launchIntent, options.toBundle())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun moveAppBackToHost(packageName: String): String {
        if (!isAuthorized()) return "Shizuku not authorized"
        return runCatching { desktopUserService?.moveAppBackToHost(packageName) }
            .getOrNull()
            ?: "Experimental relocation unavailable (UserService not bound)"
    }

    fun queryDisplayStackSnapshot(): String {
        if (!isAuthorized()) return "Shizuku not authorized"
        return runCatching { desktopUserService?.queryDisplayStackSnapshot() }
            .getOrNull()
            ?: "UserService not bound"
    }

    fun injectTouchOnDisplay(displayId: Int, action: Byte, xPx: Float, yPx: Float): Boolean {
        if (!isAuthorized()) return false
        val ok = inputBridge.dispatchTouch(displayId, action, xPx, yPx, desktopUserService)
        _inputBackendStatus.value = inputBridge.probeAndSelectBackend(desktopUserService)
        return ok
    }

    fun injectKeyOrTextOnDisplay(displayId: Int, text: String?, keyCode: Int): Boolean {
        if (!isAuthorized()) return false
        return inputBridge.dispatchKeyOrText(displayId, text, keyCode, desktopUserService)
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ANDROID_10_ADB_CMD =
            "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
        private const val SHIZUKU_PERMISSION_REQ_CODE = 1042
    }
}
