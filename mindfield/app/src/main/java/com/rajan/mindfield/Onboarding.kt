package com.rajan.mindfield

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Evidence
import com.rajan.mindfield.core.Schedule

/** Five short steps: what it is, how a day works, focus areas, times, and the Google link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Onboarding(onDone: () -> Unit) {
    val p = palette
    val context = LocalContext.current
    val state by Store.state.collectAsStateWithLifecycle()
    val cloud by GoogleSync.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askedPermission = true }
    // Back goes to the previous step, not out of the app.
    BackHandler(enabled = step > 0) { step-- }
    val first = Store.library[Curriculum.FIRST] ?: Store.library.all.first()
    val s = state.settings
    val steps = 5

    Box(Modifier.fillMaxSize().paper(p, p.brand).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            // Progress dots.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i ->
                    Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(CircleShape).background(if (i <= step) p.brand else p.line))
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "step",
            ) { current ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (current) {
                        0 -> {
                            Spacer(Modifier.size(12.dp))
                            Emblem(first, Modifier.size(132.dp).align(Alignment.CenterHorizontally), plate = true)
                            Text("Mindfield", style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(
                                "A field guide to the human mind.\nOne idea each morning. Spot it in the wild by evening.",
                                style = Quote, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${Store.library.size} concepts from social influence, decision-making, emotion, memory, habits and more, each with the real study behind it and an honest label for how well it has held up.",
                                style = MaterialTheme.typography.bodyMedium, color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        1 -> {
                            Headline("How a day works")
                            DayStep("🌅", "Morning", "A new concept arrives. Predict the study's result before you read it: guessing first makes it stick.")
                            DayStep("🔍", "During the day", "Spot it in other people and catch it in yourself. Try the day's mission, with an if-then plan.")
                            DayStep("🌙", "Evening", "A one-tap field report from the notification: spotted it, caught myself, used it. Add a line if you like.")
                            DayStep("🃏", "Later", "Quick reviews bring concepts back just as you'd start to forget.")
                            Panel {
                                SectionLabel("Honest evidence", "🧪")
                                Evidence.entries.forEach { e ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        EvidenceBadge(e)
                                        Text(e.meaning, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        2 -> {
                            Headline("Anything you'd like more of?", "Optional. Pick areas and two days in three will come from them. Skip to explore everything.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Category.entries.forEach { c ->
                                    ChoiceChip("${Palettes.emoji(c)} ${c.label}", c in s.focus, c.accent(p.dark)) {
                                        Store.settings { it.copy(focus = if (c in it.focus) it.focus - c else it.focus + c) }
                                    }
                                }
                            }
                        }
                        3 -> {
                            Headline("When should it reach you?", "Two notifications a day, three with spot checks. Change them any time.")
                            TimeChoice("🌅 Morning concept", s.morningMinute) { m -> Store.settings { it.copy(morningMinute = m) } }
                            TimeChoice("🌙 Evening field report", s.eveningMinute) { m -> Store.settings { it.copy(eveningMinute = m) } }
                            Panel(onClick = { Store.settings { it.copy(spotCheckOn = !it.spotCheckOn) } }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("🔍 Surprise spot check", style = MaterialTheme.typography.titleMedium)
                                        Text("Optional: one nudge at a random moment between noon and 6 pm. Seen it yet?", style = MaterialTheme.typography.bodySmall, color = p.muted)
                                    }
                                    Switch(checked = s.spotCheckOn, onCheckedChange = { on -> Store.settings { it.copy(spotCheckOn = on) } })
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(context)) {
                                Text(
                                    if (askedPermission) "Notifications are off. You can turn them on later in You → Notifications."
                                    else "Android will ask if Mindfield may send notifications.",
                                    style = MaterialTheme.typography.bodyMedium, color = p.muted,
                                )
                            }
                        }
                        else -> {
                            Headline("Keep your journal safe", "Like your Meditation Timer, Mindfield can link to your Google account.")
                            Text(
                                "Your field notes, predictions and progress are backed up to a private app folder in your Google Drive, which only this app can read, and come back automatically on a new phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            GoogleCard(cloud)
                        }
                    }
                }
            }
            val last = step == steps - 1
            PrimaryButton(
                when (step) {
                    0 -> "Begin"
                    3 -> if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(context) && !askedPermission) "Allow notifications" else "Next"
                    steps - 1 -> if (cloud.email != null) "Start exploring" else "Not now, start exploring"
                    else -> "Next"
                },
                p.brand,
            ) {
                when {
                    step == 3 && Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(context) && !askedPermission -> {
                        Notifier.markAsked(context)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    last -> onDone()
                    else -> step++
                }
            }
            if (step in 1 until steps - 1) {
                Text(
                    "Back",
                    Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).clickable { step-- }.padding(10.dp),
                    style = MaterialTheme.typography.labelLarge, color = p.muted,
                )
            }
        }
    }
}

@Composable
private fun DayStep(emoji: String, title: String, text: String) {
    val p = palette
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

@Composable
private fun TimeChoice(label: String, minute: Int, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    Panel(onClick = { pickTime(context, minute, onPick) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(Schedule.label(minute, DateFormat.is24HourFormat(context)), style = MaterialTheme.typography.headlineSmall, color = palette.brand)
        }
        Text("Tap to change", style = MaterialTheme.typography.bodySmall, color = palette.faint)
    }
}
