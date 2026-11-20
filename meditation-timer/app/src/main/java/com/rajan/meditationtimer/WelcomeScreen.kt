package com.rajan.meditationtimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * First run: two questions and how to sit, then the first sit. Skip is on every step and keeps
 * whatever was already answered. Tapping an answer moves on, as Headspace and Calm do.
 */
@Composable
fun WelcomeScreen(prefs: Prefs, onDone: (beginFirstSit: Boolean) -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var experience by rememberSaveable { mutableStateOf<Experience?>(null) }
    var time by rememberSaveable { mutableStateOf<SitTime?>(null) }
    // Asked only here, right after choosing a time, so it's clear what the permission is for.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // No permission, no reminder: don't show one as set when it can't arrive.
            prefs.reminder = prefs.reminder.copy(enabled = false)
            ReminderScheduler.schedule(context)
        }
        step = 2
    }
    fun finish(begin: Boolean) {
        prefs.welcomeDone = true
        Prefs.version.value++
        onDone(begin)
    }
    BackHandler(enabled = step > 0) { step-- }

    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                TextButton(onClick = { step-- }) { Text("‹ Back") }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                repeat(Welcome.STEPS) { i ->
                    Box(
                        Modifier
                            .size(if (i == step) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f)),
                    )
                }
            }
            TextButton(onClick = { finish(begin = false) }) { Text("Skip") }
        }

        AnimatedContent(
            step,
            Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeIn(tween(350, delayMillis = 80)) togetherWith fadeOut(tween(150)) },
            label = "welcome",
        ) { shown ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (shown) {
                    0 -> {
                        Text("Welcome", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("A quiet timer for sitting still, every day.", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Have you meditated before?", style = MaterialTheme.typography.headlineSmall)
                        for (option in Experience.entries) {
                            Choice(option.label, option.detail, selected = option == experience) {
                                experience = option
                                prefs.applyExperience(option)
                                step = 1
                            }
                        }
                        Hint("Your answer sets the length of your sit and your weekly goal. Both can be changed any time.")
                    }
                    1 -> {
                        Text("When could you sit most days?", style = MaterialTheme.typography.headlineSmall)
                        Hint(
                            "Tying a sit to something you already do makes it far more likely to happen. " +
                                "You'll get one quiet reminder then, skipped on days you've already sat.",
                        )
                        for (option in SitTime.entries) {
                            Choice(option.label, option.detail, selected = option == time) {
                                time = option
                                prefs.reminder = Welcome.reminderFor(option, prefs.reminder)
                                ReminderScheduler.schedule(context)
                                val needsPermission = option.minuteOfDay != null &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                if (needsPermission) {
                                    // Asked here, so the first sit doesn't ask again.
                                    prefs.sitNotificationsAsked = true
                                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    step = 2
                                }
                            }
                        }
                    }
                    else -> {
                        Text("How to sit", style = MaterialTheme.typography.headlineSmall)
                        Welcome.howToSit.forEachIndexed { i, (title, body) -> NumberedPoint(i + 1, title, body) }
                        Hint("A bell starts the sit and a bell ends it. You'll find this again, with how everything else works, under Guide on the Sit screen.")
                    }
                }
            }
        }

        if (step == 2) {
            val minutes = prefs.timerConfig.durationSec / 60
            GradientButton("Begin my first sit  ·  $minutes min", onClick = { finish(begin = true) }, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { finish(begin = false) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)) {
                Text("Look around first")
            }
        }
    }
}

/** A large tappable answer: what it is and what choosing it will do. */
@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val isSelected = selected
    val shape = RoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (isSelected) primary.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), shape)
            .semantics { this.selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NumberedPoint(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
