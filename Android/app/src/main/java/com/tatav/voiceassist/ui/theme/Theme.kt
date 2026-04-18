package com.tatav.voiceassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = SurfaceDark,
    primaryContainer = CyanPrimaryDark,
    secondary = YellowSecondary,
    onSecondary = SurfaceDark,
    secondaryContainer = YellowSecondaryDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = ErrorRed,
    outline = OutlineDark,
)

@Composable
fun VoiceAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = VoiceAssistTypography,
        content = content,
    )
}
