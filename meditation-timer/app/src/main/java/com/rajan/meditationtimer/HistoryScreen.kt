package com.rajan.meditationtimer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
fun HistoryTab() {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val records by log.records.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val summary = remember(records) { History.summarize(records, zone, today) }
    val heatmap = remember(records) { History.heatmap(records, zone, today) }

    // Back up = save a CSV file you keep (Drive, Downloads...); Restore = read one back.
    // Together they carry your history to a new phone or across a reinstall.
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(History.toCsv(records, zone)) }
        }.isSuccess
        Toast.makeText(context, if (ok) "Saved ${records.size} sessions" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val found = History.fromCsv(text, zone)
            if (found.isEmpty()) "No sessions found in that file" else "Restored ${log.merge(found)} of ${found.size} sessions"
        }.getOrElse { "Couldn't read that file" }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    LazyColumn(
        Modifier.widthIn(max = 480.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Column {
                Text("Your practice", style = MaterialTheme.typography.headlineMedium)
                Row {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = { backup.launch("meditation-history-$today.csv") }) { Text("Back up") }
                    }
                    TextButton(onClick = {
                        restore.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    }) { Text("Restore") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Current streak", dayCount(summary.currentStreak), Modifier.weight(1f))
                StatCard("Longest streak", dayCount(summary.longestStreak), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Last 7 days", formatDuration(summary.last7DaysSec), Modifier.weight(1f))
                StatCard(
                    "All time",
                    "${formatDuration(summary.totalSec)}\n${summary.sessionCount} sessions",
                    Modifier.weight(1f),
                )
            }
        }
        item { GlassCard(Modifier.fillMaxWidth()) { Heatmap(heatmap) } }
        if (summary.days.isEmpty()) {
            item {
                Text(
                    "Your sessions will appear here.",
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(summary.days, key = { it.date.toEpochDay() }) { day ->
            GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Text(day.date.format(dayFormat), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatDuration(day.totalSec.toLong()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                for (session in day.sessions) SessionLine(session, zone)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SessionLine(session: SessionRecord, zone: ZoneId) {
    val time = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).toLocalTime().format(timeFormat)
    val parts = buildList {
        add(time)
        add(formatDuration(session.actualSec.toLong()))
        if (!session.completed) add("ended early (of ${formatDuration(session.plannedSec.toLong())})")
        RATING_LABELS.getOrNull(session.rating - 1)?.let { add(it) }
    }
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (session.note.isNotBlank()) {
        Text(
            "“${session.note}”",
            Modifier.padding(start = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
        )
    }
}

private fun dayCount(days: Int) = if (days == 1) "1 day" else "$days days"

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier, padding = 16.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** 12 weeks x 7 days; each square darker the longer you sat that day. */
@Composable
private fun Heatmap(weeks: List<List<Int?>>) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = Color.White.copy(alpha = 0.07f)
    fun shade(minutes: Int?): Color = when {
        minutes == null -> Color.Transparent
        minutes == 0 -> empty
        minutes < 10 -> primary.copy(alpha = 0.35f)
        minutes < 20 -> primary.copy(alpha = 0.6f)
        minutes < 40 -> primary.copy(alpha = 0.8f)
        else -> primary
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Last 12 weeks", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in 0 until 7) {
                    Box(Modifier.size(width = 14.dp, height = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            java.time.DayOfWeek.of(d + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            for (week in weeks) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (minutes in week) {
                        Box(Modifier.size(16.dp).background(shade(minutes), RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (m in listOf(0, 5, 15, 30, 45)) Box(Modifier.size(10.dp).background(shade(m), CircleShape))
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
