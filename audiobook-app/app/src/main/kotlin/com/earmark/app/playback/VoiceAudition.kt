package com.earmark.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.earmark.app.data.AppSettings
import com.earmark.app.data.NarratorEngine
import com.earmark.core.player.Utterance
import com.earmark.core.speech.ChunkPlanner
import com.earmark.core.speech.CloudVoice
import com.earmark.core.speech.ElevenLabsVoice
import com.earmark.core.speech.OpenAiVoice
import com.earmark.core.speech.ProsodyPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The cloud voice [s] selects, with the key its audio is cached under (voice, model and
 * expressiveness, so changing any of them re-synthesises). Null for the on-device voice or
 * when the API key is missing.
 */
fun cloudVoiceFor(context: Context, s: AppSettings): Pair<CloudVoice, String>? {
    val expressiveness = Math.round(s.expressiveness * 10)
    return when (s.engine) {
        NarratorEngine.SYSTEM -> null
        NarratorEngine.ELEVENLABS -> s.elevenLabsKey.trim().takeIf { it.isNotEmpty() }?.let { key ->
            val voiceId = s.elevenLabsVoiceId.trim().ifEmpty { AppSettings.DEFAULT_ELEVENLABS_VOICE }
            ElevenLabsVoice(key, voiceId, s.elevenLabsModel, s.expressiveness) to "eleven:$voiceId:${s.elevenLabsModel}:$expressiveness"
        }
        NarratorEngine.OPENAI -> s.openAiKey.trim().takeIf { it.isNotEmpty() }?.let { key ->
            val voice = s.openAiVoice.trim().ifEmpty { "sage" }
            val app = context.applicationContext
            OpenAiVoice(key, voice, expressiveness = s.expressiveness, durationOf = { bytes -> CloudTtsEngine.durationMillis(app, bytes) }) to
                "openai:$voice:$expressiveness"
        }
    }
}

/**
 * Plays a short sample in Settings so a voice can be judged before a book is read with it.
 * On-device samples are performed exactly as narration is ([ProsodyPlanner]); cloud samples use
 * the current voice settings and are cached, so replaying one costs nothing.
 */
class VoiceAudition(context: Context, private val language: String?) : AutoCloseable {
    sealed interface State {
        data object Idle : State
        data class Loading(val key: String) : State
        /** [note] explains a substitution, e.g. a model the account can't use. */
        data class Playing(val key: String, val note: String? = null) : State
        data class Failed(val message: String) : State
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val voicesFlow = MutableStateFlow<List<Voice>?>(null)
    /** On-device voices for the book's language, best first; null while the engine starts. */
    val voices: StateFlow<List<Voice>?> = voicesFlow.asStateFlow()

    private var ttsReady = false
    private var closed = false
    private var request = 0
    private var player: MediaPlayer? = null
    private val tts = TextToSpeech(app) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        if (closed) return
        ttsReady = status == TextToSpeech.SUCCESS
        voicesFlow.value = if (ttsReady) SystemTtsEngine.listVoices(tts, language) else emptyList()
        if (!ttsReady) return
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onDone(utteranceId: String) {
                main.post { if (utteranceId == "a$request#end") stateFlow.value = State.Idle }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String, errorCode: Int) {
                main.post {
                    if (!utteranceId.startsWith("a$request#")) return@post
                    runCatching { tts.stop() }
                    val network = errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT
                    stateFlow.value = State.Failed(
                        if (network) "That voice needs an internet connection." else "That voice couldn't speak. It may still be downloading.",
                    )
                }
            }
        })
    }

    /** The voice narration uses when none is picked (what "Automatic" plays). */
    fun autoVoice(): Voice? = if (ttsReady) SystemTtsEngine.autoVoice(tts, language) else null

    /** Speaks [text] with an on-device voice ([voice] null = the engine's default). */
    fun playSystem(voice: Voice?, key: String, perform: Boolean, speed: Float, text: String) {
        stop()
        if (!ttsReady) {
            stateFlow.value = State.Failed("The phone's voice engine isn't ready.")
            return
        }
        val id = request
        if (voice != null) runCatching { tts.voice = voice }
        ProsodyPlanner.plan(text, perform = perform).forEachIndexed { k, seg ->
            tts.setPitch(seg.pitch)
            tts.setSpeechRate(speed * seg.rate)
            tts.speak(seg.text, if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "a$id#$k")
            if (seg.pauseAfterMillis > 0) tts.playSilentUtterance((seg.pauseAfterMillis / speed).toLong(), TextToSpeech.QUEUE_ADD, "a$id#p$k")
        }
        // A marker request, so "finished" doesn't depend on how the sample was split.
        tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "a$id#end")
        tts.setPitch(1f)
        tts.setSpeechRate(speed)
        stateFlow.value = State.Playing(key)
    }

    /** Synthesises [text] with [voice] (cached under [cacheKey]) and plays it. */
    fun playCloud(voice: CloudVoice, cacheKey: String, speed: Float, text: String) {
        stop()
        val id = request
        stateFlow.value = State.Loading(CLOUD)
        net.execute {
            val result = runCatching { sample(voice, cacheKey, text) }
            main.post {
                if (closed || id != request) return@post
                result.fold(
                    onSuccess = { startPlayer(it.file, speed, id, it.note) },
                    onFailure = { stateFlow.value = State.Failed(CloudTtsEngine.errorMessage(voice.name, it)) },
                )
            }
        }
    }

    private class Sample(val file: File, val note: String?)

    private fun sample(voice: CloudVoice, cacheKey: String, text: String): Sample {
        val dir = File(app.cacheDir, "tts-samples").apply { mkdirs() }
        val file = File(dir, sha256("$cacheKey\n$text") + ".mp3")
        if (file.length() > 0) return Sample(file, null)
        val requestedModel = (voice as? ElevenLabsVoice)?.modelId
        val audio = voice.synthesize(chunk = ChunkPlanner.plan(listOf(Utterance("sample", 0, 0, text)), maxChars = 5_000).first(), speed = 1f)
        if (voice is ElevenLabsVoice && voice.modelId != requestedModel) {
            // Not cached under the v3 key: this is Multilingual v2 audio.
            val fallback = File(dir, "fallback.mp3").apply { writeBytes(audio.bytes) }
            return Sample(fallback, "Eleven v3 refused the request for this key, so this sample (and narration) uses Multilingual v2.")
        }
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(audio.bytes)
        if (!tmp.renameTo(file)) tmp.delete()
        if (!file.exists()) throw IllegalStateException("The sample couldn't be saved.")
        return Sample(file, null)
    }

    private fun startPlayer(file: File, speed: Float, id: Int, note: String?) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener {
                if (id == request) {
                    releasePlayer()
                    stateFlow.value = State.Idle
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (id == request) {
                    releasePlayer()
                    stateFlow.value = State.Failed("The sample couldn't be played.")
                }
                true
            }
            mp.prepare()
            player = mp
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            mp.start()
            stateFlow.value = State.Playing(CLOUD, note)
        } catch (e: Exception) {
            if (player === mp) player = null
            mp.release()
            file.delete() // a corrupt sample must not fail forever
            stateFlow.value = State.Failed("The sample couldn't be played.")
        }
    }

    fun stop() {
        request++
        releasePlayer()
        if (ttsReady) runCatching { tts.stop() }
        stateFlow.value = State.Idle
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun close() {
        stop()
        closed = true
        net.shutdownNow()
        runCatching { tts.shutdown() }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val CLOUD = "cloud"
    }
}
