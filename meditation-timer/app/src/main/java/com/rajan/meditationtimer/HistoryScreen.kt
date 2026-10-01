package com.rajan.meditationtimer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private val syncFormat = DateTimeFormatter.ofPattern("d MMM, h:mm a")

private const val LOG_PAGE_DAYS = 30

@Composable
fun HistoryTab() {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val records by log.records.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val summary = remember(records) { History.summarize(records, zone, today) }
    val activeDays = remember(records) { records.map { it.day(zone) }.toSet() }
    val week = remember(activeDays) { History.week(activeDays, today) }
    val weeks = remember(activeDays) { History.weeksToShow(activeDays.minOrNull(), today) }
    val heatmap = remember(records, weeks) { History.heatmap(records, zone, today, weeks) }
    val mood = remember(records) { History.moodTrend(records, zone, today) }
    val checkIns = remember(records) { History.checkInSummary(records) }
    val noticing = remember(records) { History.noticing(records, zone, today) }
    LaunchedEffect(Unit) { GoogleBackup.load(context) }
    val cloud by GoogleBackup.state.collectAsStateWithLifecycle()

    // Back up = save a CSV file you keep (Drive, Downloads...); Restore = read one back.
    // Together they carry your history to a new phone or across a reinstall.
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                it.write(History.toCsv(records, zone, Prefs(context).exportSettings()))
            }
        }.isSuccess
        Toast.makeText(context, if (ok) "Saved ${records.size} sessions" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
    }
    val restore = rememberRestoreAction()
    // Years of sits would be thousands of rows drawn at once: show recent days, older on request.
    var daysShown by rememberSaveable { mutableIntStateOf(LOG_PAGE_DAYS) }

    // Order follows what you come here for: how this week is going, how sits felt, the calendar,
    // then the log. Rarely used backup controls sit at the bottom (progressive disclosure).
    LazyColumn(
        Modifier.widthIn(max = 480.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Your practice", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
        }
        if (records.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Your sits will appear here.", style = MaterialTheme.typography.titleMedium)
                    if (AutoBackup.supported) {
                        Text(
                            "Reinstalled the app? Restore brings back your history from ${AutoBackup.LOCATION}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = restore) { Text("Restore history") }
                    }
                }
            }
        } else {
            item { WeekCard(week, summary) }
            item { MonthRecapCard(records, zone, today) }
            checkIns?.let { item { CheckInCard(it) } }
            item { GlassCard(Modifier.fillMaxWidth()) { MoodChart(mood, zone) } }
            if (noticing.isNotEmpty()) item { NoticingCard(noticing) }
            item { GlassCard(Modifier.fillMaxWidth()) { Heatmap(heatmap) } }
            item {
                Text(
                    "Sessions",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // One card for the whole log, days separated by hairlines rather than a card each.
            item {
                GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    summary.days.take(daysShown).forEachIndexed { i, day ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
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
                    val hidden = summary.days.size - daysShown
                    if (hidden > 0) {
                        TextButton(onClick = { daysShown += LOG_PAGE_DAYS * 2 }) {
                            Text(if (hidden == 1) "Show 1 earlier day" else "Show earlier days ($hidden more)")
                        }
                    }
                }
            }
        }
        item { GoogleCard() }
        // Once Google backup is on, the file backup card is redundant; it returns if you disconnect.
        if (cloud.email == null) item { DataCard(hasRecords = records.isNotEmpty(), onBackup = { backup.launch("meditation-history-$today.csv") }, onRestore = restore) }
        // Which build is installed, so "is this the new APK?" has an answer.
        item {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text(
                "Meditation Timer${version?.let { " · version $it" } ?: ""}",
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The headline: this week as seven dots. It resets every Monday (a fresh start) and a missed day
 * is just an empty dot, not a lost number. The streak is shown only while it's alive: an intact
 * streak motivates, a highlighted broken one discourages (Silverman & Barasch, 2023).
 */
@Composable
private fun WeekCard(week: List<Pair<LocalDate, Boolean?>>, summary: HistorySummary) {
    val primary = MaterialTheme.colorScheme.primary
    val daysSat = week.count { it.second == true }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$daysSat", style = MaterialTheme.typography.displaySmall, color = primary)
            Text(
                if (daysSat == 1) "  day this week" else "  days this week",
                Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((date, sat) in week) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                when (sat) {
                                    true -> primary
                                    false -> Color.White.copy(alpha = 0.08f)
                                    null -> Color.Transparent
                                },
                            )
                            .border(1.dp, if (sat == null) Color.White.copy(alpha = 0.10f) else Color.Transparent, CircleShape),
                    )
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val parts = buildList {
            add("${formatDuration(summary.last7DaysSec)} in the last 7 days")
            if (summary.currentStreak >= 2) add("✦ ${streakLabel(summary.currentStreak)}")
        }
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "All time: ${formatDuration(summary.totalSec)} over ${summary.sessionCount} ${if (summary.sessionCount == 1) "sit" else "sits"}" +
                "  ·  Longest streak ${dayCount(summary.longestStreak)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Optional Google account backup. Connecting restores any earlier backup (a reinstall or a new
 * phone), then every change is uploaded to a private app folder in the user's Google Drive.
 */
@Composable
private fun GoogleCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { GoogleBackup.load(context) }
    val cloud by GoogleBackup.state.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        GoogleBackup.onConsentResult(context, result.data)
    }
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Text("Google account", style = MaterialTheme.typography.titleMedium)
        val email = cloud.email
        if (email == null) {
            Text(
                "Back up your sits, journal notes and settings to your Google Drive, in a private folder only " +
                    "this app can see. After a reinstall or on a new phone, connect again to get everything back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(email, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (cloud.lastSyncMs > 0) {
                    val at = Instant.ofEpochMilli(cloud.lastSyncMs).atZone(ZoneId.systemDefault())
                    "Backed up ${at.format(syncFormat)}. New sits are saved automatically."
                } else {
                    "Connected. New sits are saved automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        cloud.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                cloud.busy -> Text(
                    "Working…",
                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                email == null -> TextButton(onClick = { GoogleBackup.connect(context) { consent.launch(it) } }) {
                    Text("Connect Google account")
                }
                else -> {
                    TextButton(onClick = { GoogleBackup.syncNow(context) }) { Text("Back up now") }
                    TextButton(onClick = { GoogleBackup.disconnect(context) }) { Text("Disconnect") }
                }
            }
        }
    }
}

