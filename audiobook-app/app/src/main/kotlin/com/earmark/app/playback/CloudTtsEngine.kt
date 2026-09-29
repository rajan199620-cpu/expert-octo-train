package com.earmark.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.earmark.core.player.Utterance
import com.earmark.core.speech.ChunkPlanner
import com.earmark.core.speech.CloudVoice
import com.earmark.core.speech.SpeechChunk
import com.earmark.core.speech.VoiceException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Narration with a cloud voice (ElevenLabs / OpenAI).
 *
 * Sentences are grouped into paragraph-sized chunks, synthesised one chunk ahead of playback,
 * cached on disk (re-listening costs nothing), played with MediaPlayer at the chosen speed,
 * and per-sentence start times drive the controller so the highlight follows the voice.
 * Everything runs on the main thread except the network call.
 */
class CloudTtsEngine(
    context: Context,
    private val voice: CloudVoice,
    /** Identifies voice + model for the cache key. */
    private val voiceKey: String,
    private val callbacks: EngineCallbacks,
) : AppEngine {
    private val main = Handler(Looper.getMainLooper())
    private val synth = Executors.newSingleThreadExecutor()
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    private class Prepared(val chunk: SpeechChunk, val file: File, val starts: List<Long>)

    private val waiting = ArrayDeque<Utterance>()
    private val ready = ArrayDeque<Prepared>()
    private var synthesizing = false
    private var generation = 0
    private var player: MediaPlayer? = null
    private var playing: Prepared? = null
    private var reported = -1
    private var speed = 1f

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (player != null) main.postDelayed(this, 50)
        }
    }

    override fun speak(utterances: List<Utterance>, flush: Boolean) = onMain {
        if (flush) reset()
        waiting.addAll(utterances)
        pump()
    }

    override fun stop() = onMain { reset() }

    override fun setSpeed(speed: Float) = onMain {
        this.speed = speed
        player?.let { mp -> runCatching { if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(speed) } }
    }

    override fun close() {
        onMain { reset() }
        synth.shutdownNow()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun reset() {
        generation++
        releasePlayer()
        waiting.clear()
        ready.clear()
        synthesizing = false
    }

    /** Keeps one chunk synthesising ahead of playback. */
    private fun pump() {
        maybePlay()
        if (synthesizing || waiting.isEmpty() || ready.size >= 2) return
        val chunk = ChunkPlanner.plan(waiting.toList(), maxChars = 900).first()
        repeat(chunk.utterances.size) { waiting.removeFirst() }
        synthesizing = true
        val gen = generation
        synth.execute {
            val result = runCatching { prepare(chunk) }
            main.post {
                if (gen != generation) return@post
                synthesizing = false
                result.fold(
                    onSuccess = { ready.addLast(it); pump() },
                    onFailure = { e -> fail(chunk, e) },
                )
            }
        }
    }

    private fun prepare(chunk: SpeechChunk): Prepared {
        val key = sha256("$voiceKey\n${chunk.text}")
        val audio = File(cacheDir, "$key.mp3")
        val timings = File(cacheDir, "$key.timings")
        if (audio.exists() && timings.exists()) {
            val starts = timings.readText().split(',').mapNotNull { it.trim().toLongOrNull() }
            if (starts.size == chunk.utterances.size) {
                audio.setLastModified(System.currentTimeMillis())
                return Prepared(chunk, audio, starts)
            }
        }
        val out = voice.synthesize(chunk, speed)
        audio.writeBytes(out.bytes)
        timings.writeText(out.utteranceStartMillis.joinToString(","))
        trimCache()
        return Prepared(chunk, audio, out.utteranceStartMillis)
    }

    private fun maybePlay() {
        if (player != null || ready.isEmpty()) return
        val p = ready.removeFirst()
        val gen = generation
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(p.file.path)
            mp.setOnCompletionListener { if (gen == generation) finishChunk(p) }
            mp.setOnErrorListener { _, what, extra ->
                if (gen == generation) fail(p.chunk, IllegalStateException("Audio playback error ($what/$extra)"))
                true
            }
            mp.prepare() // local file: fast
            player = mp
            playing = p
            reported = -1
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
            mp.start()
            main.post(ticker)
        } catch (e: Exception) {
            mp.release()
            player = null
            playing = null
            p.file.delete() // a corrupt cached file must not fail forever
            fail(p.chunk, e)
            return
        }
        pump()
    }

    /** Reports sentence starts as playback passes their timestamps. */
    private fun tick() {
        val mp = player ?: return
        val p = playing ?: return
        val gen = generation
        val pos = runCatching { mp.currentPosition.toLong() }.getOrElse { return }
        val us = p.chunk.utterances
        while (reported + 1 < us.size && p.starts[reported + 1] <= pos) {
            if (reported >= 0) callbacks.done(us[reported].id)
            if (gen != generation) return
            reported++
            callbacks.started(us[reported].id)
            if (gen != generation) return
        }
    }

    private fun finishChunk(p: Prepared) {
        val gen = generation
        val us = p.chunk.utterances
        while (reported + 1 < us.size) {
            if (reported >= 0) callbacks.done(us[reported].id)
            if (gen != generation) return
            reported++
            callbacks.started(us[reported].id)
            if (gen != generation) return
        }
        if (reported >= 0) callbacks.done(us[reported].id)
        if (gen != generation) return
        releasePlayer()
        pump()
    }

    private fun fail(chunk: SpeechChunk, e: Throwable) {
        val message = when (e) {
            is VoiceException -> when (e.kind) {
                VoiceException.Kind.AUTH -> "${voice.name} rejected your API key. Check it in Settings."
                VoiceException.Kind.QUOTA -> "Your ${voice.name} quota is used up. Switch to the on-device voice in Settings."
                VoiceException.Kind.NETWORK -> "No connection to ${voice.name}. Cached parts still play; the on-device voice works offline."
                else -> e.message ?: "${voice.name} failed."
            }
            else -> e.message ?: "Playback failed."
        }
        val id = chunk.utterances.firstOrNull()?.id ?: return
        reset()
        callbacks.error(id, message)
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        reported = -1
    }

    private fun trimCache(maxBytes: Long = 300L * 1024 * 1024) {
        val files = cacheDir.listFiles()?.filter { it.extension == "mp3" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            File(cacheDir, f.nameWithoutExtension + ".timings").delete()
            if (total <= maxBytes * 0.8) break
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Duration of an MP3 in memory, for voices that return no timestamps (OpenAI). */
        fun durationMillis(context: Context, bytes: ByteArray): Long {
            val tmp = File.createTempFile("dur", ".mp3", context.cacheDir)
            return try {
                tmp.writeBytes(bytes)
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(tmp.path)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    r.release()
                }
            } catch (_: Exception) {
                0L
            } finally {
                tmp.delete()
            }
        }
    }
}

/** A narrator engine the app can own and close. */
interface AppEngine : com.earmark.core.player.SpeechEngine, AutoCloseable
