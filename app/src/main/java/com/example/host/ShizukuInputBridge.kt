package com.example.host

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.model.InputBackendStatus
import com.example.protocol.TouchSample
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Per-OS compatibility bridge across Android 10–15 (API 29–35) for multi-display input injection:
 * 1) Probes `IInputManager$Stub` via `ShizukuBinderWrapper` + hidden `InputEvent.setDisplayId(int)`
 * 2) Falls back to `UserService.probeShellInputDisplayFlag()` (`input -d <displayId>`) only if verified
 * 3) Surfaces unavailable status cleanly so Layout Preview cursor tracking remains intact
 */
class ShizukuInputBridge(
    private val shellTouchSlopPx: Float = DEFAULT_SHELL_TOUCH_SLOP_PX
) {

    private var inputManagerInterface: IInterface? = null
    private var injectMethod: Method? = null
    private var injectTakesUidParam: Boolean = false
    private var setDisplayIdMethod: Method? = null
    private var probed = false
    private var cachedStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY

    private var shellDownX = 0f
    private var shellDownY = 0f
    private var shellActive = false
    private var shellMovedBeyondSlop = false

    sealed interface ShellTouchPlan {
        data object None : ShellTouchPlan
        data class Tap(val xPx: Int, val yPx: Int) : ShellTouchPlan
        data class CoarseReleaseSwipe(
            val startXPx: Int,
            val startYPx: Int,
            val endXPx: Int,
            val endYPx: Int,
            val durationMs: Int = COARSE_SWIPE_DURATION_MS
        ) : ShellTouchPlan
    }

    fun probeAndSelectBackend(userService: IShizukuDesktopService?): InputBackendStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cachedStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY
            probed = true
            return cachedStatus
        }

        val binderOk = probeBinderInjection()
        val shellDFlag = try {
            userService?.probeShellInputDisplayFlag() == true
        } catch (_: Throwable) {
            false
        }
        cachedStatus = selectBackendForCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            binderInjectionSupported = binderOk,
            shellDFlagSupported = shellDFlag
        )
        probed = true
        return cachedStatus
    }

    fun resetProbe() {
        probed = false
        inputManagerInterface = null
        injectMethod = null
        setDisplayIdMethod = null
        cachedStatus = InputBackendStatus.PREVIEW_CURSOR_ONLY
        shellActive = false
        shellMovedBeyondSlop = false
    }

    /**
     * Deterministic state machine for `SHELL_INPUT_D`:
     * - `ACTION_DOWN` records anchor coordinates (spawns nothing).
     * - `ACTION_MOVE` tracks distance past [shellTouchSlopPx] in RAM (NEVER spawns subprocesses).
     * - `ACTION_UP` emits either one Tap (within slop) or one CoarseReleaseSwipe (past slop).
     */
    fun planShellTouchDispatch(action: Byte, xPx: Float, yPx: Float): ShellTouchPlan {
        return when (action) {
            TouchSample.ACTION_DOWN -> {
                shellDownX = xPx
                shellDownY = yPx
                shellActive = true
                shellMovedBeyondSlop = false
                ShellTouchPlan.None
            }
            TouchSample.ACTION_MOVE -> {
                if (shellActive && !shellMovedBeyondSlop) {
                    val dx = xPx - shellDownX
                    val dy = yPx - shellDownY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >= shellTouchSlopPx) {
                        shellMovedBeyondSlop = true
                    }
                }
                ShellTouchPlan.None
            }
            TouchSample.ACTION_UP -> {
                val plan = if (shellActive && shellMovedBeyondSlop) {
                    ShellTouchPlan.CoarseReleaseSwipe(
                        startXPx = shellDownX.toInt(),
                        startYPx = shellDownY.toInt(),
                        endXPx = xPx.toInt(),
                        endYPx = yPx.toInt()
                    )
                } else {
                    ShellTouchPlan.Tap(xPx = xPx.toInt(), yPx = yPx.toInt())
                }
                shellActive = false
                shellMovedBeyondSlop = false
                plan
            }
            else -> ShellTouchPlan.None
        }
    }

    @SuppressLint("PrivateApi")
    private fun probeBinderInjection(): Boolean {
        return try {
            val rawBinder = SystemServiceHelper.getSystemService("input") ?: return false
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val iface = asInterface.invoke(null, wrappedBinder) as? IInterface ?: return false

            val displayIdSetter = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            val methods = iface.javaClass.methods
            val threeArg = methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.size == 3
            }
            val twoArg = methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.size == 2
            }

            if (threeArg != null) {
                inputManagerInterface = iface
                injectMethod = threeArg
                injectTakesUidParam = true
                setDisplayIdMethod = displayIdSetter
                true
            } else if (twoArg != null) {
                inputManagerInterface = iface
                injectMethod = twoArg
                injectTakesUidParam = false
                setDisplayIdMethod = displayIdSetter
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun dispatchTouch(
        displayId: Int,
        action: Byte,
        xPx: Float,
        yPx: Float,
        userService: IShizukuDesktopService?
    ): Boolean {
        if (!probed) {
            probeAndSelectBackend(userService)
        }
        return when (cachedStatus) {
            InputBackendStatus.BINDER_IINPUTMANAGER -> injectMotionViaBinder(displayId, action, xPx, yPx)
            InputBackendStatus.SHELL_INPUT_D -> {
                when (val plan = planShellTouchDispatch(action, xPx, yPx)) {
                    ShellTouchPlan.None -> true
                    is ShellTouchPlan.Tap -> runCatching {
                        userService?.injectShellTouch(displayId, 0, plan.xPx, plan.yPx) == true
                    }.getOrDefault(false)
                    is ShellTouchPlan.CoarseReleaseSwipe -> runCatching {
                        userService?.injectShellSwipe(
                            displayId,
                            plan.startXPx,
                            plan.startYPx,
                            plan.endXPx,
                            plan.endYPx,
                            plan.durationMs
                        ) == true
                    }.getOrDefault(false)
                }
            }
            InputBackendStatus.PREVIEW_CURSOR_ONLY -> false
        }
    }

    fun dispatchKeyOrText(
        displayId: Int,
        text: String?,
        keyCode: Int,
        userService: IShizukuDesktopService?
    ): Boolean {
        if (!probed) {
            probeAndSelectBackend(userService)
        }
        return when (cachedStatus) {
            InputBackendStatus.BINDER_IINPUTMANAGER -> {
                if (keyCode > 0) injectKeyViaBinder(displayId, keyCode)
                else runCatching { userService?.injectShellKeyOrText(displayId, 0, text) == true }.getOrDefault(false)
            }
            InputBackendStatus.SHELL_INPUT_D -> {
                runCatching { userService?.injectShellKeyOrText(displayId, keyCode, text) == true }.getOrDefault(false)
            }
            InputBackendStatus.PREVIEW_CURSOR_ONLY -> false
        }
    }

    private fun injectMotionViaBinder(displayId: Int, action: Byte, xPx: Float, yPx: Float): Boolean {
        val iface = inputManagerInterface ?: return false
        val inject = injectMethod ?: return false
        val setDisplayId = setDisplayIdMethod ?: return false
        val androidAction = when (action) {
            TouchSample.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            TouchSample.ACTION_UP -> MotionEvent.ACTION_UP
            TouchSample.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            else -> return false
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, androidAction, xPx, yPx, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            setDisplayId.invoke(ev, displayId)
            invokeInject(iface, inject, ev)
        } catch (_: Throwable) {
            false
        } finally {
            ev.recycle()
        }
    }

    private fun injectKeyViaBinder(displayId: Int, keyCode: Int): Boolean {
        val iface = inputManagerInterface ?: return false
        val inject = injectMethod ?: return false
        val setDisplayId = setDisplayIdMethod ?: return false
        val now = SystemClock.uptimeMillis()
        return try {
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            setDisplayId.invoke(down, displayId)
            setDisplayId.invoke(up, displayId)
            invokeInject(iface, inject, down) && invokeInject(iface, inject, up)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeInject(iface: IInterface, method: Method, event: InputEvent): Boolean {
        val result = if (injectTakesUidParam) {
            // Android 13-15 signature: injectInputEventToTarget(InputEvent, int mode, int targetUid)
            method.invoke(iface, event, INJECT_INPUT_EVENT_MODE_ASYNC, android.os.Process.INVALID_UID)
        } else {
            // Android 10-12 signature: injectInputEvent(InputEvent, int mode)
            method.invoke(iface, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        }
        return (result as? Boolean) ?: true
    }

    companion object {
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val DEFAULT_SHELL_TOUCH_SLOP_PX = 8f
        const val COARSE_SWIPE_DURATION_MS = 180

        /**
         * Pure selection helper for deterministic per-OS compatibility testing (API 29..35 / Android 10..15).
         */
        fun selectBackendForCapabilities(
            sdkInt: Int,
            binderInjectionSupported: Boolean,
            shellDFlagSupported: Boolean
        ): InputBackendStatus {
            if (sdkInt < Build.VERSION_CODES.Q) return InputBackendStatus.PREVIEW_CURSOR_ONLY
            return when {
                binderInjectionSupported -> InputBackendStatus.BINDER_IINPUTMANAGER
                shellDFlagSupported -> InputBackendStatus.SHELL_INPUT_D
                else -> InputBackendStatus.PREVIEW_CURSOR_ONLY
            }
        }
    }
}
