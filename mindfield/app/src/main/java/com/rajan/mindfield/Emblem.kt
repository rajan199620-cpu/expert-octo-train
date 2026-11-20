package com.rajan.mindfield

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Evidence
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Every concept gets its own "specimen plate": a small generative drawing in its category's
 * motif, seeded by its id so it is always the same picture. Ripples for influence, a spiral for
 * the self, branching paths for choices and so on. Busted myths get a crack through them.
 *
 * Drawn with android.graphics so the same art appears in the app, the widget and notifications.
 */
object Emblems {
    fun draw(canvas: Canvas, w: Float, h: Float, concept: Concept, plate: Boolean) {
        val s = min(w, h)
        val cx = w / 2
        val cy = h / 2
        if (plate) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w, h,
                    Palettes.plate(concept.category).toArgb(), Palettes.plateEnd(concept.category).toArgb(),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(RectF(0f, 0f, w, h), s * 0.22f, s * 0.22f, bg)
        }
        val rnd = Random(concept.id.hashCode())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = s * 0.022f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val glow = Palettes.glow(concept.category).toArgb()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = android.graphics.Color.WHITE }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = glow }
        val r = s * 0.36f

        when (concept.category) {
            Category.MEMORY -> {
                // A fading trail of dots spiralling in towards one bright point: what's kept and what's lost.
                val turns = 1.4f + rnd.nextFloat()
                val n = 22
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val a = (t * turns * 2 * PI + rnd.nextFloat() * 0.2).toFloat()
                    val rr = r * (1f - t * 0.82f)
                    dot.alpha = (40 + 215 * t).toInt()
                    canvas.drawCircle(cx + rr * cos(a), cy + rr * sin(a), s * (0.012f + 0.02f * t), dot)
                }
                dot.alpha = 255
                canvas.drawCircle(cx, cy, s * 0.05f, accent)
                ink.alpha = 90
                canvas.drawCircle(cx, cy, r * 1.05f, ink)
            }
            Category.SELF -> {
                // A spiral drawn out from the centre, with a small mirrored twin.
                val path = Path()
                val turns = 2.2f + rnd.nextFloat() * 1.2f
                val steps = 160
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val a = t * turns * 2 * PI.toFloat()
                    val rr = r * t
                    val x = cx + rr * cos(a)
                    val y = cy + rr * sin(a)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                ink.alpha = 235
                canvas.drawPath(path, ink)
                canvas.drawCircle(cx + r * 0.72f, cy - r * 0.72f, s * 0.045f, accent)
            }
            Category.THINKING -> {
                // A grid of strokes all leaning the same way, except one: the bias.
                val n = 4 + rnd.nextInt(2)
                val odd = rnd.nextInt(n * n)
                val step = (r * 2) / (n - 1)
                val len = step * 0.36f
                val base = (rnd.nextFloat() * 0.6f + 0.3f)
                for (i in 0 until n) for (j in 0 until n) {
                    val x = cx - r + i * step
                    val y = cy - r + j * step
                    val isOdd = i * n + j == odd
                    val a = if (isOdd) base + PI.toFloat() / 2 else base
                    val p = if (isOdd) Paint(ink).apply { color = glow; strokeWidth = s * 0.034f } else ink.apply { alpha = 200 }
                    canvas.drawLine(x - len * cos(a), y - len * sin(a), x + len * cos(a), y + len * sin(a), p)
                }
            }
            Category.INFLUENCE -> {
                // Ripples spreading from a source, reaching a second point.
                val sx = cx - r * (0.35f + rnd.nextFloat() * 0.3f)
                val sy = cy + r * (0.35f + rnd.nextFloat() * 0.3f)
                for (k in 1..5) {
                    ink.alpha = 250 - k * 38
                    val rr = r * 0.32f * k
                    canvas.drawArc(RectF(sx - rr, sy - rr, sx + rr, sy + rr), -100f, 110f, false, ink)
                }
                canvas.drawCircle(sx, sy, s * 0.05f, accent)
                dot.alpha = 255
                canvas.drawCircle(cx + r * 0.7f, cy - r * 0.6f, s * 0.035f, dot)
            }
            Category.FEELINGS -> {
                // Layered waves, each a little calmer than the last.
                val layers = 4
                for (k in 0 until layers) {
                    val path = Path()
                    val amp = r * (0.32f - k * 0.06f) * (0.7f + rnd.nextFloat() * 0.5f)
                    val freq = 1.5f + rnd.nextFloat() * 1.2f
                    val phase = rnd.nextFloat() * 6.28f
                    val yBase = cy - r * 0.6f + k * r * 0.42f
                    for (i in 0..60) {
                        val t = i / 60f
                        val x = cx - r + t * 2 * r
                        val y = yBase + amp * sin(t * freq * 2 * PI.toFloat() + phase)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    val p = if (k == 0) Paint(ink).apply { color = glow } else ink.apply { alpha = 220 - k * 40 }
                    canvas.drawPath(path, p)
                }
            }
            Category.CONNECTION -> {
                // Overlapping circles: what two people share.
                val off = r * (0.32f + rnd.nextFloat() * 0.16f)
                val rr = r * 0.62f
                ink.alpha = 235
                canvas.drawCircle(cx - off, cy, rr, ink)
                canvas.drawCircle(cx + off, cy, rr, ink)
                val lens = Path().apply {
                    addCircle(cx - off, cy, rr, Path.Direction.CW)
                }
                val other = Path().apply { addCircle(cx + off, cy, rr, Path.Direction.CW) }
                lens.op(other, Path.Op.INTERSECT)
                accent.alpha = 200
                canvas.drawPath(lens, accent)
            }
            Category.DECISIONS -> {
                // A path that forks and forks again; one route is lit.
                val lit = rnd.nextInt(4)
                fun branch(x: Float, y: Float, len: Float, angle: Float, depth: Int, idx: Int) {
                    val x2 = x + len * cos(angle)
                    val y2 = y + len * sin(angle)
                    val onLit = depth == 0 || (idx shr (2 - depth)) == (lit shr (2 - depth))
                    val p = if (onLit) Paint(ink).apply { color = glow; strokeWidth = s * 0.03f } else ink.apply { alpha = 170 }
                    canvas.drawLine(x, y, x2, y2, p)
                    if (depth < 2) {
                        val spread = 0.55f + rnd.nextFloat() * 0.2f
                        branch(x2, y2, len * 0.72f, angle - spread, depth + 1, idx * 2)
                        branch(x2, y2, len * 0.72f, angle + spread, depth + 1, idx * 2 + 1)
                    } else {
                        canvas.drawCircle(x2, y2, s * 0.025f, if (onLit) accent else dot)
                    }
                }
                branch(cx, cy + r, r * 0.75f, -PI.toFloat() / 2, 0, 0)
            }
            Category.HABITS -> {
                // Steps rising, with a loop arrow: repetition becoming automatic.
                val n = 5
                val bw = r * 2 / (n * 1.4f)
                for (i in 0 until n) {
                    val bh = r * (0.35f + 0.33f * i) * (0.9f + rnd.nextFloat() * 0.2f)
                    val x = cx - r + i * bw * 1.4f
                    val rect = RectF(x, cy + r - bh, x + bw, cy + r)
                    val p = if (i == n - 1) Paint(accent) else Paint(dot).apply { alpha = 120 + i * 30 }
                    canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, p)
                }
                ink.alpha = 220
                canvas.drawArc(RectF(cx - r * 0.9f, cy - r * 1.05f, cx + r * 0.1f, cy - r * 0.05f), 200f, 260f, false, ink)
            }
            Category.GROUPS -> {
                // A ring of people and one standing apart.
                val n = 7 + rnd.nextInt(4)
                val apart = rnd.nextInt(n)
                for (i in 0 until n) {
                    val a = (2 * PI * i / n).toFloat()
                    val out = if (i == apart) 1.45f else 1f
                    val x = cx + r * 0.62f * out * cos(a)
                    val y = cy + r * 0.62f * out * sin(a)
                    canvas.drawCircle(x, y, s * 0.045f, if (i == apart) accent else dot.apply { alpha = 225 })
                }
                ink.alpha = 70
                canvas.drawCircle(cx, cy, r * 0.62f, ink)
            }
        }

        if (concept.evidence == Evidence.BUSTED) {
            // A crack straight through: this one didn't survive testing.
            val crack = Path().apply {
                moveTo(cx - r * 1.05f, cy - r * 0.9f)
                lineTo(cx - r * 0.25f, cy - r * 0.15f)
                lineTo(cx - r * 0.05f, cy - r * 0.35f)
                lineTo(cx + r * 0.3f, cy + r * 0.25f)
                lineTo(cx + r * 1.05f, cy + r * 0.95f)
            }
            val p = Paint(ink).apply {
                alpha = 255
                strokeWidth = s * 0.028f
                pathEffect = DashPathEffect(floatArrayOf(s * 0.05f, s * 0.025f), 0f)
            }
            canvas.drawPath(crack, p)
        }
    }

    /** For notifications and the widget. */
    fun bitmap(context: Context, concept: Concept, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), px.toFloat(), px.toFloat(), concept, plate = true)
        return b
    }
}

@Composable
fun Emblem(concept: Concept, modifier: Modifier, plate: Boolean = false) {
    Canvas(modifier) {
        drawIntoCanvas { Emblems.draw(it.nativeCanvas, size.width, size.height, concept, plate) }
    }
}
