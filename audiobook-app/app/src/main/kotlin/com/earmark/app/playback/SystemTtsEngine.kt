package com.earmark.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.earmark.core.player.Utterance
import java.util.Locale

/** Callbacks shared by all narrator engines; ids are [Utterance.id]s. */
interface EngineCallbacks {
    fun started(id: String)
    fun done(id: String)
    fun error(id: String, message: String)
}

/**
 * The phone's own TTS voice: free, offline and instant, but only as human as the installed
 * engine (Google's "network"/neural voices are decent; older engines sound robotic).
 */
class SystemTtsEngine(
    context: Context,
    private val preferredVoice: String?,
    private val language: String?,
    private val callbacks: EngineCallbacks,
) : AppEngine {
    private val pending = ArrayList<Pair<List<Utterance>, Boolean>>()
    private var ready = false
    private var failed = false
    private var rate = 1.0f
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        synchronized(this) {
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                pending.flatMap { it.first }.firstOrNull()?.let { callbacks.error(it.id, "No text-to-speech engine is available.") }
                pending.clear()
                return
            }
            // Play on the media stream so volume keys and audio focus behave like an audiobook.
            tts.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            configureVoice()
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = callbacks.started(utteranceId)
                override fun onDone(utteranceId: String) = callbacks.done(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = callbacks.error(utteranceId, "The voice engine failed.")
                override fun onError(utteranceId: String, errorCode: Int) =
                    callbacks.error(utteranceId, if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) "The voice needs a network connection." else "The voice engine failed ($errorCode).")
            })
            ready = true
            val queued = pending.toList()
            pending.clear()
            queued.forEach { (u, flush) -> enqueue(u, flush) }
        }
    }

    private fun configureVoice() {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        val byName = preferredVoice?.let { name -> voices.firstOrNull { it.name == name } }
        if (byName != null) {
            tts.voice = byName
            return
        }
        val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val candidates = voices.filter { it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        val best = candidates.maxWithOrNull(compareBy<Voice>({ it.quality }, { -it.latency }, { it.locale.country == locale.country }))
        if (best != null) tts.voice = best else tts.language = locale
    }

    @Synchronized
    override fun speak(utterances: List<Utterance>, flush: Boolean) {
        if (failed) {
            utterances.firstOrNull()?.let { callbacks.error(it.id, "No text-to-speech engine is available.") }
            return
        }
        if (!ready) {
            if (flush) pending.clear()
            pending += utterances to flush
            return
        }
        enqueue(utterances, flush)
    }

    private fun enqueue(utterances: List<Utterance>, flush: Boolean) {
        utterances.forEachIndexed { i, u ->
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(u.text.take(TextToSpeech.getMaxSpeechInputLength()), mode, Bundle(), u.id)
        }
    }

    @Synchronized
    override fun stop() {
        pending.clear()
        if (ready) tts.stop()
    }

    @Synchronized
    override fun setSpeed(speed: Float) {
        rate = speed
        if (ready) tts.setSpeechRate(speed)
    }

    override fun close() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        /** Voices the user can pick in Settings, best first. */
        fun listVoices(tts: TextToSpeech, language: String?): List<Voice> {
            val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            return runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == locale.language }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
        }
    }
}

/** A second, independent voice for the assistant's replies ("Bookmarked.", answers). */
class ReplySpeaker(context: Context) : AutoCloseable {
    private val callbacks = HashMap<String, () -> Unit>()
    private var initialised = false
    private var ready = false
    private var queued: Pair<String, () -> Unit>? = null
    private var counter = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        initialised = true
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) { main.post { callbacks.remove(utteranceId)?.invoke() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { main.post { callbacks.remove(utteranceId)?.invoke() } }
            })
        }
        val q = queued
        queued = null
        if (q != null) speak(q.first, q.second)
    }

    /** Speaks [text] then calls [onDone] on the main thread (also when speech is impossible). */
    fun speak(text: String, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }
        if (!initialised) { queued?.second?.invoke(); queued = text to onDone; return }
        if (!ready) { onDone(); return }
        val id = "reply-${counter++}"
        callbacks[id] = onDone
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) callbacks.remove(id)?.invoke()
    }

    fun stop() {
        runCatching { tts.stop() }
        val pendingCallbacks = callbacks.values.toList()
        callbacks.clear()
        pendingCallbacks.forEach { it() }
    }

    override fun close() {
        runCatching { tts.shutdown() }
    }
}
