package com.rajan.meditationtimer

import android.os.SystemClock
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BreathTab(prefs: Prefs, onDone: () -> Unit) {
    var patternName by rememberSaveable { mutableStateOf(prefs.breathPattern) }
    var minutes by rememberSaveable { mutableIntStateOf(prefs.breathMinutes) }
    // SystemClock.elapsedRealtime() when started; 0 while on the setup screen.
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var justFinished by rememberSaveable { mutableStateOf(false) }
    val pattern = BreathPattern.ALL.firstOrNull { it.name == patternName } ?: BreathPattern.ALL.first()

    if (startedAt != 0L) {
        BreathingSession(
            pattern = pattern,
            minutes = minutes,
            startedAt = startedAt,
            onStop = { startedAt = 0L },
            onFinished = {
                onDone()
                startedAt = 0L
                justFinished = true
            },
        )
        return
    }

    Column(
        Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Breathe", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
        Text(
            "A few minutes of paced breathing settles the body before a sit. Each change of phase " +
                "gives a small vibration, so you can follow it with your eyes closed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("Rhythm", pattern.description)
        ChipRow(BreathPattern.ALL.map { it.name }, pattern.name, { it }) { patternName = it }
        SectionLabel("Length", "Rounded up to finish on a full breath")
        ChipRow(listOf(1, 3, 5, 10), minutes, { "$it min" }) { minutes = it }
        if (justFinished) {
            Text("Nicely done. Ready to sit?", color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                prefs.breathPattern = pattern.name
                prefs.breathMinutes = minutes
                justFinished = false
                startedAt = SystemClock.elapsedRealtime()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun BreathingSession(
    pattern: BreathPattern,
    minutes: Int,
    startedAt: Long,
    onStop: () -> Unit,
    onFinished: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val cycles = ((minutes * 60_000L) + pattern.cycleMs - 1) / pattern.cycleMs
    val totalMs = cycles * pattern.cycleMs
    val elapsed = (now - startedAt).coerceIn(0, totalMs)
    val state = pattern.at(elapsed)

    val done = elapsed >= totalMs
    LaunchedEffect(done) { if (done) onFinished() }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.phase, state.completedCycles) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2
                val minRadius = maxRadius * 0.35f
                val radius = minRadius + (maxRadius - minRadius) * state.expansion
                drawCircle(outline, radius = maxRadius, style = Stroke(width = 2.dp.toPx()))
                drawCircle(primary.copy(alpha = 0.25f), radius = radius)
                drawCircle(primary, radius = radius, style = Stroke(width = 3.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.phase.label, style = MaterialTheme.typography.headlineSmall)
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
        OutlinedButton(onClick = onStop) { Text("Stop") }
    }
}
