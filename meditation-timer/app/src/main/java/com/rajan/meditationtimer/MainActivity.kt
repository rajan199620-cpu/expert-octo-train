package com.rajan.meditationtimer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private lateinit var bell: BellPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Bells use the alarm stream, so the volume keys should adjust that while the app is open.
        volumeControlStream = AudioManager.STREAM_ALARM
        bell = BellPlayer(applicationContext)
        val prefs = Prefs(this)
        setContent {
            MeditationTheme {
                MeditationApp(prefs = prefs, onTestBell = { bell.play(it) })
            }
        }
    }

    override fun onDestroy() {
        bell.release()
        super.onDestroy()
    }
}

private val Calm = darkColorScheme(
    primary = Color(0xFFB9CBA8),
    onPrimary = Color(0xFF1B2415),
    secondaryContainer = Color(0xFF2C3528),
    onSecondaryContainer = Color(0xFFD9E4CD),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE6E1D6),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE6E1D6),
    onSurfaceVariant = Color(0xFF9EA39A),
    outline = Color(0xFF4A5046),
)

@Composable
private fun MeditationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Calm, content = content)
}

@Composable
private fun MeditationApp(prefs: Prefs, onTestBell: (Float) -> Unit) {
    val state by SessionRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val records by remember { SessionLog.get(context).records }.collectAsStateWithLifecycle()
    val summary = remember(records) { History.summarize(records, ZoneId.systemDefault(), LocalDate.now()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHistory) { showHistory = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val s = state
            when {
                s is SessionState.Idle && showHistory -> HistoryScreen(summary) { showHistory = false }
                s is SessionState.Idle -> SetupScreen(prefs, summary, onTestBell, onHistory = { showHistory = true }) { config, volume ->
                    // Only for the lock-screen countdown; the session runs either way.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    MeditationService.start(context, config, volume)
                }
                s is SessionState.Running -> RunningScreen(s) { MeditationService.stop(context) }
                s is SessionState.Finished -> FinishedScreen(s, summary.currentStreak) { SessionRepository.reset() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    prefs: Prefs,
    summary: HistorySummary,
    onTestBell: (Float) -> Unit,
    onHistory: () -> Unit,
    onBegin: (SessionConfig, Float) -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(prefs.durationMin) }
    var opening by rememberSaveable { mutableIntStateOf(prefs.openingBellSec) }
    var closing by rememberSaveable { mutableIntStateOf(prefs.closingBellSec) }
    var bellAtEnd by rememberSaveable { mutableStateOf(prefs.bellAtEnd) }
    var volume by rememberSaveable { mutableFloatStateOf(prefs.volume) }

    Column(
        Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                streakLabel(summary.currentStreak),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onHistory) { Text("History") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { minutes = (minutes - 1).coerceAtLeast(1) }) { Text("−") }
            Text(
                "$minutes min",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            FilledTonalButton(onClick = { minutes = (minutes + 1).coerceAtMost(180) }) { Text("+") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for (m in listOf(5, 10, 15, 20, 30, 45, 60)) {
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m") })
            }
        }

        SectionLabel("Opening bell", "Rings this long after you tap Begin")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for (sec in listOf(5, 10, 15, 30)) {
                FilterChip(selected = opening == sec, onClick = { opening = sec }, label = { Text("${sec}s") })
            }
        }

        SectionLabel("Closing bell", "Rings this long before the session ends")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for (sec in listOf(5, 10, 30, 60)) {
                FilterChip(selected = closing == sec, onClick = { closing = sec }, label = { Text(secondsLabel(sec)) })
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Also ring when time is up", Modifier.weight(1f))
            Switch(checked = bellAtEnd, onCheckedChange = { bellAtEnd = it })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Volume")
            Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton(onClick = { onTestBell(volume) }) { Text("Test") }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                prefs.save(minutes, opening, closing, bellAtEnd, volume)
                onBegin(SessionConfig(minutes * 60, opening, closing, bellAtEnd), volume)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Begin", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunningScreen(session: SessionState.Running, onEnd: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.startElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val total = session.config.durationMs
    val elapsed = (now - session.startElapsedMs).coerceIn(0, total)
    val remaining = total - elapsed
    val next = BellSchedule.next(session.config, elapsed)
    val track = MaterialTheme.colorScheme.outline
    val arc = MaterialTheme.colorScheme.primary

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                drawArc(arc, -90f, 360f * remaining / total, useCenter = false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(remaining), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                if (next != null) {
                    Text(
                        cueHint(next, elapsed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = onEnd) { Text("End session") }
    }
}

@Composable
private fun FinishedScreen(session: SessionState.Finished, streak: Int, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Session complete", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${session.config.durationSec / 60} minutes",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (streak > 0) Text(streakLabel(streak), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone) { Text("Done") }
    }
}

private fun secondsLabel(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "${sec}s"

private fun cueHint(next: TimedCue, elapsedMs: Long): String {
    val name = when (next.cue) {
        Cue.OPENING -> "Opening bell"
        Cue.CLOSING -> "Closing bell"
        Cue.END -> "Final bell"
    }
    return "$name in ${formatClock(next.atMs - elapsedMs)}"
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun streakLabel(days: Int) = when (days) {
    0 -> "No streak yet — today is a good day to start"
    1 -> "1-day streak"
    else -> "$days-day streak"
}

@Composable
private fun HistoryScreen(summary: HistorySummary, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    LazyColumn(
        Modifier.widthIn(max = 480.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("History", style = MaterialTheme.typography.headlineSmall)
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
        item { WeekStrip(summary.last7Days) }
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
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                    Text(day.date.format(dayFormat), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(formatDuration(day.totalSec.toLong()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                for (session in day.sessions) {
                    val time = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).toLocalTime().format(timeFormat)
                    val note = if (session.completed) "" else "  ·  ended early (of ${formatDuration(session.plannedSec.toLong())})"
                    Text(
                        "$time  ·  ${formatDuration(session.actualSec.toLong())}$note",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun dayCount(days: Int) = if (days == 1) "1 day" else "$days days"

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** One dot per day for the last week: filled if you sat that day. */
@Composable
private fun WeekStrip(days: List<Pair<LocalDate, Boolean>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((date, sat) in days) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.size(14.dp).background(
                        if (sat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
            }
        }
    }
}
