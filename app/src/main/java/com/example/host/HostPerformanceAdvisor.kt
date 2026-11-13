package com.example.host

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.model.LimitTier
import com.example.model.PerformanceAdvisory

/**
 * Host-only Performance Advisor (Decision 9).
 * Uses free RAM (ActivityManager.MemoryInfo), PowerManager.getCurrentThermalStatus() (API 29+),
 * and battery state. Deliberately avoids /proc/stat CPU reads restricted on Android 8+.
 */
class HostPerformanceAdvisor(private val context: Context) {

    fun sample(
        displayAppsCount: Int,
        shizukuAuthorized: Boolean
    ): PerformanceAdvisory {
        val freeRamPercent = readFreeRamPercent()
        val thermalStatus = readThermalStatus()
        val (batteryPercent, isCharging) = readBatteryState()

        return evaluate(
            displayAppsCount = displayAppsCount,
            shizukuAuthorized = shizukuAuthorized,
            freeRamPercent = freeRamPercent,
            thermalStatus = thermalStatus,
            batteryPercent = batteryPercent,
            isCharging = isCharging
        )
    }

    private fun readFreeRamPercent(): Int {
        return try {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 100
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val total = info.totalMem.coerceAtLeast(1L)
            ((info.availMem.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } catch (_: Throwable) {
            100
        }
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return 0
            pm.currentThermalStatus
        } catch (_: Throwable) {
            0
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        return try {
            val bm = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 } ?: 100
            val intent = context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            level to charging
        } catch (_: Throwable) {
            100 to false
        }
    }

    companion object {
        private const val THERMAL_LIGHT = 1
        private const val THERMAL_MODERATE = 3
        private const val THERMAL_SEVERE = 4

        fun evaluate(
            displayAppsCount: Int,
            shizukuAuthorized: Boolean,
            freeRamPercent: Int,
            thermalStatus: Int,
            batteryPercent: Int,
            isCharging: Boolean
        ): PerformanceAdvisory {
            val clampedRam = freeRamPercent.coerceIn(0, 100)
            val clampedBattery = batteryPercent.coerceIn(0, 100)

            val thermalLabel = when {
                thermalStatus >= THERMAL_SEVERE -> "hot"
                thermalStatus >= THERMAL_MODERATE -> "warm"
                thermalStatus == THERMAL_LIGHT -> "light"
                else -> "normal"
            }

            val showCloseWarning = thermalStatus >= THERMAL_MODERATE || clampedRam < 25

            val tier = when {
                showCloseWarning || (clampedBattery <= 15 && !isCharging) -> LimitTier.AT_LIMIT
                thermalStatus >= THERMAL_LIGHT || clampedRam < 45 || (clampedBattery <= 30 && !isCharging) ->
                    LimitTier.COMFORTABLE
                else -> LimitTier.LIGHT
            }

            return PerformanceAdvisory(
                displayAppsCount = displayAppsCount.coerceAtLeast(0),
                displayAppsLaunchedHereOnly = !shizukuAuthorized,
                freeRamPercent = clampedRam,
                thermalStatus = thermalStatus,
                thermalLabel = thermalLabel,
                batteryPercent = clampedBattery,
                suggestedLimitTier = tier,
                showCloseAppWarning = showCloseWarning
            )
        }
    }
}
