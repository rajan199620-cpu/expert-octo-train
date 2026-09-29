package com.earmark.app.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earmark.app.data.AppSettings
import com.earmark.app.data.NarratorEngine
import com.earmark.app.data.SettingsStore
import com.earmark.app.playback.PlayerHub
import com.earmark.app.playback.SystemTtsEngine
import com.earmark.core.speech.CostEstimator
import com.earmark.core.text.LexiconEntry
import com.earmark.core.text.ScriptDetector
import com.earmark.core.text.SpeechNormalizer
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, hub: PlayerHub, onBack: () -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val session by hub.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dirtyEngine by remember { mutableStateOf(false) }

    fun update(engineAffected: Boolean = false, f: (AppSettings) -> AppSettings) {
        store.update(f)
        if (engineAffected) dirtyEngine = true
    }

    // Apply voice/pronunciation changes when leaving the screen, not on every keystroke.
    DisposableEffect(Unit) {
        onDispose { if (dirtyEngine) hub.reloadEngine() }
    }

    // Enumerate on-device voices once.
    var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = session?.book?.let { ScriptDetector.guessLanguage(it) }
                voices = tts?.let { t ->
                    SystemTtsEngine.listVoices(t, lang).map { v ->
                        val net = if (v.isNetworkConnectionRequired) "online" else "offline"
                        v.name to "${v.locale.getDisplayName(Locale.getDefault())} · quality ${v.quality} · $net"
                    }
                }.orEmpty()
            }
        }
        onDispose { tts?.shutdown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Narrator voice")
            EngineOption(settings.engine == NarratorEngine.SYSTEM, "On-device voice", "Free, works offline, instant. Sounds as good as your phone's TTS engine.") {
                update(true) { it.copy(engine = NarratorEngine.SYSTEM) }
            }
            EngineOption(settings.engine == NarratorEngine.ELEVENLABS, "ElevenLabs", "The most human-sounding voices. Needs your API key and internet; billed per character.") {
                update(true) { it.copy(engine = NarratorEngine.ELEVENLABS) }
            }
            EngineOption(settings.engine == NarratorEngine.OPENAI, "OpenAI", "Natural, steerable narration. Needs your API key and internet; cheaper than ElevenLabs.") {
                update(true) { it.copy(engine = NarratorEngine.OPENAI) }
            }
            Spacer(Modifier.height(8.dp))
            when (settings.engine) {
                NarratorEngine.SYSTEM -> {
                    Text("Voice", style = MaterialTheme.typography.labelLarge)
                    if (voices.isEmpty()) Text("Loading voices…", style = MaterialTheme.typography.bodySmall)
                    voices.take(12).forEach { (name, label) ->
                        Row(Modifier.fillMaxWidth().clickable { update(true) { it.copy(systemVoiceName = name) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.systemVoiceName == name, onClick = { update(true) { it.copy(systemVoiceName = name) } })
                            Column { Text(name, style = MaterialTheme.typography.bodyMedium); Text(label, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Download better voices") }
                }
                NarratorEngine.ELEVENLABS -> {
                    KeyField("ElevenLabs API key", settings.elevenLabsKey) { v -> update(true) { it.copy(elevenLabsKey = v) } }
                    PlainField("Voice ID", settings.elevenLabsVoiceId) { v -> update(true) { it.copy(elevenLabsVoiceId = v) } }
                }
                NarratorEngine.OPENAI -> {
                    KeyField("OpenAI API key", settings.openAiKey) { v -> update(true) { it.copy(openAiKey = v) } }
                    PlainField("Voice (alloy, ash, coral, sage, verse…)", settings.openAiVoice) { v -> update(true) { it.copy(openAiVoice = v.trim()) } }
                }
            }

            session?.book?.let { book ->
                val chars = book.totalChars
                Spacer(Modifier.height(12.dp))
                Text("What \"${book.title}\" costs to narrate", style = MaterialTheme.typography.labelLarge)
                CostEstimator.Voice.values().forEach { v ->
                    val e = CostEstimator.estimate(chars, v)
                    Text("${v.label}: ${if (e.usd == 0.0) "free" else "about $" + String.format(Locale.US, "%.2f", e.usd)} for ≈ ${String.format(Locale.US, "%.1f", e.audioHours)} h of audio", style = MaterialTheme.typography.bodySmall)
                }
                Text("Cloud audio is cached, so re-listening is free. Prices change; check your provider.", style = MaterialTheme.typography.bodySmall)
            }

            Divider()
            Section("Ask the book")
            KeyField("Anthropic API key (for questions and recaps)", settings.anthropicKey) { v -> update(true) { it.copy(anthropicKey = v) } }
            Text("Without a key, questions fall back to finding the most relevant passage offline.", style = MaterialTheme.typography.bodySmall)
            Toggle("Spoiler-safe answers", "Answers only use the book up to where you are.", settings.spoilerSafe) { v -> update { it.copy(spoilerSafe = v) } }
            Toggle("Send the whole book as context", "Better answers for short books; more expensive on the first question.", settings.fullBookContext) { v -> update(true) { it.copy(fullBookContext = v) } }

            Divider()
            Section("Controls")
            Toggle("Earbud double-tap / next = bookmark", "Bookmark what you just heard without speaking. A short tone confirms.", settings.headsetNextBookmarks) { v -> update { it.copy(headsetNextBookmarks = v) } }
            Toggle("Spoken confirmations", "Say \"Bookmarked\" etc. after voice commands.", settings.spokenConfirmations) { v -> update { it.copy(spokenConfirmations = v) } }

            Divider()
            Section("Pronunciation")
            Text("Teach the voice names and terms it gets wrong.", style = MaterialTheme.typography.bodySmall)
            settings.lexicon.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.from} → ${e.to}", modifier = Modifier.weight(1f))
                    IconButton(onClick = { update(true) { s -> s.copy(lexicon = s.lexicon.filterIndexed { k, _ -> k != i }) } }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
            var from by remember { mutableStateOf("") }
            var to by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(from, { from = it }, label = { Text("Written") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(to, { to = it }, label = { Text("Say it as") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(
                onClick = {
                    update(true) { it.copy(lexicon = it.lexicon + LexiconEntry(from.trim(), to.trim())) }
                    from = ""; to = ""
                },
                enabled = from.isNotBlank() && to.isNotBlank(),
            ) { Text("Add") }
            if (from.isNotBlank() && to.isNotBlank()) {
                val preview = SpeechNormalizer(com.earmark.core.text.Lexicon(listOf(LexiconEntry(from, to)))).normalize("Example: $from.")
                Text("Will be read as: $preview", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 12.dp))

@Composable
private fun EngineOption(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun PlainField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}
