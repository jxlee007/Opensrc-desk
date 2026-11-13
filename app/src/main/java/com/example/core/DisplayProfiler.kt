package com.example.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.example.model.DisplayProfile
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class DisplayProfiler(private val context: Context) {

    @Suppress("DEPRECATION")
    fun captureRealMetrics(deviceId: String): DisplayProfile {
        val metrics = DisplayMetrics()
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }

        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        val xdpi = if (metrics.xdpi > 40f) metrics.xdpi else metrics.densityDpi.toFloat()
        val ydpi = if (metrics.ydpi > 40f) metrics.ydpi else metrics.densityDpi.toFloat()

        val physicalWidthInches = width / xdpi
        val physicalHeightInches = height / ydpi
        val diagonalInches = hypot(physicalWidthInches.toDouble(), physicalHeightInches.toDouble()).toFloat()

        val densityScale = (metrics.densityDpi.toFloat() / 160f).coerceAtLeast(0.75f)
        val smallestWidthDp = (min(width, height) / densityScale).roundToInt()
        val aspectValue = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        val aspectLabel = computeAspectRatioLabel(width, height)

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val supports5Ghz = try {
            wifiManager?.is5GHzBandSupported ?: false
        } catch (_: Throwable) {
            false
        }

        val isTabletClass = diagonalInches >= 7.0f || smallestWidthDp >= 600
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

        return DisplayProfile(
            deviceId = deviceId,
            deviceName = deviceName,
            sdkInt = Build.VERSION.SDK_INT,
            widthPixels = width,
            heightPixels = height,
            densityDpi = metrics.densityDpi,
            xdpi = xdpi,
            ydpi = ydpi,
            aspectRatioLabel = aspectLabel,
            aspectRatioValue = aspectValue,
            physicalDiagonalInches = diagonalInches,
            smallestWidthDp = smallestWidthDp,
            supports5GhzWifi = supports5Ghz,
            isTabletClass = isTabletClass
        )
    }

    private fun computeAspectRatioLabel(width: Int, height: Int): String {
        val g = gcd(width, height)
        val rw = width / g
        val rh = height / g
        if (rw <= 25 && rh <= 25) return "$rw:$rh"
        val ratio = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        return when {
            kotlin.math.abs(ratio - 16f / 10f) < 0.04f -> "16:10"
            kotlin.math.abs(ratio - 16f / 9f) < 0.04f -> "16:9"
            kotlin.math.abs(ratio - 4f / 3f) < 0.04f -> "4:3"
            kotlin.math.abs(ratio - 3f / 2f) < 0.04f -> "3:2"
            else -> "${"%.2f".format(ratio)}:1"
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x.coerceAtLeast(1)
    }
}
