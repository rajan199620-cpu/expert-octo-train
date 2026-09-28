package com.rajan.meditationtimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/**
 * How the mind felt over the last 12 weeks: one dot per rated sit (y = Restless … Deep, x = date)
 * and a 2dp line for the rolling average of the last five, which is what shows a trend. One series,
 * so no legend; the title names it. Tap anywhere to inspect the nearest sit. The session list below
 * the chart carries every rating as text (the table view).
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MoodChart(points: List<MoodPoint>, zone: ZoneId) {
    Column(Modifier.fillMaxWidth()) {
        Text("How your sits felt", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Each dot is a sit you rated; the line is the average of your last five.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (points.size < 2) {
            Text(
                "Rate a couple of sits on the ‘Session complete’ screen and your trend will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
        val measurer = rememberTextMeasurer()
        val axisStyle = TextStyle(color = InkMuted, fontSize = 11.sp)
        val firstMs = points.first().startedAtMs
        val lastMs = points.last().startedAtMs
        val spanMs = (lastMs - firstMs).coerceAtLeast(1L)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val gutter = 76.dp.toPx()
                        val plotW = size.width - gutter - 8.dp.toPx()
                        val xs = points.map { gutter + plotW * (it.startedAtMs - firstMs) / spanMs }
                        selected = xs.indices.minBy { abs(xs[it] - tap.x) }
                    }
                },
        ) {
            val gutter = 76.dp.toPx()
            val top = 8.dp.toPx()
            val axisBand = 22.dp.toPx() // x-axis labels live inside the chart's own height
            val plotW = size.width - gutter - 8.dp.toPx()
            val plotH = size.height - top - axisBand
            fun x(p: MoodPoint) = gutter + plotW * (p.startedAtMs - firstMs) / spanMs
            fun y(v: Double) = top + plotH * (5 - v).toFloat() / 4f

            // Recessive hairline grid, one line per feeling, labelled in text ink.
            for (level in 1..5) {
                val gy = y(level.toDouble())
                drawLine(GridLine, Offset(gutter, gy), Offset(gutter + plotW, gy), strokeWidth = 1f)
                val label = measurer.measure(RATING_LABELS[level - 1], axisStyle)
                drawText(label, topLeft = Offset(0f, gy - label.size.height / 2f))
            }
            // Dates at both ends of the x-axis.
            val startLabel = measurer.measure(Instant.ofEpochMilli(firstMs).atZone(zone).format(axisDate), axisStyle)
            val endLabel = measurer.measure(Instant.ofEpochMilli(lastMs).atZone(zone).format(axisDate), axisStyle)
            val labelY = size.height - startLabel.size.height
            drawText(startLabel, topLeft = Offset(gutter, labelY))
            drawText(endLabel, topLeft = Offset(gutter + plotW - endLabel.size.width, labelY))

            // Crosshair on the selected sit.
            val sel = points[selected]
            drawLine(GridLine.copy(alpha = 0.2f), Offset(x(sel), top), Offset(x(sel), top + plotH), strokeWidth = 1f)

            // Rolling-average line: 2dp, round joins and caps.
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p), y(p.rollingAverage)) else path.lineTo(x(p), y(p.rollingAverage))
            }
            drawPath(path, MoodMark, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // One dot per sit, with a 2dp surface ring so overlaps stay legible.
            val r = 4.dp.toPx()
            val ring = 2.dp.toPx()
            points.forEachIndexed { i, p ->
                val c = Offset(x(p), y(p.rating.toDouble()))
                val radius = if (i == selected) r * 1.5f else r
                drawCircle(SurfaceRing, radius + ring, c)
                drawCircle(MoodMark.copy(alpha = if (i == selected) 1f else 0.7f), radius, c)
            }
        }

        val sel = points[selected]
        val at = Instant.ofEpochMilli(sel.startedAtMs).atZone(zone)
        Text(
            "${at.format(detailDate)}, ${at.format(detailTime)}  ·  ${RATING_LABELS[sel.rating - 1]}",
            style = MaterialTheme.typography.bodyMedium,
        )
        History.mostCommonRating(points)?.let {
            Text(
                "Most often: ${RATING_LABELS[it - 1]}  ·  ${points.size} rated sits in the last 12 weeks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
