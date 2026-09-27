package com.rajan.meditationtimer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Calm = darkColorScheme(
    primary = Color(0xFFB9CBA8),
    onPrimary = Color(0xFF1B2415),
    secondaryContainer = Color(0xFF2C3528),
    onSecondaryContainer = Color(0xFFD9E4CD),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE6E1D6),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE6E1D6),
    onSurfaceVariant = Color(0xFF9EA39A),
    outline = Color(0xFF4A5046),
)

@Composable
fun MeditationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Calm, content = content)
}
