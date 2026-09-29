package com.earmark.app.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.earmark.app.playback.VoiceAudition
import com.earmark.app.playback.cloudVoiceFor
import com.earmark.core.speech.CloudVoice
import com.earmark.core.speech.CostEstimator
import com.earmark.core.speech.ElevenLabsVoice
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

    // Lists on-device voices and plays samples. The sample is the book's own text when it isn't
    // in a Latin script (an English sample read by, say, a Hindi voice says little about it).
    val language = remember(session) { session?.book?.let { ScriptDetector.guessLanguage(it) } }
    val audition = remember { VoiceAudition(context, language) }
    DisposableEffect(audition) { onDispose { audition.close() } }
    val voices by audition.voices.collectAsStateWithLifecycle()
    val auditionState by audition.state.collectAsStateWithLifecycle()
    val sampleText = remember(session) {
        val s = session
        if (s == null || language == null || s.book.sentences.isEmpty()) return@remember SystemTtsEngine.SAMPLE
        val from = s.controller.state.position.coerceIn(0, s.book.sentences.size - 1)
        (from until minOf(from + 3, s.book.sentences.size)).joinToString(" ") { s.controller.speechTextFor(it) }
            .ifBlank { SystemTtsEngine.SAMPLE }
    }
    fun hearSystem(voice: Voice?, key: String) {
        if ((auditionState as? VoiceAudition.State.Playing)?.key == key) {
            audition.stop()
        } else {
            hub.pause()
            audition.playSystem(voice, key, settings.performReading, settings.speed, sampleText)
        }
    }
    fun hearCloud(voice: CloudVoice, cacheKey: String) {
        hub.pause()
        audition.playCloud(voice, cacheKey, settings.speed, sampleText)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Section("Narrator voice")
            Group {
                EngineOption(settings.engine == NarratorEngine.SYSTEM, "On-device voice", "Free, works offline, instant. Sounds as good as your phone's TTS engine.") {
                    update(true) { it.copy(engine = NarratorEngine.SYSTEM) }
                }
                EngineOption(settings.engine == NarratorEngine.ELEVENLABS, "ElevenLabs", "The most human-sounding voices. Needs your API key and internet; billed per character.") {
                    update(true) { it.copy(engine = NarratorEngine.ELEVENLABS) }
                }
                EngineOption(settings.engine == NarratorEngine.OPENAI, "OpenAI", "Natural, steerable narration. Needs your API key and internet; cheaper than ElevenLabs.") {
                    update(true) { it.copy(engine = NarratorEngine.OPENAI) }
                }
            }
            Group {
                when (settings.engine) {
                    NarratorEngine.SYSTEM -> {
                        Text("Voice", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        Hint("Tap ▶ to hear a voice before you pick it.")
                        val list = voices
                        val playingKey = (auditionState as? VoiceAudition.State.Playing)?.key
                        VoiceRow(
                            title = "Automatic",
                            subtitle = "The best voice installed for this book's language",
                            selected = settings.systemVoiceName == null || list?.none { it.name == settings.systemVoiceName } == true,
                            playing = playingKey == AUTO_VOICE,
                            onSelect = { update(true) { it.copy(systemVoiceName = null) } },
                            onHear = { hearSystem(audition.autoVoice(), AUTO_VOICE) },
                        )
                        when {
                            list == null -> Hint("Loading voices…")
                            list.isEmpty() -> Hint("No voices are listed for this language. Download one below.")
                        }
                        list.orEmpty().take(12).forEach { v ->
                            VoiceRow(
                                title = voiceTitle(v),
                                subtitle = voiceSubtitle(v),
                                selected = settings.systemVoiceName == v.name,
                                playing = playingKey == v.name,
                                onSelect = { update(true) { it.copy(systemVoiceName = v.name) } },
                                onHear = { hearSystem(v, v.name) },
                            )
                        }
                        AuditionMessage(auditionState)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Toggle(
                            "Perform the reading",
                            "Real pauses after headings, paragraphs and chapters; a lighter voice for dialogue; questions that rise.",
                            settings.performReading,
                        ) { v -> update(true) { it.copy(performReading = v) } }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Download better voices") }
                        Hint(
                            "Phone voices can't act out emotion: they only change pitch, pace and pauses. " +
                                "For narration with real feeling, pick ElevenLabs above (it needs an API key).",
                        )
                    }
                    NarratorEngine.ELEVENLABS -> {
                        KeyField("ElevenLabs API key", settings.elevenLabsKey) { v -> update(true) { it.copy(elevenLabsKey = v) } }
                        PlainField("Voice ID", settings.elevenLabsVoiceId) { v -> update(true) { it.copy(elevenLabsVoiceId = v) } }
                        Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        EngineOption(settings.elevenLabsModel != ElevenLabsVoice.MODEL_V3, "Multilingual v2", "Consistent, reliable narration.") {
                            update(true) { it.copy(elevenLabsModel = ElevenLabsVoice.MULTILINGUAL_V2) }
                        }
                        EngineOption(
                            settings.elevenLabsModel == ElevenLabsVoice.MODEL_V3,
                            "Eleven v3",
                            "The most emotional delivery. Newer and less predictable; falls back to v2 if your key can't use it.",
                        ) { update(true) { it.copy(elevenLabsModel = ElevenLabsVoice.MODEL_V3) } }
                        ExpressivenessSlider(settings.expressiveness) { v -> update(true) { it.copy(expressiveness = v) } }
                        CloudSample(settings, auditionState, onPlay = ::hearCloud, onStop = audition::stop)
                    }
                    NarratorEngine.OPENAI -> {
                        KeyField("OpenAI API key", settings.openAiKey) { v -> update(true) { it.copy(openAiKey = v) } }
                        PlainField("Voice (alloy, ash, coral, sage, verse…)", settings.openAiVoice) { v -> update(true) { it.copy(openAiVoice = v.trim()) } }
                        ExpressivenessSlider(settings.expressiveness) { v -> update(true) { it.copy(expressiveness = v) } }
                        Hint("Each passage is directed to match its mood: tense, tender, sad, joyful…")
                        CloudSample(settings, auditionState, onPlay = ::hearCloud, onStop = audition::stop)
                    }
                }
            }

            session?.book?.let { book ->
                val chars = book.totalChars
                Group {
                    Text("What \"${book.title}\" costs to narrate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
                    CostEstimator.Voice.values().forEach { v ->
                        val e = CostEstimator.estimate(chars, v)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(v.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(if (e.usd == 0.0) "free" else "≈ $" + String.format(Locale.US, "%.2f", e.usd), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("≈ ${String.format(Locale.US, "%.1f", CostEstimator.estimate(chars, CostEstimator.Voice.SYSTEM).audioHours)} h of audio. Cloud audio is cached, so re-listening is free. Prices change; check your provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            Section("Ask the book")
            Group {
                KeyField("Anthropic API key (for questions and recaps)", settings.anthropicKey) { v -> update(true) { it.copy(anthropicKey = v) } }
                Text("Without a key, questions fall back to finding the most relevant passage offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Spoiler-safe answers", "Answers only use the book up to where you are.", settings.spoilerSafe) { v -> update { it.copy(spoilerSafe = v) } }
                Toggle("Send the whole book as context", "Better answers for short books; more expensive on the first question.", settings.fullBookContext) { v -> update(true) { it.copy(fullBookContext = v) } }
            }

            Section("Listening")
            Group {
                Toggle("Earbud double-tap = bookmark", "Bookmark what you just heard without speaking. A short tone confirms.", settings.headsetNextBookmarks) { v -> update { it.copy(headsetNextBookmarks = v) } }
                Toggle("Spoken confirmations", "Say \"Bookmarked\" etc. after voice commands.", settings.spokenConfirmations) { v -> update { it.copy(spokenConfirmations = v) } }
            }

            Section("Pronunciation")
            Group {
                Text("Teach the voice names and terms it gets wrong.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
                    modifier = Modifier.padding(vertical = 8.dp),
                ) { Text("Add") }
                if (from.isNotBlank() && to.isNotBlank()) {
                    val preview = SpeechNormalizer(com.earmark.core.text.Lexicon(listOf(LexiconEntry(from, to)))).normalize("Example: $from.")
                    Text("Will be read as: $preview", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

private const val AUTO_VOICE = "auto"

/** "English (India) · ENA": the language plus the engine's short code ("en-in-x-ena-network"). */
private fun voiceTitle(v: Voice): String {
    val locale = v.locale.getDisplayName(Locale.getDefault())
    val code = Regex("-x-([a-z0-9]{2,5})(?:[-#]|\$)").find(v.name)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    return "$locale · ${code ?: v.name}"
}

private fun voiceSubtitle(v: Voice): String {
    val quality = when {
        v.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
        v.quality >= Voice.QUALITY_HIGH -> "High quality"
        v.quality >= Voice.QUALITY_NORMAL -> "Normal quality"
        else -> "Basic quality"
    }
    return if (v.isNetworkConnectionRequired) "$quality · most natural, needs internet" else "$quality · works offline"
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, playing: Boolean, onSelect: () -> Unit, onHear: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onHear) {
            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing) "Stop sample" else "Hear $title")
        }
    }
}

@Composable
private fun AuditionMessage(state: VoiceAudition.State) {
    val (text, isError) = when (state) {
        is VoiceAudition.State.Failed -> state.message to true
        is VoiceAudition.State.Playing -> (state.note ?: return) to false
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ExpressivenessSlider(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Expressiveness", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                when {
                    local < 0.2f -> "Even"
                    local < 0.45f -> "Subtle"
                    local < 0.75f -> "Expressive"
                    else -> "Dramatic"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = 0f..1f, steps = 9)
        Row {
            Text("Calm, even", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Acted, dramatic", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Hear a sample" for the selected cloud voice with the current settings. */
@Composable
private fun CloudSample(settings: AppSettings, state: VoiceAudition.State, onPlay: (CloudVoice, String) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val cloud = remember(settings) { cloudVoiceFor(context, settings) }
    val loading = state is VoiceAudition.State.Loading
    val busy = loading || (state as? VoiceAudition.State.Playing)?.key == VoiceAudition.CLOUD
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { if (busy) onStop() else cloud?.let { onPlay(it.first, it.second) } }, enabled = cloud != null) {
            Icon(if (busy) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Preparing…" else if (busy) "Stop" else "Hear a sample")
        }
        if (loading) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    AuditionMessage(state)
    Hint(
        if (cloud == null) "Add your API key to hear this voice."
        else "A sample uses about 150 characters of your quota; replaying it is free. Changes apply when you leave Settings.",
    )
}

@Composable
private fun Group(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun Section(title: String) {
    SectionLabel(title, Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp))
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
