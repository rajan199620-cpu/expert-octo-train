package com.rajan.mindfield

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Codec
import com.rajan.mindfield.core.Evidence
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Schedule
import com.rajan.mindfield.core.Stats
import com.rajan.mindfield.core.Sync
import com.rajan.mindfield.core.ThemeMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeScreen(state: AppState, today: LocalDate, nav: Nav) {
    val p = palette
    val library = Store.library
    val cloud by GoogleSync.state.collectAsStateWithLifecycle()
    val days = Stats.checkInDays(state)
    val streak = Stats.streak(days, today)
    val longest = Stats.longestStreak(days)
    val predictions = Stats.predictions(state, library)
    val modes = Stats.modeCounts(state)
    val life = Stats.lifeList(state)
    val todayColor = state.assignments[today]?.conceptId?.let { library[it] }?.category?.accent(p.dark) ?: p.brand
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Who and how far.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(todayColor), contentAlignment = Alignment.Center) {
                Text(cloud.email?.firstOrNull()?.uppercase() ?: "🔍", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Your field guide", style = MaterialTheme.typography.headlineSmall)
                Text(cloud.email ?: "Not linked to Google yet", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        Panel {
            Row {
                BigStat("Day", "${Stats.dayNumber(state, today)}", Modifier.weight(1f))
                BigStat("Streak", "$streak", Modifier.weight(1f))
                BigStat("Best", "$longest", Modifier.weight(1f))
            }
            WeekDots(Stats.week(days, today), todayColor, Modifier.fillMaxWidth())
            Text("A day counts when you file a field report, even a “not today”.", style = MaterialTheme.typography.bodySmall, color = p.faint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Discovered", "${state.unlocked.size}", "of ${library.size}", Modifier.weight(1f))
            StatTile("Seen in the wild", "${life.size}", "concepts", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Field notes", "${state.liveEntries.size}", "written", Modifier.weight(1f))
            StatTile(
                "Predictions",
                if (predictions.made == 0) "–" else "${predictions.right * 100 / predictions.made}%",
                if (predictions.made == 0) "none yet" else "right · ${predictions.surprised} surprises",
                Modifier.weight(1f),
            )
        }
        if (state.liveEntries.isNotEmpty()) {
            Panel {
                SectionLabel("How it shows up", "🔭")
                val colors = listOf(Category.MEMORY, Category.SELF, Category.HABITS, Category.GROUPS).map { it.accent(p.dark) }
                SplitBar(Mode.entries.mapIndexed { i, m -> (modes[m] ?: 0) to colors[i] })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mode.entries.forEachIndexed { i, m -> Legend("${m.emoji} ${m.short}", modes[m] ?: 0, colors[i]) }
                }
                val used = Stats.outcomes(state)
                if (used.total > 0) {
                    Text(
                        "When you used a concept on purpose: ${used.worked} worked, ${used.mixed} mixed, ${used.backfired} backfired.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted,
                    )
                }
            }
        }
        if (life.size >= 3) {
            Panel {
                SectionLabel("Where you notice psychology", "🧭")
                RadarChart(Stats.categoryCounts(state, library))
            }
            Panel {
                SectionLabel("Most spotted", "🏆")
                Stats.topConcepts(state).forEachIndexed { i, (id, n) ->
                    val c = library[id] ?: return@forEachIndexed
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.openConcept(id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = p.faint)
                        Emblem(c, Modifier.size(34.dp), plate = true)
                        Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("👀 $n", style = MaterialTheme.typography.labelLarge, color = c.category.accent(p.dark))
                    }
                }
            }
        }
        Panel {
            SectionLabel("Last 12 weeks", "🗓")
            Heatmap(Stats.heatmap(state, today), today, todayColor)
        }

        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
        NotificationSettings(state)
        FocusSettings(state)
        AppearanceSettings(state)
        GoogleCard(cloud)
        BackupCard()
        AboutCard()
        Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.muted)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    val p = palette
    Panel(modifier, padding = 16.dp) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = p.faint)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, checked: Boolean?, onClick: () -> Unit) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = if (checked != null) Role.Switch else Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = { onClick() },
                colors = SwitchDefaults.colors(checkedTrackColor = p.brand, checkedThumbColor = if (p.dark) p.bg else Color.White),
            )
        }
    }
}

