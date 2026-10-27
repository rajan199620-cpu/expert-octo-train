package com.rajan.mindfield

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rajan.mindfield.core.Mode
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's line icons, drawn by hand so no icon library is needed and every icon shares one
 * stroke. They replace emoji in the interface: emoji differ from phone to phone, clash with the
 * line icons in the tab bar, and a screen reader reads them aloud ("eyes, Spotted it").
 */
enum class Glyph {
    EYE, MIRROR, TARGET, MOON, BULB, FLASK, GLOBE, SEARCH, WARNING, NOTEBOOK, COMPASS, SHARE, LOCK, FLAME,
    BELL, BELL_OFF, DISK, CLOUD, FOLDER, CARDS, STAR, CALENDAR, CLOCK, SUN, GEAR, PEN, BURST, BARS, SPROUT,
    CHECK, SCALE, PALETTE, CHEVRON_RIGHT, CHEVRON_DOWN, ARROW_LEFT,
}

/** The icon for each answer to "how did it show up?". */
val Mode.glyph: Glyph
    get() = when (this) {
        Mode.SPOTTED -> Glyph.EYE
        Mode.MYSELF -> Glyph.MIRROR
        Mode.USED -> Glyph.TARGET
        Mode.NOT_TODAY -> Glyph.MOON
    }

