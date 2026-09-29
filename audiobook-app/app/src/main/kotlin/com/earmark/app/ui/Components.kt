package com.earmark.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A generated cover, since documents rarely carry one: a gradient chosen from the title,
 * the title set in serif, and a bookmark ribbon (Apple Books' placeholder covers, warmer).
 */
@Composable
fun BookCover(title: String, author: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (top, bottom) = CoverPalettes[Math.floorMod(title.hashCode(), CoverPalettes.size)]
    Box(
        modifier
            .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
            .background(Brush.linearGradient(listOf(top, bottom)))
            .drawBehind {
                // Spine shadow and a ribbon in the top-right corner.
                drawRect(Color.Black.copy(alpha = 0.18f), size = size.copy(width = size.width * 0.06f))
                val w = size.width * 0.12f
                val x = size.width * 0.78f
                val ribbon = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x + w, 0f)
                    lineTo(x + w, size.height * 0.22f)
                    lineTo(x + w / 2, size.height * 0.17f)
                    lineTo(x, size.height * 0.22f)
                    close()
                }
                drawPath(ribbon, Color(0xFFFFD27A))
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = if (compact) 12.dp else 18.dp, end = 12.dp, top = if (compact) 14.dp else 22.dp, bottom = 12.dp)) {
            Text(
                title,
                color = Color.White,
                fontFamily = SerifFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 15.sp else 20.sp,
                lineHeight = if (compact) 18.sp else 24.sp,
                maxLines = if (compact) 4 else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 18.dp),
            )
            Spacer(Modifier.weight(1f))
            author?.let {
                Text(it.uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = if (compact) 9.sp else 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Three bouncing bars shown next to the chapter title while narrating. */
@Composable
fun EqualizerBars(playing: Boolean, color: Color, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "eq")
    val bars = listOf(520, 380, 640).mapIndexed { i, ms ->
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar$i",
        )
    }
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEach { anim ->
            val fraction = if (playing) anim.value else 0.25f
            Box(Modifier.width(3.dp).fillMaxHeight(fraction).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** Expanding rings behind the mic while it listens (voice-assistant convention). */
@Composable
fun PulseRings(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        for (k in 0..1) {
            val p = (phase.value + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = size.minDimension / 2f * (0.6f + 0.6f * p))
        }
    }
}

/** A live "voice" waveform for the listening card. */
@Composable
fun ListeningWave(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t = transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "t")
    Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(9) { i ->
            val h = 0.3f + 0.7f * ((kotlin.math.sin(t.value + i * 0.7f) + 1f) / 2f)
            Box(Modifier.width(4.dp).fillMaxHeight(h).clip(CircleShape).background(color))
        }
    }
}

/** Small scale animation for buttons that just did something (bookmark filled). */
fun Modifier.pop(scale: Float): Modifier = graphicsLayer { scaleX = scale; scaleY = scale }

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
