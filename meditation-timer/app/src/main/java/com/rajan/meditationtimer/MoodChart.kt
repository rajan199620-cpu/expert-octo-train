package com.rajan.meditationtimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/** Chart marks: a lavender step validated for lightness band, chroma and contrast on the dark surface. */
private val MoodMark = Color(0xFF9580E6)
private val SurfaceRing = Color(0xFF1C1733)
private val GridLine = Color.White.copy(alpha = 0.08f)
private val axisDate = DateTimeFormatter.ofPattern("d MMM")
private val detailDate = DateTimeFormatter.ofPattern("EEE d MMM")
private val detailTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A five-sit average needs five sits; before that the line would just echo the dots. */
const val MIN_FOR_TREND = 5

/**
 * How the mind felt over the last 12 weeks: one dot per rated sit (y = Restless … Deep, x = date).
 * From [MIN_FOR_TREND] rated sits on, a 2dp line adds the rolling average of the last five, which
 * is what shows a trend. One series, so no legend. Tap to inspect the nearest sit.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MoodChart(points: List<MoodPoint>, zone: ZoneId) {
    val showTrend = points.size >= MIN_FOR_TREND
    Column(Modifier.fillMaxWidth()) {
        Text("How your sits felt", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            when {
                points.size < 2 -> "Rate a couple of sits on the ‘Session complete’ screen and they’ll appear here."
                showTrend -> "Each dot is a rated sit; the line is the average of your last five."
                else -> "Each dot is a rated sit. A trend line appears after $MIN_FOR_TREND (${points.size} so far)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (points.size < 2) return@Column

        var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
        val measurer = rememberTextMeasurer()
        val axisStyle = TextStyle(color = InkMuted, fontSize = 11.sp)
        val firstMs = points.first().startedAtMs
        val lastMs = points.last().startedAtMs
        val spanMs = (lastMs - firstMs).coerceAtLeast(1L)
        // Gutter as wide as the longest feeling label, not a fixed guess.
        val labels = remember(measurer) { RATING_LABELS.map { measurer.measure(it, axisStyle) } }
        val density = LocalDensity.current
        val gutterPx = with(density) { labels.maxOf { it.size.width } + 10.dp.toPx() }
        // End dots sit fully inside the card instead of being cut by its edge.
        val insetPx = with(density) { 10.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(180.dp)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val plotW = size.width - gutterPx - 2 * insetPx
                        val xs = points.map { gutterPx + insetPx + plotW * (it.startedAtMs - firstMs) / spanMs }
                        selected = xs.indices.minBy { abs(xs[it] - tap.x) }
                    }
                },
        ) {
            val top = 8.dp.toPx()
            // x-axis dates live inside the chart's own height, below the half of the bottom row's
            // label that hangs under its line; measured, so large system text never collides.
            val labelH = labels.maxOf { it.size.height }.toFloat()
            val axisBand = labelH * 1.5f + 4.dp.toPx()
            val plotW = size.width - gutterPx - 2 * insetPx
            val plotH = size.height - top - axisBand
            fun x(p: MoodPoint) = gutterPx + insetPx + plotW * (p.startedAtMs - firstMs) / spanMs
            fun y(v: Double) = top + plotH * (5 - v).toFloat() / 4f

            // Recessive hairline grid, one line per feeling, labelled in text ink.
            for (level in 1..5) {
                val gy = y(level.toDouble())
                drawLine(GridLine, Offset(gutterPx, gy), Offset(size.width, gy), strokeWidth = 1f)
                val label = labels[level - 1]
                drawText(label, topLeft = Offset(0f, gy - label.size.height / 2f))
            }
            // Dates under the first and last sit (one label if they share a day).
            val firstDay = Instant.ofEpochMilli(firstMs).atZone(zone).format(axisDate)
            val lastDay = Instant.ofEpochMilli(lastMs).atZone(zone).format(axisDate)
            val startLabel = measurer.measure(firstDay, axisStyle)
            val labelY = size.height - startLabel.size.height
            drawText(startLabel, topLeft = Offset(gutterPx + insetPx - startLabel.size.width / 2f, labelY))
            if (lastDay != firstDay) {
                val endLabel = measurer.measure(lastDay, axisStyle)
                drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, labelY))
            }

            if (showTrend) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(p), y(p.rollingAverage)) else path.lineTo(x(p), y(p.rollingAverage))
                }
                drawPath(path, MoodMark, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // One dot per sit, with a surface ring so overlaps stay legible; the selected one gets
            // an outline halo rather than a bigger size, so size never reads as "more".
            val r = 4.dp.toPx()
            val ring = 2.dp.toPx()
            points.forEachIndexed { i, p ->
                val c = Offset(x(p), y(p.rating.toDouble()))
                drawCircle(SurfaceRing, r + ring, c)
                drawCircle(MoodMark, r, c)
                if (i == selected) drawCircle(Ink, r + ring + 1.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
        }

        val sel = points[selected]
        val at = Instant.ofEpochMilli(sel.startedAtMs).atZone(zone)
        Text(
            "${at.format(detailDate)} · ${at.format(detailTime)} · ${RATING_LABELS[sel.rating - 1]}",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        History.mostCommonRating(points)?.let {
            Text(
                "Most often ${RATING_LABELS[it - 1]} · ${points.size} rated sits",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
