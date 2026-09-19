package com.audioape.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AudioApeDarkColorScheme =
    darkColorScheme(
        primary = AudioApeColors.AccentPrimary,
        onPrimary = AudioApeColors.TextPrimary,
        primaryContainer = AudioApeColors.AccentPressed,
        onPrimaryContainer = AudioApeColors.TextPrimary,
        background = AudioApeColors.BackgroundBase,
        onBackground = AudioApeColors.TextPrimary,
        surface = AudioApeColors.BackgroundRaised,
        onSurface = AudioApeColors.TextPrimary,
        surfaceVariant = AudioApeColors.BackgroundOverlay,
        onSurfaceVariant = AudioApeColors.TextSecondary,
        outline = AudioApeColors.BorderSubtle,
        error = AudioApeColors.StatusError,
        onError = AudioApeColors.BackgroundBase,
    )

@Composable
internal fun AudioApeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AudioApeDarkColorScheme,
        content = content,
    )
}
