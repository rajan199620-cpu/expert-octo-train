package com.rajan.meditationtimer

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Warm off-white text and a softer lilac-grey for secondary text, both easy on the eyes at night. */
val Ink = Color(0xFFF3EEE6)
val InkMuted = Color(0xFFB4AFC4)
private val OnAccent = Color(0xFF1A1426)
private val Glass = Color.White.copy(alpha = 0.06f)
private val GlassEdge = Color.White.copy(alpha = 0.09f)

/** Deep dusk sky: indigo at the top fading to night teal. */
private val NightSky = Brush.verticalGradient(listOf(Color(0xFF1B1535), Color(0xFF151E38), Color(0xFF0D1823)))

/** Each part of the app has its own light, so it never looks like one grey screen. */
data class Accent(val main: Color, val second: Color)

object Accents {
    val SIT = Accent(Color(0xFFF5C97B), Color(0xFFEE9A8C)) // candle amber -> rose
    val BREATHE = Accent(Color(0xFF7ED8C8), Color(0xFF7AB4F5)) // sea teal -> sky
    val MALA = Accent(Color(0xFFF6A96B), Color(0xFFF5D38A)) // saffron -> gold
    val HISTORY = Accent(Color(0xFFB9A7F5), Color(0xFFF0A6CA)) // lavender -> pink
}

val Tab.accent: Accent
    get() = when (this) {
        Tab.SIT -> Accents.SIT
        Tab.BREATHE -> Accents.BREATHE
        Tab.MALA -> Accents.MALA
        Tab.HISTORY -> Accents.HISTORY
    }

val LocalAccent = staticCompositionLocalOf { Accents.SIT }

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light),
    displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light),
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light),
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
)

private fun schemeFor(accent: Accent) = darkColorScheme(
    primary = accent.main,
    onPrimary = OnAccent,
    secondaryContainer = accent.main.copy(alpha = 0.22f),
    onSecondaryContainer = accent.main,
    background = Color.Transparent,
    onBackground = Ink,
    surface = Color.Transparent,
    onSurface = Ink,
    surfaceVariant = Color.White.copy(alpha = 0.08f),
    onSurfaceVariant = InkMuted,
    surfaceContainerHighest = Color.White.copy(alpha = 0.10f),
    outline = Color.White.copy(alpha = 0.18f),
    outlineVariant = Color.White.copy(alpha = 0.10f),
)

@Composable
fun MeditationTheme(accent: Accent = Accents.SIT, content: @Composable () -> Unit) {
    val scheme = remember(accent) { schemeFor(accent) }
    // There is no opaque Surface behind the content (the sky gradient shows through), so set the
    // default text colour here; otherwise any Text without an explicit colour falls back to black.
    CompositionLocalProvider(LocalAccent provides accent, LocalContentColor provides Ink) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

/** Night-sky gradient with two soft pools of the current accent light drifting slowly. */
@Composable
fun AmbientBackground(accent: Accent, content: @Composable BoxScope.() -> Unit) {
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(NightSky)
            .drawBehind {
                val w = size.width
                val h = size.height
                val c1 = Offset(w * (0.15f + 0.25f * drift), h * (0.10f + 0.06f * drift))
                val r1 = w * 0.95f
                drawCircle(Brush.radialGradient(listOf(accent.main.copy(alpha = 0.26f), Color.Transparent), c1, r1), r1, c1)
                val c2 = Offset(w * (0.9f - 0.3f * drift), h * (0.80f - 0.08f * drift))
                val r2 = w * 1.1f
                drawCircle(Brush.radialGradient(listOf(accent.second.copy(alpha = 0.16f), Color.Transparent), c2, r2), r2, c2)
            },
        content = content,
    )
}

/** Frosted-glass panel used to group settings and stats. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassEdge, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The main call to action: a pill glowing from the accent into its partner colour. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A slight give under the finger makes the button feel physical.
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(29.dp))
            .background(Brush.horizontalGradient(listOf(accent.main, accent.second)))
            .clickable(interaction, LocalIndication.current) { view.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = OnAccent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A light tap felt under the finger, so a press registers even with eyes closed. */
fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

enum class ControlIcon { PAUSE, PLAY, STOP }

/** Round glass media control with its label underneath; the whole column is the touch target. */
@Composable
fun ControlButton(label: String, icon: ControlIcon, onClick: () -> Unit) {
    val view = LocalView.current
    val color = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { view.tick(); onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(Glass).border(1.dp, GlassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val s = size.minDimension
                val corner = CornerRadius(s * 0.08f)
                when (icon) {
                    ControlIcon.PAUSE -> {
                        val bar = Size(s * 0.28f, s)
                        drawRoundRect(color, Offset(s * 0.1f, 0f), bar, corner)
                        drawRoundRect(color, Offset(s * 0.62f, 0f), bar, corner)
                    }
                    ControlIcon.PLAY -> drawPath(
                        Path().apply {
                            moveTo(s * 0.18f, 0f)
                            lineTo(s * 0.95f, s / 2)
                            lineTo(s * 0.18f, s)
                            close()
                        },
                        color,
                    )
                    ControlIcon.STOP -> drawRoundRect(color, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), CornerRadius(s * 0.14f))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RoundButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun Pill(text: String) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(primary.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Soft halo behind a focal element (timer ring, breathing orb, mala). */
fun Modifier.glow(color: Color, strength: Float = 1f) = drawBehind {
    val r = size.minDimension * 0.62f
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.30f * strength), Color.Transparent), center, r), r, center)
}

/** Small line icons for the tab bar, drawn by hand so no icon library is needed. */
@Composable
fun TabIcon(tab: Tab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        val c = center
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        when (tab) {
            Tab.SIT -> {
                // Lotus: three petals over a base line.
                val petal = Size(s * 0.28f, s * 0.58f)
                val pivot = Offset(c.x, c.y + s * 0.24f)
                for (deg in listOf(-52f, 0f, 52f)) {
                    rotate(deg, pivot) {
                        drawOval(color, Offset(c.x - petal.width / 2, pivot.y - petal.height), petal, style = stroke)
                    }
                }
                drawLine(color, Offset(c.x - s * 0.42f, pivot.y + s * 0.1f), Offset(c.x + s * 0.42f, pivot.y + s * 0.1f), stroke.width, StrokeCap.Round)
            }
            Tab.BREATHE -> {
                drawCircle(color, radius = s * 0.17f)
                drawCircle(color, radius = s * 0.42f, style = stroke)
            }
            Tab.MALA -> {
                val r = s * 0.34f
                for (i in 1 until 10) {
                    val a = Math.toRadians(90.0 + 36.0 * i)
                    drawCircle(color, s * 0.065f, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
                }
                drawCircle(color, s * 0.11f, Offset(c.x, c.y + r))
            }
            Tab.HISTORY -> {
                listOf(0.35f, 0.7f, 0.5f).forEachIndexed { i, h ->
                    val x = c.x + (i - 1) * s * 0.3f
                    drawLine(color, Offset(x, s * 0.86f), Offset(x, s * 0.86f - s * h), s * 0.16f, StrokeCap.Round)
                }
            }
        }
    }
}
