package com.example.host

import kotlin.math.hypot

data class ObservedTouchEvent(
    val displayId: Int,
    val xPx: Float,
    val yPx: Float,
    val timestampMs: Long
)

data class StrayEventTelemetry(
    val xPx: Float,
    val yPx: Float,
    val distancePx: Float,
    val deltaMs: Long
)

data class InputReceiptEvaluation(
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val targetRadiusPx: Float,
    val matchTolerancePx: Float,
    val baselineCleanDisplay0: Boolean,
    val virtualDisplayMatched: Boolean,
    val display0LeakDetected: Boolean,
    val nearestStrayDisplay0: StrayEventTelemetry?,
    val passed: Boolean,
    val summaryLine: String
)

/**
 * Deterministic verifier for the On-Device Hardware Verification Runner:
 * - Measures target radius dynamically from live on-screen bounds (`minOf(w, h) / 2f`)
 * - Derives `matchTolerancePx = maxOf(4f, targetRadiusPx * 0.5f)`
 * - Requires a clean 2s baseline on `displayId 0` prior to injection
 * - Evaluates `[tInjectMs, tInjectMs + 1500]` coordinate & timestamp receipts on the VirtualDisplay
 *   vs `displayId 0`, always logging `(x, y, distancePx, deltaMs)` of the nearest stray `displayId 0` event.
 */
object HardwareVerificationRunner {
    const val RECEIPT_WINDOW_MS = 1_500L
    const val BASELINE_WINDOW_MS = 2_000L

    fun computeTargetRadiusPx(widthPx: Int, heightPx: Int): Float =
        minOf(widthPx.coerceAtLeast(0), heightPx.coerceAtLeast(0)) / 2f

    fun computeMatchTolerancePx(targetRadiusPx: Float): Float =
        maxOf(4f, targetRadiusPx * 0.5f)

    fun evaluateInputReceiptGate(
        targetWidthPx: Int,
        targetHeightPx: Int,
        injectedXPx: Float,
        injectedYPx: Float,
        tInjectMs: Long,
        baselineDisplay0Events: List<ObservedTouchEvent>,
        virtualDisplayEvents: List<ObservedTouchEvent>,
        display0Events: List<ObservedTouchEvent>
    ): InputReceiptEvaluation {
        val targetRadiusPx = computeTargetRadiusPx(targetWidthPx, targetHeightPx)
        val matchTolerancePx = computeMatchTolerancePx(targetRadiusPx)
        val baselineClean = targetRadiusPx > 0f && baselineDisplay0Events.isEmpty()

        val windowEndMs = tInjectMs + RECEIPT_WINDOW_MS
        val inWindowVirtual = virtualDisplayEvents.filter {
            it.timestampMs in tInjectMs..windowEndMs
        }
        val inWindowDisplay0 = display0Events.filter {
            it.timestampMs in tInjectMs..windowEndMs
        }

        val vdMatched = inWindowVirtual.any { ev ->
            hypot((ev.xPx - injectedXPx).toDouble(), (ev.yPx - injectedYPx).toDouble()) <= matchTolerancePx
        }

        var nearestStray: StrayEventTelemetry? = null
        var display0Leak = false
        for (ev in inWindowDisplay0) {
            val dist = hypot((ev.xPx - injectedXPx).toDouble(), (ev.yPx - injectedYPx).toDouble()).toFloat()
            if (dist <= matchTolerancePx) {
                display0Leak = true
            }
            if (nearestStray == null || dist < nearestStray.distancePx) {
                nearestStray = StrayEventTelemetry(
                    xPx = ev.xPx,
                    yPx = ev.yPx,
                    distancePx = dist,
                    deltaMs = ev.timestampMs - tInjectMs
                )
            }
        }

        val passed = baselineClean && vdMatched && !display0Leak
        val straySummary = nearestStray?.let {
            "nearestStrayDisplay0=(${it.xPx.toInt()}, ${it.yPx.toInt()}, dist=${"%.1f".format(it.distancePx)}px, +${it.deltaMs}ms)"
        } ?: "nearestStrayDisplay0=none"

        val summary = buildString {
            append(if (passed) "PASS" else "FAIL")
            append(" · radius=${"%.1f".format(targetRadiusPx)}px tol=${"%.1f".format(matchTolerancePx)}px")
            append(" · baseline=${if (baselineClean) "clean" else "dirty"}")
            append(" · vdHit=$vdMatched · d0Leak=$display0Leak · $straySummary")
        }

        return InputReceiptEvaluation(
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            targetRadiusPx = targetRadiusPx,
            matchTolerancePx = matchTolerancePx,
            baselineCleanDisplay0 = baselineClean,
            virtualDisplayMatched = vdMatched,
            display0LeakDetected = display0Leak,
            nearestStrayDisplay0 = nearestStray,
            passed = passed,
            summaryLine = summary
        )
    }
}
