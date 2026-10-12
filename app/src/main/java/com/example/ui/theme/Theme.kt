package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GeraApkMilitaryColorScheme = darkColorScheme(
    primary = MilitaryGreenLight,
    onPrimary = OnCyanPrimary,
    primaryContainer = MilitaryGreenContainer,
    onPrimaryContainer = TextPrimary,
    secondary = EmeraldAccent,
    onSecondary = OnEmeraldAccent,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberWarning,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = TextPrimary,
    error = RoseError,
    errorContainer = RoseContainer,
    onError = Color.White,
    background = SlateBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GeraApkMilitaryColorScheme,
        typography = Typography,
        content = content
    )
}
