package com.rajan.meditationtimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val RATING_LABELS = listOf("Restless", "Scattered", "Okay", "Calm", "Deep")

@Composable
fun TimerTab(
    session: SessionState,
    prefs: Prefs,
    onTestBell: (Float, AlertMode) -> Unit,
    onHistory: () -> Unit,
    onPrinciples: () -> Unit,
) {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val records by log.records.collectAsStateWithLifecycle()
    val streak = remember(records) {
        History.currentStreak(records.map { it.day(ZoneId.systemDefault()) }.toSet(), LocalDate.now())
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val restore = rememberRestoreAction()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (session) {
            SessionState.Idle -> SetupScreen(
                prefs, streak, hasHistory = records.isNotEmpty(), onTestBell, onHistory, onRestore = restore, onPrinciples,
            ) { config, volume, mode, dnd ->
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

private fun greeting(): String = when (LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Quiet night"
}

@Composable
private fun SetupScreen(
    prefs: Prefs,
    streak: Int,
    hasHistory: Boolean,
    onTestBell: (Float, AlertMode) -> Unit,
    onHistory: () -> Unit,
    onRestore: () -> Unit,
    onPrinciples: () -> Unit,
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

    val lesson = rememberLessonIndex()

    // Begin stays pinned at the bottom; everything else scrolls above it.
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (streak > 0) "✦ ${streakLabel(streak)}" else "Settle in whenever you're ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onHistory) { Text("History") }
        }

        // After an update (which currently needs a reinstall), one tap brings the history back.
        if (!hasHistory && AutoBackup.supported) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Updated or reinstalled the app?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bring back your sessions from the automatic backup in ${AutoBackup.LOCATION}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRestore) { Text("Restore history") }
            }
        }

        PrincipleCard(lesson, onOpenAll = onPrinciples)

        DurationDial(minutes, onMinus = { minutes = (minutes - 1).coerceAtLeast(1) }, onPlus = { minutes = (minutes + 1).coerceAtMost(180) })
        ChipRow(listOf(5, 10, 15, 20, 30, 45, 60), minutes, { "$it" }, center = true) { minutes = it }

        GlassCard(Modifier.fillMaxWidth()) {
            CardTitle("Bells")
            SectionLabel("Opening bell", "Rings this long after you tap Begin")
            ChipRow(listOf(5, 10, 15, 30), opening, { "${it}s" }) { opening = it }
            SectionLabel("Closing bell", "Rings this long before the session ends")
            ChipRow(listOf(5, 10, 30, 60), closing, ::secondsLabel) { closing = it }
            SectionLabel("Interval bells", "A soft reminder to come back to the breath")
            ChipRow(listOf(0, 5, 10, 15), interval, { if (it == 0) "Off" else "Every $it min" }) { interval = it }
            SwitchRow("Also ring when time is up", bellAtEnd) { bellAtEnd = it }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            CardTitle("Sound & stillness")
            SectionLabel("How cues reach you", "Vibrate only is for sitting next to someone")
            ChipRow(AlertMode.entries, alertMode, { it.label }) { alertMode = it }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Volume")
                Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                TextButton(onClick = { onTestBell(volume, alertMode) }) { Text("Test") }
            }
            SwitchRow("Silence notifications while I sit", autoDnd) {
                autoDnd = it
                if (it && !dndAccess) context.startActivity(Dnd.accessSettings)
            }
            if (autoDnd && !dndAccess) {
                TextButton(onClick = { context.startActivity(Dnd.accessSettings) }) {
                    Text("Needs Do Not Disturb access — tap to allow")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
    GradientButton(
        "Begin  ·  $minutes min",
        onClick = {
            val config = SessionConfig(minutes * 60, opening, closing, bellAtEnd, interval)
            prefs.saveTimer(config, volume, alertMode, autoDnd)
            onBegin(config, volume, alertMode, autoDnd)
        },
        modifier = Modifier.padding(vertical = 8.dp),
    )
    }
}

/** Big glowing ring showing the chosen length; a full ring is one hour. */
@Composable
private fun DurationDial(minutes: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundButton("−", onMinus)
        Box(Modifier.size(200.dp).glow(accent.main), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val stroke = 8.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(stroke))
                rotate(-90f) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.second, accent.main, accent.second)),
                        startAngle = 0f,
                        sweepAngle = 360f * minutes.coerceAtMost(60) / 60f,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$minutes", style = MaterialTheme.typography.displayLarge)
                Text("minutes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RoundButton("+", onPlus)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, center: Boolean = false, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (center) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun SectionLabel(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
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
    val accent = LocalAccent.current
    // The halo swells and fades over ~11 s, about the length of one slow breath.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_500), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(300.dp).glow(accent.main, pulse), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                val stroke = 6.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(stroke))
                rotate(-90f) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.second, accent.main, accent.second)),
                        startAngle = 0f,
                        sweepAngle = 360f * remaining / total,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(remaining), style = MaterialTheme.typography.displayLarge)
                if (next != null) {
                    Text(
                        cueHint(next, elapsed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "Today’s focus: ${Principles.forLesson(rememberLessonIndex()).title}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Close your eyes. The bell will call you back.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onEnd) { Text("End session", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FinishedScreen(session: SessionState.Finished, streak: Int, onSave: (Int, String) -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    val accent = LocalAccent.current
    val focus = LocalFocusManager.current

    Column(
        Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(160.dp).glow(accent.main), contentAlignment = Alignment.Center) {
            Text("${session.config.durationSec / 60}", style = MaterialTheme.typography.displayLarge)
        }
        Text("minutes of stillness", style = MaterialTheme.typography.headlineSmall)
        if (streak > 0) Pill("✦ ${streakLabel(streak)}")

        // Optional and judgement-free: noticing, not scoring.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("How did the mind feel?", "Optional — helps you spot patterns later")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                label = { Text("A line for your journal") },
                modifier = Modifier.fillMaxWidth(),
                // Capital first letter, and a Done key that closes the keyboard so Save is reachable.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
        }
        GradientButton(if (rating > 0 || note.isNotBlank()) "Save" else "Done", onClick = { onSave(rating, note) })
    }
}

fun streakLabel(days: Int) = when (days) {
    0 -> "No streak yet"
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
