package com.rajan.meditationtimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId

val RATING_LABELS = listOf("Restless", "Scattered", "Okay", "Calm", "Deep")

@Composable
fun TimerTab(session: SessionState, prefs: Prefs, onTestBell: (Float, AlertMode) -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val records by log.records.collectAsStateWithLifecycle()
    val streak = remember(records) {
        History.currentStreak(records.map { it.day(ZoneId.systemDefault()) }.toSet(), LocalDate.now())
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (session) {
            SessionState.Idle -> SetupScreen(prefs, streak, onTestBell, onHistory) { config, volume, mode, dnd ->
                // Only for the lock-screen countdown; the session runs either way.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                MeditationService.start(context, config, volume, mode, dnd)
            }
            is SessionState.Running -> RunningScreen(session) { MeditationService.stop(context) }
            is SessionState.Finished -> FinishedScreen(
                session,
                streak,
                onSave = { rating, note ->
                    if (rating > 0 || note.isNotBlank()) log.annotate(session.startedAtMs, rating, note)
                    SessionRepository.reset()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    prefs: Prefs,
    streak: Int,
    onTestBell: (Float, AlertMode) -> Unit,
    onHistory: () -> Unit,
    onBegin: (SessionConfig, Float, AlertMode, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val saved = remember { prefs.timerConfig }
    var minutes by rememberSaveable { mutableIntStateOf(saved.durationSec / 60) }
    var opening by rememberSaveable { mutableIntStateOf(saved.openingBellSec) }
    var closing by rememberSaveable { mutableIntStateOf(saved.closingBellSec) }
    var bellAtEnd by rememberSaveable { mutableStateOf(saved.bellAtEnd) }
    var interval by rememberSaveable { mutableIntStateOf(saved.intervalMin) }
    var volume by rememberSaveable { mutableFloatStateOf(prefs.volume) }
    var alertMode by rememberSaveable { mutableStateOf(prefs.alertMode) }
    var autoDnd by rememberSaveable { mutableStateOf(prefs.autoDnd) }
    val dnd = remember { Dnd(context) }
    var dndAccess by remember { mutableStateOf(dnd.hasAccess) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dndAccess = dnd.hasAccess }

    Column(
        Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                streakLabel(streak),
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
        ChipRow(listOf(5, 10, 15, 20, 30, 45, 60), minutes, { "$it" }) { minutes = it }

        SectionLabel("Opening bell", "Rings this long after you tap Begin")
        ChipRow(listOf(5, 10, 15, 30), opening, { "${it}s" }) { opening = it }

        SectionLabel("Closing bell", "Rings this long before the session ends")
        ChipRow(listOf(5, 10, 30, 60), closing, ::secondsLabel) { closing = it }

        SectionLabel("Interval bells", "A soft reminder to come back to the breath")
        ChipRow(listOf(0, 5, 10, 15), interval, { if (it == 0) "Off" else "Every $it min" }) { interval = it }

        SectionLabel("How cues reach you", "Vibrate only is for sitting next to someone")
        ChipRow(AlertMode.entries, alertMode, { it.label }) { alertMode = it }

        SwitchRow("Also ring when time is up", bellAtEnd) { bellAtEnd = it }
        SwitchRow("Silence notifications while I sit", autoDnd) {
            autoDnd = it
            if (it && !dndAccess) context.startActivity(Dnd.accessSettings)
        }
        if (autoDnd && !dndAccess) {
            TextButton(onClick = { context.startActivity(Dnd.accessSettings) }) {
                Text("Needs Do Not Disturb access — tap to allow Meditation Timer")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Volume")
            Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton(onClick = { onTestBell(volume, alertMode) }) { Text("Test") }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val config = SessionConfig(minutes * 60, opening, closing, bellAtEnd, interval)
                prefs.saveTimer(config, volume, alertMode, autoDnd)
                onBegin(config, volume, alertMode, autoDnd)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Begin", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun SectionLabel(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RunningScreen(session: SessionState.Running, onEnd: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.startElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(200)
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FinishedScreen(session: SessionState.Finished, streak: Int, onSave: (Int, String) -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Session complete", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${session.config.durationSec / 60} minutes",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (streak > 0) Text(streakLabel(streak), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        // Optional and judgement-free: noticing, not scoring.
        SectionLabel("How did the mind feel?", "Optional — helps you spot patterns later")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            RATING_LABELS.forEachIndexed { i, label ->
                FilterChip(
                    selected = rating == i + 1,
                    onClick = { rating = if (rating == i + 1) 0 else i + 1 },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(500) },
            label = { Text("A line for your journal (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(rating, note) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (rating > 0 || note.isNotBlank()) "Save" else "Done")
        }
    }
}

fun streakLabel(days: Int) = when (days) {
    0 -> "No streak yet — today is a good day to start"
    1 -> "1-day streak"
    else -> "$days-day streak"
}

private fun secondsLabel(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "${sec}s"

private fun cueHint(next: TimedCue, elapsedMs: Long): String {
    val name = when (next.cue) {
        Cue.OPENING -> "Opening bell"
        Cue.INTERVAL -> "Interval bell"
        Cue.CLOSING -> "Closing bell"
        Cue.END -> "Final bell"
    }
    return "$name in ${formatClock(next.atMs - elapsedMs)}"
}
