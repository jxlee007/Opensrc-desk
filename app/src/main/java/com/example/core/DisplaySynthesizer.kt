package com.example.core

import com.example.model.DisplayProfile
import com.example.model.ResolutionCeiling
import com.example.model.VirtualDisplayConfig
import kotlin.math.roundToInt

object DisplaySynthesizer {

    fun defaultCeilingForProfile(profile: DisplayProfile): ResolutionCeiling {
        val shortEdge = minOf(profile.widthPixels, profile.heightPixels)
        return when {
            shortEdge <= 720 -> ResolutionCeiling.P720
            shortEdge <= 800 -> ResolutionCeiling.P800 // e.g. Galaxy Tab 2 1280x800
            shortEdge <= 1080 -> ResolutionCeiling.P1080
            else -> ResolutionCeiling.P1200
        }
    }

    fun synthesize(
        profile: DisplayProfile,
        ceiling: ResolutionCeiling = defaultCeilingForProfile(profile),
        forceTabletLayout: Boolean = profile.isTabletClass,
        manualDpiOverride: Int? = null,
        prefer24GhzBitrate: Boolean = !profile.supports5GhzWifi
    ): VirtualDisplayConfig {
        val nativeShort = minOf(profile.widthPixels, profile.heightPixels)
        val targetShortRaw = minOf(nativeShort, ceiling.shortEdgeCap)
        val targetShort = alignTo16(targetShortRaw).coerceAtLeast(480)
        val targetLong = alignTo16((targetShort * profile.aspectRatioValue).roundToInt())
            .coerceAtLeast(targetShort)

        val autoDpi = when {
            forceTabletLayout || profile.isTabletClass -> when (ceiling) {
                ResolutionCeiling.P720, ResolutionCeiling.P800 -> 160
                ResolutionCeiling.P1080 -> 180
                ResolutionCeiling.P1200 -> 213
            }
            else -> when (ceiling) {
                ResolutionCeiling.P720, ResolutionCeiling.P800 -> 240
                ResolutionCeiling.P1080 -> 280
                ResolutionCeiling.P1200 -> 320
            }
        }

        val finalDpi = (manualDpiOverride ?: autoDpi).coerceIn(120, 360)

        val bitrateMbps = if (prefer24GhzBitrate) {
            when (ceiling) {
                ResolutionCeiling.P720, ResolutionCeiling.P800 -> 4
                ResolutionCeiling.P1080 -> 5
                ResolutionCeiling.P1200 -> 6
            }
        } else {
            when (ceiling) {
                ResolutionCeiling.P720, ResolutionCeiling.P800 -> 6
                ResolutionCeiling.P1080 -> 8
                ResolutionCeiling.P1200 -> 10
            }
        }

        return VirtualDisplayConfig(
            targetWidth = targetLong,
            targetHeight = targetShort,
            targetDpi = finalDpi,
            forceTabletLayout = forceTabletLayout,
            resolutionCeiling = ceiling,
            refreshRateFps = 60,
            bitrateMbps = bitrateMbps,
            is24GhzAdapted = prefer24GhzBitrate
        )
    }

    private fun alignTo16(value: Int): Int {
        return ((value + 8) / 16) * 16
    }
}
