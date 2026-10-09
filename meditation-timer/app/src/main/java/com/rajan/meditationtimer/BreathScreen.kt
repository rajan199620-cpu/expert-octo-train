package com.rajan.meditationtimer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.draw.clip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

@Composable
fun BreathTab(prefs: Prefs, onDone: () -> Unit) {
    var patternName by rememberSaveable { mutableStateOf(prefs.breathPattern) }
    var minutes by rememberSaveable { mutableIntStateOf(prefs.breathMinutes) }
    var cue by rememberSaveable { mutableStateOf(prefs.breathCue) }
    // SystemClock.elapsedRealtime() when started; 0 while on the setup screen.
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var justFinished by rememberSaveable { mutableStateOf(false) }
    val pattern = BreathPattern.ALL.firstOrNull { it.name == patternName } ?: BreathPattern.ALL.first()
    var checkStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var checks by remember { mutableStateOf(prefs.breathChecks) }
    var lastResult by remember { mutableStateOf<BreathCountResult?>(null) }
    val context = LocalContext.current

    if (checkStartedAt != 0L) {
        BreathCheckSession(
            startedAt = checkStartedAt,
            onStop = { checkStartedAt = 0L },
            onFinished = { result ->
                onDone()
                checkStartedAt = 0L
                lastResult = result
                if (result.total > 0) {
                    prefs.addBreathCheck(BreathCheck(System.currentTimeMillis(), result))
                    checks = prefs.breathChecks
                    // The scores ride in the backup's settings line, so a new one is backed up now.
                    SessionLog.get(context).backUp()
                }
            },
        )
        return
    }

    if (startedAt != 0L) {
        BreathingSession(
            pattern = pattern,
            minutes = minutes,
            cue = cue,
            startedAt = startedAt,
            onStop = { startedAt = 0L },
            onFinished = {
                // The service rings the closing bell on time, even with the phone locked; the screen
                // only rings it if the service never got to pace this exercise.
                if (!BreathService.ringsEnd(startedAt)) onDone()
                startedAt = 0L
                justFinished = true
            },
        )
        return
    }

    Column(
        Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Breathe", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium)
        Text(
            "A few minutes of paced breathing settles the body before a sit. You can follow it with " +
                "your eyes closed: " + when (cue) {
                    BreathCue.SOUND -> "breathe in with the sound of the in-breath, out with the out-breath; silence means hold."
                    BreathCue.VIBRATION -> "one tap means breathe in, two taps breathe out, a long buzz hold."
                    BreathCue.BOTH -> "the sound of the breath, with one tap in, two taps out and a long buzz to hold."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BreathOrb(expansion = 0.55f, modifier = Modifier.size(180.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("Rhythm", pattern.description)
            ChipRow(BreathPattern.ALL.map { it.name }, pattern.name, { it }) { patternName = it }
            // The two traditional practices say how to do them and what the evidence is, plainly.
            pattern.howTo?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            pattern.evidence?.let {
                Text("Evidence: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionLabel("Length", if (pattern.breathsPerRound > 1) "Rounded up to finish on a full round" else "Rounded up to finish on a full breath")
            ChipRow(listOf(1, 3, 5, 10), minutes, { "$it min" }) { minutes = it }
            SectionLabel("Cue", cueSubtitle(cue))
            ChipRow(BreathCue.entries, cue, { it.label }) { cue = it }
        }
        if (justFinished) {
            Pill("✦ Nicely done. Ready to sit?")
        }
        GradientButton("Start", onClick = {
            prefs.breathPattern = pattern.name
            prefs.breathMinutes = minutes
            prefs.breathCue = cue
            justFinished = false
            startedAt = SystemClock.elapsedRealtime()
        })
        AttentionCheckCard(checks, lastResult) {
            lastResult = null
            checkStartedAt = SystemClock.elapsedRealtime()
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** What each cue is, said where it's chosen (here and with the settle-in breaths). */
fun cueSubtitle(cue: BreathCue): String = when (cue) {
    BreathCue.SOUND -> "A soft breath in and out, from the speaker or earphones; volume keys set the level"
    BreathCue.VIBRATION -> "One tap in, two taps out, a long buzz to hold: silent, for a shared room"
    BreathCue.BOTH -> "The sound of the breath and the taps together"
}

private const val CHECK_MINUTES = 5

/** How long a check runs. Tests shorten it rather than simulate five minutes of a live screen. */
internal var checkLengthMs = CHECK_MINUTES * 60_000L
private val checkDate = DateTimeFormatter.ofPattern("d MMM")

/** Skill, not mood: a repeatable breath-counting check whose accuracy you can track over weeks. */
@Composable
private fun AttentionCheckCard(checks: List<BreathCheck>, lastResult: BreathCountResult?, onStart: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Attention check")
        Text(
            "Count your breaths from 1 to 9, again and again, for $CHECK_MINUTES minutes. Press volume-down on " +
                "breaths 1–8 and volume-up on breath 9. Lost count? Hold volume-down for a second (or tap " +
                "Lost count), then start again at 1: catching it yourself isn't held against you. Your score is " +
                "how many rounds of nine you counted exactly — a measure of how steady your attention is.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Adapted from a breath-counting task validated as a measure of mindfulness (Levinson et al., " +
                "Frontiers in Psychology, 2014). This version is shorter, so compare your results with each " +
                "other over weeks, not with the study.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastResult?.let {
            val pct = it.accuracyPercent
            val restarts = when (it.resets) {
                0 -> ""
                1 -> " · 1 restart"
                else -> " · ${it.resets} restarts"
            }
            Pill(if (pct == null) "No full round of nine counted — try again" else "$pct% · ${it.correct} of ${it.total} rounds exact$restarts")
        }
        if (checks.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            Text(
                "Your checks: " + checks.takeLast(6).joinToString("  ·  ") {
                    "${Instant.ofEpochMilli(it.atMs).atZone(zone).format(checkDate)} ${it.result.accuracyPercent}%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (checks.size >= 2) {
                Text(
                    "First ${checks.first().result.accuracyPercent}% → latest ${checks.last().result.accuracyPercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TextButton(onClick = onStart) { Text("Start a $CHECK_MINUTES-minute check") }
    }
}

@Composable
private fun BreathCheckSession(startedAt: Long, onStop: () -> Unit, onFinished: (BreathCountResult) -> Unit) {
    // Back stops the check, like the button, instead of closing the app mid-count.
    BackHandler(onBack = onStop)
    val view = LocalView.current
    // Kept through a rotation: the clock carries on, so the presses must too.
    val presses = rememberSaveable(
        saver = listSaver<SnapshotStateList<BreathCount.Key>, Int>({ keys -> keys.map { it.ordinal } }, { saved -> saved.map { BreathCount.Key.entries[it] }.toMutableStateList() }),
    ) { mutableStateListOf<BreathCount.Key>() }
    fun press(nine: Boolean) {
        presses += if (nine) BreathCount.Key.NINE else BreathCount.Key.BREATH
        view.performHapticFeedback(if (nine) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    }
    // Lost count: back to 1, felt as a distinct double buzz so it registers with eyes closed.
    fun restart() {
        presses += BreathCount.Key.RESET
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
        )
    }
    // Volume keys only reach the app while the screen is on.
    DisposableEffect(view) {
        view.keepScreenOn = true
        VolumeKeys.handler = { up -> press(nine = up) }
        VolumeKeys.onHoldDown = { restart() }
        onDispose {
            view.keepScreenOn = false
            VolumeKeys.handler = null
            VolumeKeys.onHoldDown = null
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(250)
        }
    }
    val totalMs = checkLengthMs
    val remaining = (totalMs - (now - startedAt)).coerceAtLeast(0)
    val done = remaining == 0L
    LaunchedEffect(done) { if (done) onFinished(BreathCount.scoreKeys(presses.toList())) }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text("Count breaths 1 to 9", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Volume-down on 1–8 · volume-up on 9 · eyes closed.\nLost count? Hold volume-down, then back to 1.\nNo count is shown — that’s the point.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(formatClock(remaining), style = MaterialTheme.typography.displayMedium)
        // On-screen keys as a fallback for phones where the volume keys are awkward.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckKey("1 – 8", Modifier.weight(1f)) { press(nine = false) }
            CheckKey("9", Modifier.weight(1f)) { press(nine = true) }
        }
        TextButton(onClick = { restart() }) { Text("Lost count — back to 1") }
        TextButton(onClick = onStop) { Text("Stop without saving", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CheckKey(label: String, modifier: Modifier, onPress: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .height(120.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BreathingSession(
    pattern: BreathPattern,
    minutes: Int,
    cue: BreathCue,
    startedAt: Long,
    onStop: () -> Unit,
    onFinished: () -> Unit,
) {
    // Back stops the exercise, like the button, instead of closing the app mid-breath.
    BackHandler(onBack = onStop)
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val totalMs = pattern.breathsFor(minutes) * pattern.cycleMs
    val elapsed = (now - startedAt).coerceIn(0, totalMs)
    val state = pattern.at(elapsed)

    val done = elapsed >= totalMs
    LaunchedEffect(done) { if (done) onFinished() }

    // The breath sound, or one tap to breathe in, two to breathe out and a long buzz to hold, so each
    // phase can be told apart with eyes closed. They come from a service so they carry on with the
    // phone locked; it stops when this screen goes (Stop, Back), but not when it is only rebuilt,
    // nor at the end, where it rings the closing bell and stops itself.
    val context = LocalContext.current
    LaunchedEffect(startedAt) {
        BreathService.start(context, pattern, minutes, startedAt, cue)
        // Stopped from the notification: close the exercise here too.
        BreathService.cancelled.first { it == startedAt }
        onStop()
    }
    DisposableEffect(startedAt) {
        onDispose {
            val over = SystemClock.elapsedRealtime() - startedAt >= totalMs
            if (!over && context.findActivity()?.isChangingConfigurations != true) BreathService.stop(context)
        }
    }

    // The breath plays as media: while it does, the volume keys set its level rather than the bell's.
    var silenced by remember { mutableStateOf(false) }
    if (cue.sound) {
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            val before = activity?.volumeControlStream
            activity?.volumeControlStream = AudioManager.STREAM_MUSIC
            onDispose { if (activity != null && before != null) activity.volumeControlStream = before }
        }
        LaunchedEffect(Unit) {
            val audio = context.getSystemService(AudioManager::class.java)
            while (true) {
                silenced = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            BreathOrb(state.expansion, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pattern.cue(state), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${(state.msLeftInPhase + 999) / 1000}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light,
                )
            }
        }
        Text(
            "${pattern.name}  ·  ${formatClock(totalMs - elapsed)} left",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (silenced) {
            Text(
                "Media volume is off: press volume up to hear the breath.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onStop) { Text("Stop", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A luminous orb that swells with the in-breath; [expansion] is 0 (empty lungs) .. 1 (full). */
@Composable
private fun BreathOrb(expansion: Float, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Canvas(modifier) {
        val maxRadius = size.minDimension / 2
        val radius = maxRadius * (0.38f + 0.52f * expansion)
        // Outer halo grows with the breath.
        drawCircle(
            Brush.radialGradient(listOf(accent.main.copy(alpha = 0.10f + 0.25f * expansion), Color.Transparent), center, maxRadius),
            maxRadius,
            center,
        )
        drawCircle(Color.White.copy(alpha = 0.10f), radius = maxRadius * 0.92f, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(
            Brush.radialGradient(listOf(accent.main.copy(alpha = 0.85f), accent.second.copy(alpha = 0.35f)), center, radius),
            radius,
            center,
        )
        drawCircle(Color.White.copy(alpha = 0.35f), radius = radius, style = Stroke(width = 1.5.dp.toPx()))
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
