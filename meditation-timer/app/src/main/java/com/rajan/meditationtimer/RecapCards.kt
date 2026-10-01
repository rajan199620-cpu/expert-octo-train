package com.rajan.meditationtimer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val shortDay = DateTimeFormatter.ofPattern("d MMM")
private val longDay = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun monthName(m: YearMonth, today: LocalDate): String {
    val name = m.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return if (m.year == today.year) name else "$name ${m.year}"
}

/** On the Sit screen: a note you wrote on this date a while ago, in your own words. */
@Composable
fun MemoryCard(memory: Memory, zone: ZoneId, onHide: () -> Unit) {
    val r = memory.record
    val date = Instant.ofEpochMilli(r.startedAtMs).atZone(zone).toLocalDate()
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(memory.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        // Long notes are cut at three lines: this is a glimpse, the full note is in History.
        Text(
            "“${r.note}”",
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val parts = buildList {
            add(date.format(if (date.year == LocalDate.now().year) shortDay else longDay))
            add(formatDuration(r.actualSec.toLong()))
            RATING_LABELS.getOrNull(r.rating - 1)?.let { add(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                parts.joinToString("  ·  "),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onHide, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Hide") }
        }
    }
}

/** On the Sit screen in the first days of a month: last month's review is ready. */
@Composable
fun RecapReadyCard(month: YearMonth, today: LocalDate, onOpen: () -> Unit, onHide: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your ${monthName(month, today)} in review", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Time sat, your best week, and what the sits changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onHide, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Hide") }
        }
    }
}

/**
 * One month summed up, opening on the last finished month, with ‹ › to step through the
 * months that have sits. Each line appears only when that month has the data behind it.
 */
@Composable
fun MonthRecapCard(records: List<SessionRecord>, zone: ZoneId, today: LocalDate) {
    val months = remember(records) { Recap.months(records, zone, today) }
    val first = Recap.defaultMonth(months, today) ?: return
    var shownText by rememberSaveable { mutableStateOf(first.toString()) }
    val shown = YearMonth.parse(shownText).takeIf { it in months } ?: first
    val index = months.indexOf(shown)
    val recap = remember(records, shown) { Recap.month(records, zone, shown, today) } ?: return

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                monthName(shown, today) + if (recap.inProgress) " so far" else " in review",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // Older months to the left, newer to the right, like a calendar.
            TextButton(
                onClick = { shownText = months[index + 1].toString() },
                enabled = index < months.lastIndex,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.widthIn(min = 40.dp),
            ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            TextButton(
                onClick = { shownText = months[index - 1].toString() },
                enabled = index > 0,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.widthIn(min = 40.dp),
            ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatDuration(recap.totalSec), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "  over ${recap.sits} ${if (recap.sits == 1) "sit" else "sits"}",
                Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        recap.previousTotalSec?.let { before ->
            val diff = recap.totalSec - before
            val previous = monthName(shown.minusMonths(1), today)
            Text(
                when {
                    // A month still under way is compared without judging it: it isn't over.
                    recap.inProgress -> "${formatDuration(before)} in all of $previous"
                    abs(diff) < 60 -> "About the same as $previous"
                    diff > 0 -> "${formatDuration(diff)} more than $previous"
                    else -> "${formatDuration(-diff)} less than $previous"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val stats = buildList {
            add("Days sat" to "${recap.daysSat} of ${recap.daysSoFar}")
            add("Best week" to "${recap.bestWeekStart.format(shortDay)} · ${formatDuration(recap.bestWeekSec)}")
            add("Longest sit" to formatDuration(recap.longestSec.toLong()))
            recap.averageShift?.let { s ->
                val v = String.format(Locale.ROOT, "%.1f", abs(s))
                add(
                    "After a sit" to when {
                        s > 0.05 -> "+$v calmer"
                        s < -0.05 -> "−$v less settled"
                        else -> "no change"
                    },
                )
            }
            recap.commonRating?.let { add("Most often felt" to RATING_LABELS[it - 1]) }
            recap.noticingPerTenMin?.let { add("Wandering caught" to String.format(Locale.ROOT, "%.1f per 10 min", it)) }
            if (recap.notes > 0) add("Journal" to if (recap.notes == 1) "1 note" else "${recap.notes} notes")
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}