/** One icon; decorative, so it adds nothing for a screen reader (the label next to it says it all). */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val s = size.minDimension
    val w = (s * 0.09f).coerceAtLeast(1.2.dp.toPx())
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), w, StrokeCap.Round)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(color, r * s, p(x, y)) else drawCircle(color, r * s, p(x, y), style = stroke)
    fun rect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f) =
        drawRoundRect(color, p(x, y), Size(rw * s, rh * s), CornerRadius(r * s), style = stroke)
    fun path(build: Path.() -> Unit, fill: Boolean = false) {
        val path = Path().apply(build)
        if (fill) drawPath(path, color) else drawPath(path, color, style = stroke)
    }
    fun Path.m(x: Float, y: Float) = moveTo(x * s, y * s)
    fun Path.l(x: Float, y: Float) = lineTo(x * s, y * s)
    fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s)
    fun rays(cx: Float, cy: Float, r1: Float, r2: Float, n: Int, start: Double = 0.0) {
        for (i in 0 until n) {
            val a = Math.toRadians(start + 360.0 * i / n)
            line(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat())
        }
    }

    when (glyph) {
        Glyph.EYE -> {
            path({ m(0.06f, 0.5f); c(0.28f, 0.16f, 0.72f, 0.16f, 0.94f, 0.5f); c(0.72f, 0.84f, 0.28f, 0.84f, 0.06f, 0.5f); close() })
            circle(0.5f, 0.5f, 0.13f)
        }
        Glyph.MIRROR -> {
            // A hand mirror: you caught it in yourself.
            drawOval(color, p(0.24f, 0.06f), Size(0.52f * s, 0.6f * s), style = stroke)
            line(0.5f, 0.66f, 0.5f, 0.94f)
            line(0.38f, 0.26f, 0.48f, 0.18f)
        }
        Glyph.TARGET -> {
            circle(0.5f, 0.5f, 0.4f)
            circle(0.5f, 0.5f, 0.22f)
            circle(0.5f, 0.5f, 0.06f, fill = true)
        }
        Glyph.MOON -> path({ m(0.62f, 0.1f); c(0.1f, 0.14f, 0.1f, 0.86f, 0.62f, 0.9f); c(0.34f, 0.78f, 0.34f, 0.22f, 0.62f, 0.1f); close() })
        Glyph.BULB -> {
            path({ m(0.38f, 0.68f); c(0.38f, 0.56f, 0.2f, 0.5f, 0.2f, 0.36f); c(0.2f, 0.18f, 0.34f, 0.06f, 0.5f, 0.06f); c(0.66f, 0.06f, 0.8f, 0.18f, 0.8f, 0.36f); c(0.8f, 0.5f, 0.62f, 0.56f, 0.62f, 0.68f); close() })
            line(0.4f, 0.8f, 0.6f, 0.8f)
            line(0.44f, 0.92f, 0.56f, 0.92f)
        }
        Glyph.FLASK -> {
            path({ m(0.38f, 0.08f); l(0.38f, 0.4f); l(0.14f, 0.88f); l(0.86f, 0.88f); l(0.62f, 0.4f); l(0.62f, 0.08f) })
            line(0.3f, 0.08f, 0.7f, 0.08f)
            line(0.25f, 0.66f, 0.75f, 0.66f)
        }
        Glyph.GLOBE -> {
            circle(0.5f, 0.5f, 0.42f)
            drawOval(color, p(0.32f, 0.08f), Size(0.36f * s, 0.84f * s), style = stroke)
            line(0.08f, 0.5f, 0.92f, 0.5f)
        }
        Glyph.SEARCH -> {
            circle(0.42f, 0.42f, 0.28f)
            line(0.63f, 0.63f, 0.9f, 0.9f)
        }
        Glyph.WARNING -> {
            path({ m(0.5f, 0.08f); l(0.94f, 0.88f); l(0.06f, 0.88f); close() })
            line(0.5f, 0.38f, 0.5f, 0.6f)
            circle(0.5f, 0.74f, 0.035f, fill = true)
        }
        Glyph.NOTEBOOK -> {
            rect(0.18f, 0.08f, 0.64f, 0.84f)
            for (i in 0 until 3) line(0.32f, 0.32f + 0.18f * i, 0.68f, 0.32f + 0.18f * i)
        }
        Glyph.COMPASS -> {
            circle(0.5f, 0.5f, 0.42f)
            path({ m(0.66f, 0.34f); l(0.56f, 0.56f); l(0.34f, 0.66f); l(0.44f, 0.44f); close() })
        }
        Glyph.SHARE -> {
            circle(0.74f, 0.2f, 0.12f)
            circle(0.26f, 0.5f, 0.12f)
            circle(0.74f, 0.8f, 0.12f)
            line(0.36f, 0.44f, 0.64f, 0.27f)
            line(0.36f, 0.56f, 0.64f, 0.73f)
        }
        Glyph.LOCK -> {
            rect(0.2f, 0.44f, 0.6f, 0.46f)
            path({ m(0.32f, 0.44f); l(0.32f, 0.3f); c(0.32f, 0.06f, 0.68f, 0.06f, 0.68f, 0.3f); l(0.68f, 0.44f) })
        }
        Glyph.FLAME -> path(
            {
                m(0.5f, 0.06f); c(0.62f, 0.3f, 0.84f, 0.4f, 0.84f, 0.64f); c(0.84f, 0.84f, 0.68f, 0.94f, 0.5f, 0.94f)
                c(0.32f, 0.94f, 0.16f, 0.84f, 0.16f, 0.64f); c(0.16f, 0.48f, 0.28f, 0.38f, 0.34f, 0.26f)
                c(0.38f, 0.36f, 0.42f, 0.42f, 0.48f, 0.44f); c(0.54f, 0.32f, 0.52f, 0.18f, 0.5f, 0.06f); close()
            },
        )
        Glyph.BELL, Glyph.BELL_OFF -> {
            path({ m(0.22f, 0.72f); c(0.3f, 0.62f, 0.3f, 0.52f, 0.3f, 0.42f); c(0.3f, 0.24f, 0.4f, 0.14f, 0.5f, 0.14f); c(0.6f, 0.14f, 0.7f, 0.24f, 0.7f, 0.42f); c(0.7f, 0.52f, 0.7f, 0.62f, 0.78f, 0.72f); close() })
            line(0.42f, 0.86f, 0.58f, 0.86f)
            if (glyph == Glyph.BELL_OFF) line(0.12f, 0.1f, 0.88f, 0.9f)
        }
        Glyph.DISK -> {
            rect(0.12f, 0.12f, 0.76f, 0.76f)
            rect(0.3f, 0.12f, 0.4f, 0.24f, 0.02f)
            circle(0.5f, 0.64f, 0.1f)
        }
        Glyph.CLOUD -> path({ m(0.26f, 0.76f); c(0.08f, 0.76f, 0.08f, 0.5f, 0.28f, 0.5f); c(0.3f, 0.28f, 0.58f, 0.22f, 0.66f, 0.42f); c(0.84f, 0.38f, 0.96f, 0.6f, 0.84f, 0.7f); c(0.8f, 0.74f, 0.78f, 0.76f, 0.72f, 0.76f); close() })
        Glyph.FOLDER -> path({ m(0.08f, 0.82f); l(0.08f, 0.2f); l(0.38f, 0.2f); l(0.46f, 0.3f); l(0.92f, 0.3f); l(0.92f, 0.82f); close() })
        Glyph.CARDS -> {
            drawRoundRect(color.copy(alpha = 0.55f), p(0.24f, 0.1f), Size(0.6f * s, 0.5f * s), CornerRadius(0.08f * s), style = stroke)
            rect(0.14f, 0.36f, 0.6f, 0.5f)
        }
        Glyph.STAR -> path(
            {
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + 36.0 * i)
                    val r = if (i % 2 == 0) 0.44f else 0.19f
                    val x = 0.5f + r * cos(a).toFloat()
                    val y = 0.54f + r * sin(a).toFloat()
                    if (i == 0) m(x, y) else l(x, y)
                }
                close()
            },
        )
        Glyph.CALENDAR -> {
            rect(0.1f, 0.18f, 0.8f, 0.72f)
            line(0.1f, 0.4f, 0.9f, 0.4f)
            line(0.32f, 0.08f, 0.32f, 0.26f)
            line(0.68f, 0.08f, 0.68f, 0.26f)
        }
        Glyph.CLOCK -> {
            circle(0.5f, 0.5f, 0.42f)
            line(0.5f, 0.5f, 0.5f, 0.26f)
            line(0.5f, 0.5f, 0.68f, 0.6f)
        }
        Glyph.SUN -> {
            circle(0.5f, 0.5f, 0.18f)
            rays(0.5f, 0.5f, 0.3f, 0.44f, 8)
        }
        Glyph.GEAR -> {
            circle(0.5f, 0.5f, 0.27f)
            circle(0.5f, 0.5f, 0.1f)
            rays(0.5f, 0.5f, 0.3f, 0.44f, 8, 22.5)
        }
        Glyph.PEN -> {
            path({ m(0.16f, 0.84f); l(0.2f, 0.66f); l(0.68f, 0.18f); l(0.82f, 0.32f); l(0.34f, 0.8f); close() })
            line(0.58f, 0.28f, 0.72f, 0.42f)
        }
        Glyph.BURST -> rays(0.5f, 0.5f, 0.16f, 0.44f, 8)
        Glyph.BARS -> {
            line(0.2f, 0.88f, 0.2f, 0.56f)
            line(0.5f, 0.88f, 0.5f, 0.2f)
            line(0.8f, 0.88f, 0.8f, 0.42f)
        }
        Glyph.SPROUT -> {
            line(0.5f, 0.92f, 0.5f, 0.42f)
            path({ m(0.5f, 0.56f); c(0.5f, 0.3f, 0.3f, 0.22f, 0.12f, 0.24f); c(0.12f, 0.46f, 0.3f, 0.58f, 0.5f, 0.56f) })
            path({ m(0.5f, 0.44f); c(0.5f, 0.2f, 0.7f, 0.1f, 0.88f, 0.12f); c(0.88f, 0.34f, 0.7f, 0.46f, 0.5f, 0.44f) })
        }
        Glyph.CHECK -> path({ m(0.16f, 0.52f); l(0.4f, 0.76f); l(0.86f, 0.26f) })
        Glyph.SCALE -> {
            line(0.5f, 0.12f, 0.5f, 0.88f)
            line(0.14f, 0.26f, 0.86f, 0.26f)
            line(0.34f, 0.88f, 0.66f, 0.88f)
            path({ m(0.06f, 0.56f); l(0.14f, 0.28f); l(0.22f, 0.56f) })
            path({ m(0.78f, 0.56f); l(0.86f, 0.28f); l(0.94f, 0.56f) })
            line(0.04f, 0.56f, 0.24f, 0.56f)
            line(0.76f, 0.56f, 0.96f, 0.56f)
        }
        Glyph.PALETTE -> {
            path({ m(0.5f, 0.08f); c(0.24f, 0.08f, 0.08f, 0.28f, 0.08f, 0.5f); c(0.08f, 0.74f, 0.28f, 0.92f, 0.5f, 0.92f); c(0.6f, 0.92f, 0.62f, 0.84f, 0.58f, 0.76f); c(0.54f, 0.68f, 0.6f, 0.6f, 0.7f, 0.62f); c(0.84f, 0.64f, 0.92f, 0.56f, 0.92f, 0.46f); c(0.92f, 0.24f, 0.74f, 0.08f, 0.5f, 0.08f); close() })
            circle(0.3f, 0.44f, 0.05f, fill = true)
            circle(0.44f, 0.26f, 0.05f, fill = true)
            circle(0.66f, 0.3f, 0.05f, fill = true)
        }
        Glyph.CHEVRON_RIGHT -> path({ m(0.36f, 0.16f); l(0.7f, 0.5f); l(0.36f, 0.84f) })
        Glyph.CHEVRON_DOWN -> path({ m(0.16f, 0.36f); l(0.5f, 0.7f); l(0.84f, 0.36f) })
        Glyph.ARROW_LEFT -> {
            line(0.86f, 0.5f, 0.14f, 0.5f)
            path({ m(0.42f, 0.2f); l(0.14f, 0.5f); l(0.42f, 0.8f) })
        }
    }
}
