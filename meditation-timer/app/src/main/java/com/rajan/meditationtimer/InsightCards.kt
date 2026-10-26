package com.rajan.meditationtimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

private val Bar = Color(0xFF9580E6)

/** What a sit changes: the after check-in minus the before one, averaged. */
@Composable
fun CheckInCard(summary: CheckInSummary) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("What a sit changes", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        val shift = summary.averageShift
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                (if (shift > 0) "+" else if (shift < 0) "−" else "") + String.format(Locale.ROOT, "%.1f", abs(shift)),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                when {
                    shift > 0.05 -> "  steps calmer after a sit"
                    shift < -0.05 -> "  steps less settled after a sit"
                    else -> "  no change on average"
                },
                Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            "Calmer after ${summary.better} of ${summary.sits} ${if (summary.sits == 1) "sit" else "sits"}" +
                " · same ${summary.same} · less ${summary.worse}. Scale: ${CHECK_IN_LABELS.joinToString(" → ")}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Caught-wandering counts per sit, scaled to 10 minutes so a long and a short sit compare.
 * Deliberately not framed as good or bad: noticing is the skill, and early on more noticing
 * usually means sharper awareness, not a worse sit.
 */
@Composable
fun NoticingCard(points: List<NoticingPoint>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Catching the wandering mind", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Each bar is a sit: times you noticed the mind had wandered, per 10 minutes. Noticing is the skill being trained.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val shown = points.takeLast(20)
        val max = (shown.maxOfOrNull { it.perTenMin } ?: 0.0).coerceAtLeast(1.0)
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp)) {
            val gap = 4.dp.toPx()
            val slots = 20
            val w = (size.width - gap * (slots - 1)) / slots
            shown.forEachIndexed { i, p ->
                val h = (size.height * (p.perTenMin / max)).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    Bar.copy(alpha = if (i == shown.lastIndex) 1f else 0.6f),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        val last = shown.last()
        val avg = shown.map { it.perTenMin }.average()
        Text(
            "Last sit: ${last.count} ${if (last.count == 1) "time" else "times"}" +
                String.format(Locale.ROOT, " · average %.1f per 10 min over %d %s", avg, shown.size, if (shown.size == 1) "sit" else "sits"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