private fun pickTime(context: Context, minute: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, minute / 60, minute % 60, false).show()
}

@Composable
private fun NotificationSettings(state: AppState) {
    val p = palette
    val context = LocalContext.current
    val s = state.settings
    var allowed by remember { mutableStateOf(Notifier.allowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Panel {
        SectionLabel("Notifications", "🔔")
        if (!allowed) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bad.copy(alpha = 0.10f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Notifications are off, so the daily concept can't reach you.", style = MaterialTheme.typography.bodyMedium)
                SoftButton("Allow notifications", color = p.bad) {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        SettingRow("Morning concept", if (s.morningOn) "At ${Schedule.label(s.morningMinute)} · tap to change" else "Off", s.morningOn) {
            Store.settings { it.copy(morningOn = !it.morningOn) }
        }
        if (s.morningOn) SoftButton("Change morning time (${Schedule.label(s.morningMinute)})") {
            pickTime(context, s.morningMinute) { m -> Store.settings { it.copy(morningMinute = m) } }
        }
        SettingRow("Evening field report", if (s.eveningOn) "At ${Schedule.label(s.eveningMinute)}; log in one tap from the notification" else "Off", s.eveningOn) {
            Store.settings { it.copy(eveningOn = !it.eveningOn) }
        }
        if (s.eveningOn) SoftButton("Change evening time (${Schedule.label(s.eveningMinute)})") {
            pickTime(context, s.eveningMinute) { m -> Store.settings { it.copy(eveningMinute = m) } }
        }
        SettingRow(
            "Surprise spot checks",
            "A nudge at a random time between noon and 6 pm: seen it yet? Skipped once you've logged.",
            s.spotCheckOn,
        ) { Store.settings { it.copy(spotCheckOn = !it.spotCheckOn) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusSettings(state: AppState) {
    val p = palette
    val focus = state.settings.focus
    Panel {
        SectionLabel("Focus areas", "🎯")
        Text(
            "Pick areas to see more of: two days in three come from them, the third keeps some variety. Leave all off to explore everything. Changes apply from tomorrow.",
            style = MaterialTheme.typography.bodySmall, color = p.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { c ->
                ChoiceChip("${Palettes.emoji(c)} ${c.short}", c in focus, c.accent(p.dark)) {
                    Store.settings { it.copy(focus = if (c in it.focus) it.focus - c else it.focus + c) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettings(state: AppState) {
    val p = palette
    Panel {
        SectionLabel("Appearance & guide", "🎨")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { t -> ChoiceChip(t.label, state.settings.theme == t, p.brand) { Store.settings { it.copy(theme = t) } } }
        }
        SettingRow(
            "Show undiscovered concepts",
            "Off keeps the guide a collection you fill day by day. On shows everything (spoilers).",
            state.settings.showAll,
        ) { Store.settings { it.copy(showAll = !it.showAll) } }
    }
}

/** Linking to Google, exactly like the Meditation Timer: a private backup in your Drive. */
@Composable
fun GoogleCard(cloud: CloudState, compact: Boolean = false) {
    val p = palette
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        GoogleSync.onConsentResult(context, result.data)
    }
    Panel {
        SectionLabel("Google account", "☁️")
        if (cloud.email != null) {
            Text("Linked to ${cloud.email}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your journal, predictions, review schedule and settings are backed up to a private app folder in your Google Drive after every change. Only this app can see it.",
                style = MaterialTheme.typography.bodySmall, color = p.muted,
            )
            if (cloud.lastSyncMs > 0) {
                val t = java.time.Instant.ofEpochMilli(cloud.lastSyncMs).atZone(java.time.ZoneId.systemDefault())
                Text("Last backed up ${t.format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()))}", style = MaterialTheme.typography.bodySmall, color = p.faint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(if (cloud.busy) "Syncing…" else "Sync now") { if (!cloud.busy) GoogleSync.syncNow(context) }
                SoftButton("Unlink", color = p.muted) { GoogleSync.disconnect(context) }
            }
        } else {
            Text(
                "Link your Google account to keep your field journal safe and bring it back on a new phone. It's stored in a hidden app folder in your Drive that only this app can read.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton(if (cloud.busy) "Connecting…" else "Connect Google account", p.brand, enabled = !cloud.busy) {
                GoogleSync.connect(context) { consent.launch(it) }
            }
        }
        cloud.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (cloud.needsSetup) p.bad else p.muted) }
        if (cloud.needsSetup && !compact) SetupHelp()
    }
}

/** The one-time Google Cloud step, with the exact values to paste. */
@Composable
private fun SetupHelp() {
    val p = palette
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.raised).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("One-time setup (2 minutes)", style = MaterialTheme.typography.titleSmall)
        Text(
            "In the same Google Cloud project you used for the Meditation Timer: APIs & Services → Credentials → Create credentials → OAuth client ID → Android. Use these values, then tap Connect again:",
            style = MaterialTheme.typography.bodySmall,
        )
        CopyRow("Package name", GoogleSync.PACKAGE, context)
        CopyRow("SHA-1", GoogleSync.SHA1, context)
        Text("Drive API and the consent screen are already set up from the Meditation Timer, so nothing else is needed.", style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun CopyRow(label: String, value: String, context: Context) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface).clickable {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = p.muted)
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp))
        }
        Text("Copy", style = MaterialTheme.typography.labelLarge, color = p.brand)
    }
}

/** A backup file you keep yourself, for when Google isn't an option. */
@Composable
private fun BackupCard() {
    val p = palette
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(Codec.encode(Store.state.value, System.currentTimeMillis())) }
                "Saved a full backup."
            }.getOrElse { "Couldn't save: ${it.message}" }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            message = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val other = Codec.decode(text)
                val before = Store.state.value
                Store.update { Sync.merge(it, other) }
                val n = Sync.restoredEntries(before, Store.state.value)
                if (n == 0) "Merged. Nothing new in that file." else "Restored $n field ${if (n == 1) "note" else "notes"}."
            }.getOrElse { "That file couldn't be read: ${it.message}" }
        }
    }
    Panel {
        SectionLabel("Backup file", "💾")
        Text("Save everything to a file you choose, or merge one back in. Restoring never deletes what's already here.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Save backup") { export.launch("mindfield-backup-${LocalDate.now()}.json") }
            SoftButton("Restore") { import.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.muted) }
    }
}

