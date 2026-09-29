package com.earmark.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.earmark.core.player.Utterance
import com.earmark.core.speech.ProsodyPlanner
import com.earmark.core.speech.SpeechSegment
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Callbacks shared by all narrator engines; ids are [Utterance.id]s. */
interface EngineCallbacks {
    fun started(id: String)
    fun done(id: String)
    fun error(id: String, message: String)

    /** Characters [start, end) of the utterance's speech text are being spoken (word tracking). */
    fun range(id: String, start: Int, end: Int) {}
}

/**
 * The phone's own TTS voice: free, offline and instant, but only as human as the installed
 * engine (Google's "network"/neural voices are decent; older engines sound robotic).
 *
 * Phone voices read every sentence with the same flat melody, so each sentence is performed
 * as a few pieces ([ProsodyPlanner]): dialogue in a different pitch, questions and
 * exclamations lifted, and real silences after headings, paragraphs and chapters. Each piece
 * is its own TTS request ("id#k"); callbacks are folded back into one start/done per sentence.
 */
class SystemTtsEngine(
    context: Context,
    private val preferredVoice: String?,
    private val language: String?,
    private val perform: Boolean,
    private val callbacks: EngineCallbacks,
) : AppEngine {
    private class Plan(val segments: List<SpeechSegment>, val lastRequestId: String)

    private val plans = ConcurrentHashMap<String, Plan>()
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
                override fun onStart(utteranceId: String) {
                    val (uid, piece, isPause) = split(utteranceId) ?: return
                    if (piece == 0 && !isPause) callbacks.started(uid)
                }

                override fun onDone(utteranceId: String) {
                    val (uid, _, _) = split(utteranceId) ?: return
                    val plan = plans[uid] ?: return
                    if (utteranceId == plan.lastRequestId) {
                        plans.remove(uid)
                        callbacks.done(uid)
                    }
                }

                // Word-by-word progress (Google's engine reports it; others may not).
                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                    val (uid, piece, isPause) = split(utteranceId) ?: return
                    if (isPause) return
                    val seg = plans[uid]?.segments?.getOrNull(piece) ?: return
                    callbacks.range(uid, seg.offset + start, seg.offset + end)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    split(utteranceId)?.let { callbacks.error(it.first, "The voice engine failed.") }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    val uid = split(utteranceId)?.first ?: return
                    callbacks.error(uid, if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) "The voice needs a network connection." else "The voice engine failed ($errorCode).")
                }
            })
            ready = true
            val queued = pending.toList()
            pending.clear()
            queued.forEach { (u, flush) -> enqueue(u, flush) }
        }
    }

    private fun configureVoice() {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        val chosen = preferredVoice?.let { name -> voices.firstOrNull { it.name == name } } ?: autoVoice(tts, language)
        if (chosen != null) tts.voice = chosen else tts.language = localeFor(language)
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
        if (flush) plans.clear()
        var first = flush
        val max = TextToSpeech.getMaxSpeechInputLength()
        for (u in utterances) {
            val segments = ProsodyPlanner.plan(u.text, u.isHeading, u.endsParagraph, u.endsChapter, perform)
            val last = segments.lastIndex
            val lastId = if (segments[last].pauseAfterMillis > 0) "${u.id}#p$last" else "${u.id}#$last"
            // Register before speaking: callbacks for the first piece can arrive immediately.
            plans[u.id] = Plan(segments, lastId)
            segments.forEachIndexed { k, seg ->
                // Pitch and rate are captured per request, so each piece gets its own delivery.
                tts.setPitch(seg.pitch)
                tts.setSpeechRate(rate * seg.rate)
                tts.speak(seg.text.take(max), if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "${u.id}#$k")
                first = false
                if (seg.pauseAfterMillis > 0) {
                    tts.playSilentUtterance((seg.pauseAfterMillis / rate).toLong(), TextToSpeech.QUEUE_ADD, "${u.id}#p$k")
                }
            }
        }
        tts.setPitch(1f)
        tts.setSpeechRate(rate)
    }

    /** "gen:idx#k" or "gen:idx#pk" -> (utterance id, piece index, is a pause). */
    private fun split(requestId: String): Triple<String, Int, Boolean>? {
        val hash = requestId.lastIndexOf('#')
        if (hash <= 0) return null
        val part = requestId.substring(hash + 1)
        val isPause = part.startsWith("p")
        val piece = part.removePrefix("p").toIntOrNull() ?: return null
        return Triple(requestId.substring(0, hash), piece, isPause)
    }

    @Synchronized
    override fun stop() {
        pending.clear()
        plans.clear()
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
        /**
         * Voices the user can pick in Settings, best first. Online ("network") voices are the
         * engine's neural voices and sound noticeably more natural, so they rank above offline
         * voices of the same quality.
         */
        fun listVoices(tts: TextToSpeech, language: String?): List<Voice> {
            val locale = localeFor(language)
            return installed(tts, locale)
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { it.isNetworkConnectionRequired }
                        .thenByDescending { it.locale.country == locale.country }
                        .thenBy { it.name },
                )
        }

        /**
         * The voice used when none is chosen: the highest quality installed voice for the
         * language, preferring low latency (an offline voice keeps reading without a network).
         */
        fun autoVoice(tts: TextToSpeech, language: String?): Voice? {
            val locale = localeFor(language)
            return installed(tts, locale).maxWithOrNull(compareBy<Voice>({ it.quality }, { -it.latency }, { it.locale.country == locale.country }))
        }

        private fun localeFor(language: String?): Locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()

        private fun installed(tts: TextToSpeech, locale: Locale): List<Voice> =
            runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }

        const val SAMPLE =
            "The storm had finally passed. \u201CIs anyone there?\u201D she called into the dark. " +
                "Nobody answered... and then, far below, a light began to glow."
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