/** Backup and restore: needed rarely, so they live at the bottom, with the automatic copy explained. */
@Composable
private fun DataCard(hasRecords: Boolean, onBackup: () -> Unit, onRestore: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Text("Your data", style = MaterialTheme.typography.titleMedium)
        if (AutoBackup.supported) {
            Text(
                "Saved automatically, without journal notes, to ${AutoBackup.LOCATION}. " +
                    "Back up saves everything, notes included, wherever you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            if (hasRecords) TextButton(onClick = onBackup) { Text("Back up") }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

@Composable
private fun SessionLine(session: SessionRecord, zone: ZoneId) {
    val time = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).toLocalTime().format(timeFormat)
    val parts = buildList {
        add(time)
        add(formatDuration(session.actualSec.toLong()))
        if (!session.completed) add("of ${formatDuration(session.plannedSec.toLong())}, ended early")
        RATING_LABELS.getOrNull(session.rating - 1)?.let { add(it) }
        if (session.before in 1..5 && session.after in 1..5) {
            add("${CHECK_IN_LABELS[session.before - 1]} → ${CHECK_IN_LABELS[session.after - 1]}")
        }
        if (session.noticed >= 0) add("noticed ${session.noticed}×")
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

/** Up to 12 weeks x 7 days; each square darker the longer you sat that day. */
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
        Text("Last ${weeks.size} weeks", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
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

/** Picks a backup CSV (opening in the auto-backup folder) and merges it into the history. */
@Composable
fun rememberRestoreAction(): () -> Unit {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val launcher = rememberLauncherForActivityResult(OpenBackup()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val found = History.fromCsv(text, ZoneId.systemDefault())
            val settings = History.settingsFrom(text)
            if (found.isEmpty() && settings == null) {
                "No sessions found in that file"
            } else {
                AutoBackup.adopt(context, uri)
                settings?.let { Prefs(context).importSettings(it) }
                "Restored ${log.merge(found)} of ${found.size} sessions" + if (settings != null) " and your settings" else ""
            }
        }.getOrElse { "Couldn't read that file" }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    return { launcher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
}
