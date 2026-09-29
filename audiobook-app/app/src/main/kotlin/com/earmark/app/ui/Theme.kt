package com.earmark.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.earmark.app.data.ReaderTheme

/*
 * "Ink and amber": a deep ink-blue brand with a warm amber accent, on paper-coloured
 * surfaces. Deliberately not Material You dynamic colour: a reading app should feel like
 * the same calm place on every phone (Apple Books, Kindle and Audible all do this).
 */
private val Ink = Color(0xFF2D3F7C)
private val Amber = Color(0xFFE59A2F)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF0B1847),
    secondary = Color(0xFF9A5B00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2BC),
    onSecondaryContainer = Color(0xFF301800),
    tertiary = Color(0xFF2E7A6B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8F0E0),
    onTertiaryContainer = Color(0xFF00201A),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4D463B),
    surfaceContainer = Color(0xFFF3EEE6),
    surfaceContainerHigh = Color(0xFFEDE7DE),
    surfaceContainerHighest = Color(0xFFE7E1D7),
    outline = Color(0xFF7F776A),
    outlineVariant = Color(0xFFD1C8B8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C4FF),
    onPrimary = Color(0xFF15245A),
    primaryContainer = Color(0xFF2D3F7C),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFFFB961),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6B3D00),
    onSecondaryContainer = Color(0xFFFFE2BC),
    tertiary = Color(0xFF8ED6C4),
    onTertiary = Color(0xFF00382E),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E1DA),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE4E1DA),
    surfaceVariant = Color(0xFF2B2E36),
    onSurfaceVariant = Color(0xFFC6C2B9),
    surfaceContainer = Color(0xFF1B1E24),
    surfaceContainerHigh = Color(0xFF23262D),
    surfaceContainerHighest = Color(0xFF2D3038),
    outline = Color(0xFF8F8B83),
    outlineVariant = Color(0xFF45474E),
)

val SerifFamily: FontFamily = FontFamily.Serif

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun EarmarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** Colours of the reading surface, independent of the app chrome (like Kindle's page colours). */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    /** Sentences already heard fade back, so the eye finds the voice (Speechify's "karaoke" effect). */
    val readText: Color,
    val currentBackground: Color,
    val currentText: Color,
    val heading: Color,
    val accent: Color,
    val isDark: Boolean,
)

fun readerPalette(theme: ReaderTheme, systemDark: Boolean): ReaderPalette = when (theme) {
    ReaderTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF4ECD8), text = Color(0xFF3B2F1E), readText = Color(0x993B2F1E),
        currentBackground = Color(0xFFD6DEEF), currentText = Color(0xFF1D1A14), heading = Color(0xFF5B4326),
        accent = Color(0xFF9A5B00), isDark = false,
    )
    ReaderTheme.NIGHT -> ReaderPalette(
        background = Color(0xFF0E1014), text = Color(0xFFD9D5CC), readText = Color(0x80D9D5CC),
        currentBackground = Color(0xFF34488C), currentText = Color.White, heading = Color(0xFFB9C4FF),
        accent = Color(0xFFFFB961), isDark = true,
    )
    ReaderTheme.PAPER -> ReaderPalette(
        background = Color(0xFFFBF8F3), text = Color(0xFF1F1D1A), readText = Color(0x8C1F1D1A),
        currentBackground = Color(0xFFDDE3FA), currentText = Color(0xFF10183A), heading = Ink,
        accent = Amber, isDark = false,
    )
    ReaderTheme.AUTO -> readerPalette(if (systemDark) ReaderTheme.NIGHT else ReaderTheme.PAPER, systemDark)
}

fun readingStyle(scale: Float): TextStyle = TextStyle(
    fontFamily = SerifFamily,
    fontSize = (19 * scale).sp,
    lineHeight = (19 * scale * 1.62f).sp,
    letterSpacing = 0.1.sp,
)

/** Deterministic two-colour gradients for generated covers. */
val CoverPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFF2D3F7C) to Color(0xFF6A7FD1),
    Color(0xFF8C3B2E) to Color(0xFFE59A2F),
    Color(0xFF2E7A6B) to Color(0xFF8ED6C4),
    Color(0xFF5B2E7A) to Color(0xFFC08AE0),
    Color(0xFF1F4E5F) to Color(0xFF5FB0C9),
    Color(0xFF7A2E4F) to Color(0xFFF08BB0),
    Color(0xFF4A5A23) to Color(0xFFB8C96A),
    Color(0xFF3B3B3B) to Color(0xFF9A9A9A),
)
