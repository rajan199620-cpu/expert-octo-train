package com.earmark.app.data

import android.content.Context
import com.earmark.core.speech.ElevenLabsVoice
import com.earmark.core.storage.JsonCodec
import com.earmark.core.text.LexiconEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class NarratorEngine { SYSTEM, ELEVENLABS, OPENAI }

enum class ReaderTheme { AUTO, PAPER, SEPIA, NIGHT }

data class AppSettings(
    val engine: NarratorEngine = NarratorEngine.SYSTEM,
    val systemVoiceName: String? = null,
    val speed: Float = 1.0f,
    val elevenLabsKey: String = "",
    val elevenLabsVoiceId: String = DEFAULT_ELEVENLABS_VOICE,
    val openAiKey: String = "",
    val openAiVoice: String = "sage",
    val anthropicKey: String = "",
    val fullBookContext: Boolean = false,
    val spoilerSafe: Boolean = true,
    val headsetNextBookmarks: Boolean = true,
    val spokenConfirmations: Boolean = true,
    val lexicon: List<LexiconEntry> = emptyList(),
    val readerTheme: ReaderTheme = ReaderTheme.AUTO,
    val fontScale: Float = 1.0f,
    /** On-device voice: pauses, dialogue voice, question/exclamation tone. */
    val performReading: Boolean = true,
    /** Cloud voices: 0 = even, 1 = dramatic. */
    val expressiveness: Float = 0.6f,
    val elevenLabsModel: String = ElevenLabsVoice.MULTILINGUAL_V2,
) {
    companion object {
        /** "Rachel", one of ElevenLabs' default voices. */
        const val DEFAULT_ELEVENLABS_VOICE = "21m00Tcm4TlvDq8ikWAM"
    }
}

/**
 * Settings live in app-private SharedPreferences (excluded from backup). API keys are stored
 * there too: they are the user's own keys, on the user's own device.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val lexiconFile = File(context.filesDir, "lexicon.json")
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val lexicon = if (lexiconFile.exists()) {
            (JsonCodec.decodeLexicon(lexiconFile.readText()) as? JsonCodec.Decoded.Ok)?.value.orEmpty()
        } else emptyList()
        return AppSettings(
            engine = runCatching { NarratorEngine.valueOf(prefs.getString("engine", null) ?: "SYSTEM") }.getOrDefault(NarratorEngine.SYSTEM),
            systemVoiceName = prefs.getString("systemVoice", null),
            speed = prefs.getFloat("speed", 1.0f),
            elevenLabsKey = prefs.getString("elevenKey", "").orEmpty(),
            elevenLabsVoiceId = prefs.getString("elevenVoice", null) ?: AppSettings.DEFAULT_ELEVENLABS_VOICE,
            openAiKey = prefs.getString("openAiKey", "").orEmpty(),
            openAiVoice = prefs.getString("openAiVoice", null) ?: "sage",
            anthropicKey = prefs.getString("anthropicKey", "").orEmpty(),
            fullBookContext = prefs.getBoolean("fullBook", false),
            spoilerSafe = prefs.getBoolean("spoilerSafe", true),
            headsetNextBookmarks = prefs.getBoolean("headsetBookmark", true),
            spokenConfirmations = prefs.getBoolean("spokenConfirmations", true),
            lexicon = lexicon,
            readerTheme = runCatching { ReaderTheme.valueOf(prefs.getString("readerTheme", null) ?: "AUTO") }.getOrDefault(ReaderTheme.AUTO),
            fontScale = prefs.getFloat("fontScale", 1.0f).coerceIn(0.8f, 1.6f),
            performReading = prefs.getBoolean("performReading", true),
            expressiveness = prefs.getFloat("expressiveness", 0.6f).coerceIn(0f, 1f),
            elevenLabsModel = prefs.getString("elevenModel", null) ?: ElevenLabsVoice.MULTILINGUAL_V2,
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("engine", s.engine.name)
            .putString("systemVoice", s.systemVoiceName)
            .putFloat("speed", s.speed)
            .putString("elevenKey", s.elevenLabsKey.trim())
            .putString("elevenVoice", s.elevenLabsVoiceId.trim())
            .putString("openAiKey", s.openAiKey.trim())
            .putString("openAiVoice", s.openAiVoice)
            .putString("anthropicKey", s.anthropicKey.trim())
            .putBoolean("fullBook", s.fullBookContext)
            .putBoolean("spoilerSafe", s.spoilerSafe)
            .putBoolean("headsetBookmark", s.headsetNextBookmarks)
            .putBoolean("spokenConfirmations", s.spokenConfirmations)
            .putString("readerTheme", s.readerTheme.name)
            .putFloat("fontScale", s.fontScale)
            .putBoolean("performReading", s.performReading)
            .putFloat("expressiveness", s.expressiveness)
            .putString("elevenModel", s.elevenLabsModel)
            .apply()
        lexiconFile.writeText(JsonCodec.encodeLexicon(s.lexicon))
    }
}
