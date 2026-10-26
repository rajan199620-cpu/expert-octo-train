package com.rajan.meditationtimer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.key
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
                it.write(History.toCsv(records, zone, Prefs(context).exportSettings(), SessionLog.get(context).deleted))
            }
        }.isSuccess
        Toast.makeText(context, if (ok) "Saved ${records.size} sessions" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
    }
    val restore = rememberRestoreAction()
    // Years of sits would be thousands of rows drawn at once: show recent days, older on request.
    var daysShown by rememberSaveable { mutableIntStateOf(LOG_PAGE_DAYS) }

    val goal = remember { Prefs(context).weeklyGoal }
    val weekGoal = remember(activeDays, goal) { History.weekGoal(activeDays, today, goal) }
    val streak = remember(activeDays) { History.streak(activeDays, today) }
    val standing = remember(records) { Compare.standing(records, zone, today) }
    val progress = remember(records) { Progress.report(records, zone, today) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showBackup by rememberSaveable { mutableStateOf(false) }

    // Three short pages instead of one long one (Strava's Progress / Activities, Headspace's
    // stats-first profile): what you come for most, the week and the month, opens first; trends
    // and the full log are one tap away; backup is behind a button, as settings are elsewhere.
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your practice", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { showBackup = true }) { Text("Backup") }
        }
        if (records.isEmpty()) {
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
        } else {
            Segments(listOf("Overview", "Trends", "Sessions"), tab) { tab = it }
            // Each tab opens at its top: a list scrolled down in Overview mustn't open Trends halfway down.
            key(tab) { LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        item { WeekCard(week, summary, weekGoal, streak) }
                        progress?.let { item { StandingCard(it, standing) } }
                        item { MonthRecapCard(records, zone, today) }
                        // Headlines from Trends, each a door to the full chart (as Apple Fitness does).
                        if (checkIns != null || noticing.isNotEmpty()) {
                            item { Highlights(checkIns, noticing) { tab = 1 } }
                        }
                    }
                    1 -> {
                        checkIns?.let { item { CheckInCard(it) } }
                        item { GlassCard(Modifier.fillMaxWidth()) { MoodChart(mood, zone) } }
                        if (noticing.isNotEmpty()) item { NoticingCard(noticing) }
                        item { GlassCard(Modifier.fillMaxWidth()) { Heatmap(heatmap) } }
                    }
                    else -> item {
                        // One card for the whole log, days separated by hairlines rather than a card each.
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
                                for (session in day.sessions) {
                                    SessionLine(session, zone) { SessionLog.get(context).delete(session.startedAtMs) }
                                }
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
                item { Spacer(Modifier.height(4.dp)) }
            } }
        }
    }

    if (showBackup) {
        AppSheet("Backup", onDismiss = { showBackup = false }) {
            GoogleCard()
            // Once Google backup is on, the file backup is redundant; it returns if you disconnect.
            if (cloud.email == null) DataCard(hasRecords = records.isNotEmpty(), onBackup = { backup.launch("meditation-history-$today.csv") }, onRestore = restore)
            // Which build is installed, so "is this the new APK?" has an answer.
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text(
                "Meditation Timer${version?.let { " · version $it" } ?: ""}",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Where your practice stands now, worked out afresh from your sits every day: your level (minutes a
 * day over 4 weeks, with this week against it), how long you've kept it up and your lifetime
 * hours, each set against what published studies used or found. It rises when you sit more or
 * longer and eases when you sit less. Tap for the research behind each landmark and how you
 * compare with other meditators.
 */
@Composable
private fun StandingCard(report: ProgressReport, standing: Standing?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    GlassCard(Modifier.fillMaxWidth().clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Where you stand", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = primary)
            report.trend?.let {
                Pill(
                    when (it) {
                        Trend.BUILDING -> "↑ ${it.label}"
                        Trend.STEADY -> "→ ${it.label}"
                        Trend.EASING -> "↓ ${it.label}"
                    },
                )
            }
        }
        Text(Progress.headline(report), style = MaterialTheme.typography.displaySmall, color = primary)
        Text(Progress.windowLine(report), style = MaterialTheme.typography.bodySmall, color = muted)
        if (report.curve.size >= Progress.WEEK) LevelChart(report.curve)
        Text(Progress.runLine(report), style = MaterialTheme.typography.bodyMedium)
        Text(Progress.hoursLine(report), style = MaterialTheme.typography.bodyMedium, color = muted)
        standing?.let { Text(Compare.summary(it), style = MaterialTheme.typography.bodyMedium, color = muted) }
        if (open) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Text("What the research found", style = MaterialTheme.typography.titleSmall)
            for (dose in Progress.DOSES) CompareLine(dose.finding, dose.source)
            CompareLine(
                "Weeks 2 and 4: on an 8-week MBSR course, mindfulness had risen measurably by week 2, and stress fell by week 4.",
                "Baer et al. · Journal of Clinical Psychology · 2012",
            )
            CompareLine(Progress.habitLine(report), "Lally et al. · European Journal of Social Psychology · 2010")
            CompareLine(
                "160 hours of lifetime practice went with clearly lower distress and higher life satisfaction among 1,668 " +
                    "meditators, whose average was 1,095 hours. The yogis studied by Richard Davidson's lab had 12,000 to 62,000.",
                "Bowles et al. · Mindfulness · 2022 (corrected 2023) · Goleman & Davidson · Altered Traits · 2017",
            )
            Text("How you compare", style = MaterialTheme.typography.titleSmall)
            CompareLine(
                "Experienced meditators: 41% sit daily, 30% more than weekly, 11% weekly, 18% less often.",
                "${Compare.EXPERIENCED.detail} · ${Compare.EXPERIENCED.source}",
            )
            standing?.let {
                CompareLine(Compare.indiaLine(it), "${Compare.INDIA.detail}; includes religious meditation · ${Compare.INDIA.source}")
                CompareLine(Compare.appLine(it), "Logged use by 655 new users of the Medito app · ${Compare.APP_SOURCE}")
            }
            Text(
                "Studies describe groups, not you, and they don't promise a result at a dose: across 203 trials, longer " +
                    "programmes weren't clearly better for distress (Strohmaier, Mindfulness, 2020). Your minutes are " +
                    "timed; the studies' were mostly self-reported, which tends to flatter.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                fontStyle = FontStyle.Italic,
            )
        }
        Text(if (open) "Less" else "The research behind it  ›", style = MaterialTheme.typography.labelMedium, color = primary)
    }
}

/**
 * Your 4-week level as it stood each day, up to 12 weeks back: one line, its area a faint wash,
 * today's value as a dot, and the trial's 13 minutes a day as a dashed threshold.
 */
@Composable
private fun LevelChart(curve: List<Double>) {
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val trial = Progress.TRIAL.minutesPerDay
    val top = maxOf(curve.max() * 1.15, trial * 1.4)
    val described = "Your 4-week level over the last ${curve.size} days: from ${Progress.minutes(curve.first())} " +
        "to ${Progress.minutes(curve.last())} a day. Dashed line: the trial's 13 minutes a day."
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = described }) {
        Canvas(Modifier.fillMaxWidth().height(76.dp)) {
            val inset = 6.dp.toPx()
            val w = size.width
            val bottom = size.height - inset
            fun x(i: Int) = inset + (w - 2 * inset) * i / (curve.size - 1).coerceAtLeast(1)
            fun y(v: Double) = bottom - (bottom - inset) * (v / top).toFloat()
            // The threshold, dashed, with its label sitting just above it at the left.
            val ty = y(trial)
            drawLine(
                muted.copy(alpha = 0.6f), Offset(0f, ty), Offset(w, ty),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            val label = measurer.measure("13 min a day · trial", labelStyle)
            drawText(label, topLeft = Offset(0f, (ty - label.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            val path = Path().apply {
                moveTo(x(0), y(curve[0]))
                for (i in 1..curve.lastIndex) lineTo(x(i), y(curve[i]))
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(curve.lastIndex), bottom)
                lineTo(x(0), bottom)
                close()
            }
            drawPath(area, line.copy(alpha = 0.10f))
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val end = Offset(x(curve.lastIndex), y(curve.last()))
            drawCircle(ChartRing, radius = 6.dp.toPx(), center = end)
            drawCircle(line, radius = 4.dp.toPx(), center = end)
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (curve.size >= Progress.CURVE_DAYS) "12 weeks ago" else "${curve.size - 1} days ago",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Text("Today", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** The night sky behind the cards, so the dot's ring parts it from the line. */
private val ChartRing = Color(0xFF1A1B36)

@Composable
private fun CompareLine(text: String, source: String) {
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two headline numbers from Trends; tapping opens the full charts. */
@Composable
private fun Highlights(checkIns: CheckInSummary?, noticing: List<NoticingPoint>, onOpen: () -> Unit) {
    // Equal heights, whichever label wraps.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        checkIns?.let {
            val shift = it.averageShift
            HighlightTile(
                (if (shift > 0) "+" else if (shift < 0) "−" else "") + String.format(Locale.ROOT, "%.1f", kotlin.math.abs(shift)),
                if (shift >= 0) "calmer after a sit" else "less settled after a sit",
                Modifier.weight(1f),
                onOpen,
            )
        }
        if (noticing.isNotEmpty()) {
            val avg = noticing.takeLast(20).map { it.perTenMin }.average()
            HighlightTile(String.format(Locale.ROOT, "%.1f", avg), "wanderings caught per 10 min", Modifier.weight(1f), onOpen)
        }
    }
}

@Composable
private fun HighlightTile(value: String, label: String, modifier: Modifier, onOpen: () -> Unit) {
    GlassCard(modifier.fillMaxHeight().clickable(onClick = onOpen), padding = 16.dp) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Trends ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The headline: this week as seven dots. It resets every Monday (a fresh start) and a missed day
 * is just an empty dot, not a lost number. The streak is shown only while it's alive: an intact
 * streak motivates, a highlighted broken one discourages (Silverman & Barasch, 2023).
 */
@Composable
private fun WeekCard(week: List<Pair<LocalDate, Boolean?>>, summary: HistorySummary, goal: WeekGoal, streak: Streak) {
    val primary = MaterialTheme.colorScheme.primary
    val daysSat = week.count { it.second == true }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$daysSat", style = MaterialTheme.typography.displaySmall, color = primary)
            Text(
                when {
                    goal.goal <= 0 -> if (daysSat == 1) "  day this week" else "  days this week"
                    else -> "  of ${goal.goal} days this week"
                },
                Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (goal.goal > 0) {
            Text(
                when {
                    goal.met && goal.weeksRunning >= 2 -> "Goal met ✓  ·  ${goal.weeksRunning} weeks running"
                    goal.met -> "Goal met ✓"
                    goal.weeksRunning >= 1 -> "${dayCount(goal.daysLeft)} to go  ·  met ${if (goal.weeksRunning == 1) "last week" else "${goal.weeksRunning} weeks running"}"
                    else -> "${dayCount(goal.daysLeft)} to go"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (goal.met) primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (summary.currentStreak >= 2) add("✦\u00A0${streakLabel(summary.currentStreak)}")
            // A forgiving streak says when it forgave, so the rule is never a surprise.
            if (summary.currentStreak >= 2 && streak.restThisWeek) add("rest\u00A0day\u00A0used")
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
    var confirming by remember { mutableStateOf(false) }
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
                // One stray tap shouldn't quietly stop the backups: ask first, right here.
                confirming -> {
                    TextButton(onClick = { confirming = false; GoogleBackup.disconnect(context) }) { Text("Yes, disconnect") }
                    TextButton(onClick = { confirming = false }) { Text("Keep backing up") }
                }
                else -> {
                    TextButton(onClick = { GoogleBackup.syncNow(context) }) { Text("Back up now") }
                    TextButton(onClick = { confirming = true }) { Text("Disconnect") }
                }
            }
        }
        if (confirming && email != null && !cloud.busy) {
            Text(
                "New sits will stop being backed up. Nothing is deleted: what's on this phone and in your Drive stays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** One sit in the log. Tapping it offers to delete it (a mis-tap, a sit that wasn't one). */
@Composable
private fun SessionLine(session: SessionRecord, zone: ZoneId, onDelete: () -> Unit) {
    var asking by remember(session.startedAtMs) { mutableStateOf(false) }
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
        Modifier.fillMaxWidth().clickable(onClickLabel = "Delete this sit") { asking = !asking }.padding(vertical = 2.dp),
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
    if (asking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Delete this sit? It goes from your history and your backups.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { asking = false; onDelete() }) { Text("Delete") }
            TextButton(onClick = { asking = false }) { Text("Keep") }
        }
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
                "Restored ${log.merge(found, History.deletedFrom(text))} of ${found.size} sessions" + if (settings != null) " and your settings" else ""
            }
        }.getOrElse { "Couldn't read that file" }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    return { launcher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
}