@Composable
private fun AboutCard() {
    val p = palette
    var open by remember { mutableStateOf(false) }
    Panel(onClick = { open = !open }) {
        SectionLabel("About the evidence", "🧪")
        Text("Why every concept has a strength label", style = MaterialTheme.typography.titleSmall)
        Evidence.entries.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                EvidenceBadge(e)
                Text(e.meaning, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
            }
        }
        if (open) {
            Text(
                "Psychology's replication crisis showed that many famous findings were smaller than claimed, or not real. Mindfield labels each concept by how well it has held up in large, repeated studies, and teaches the myths as myths.\n\n" +
                    "How the app is built to make ideas stick:\n" +
                    "• Predict first: guessing before you learn improves memory (the pretesting effect).\n" +
                    "• Missions with if-then plans: deciding when and where makes action far more likely.\n" +
                    "• Field reports: connecting an idea to your own life is one of the strongest memory aids (the self-reference effect).\n" +
                    "• Spaced review: questions return after 1, 3, 7, 16, 35 and 90 days.\n" +
                    "• Interleaving: the nine areas take turns, so you learn to tell similar ideas apart.\n\n" +
                    "Every concept lists its sources. ${Store.library.size} concepts; about five months of daily discovery, then the app brings back the ones you've spotted least.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Tap for how the app is designed to make ideas stick.", style = MaterialTheme.typography.bodySmall, color = p.brand)
        }
        Text("Mindfield ${BuildInfo.version(LocalContext.current)}", style = MaterialTheme.typography.labelSmall, color = p.faint)
    }
}

object BuildInfo {
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
}
