package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val WorkstationDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = CyanMuted,
    onPrimaryContainer = TextPrimary,
    secondary = EmeraldOnline,
    onSecondary = TextOnAccent,
    tertiary = AmberWarning,
    onTertiary = TextOnAccent,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = CrimsonError,
    onError = TextPrimary
)

private val WorkstationShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp)
)

@Composable
fun ExtendedDesktopTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WorkstationDarkColorScheme,
        typography = ExtendedDesktopTypography,
        shapes = WorkstationShapes,
        content = content
    )
}
