package com.rajan.mindfield

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.rajan.mindfield.core.Category
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One bar split into coloured parts, in proportion. */
@Composable
fun SplitBar(segments: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    val p = palette
    val total = segments.sumOf { it.first }
    Row(modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(p.raised)) {
        if (total > 0) {
            segments.filter { it.first > 0 }.forEach { (n, color) ->
                Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(color))
            }
        }
    }
}

/**
 * Where you notice psychology: one spoke per category, its length the square root of your
 * sightings there (so one busy area doesn't flatten the rest).
 */
@Composable
fun RadarChart(counts: Map<Category, Int>, modifier: Modifier = Modifier) {
    val p = palette
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = p.muted)
    val max = max(1.0, sqrt(counts.values.maxOrNull()?.toDouble() ?: 1.0))
    val description = counts.entries.joinToString { "${it.key.short} ${it.value}" }
    Canvas(modifier.fillMaxWidth().aspectRatio(1.15f).semantics { contentDescription = "Sightings by area: $description" }) {
        val c = center
        val r = size.minDimension * 0.33f
        val cats = Category.entries
        val n = cats.size
        fun point(i: Int, frac: Float): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(c.x + r * frac * cos(a).toFloat(), c.y + r * frac * sin(a).toFloat())
        }
        for (ring in 1..4) {
            val path = Path()
            for (i in 0..n) {
                val pt = point(i % n, ring / 4f)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(path, p.line, style = Stroke(1.dp.toPx()))
        }
        for (i in 0 until n) drawLine(p.line, c, point(i, 1f), 1.dp.toPx())
        val shape = Path()
        for (i in 0..n) {
            val v = sqrt((counts[cats[i % n]] ?: 0).toDouble()) / max
            val pt = point(i % n, v.toFloat().coerceAtLeast(0.04f))
            if (i == 0) shape.moveTo(pt.x, pt.y) else shape.lineTo(pt.x, pt.y)
        }
        drawPath(shape, p.brand.copy(alpha = 0.18f))
        drawPath(shape, p.brand, style = Stroke(2.dp.toPx()))
        for (i in 0 until n) {
            val v = sqrt((counts[cats[i]] ?: 0).toDouble()) / max
            drawCircle(Palettes.accent(cats[i], p.dark), 4.dp.toPx(), point(i, v.toFloat().coerceAtLeast(0.04f)))
            label(measurer, cats[i].short, point(i, 1.28f), labelStyle)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: androidx.compose.ui.text.TextStyle,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

/** The last weeks as a grid, Monday at the top; darker means more field notes that day. */
@Composable
fun Heatmap(weeks: List<List<Pair<LocalDate, Int>>>, today: LocalDate, color: Color, modifier: Modifier = Modifier) {
    val p = palette
    val peak = weeks.flatten().maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(weeks.size / 7f).semantics { contentDescription = "Field notes over the last ${weeks.size} weeks" }) {
            val cols = weeks.size
            val gap = 3.dp.toPx()
            val cell = (size.width - gap * (cols - 1)) / cols
            weeks.forEachIndexed { w, days ->
                days.forEachIndexed { d, (day, n) ->
                    val x = w * (cell + gap)
                    val y = d * (cell + gap)
                    val fill = when {
                        day > today -> Color.Transparent
                        n == 0 -> p.raised
                        else -> color.copy(alpha = 0.35f + 0.65f * n / peak)
                    }
                    drawRoundRect(fill, Offset(x, y), Size(cell, cell), CornerRadius(cell * 0.25f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = p.faint)
            listOf(0f, 0.4f, 0.7f, 1f).forEach { a ->
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (a == 0f) p.raised else color.copy(alpha = 0.35f + 0.65f * a)))
            }
            Text("More", style = MaterialTheme.typography.labelSmall, color = p.faint)
        }
    }
}
