package com.example.host

import com.example.model.MirrorContentBounds
import kotlin.math.roundToInt

/**
 * Pure geometry helpers for Host Screen Mirroring (Phase 3):
 * - Computes centered-fit normalized content RectF ([0..1]) when mirroring Host Display 0
 *   into the persistent encoder surface (e.g., 1280x800).
 * - Maps normalized Display touches back to Host Display 0 pixel coordinates while ignoring
 *   taps that land inside letterbox or pillarbox margins.
 * - Computes even-dimension host-aspect-matched VirtualDisplay sizes when an OEM ROM
 *   stretches VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR instead of letterboxing.
 */
object MirrorGeometry {

    fun computeCenteredFitBounds(
        hostWidthPx: Int,
        hostHeightPx: Int,
        encoderWidthPx: Int,
        encoderHeightPx: Int
    ): MirrorContentBounds {
        if (hostWidthPx <= 0 || hostHeightPx <= 0 || encoderWidthPx <= 0 || encoderHeightPx <= 0) {
            return MirrorContentBounds(
                normLeft = 0f,
                normTop = 0f,
                normRight = 1f,
                normBottom = 1f,
                hostWidthPx = hostWidthPx.coerceAtLeast(1),
                hostHeightPx = hostHeightPx.coerceAtLeast(1)
            )
        }
        val hostAspect = hostWidthPx.toFloat() / hostHeightPx.toFloat()
        val encAspect = encoderWidthPx.toFloat() / encoderHeightPx.toFloat()

        return if (hostAspect < encAspect) {
            // Host is narrower than encoder (e.g., portrait phone inside 16:10 tablet) -> pillarboxed
            val normWidth = (hostAspect / encAspect).coerceIn(0.01f, 1f)
            val marginX = ((1f - normWidth) * 0.5f).coerceIn(0f, 0.49f)
            MirrorContentBounds(
                normLeft = marginX,
                normTop = 0f,
                normRight = 1f - marginX,
                normBottom = 1f,
                hostWidthPx = hostWidthPx,
                hostHeightPx = hostHeightPx
            )
        } else if (hostAspect > encAspect) {
            // Host is wider than encoder -> letterboxed
            val normHeight = (encAspect / hostAspect).coerceIn(0.01f, 1f)
            val marginY = ((1f - normHeight) * 0.5f).coerceIn(0f, 0.49f)
            MirrorContentBounds(
                normLeft = 0f,
                normTop = marginY,
                normRight = 1f,
                normBottom = 1f - marginY,
                hostWidthPx = hostWidthPx,
                hostHeightPx = hostHeightPx
            )
        } else {
            MirrorContentBounds(
                normLeft = 0f,
                normTop = 0f,
                normRight = 1f,
                normBottom = 1f,
                hostWidthPx = hostWidthPx,
                hostHeightPx = hostHeightPx
            )
        }
    }

    fun computeAspectFitSurfaceSize(
        hostWidthPx: Int,
        hostHeightPx: Int,
        maxWidthPx: Int,
        maxHeightPx: Int
    ): Pair<Int, Int> {
        if (hostWidthPx <= 0 || hostHeightPx <= 0 || maxWidthPx <= 0 || maxHeightPx <= 0) {
            return maxWidthPx.coerceAtLeast(2) to maxHeightPx.coerceAtLeast(2)
        }
        val scale = minOf(
            maxWidthPx.toFloat() / hostWidthPx.toFloat(),
            maxHeightPx.toFloat() / hostHeightPx.toFloat()
        )
        val rawW = (hostWidthPx * scale).roundToInt().coerceIn(2, maxWidthPx)
        val rawH = (hostHeightPx * scale).roundToInt().coerceIn(2, maxHeightPx)
        // H.264 encoders require even dimensions
        val evenW = (rawW and 1.inv()).coerceAtLeast(2)
        val evenH = (rawH and 1.inv()).coerceAtLeast(2)
        return evenW to evenH
    }

    /**
     * Maps normalized Display coordinates [0..1] into Host Display 0 pixel coordinates, or
     * returns null if the touch lands inside the letterbox/pillarbox margins.
     */
    fun mapDisplayTouchToHostDisplay0(
        normX: Float,
        normY: Float,
        bounds: MirrorContentBounds?
    ): Pair<Float, Float>? {
        val b = bounds ?: return null
        val contentWidthNorm = b.normRight - b.normLeft
        val contentHeightNorm = b.normBottom - b.normTop
        if (contentWidthNorm <= 0f || contentHeightNorm <= 0f) return null
        if (normX < b.normLeft || normX > b.normRight || normY < b.normTop || normY > b.normBottom) {
            return null
        }
        val relX = ((normX - b.normLeft) / contentWidthNorm).coerceIn(0f, 1f)
        val relY = ((normY - b.normTop) / contentHeightNorm).coerceIn(0f, 1f)
        val maxX = (b.hostWidthPx - 1).coerceAtLeast(0).toFloat()
        val maxY = (b.hostHeightPx - 1).coerceAtLeast(0).toFloat()
        return (relX * maxX) to (relY * maxY)
    }
}
