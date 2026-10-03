package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the background sound under a sit: a generated soundscape streamed to an AudioTrack, or
 * the user's own recording looped by MediaPlayer. It fades in, fades out on pause, dips under
 * every bell so the bell is always heard, and fades away at the end instead of cutting off.
 *
 * All calls are cheap and safe from the main thread, in any order and any number of times; if
 * audio can't be played for any reason, the sit simply goes on in silence.
 */
class AmbientPlayer(private val context: Context) {
    enum class State { STOPPED, PLAYING, PAUSED }

    @Volatile var state = State.STOPPED
        private set

    private val lock = Object()
    @Volatile private var volume = 0.5f
    /** Requested fade level 0..1 and how fast to get there; read by whichever engine is running. */
    @Volatile private var fadeTarget = 0f
    @Volatile private var fadeMs = 0
    @Volatile private var fadeVersion = 0
    @Volatile private var duckUntil = 0L
    @Volatile private var stopping = false

    @Volatile private var thread: Thread? = null
    private var media: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(kind: Ambience, volume: Float, customUri: Uri? = null, fadeInMs: Int = FADE_IN_MS) {
        stopNow()
        if (kind == Ambience.OFF) return
        this.volume = sliderGain(volume)
        stopping = false
        state = State.PLAYING
        fade(1f, fadeInMs)
        when (kind) {
            Ambience.CUSTOM -> customUri?.let(::startMedia) ?: run { state = State.STOPPED }
            else -> startSynth(kind)
        }
    }

    fun setVolume(value: Float) {
        volume = sliderGain(value)
        media?.let { applyMediaVolume() }
    }

    fun pause() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        fade(0f, PAUSE_FADE_MS)
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        synchronized(lock) { lock.notifyAll() }
        media?.let { runCatching { if (!it.isPlaying) it.start() } }
        fade(1f, RESUME_FADE_MS)
    }

    /** Dips the sound for a few seconds so a bell rings clearly over it. */
    fun duck() {
        duckUntil = SystemClock.elapsedRealtime() + DUCK_MS
        if (media != null) {
            applyMediaVolume()
            main.postDelayed({ applyMediaVolume() }, DUCK_MS + 50)
        }
    }

    /** Fades out over [fadeOutMs], then frees everything. Calling it again changes nothing. */
    fun stop(fadeOutMs: Int = END_FADE_MS) {
        if (state == State.STOPPED || stopping) return
        stopping = true
        fade(0f, fadeOutMs)
        synchronized(lock) { lock.notifyAll() }
        // The synth thread ends itself once silent; a recording is released after its fade.
        if (media != null) main.postDelayed({ releaseMedia() }, fadeOutMs.toLong() + 100)
    }

    /** Immediately, no fade: for tearing down before a new start. */
    fun stopNow() {
        stopping = true
        synchronized(lock) { lock.notifyAll() }
        thread?.let { it.interrupt(); runCatching { it.join(500) } }
        thread = null
        releaseMedia()
        state = State.STOPPED
    }

    private fun fade(target: Float, ms: Int) {
        fadeTarget = target
        fadeMs = ms
        fadeVersion++
        media?.let { mediaFade() }
    }

    // --- Generated sound ---

    private fun startSynth(kind: Ambience) {
        val t = Thread({ runSynth(kind) }, "ambience").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        thread = t
        t.start()
    }

    private fun runSynth(kind: Ambience) {
        live.incrementAndGet()
        var track: AudioTrack? = null
        try {
            val scape = Soundscape.create(kind, RATE) ?: return
            val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, BLOCK * 4) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val fadeGain = GainRamp(RATE)
            val duckGain = GainRamp(RATE, 1f)
            var seenFade = -1
            var ducked = false
            val block = FloatArray(BLOCK * 2)
            val pcm = ShortArray(BLOCK * 2)
            val began = System.nanoTime()
            var framesOut = 0L
            while (!Thread.currentThread().isInterrupted) {
                if (seenFade != fadeVersion) { seenFade = fadeVersion; fadeGain.to(fadeTarget, fadeMs) }
                val duckNow = SystemClock.elapsedRealtime() < duckUntil
                if (duckNow != ducked) { ducked = duckNow; duckGain.to(if (duckNow) DUCK_LEVEL else 1f, if (duckNow) 400 else 2500) }

                // Silent: stopped for good, or paused (park until resumed, holding no CPU).
                if (fadeGain.settled && fadeGain.value == 0f) {
                    if (stopping) break
                    if (state == State.PAUSED) {
                        runCatching { track.pause() }
                        synchronized(lock) { while (state == State.PAUSED && !stopping) lock.wait(1000) }
                        if (stopping) break
                        runCatching { track.play() }
                        continue
                    }
                }

                scape.render(block, BLOCK)
                val v = volume
                for (i in 0 until BLOCK) {
                    val g = fadeGain.next() * duckGain.next() * v
                    pcm[2 * i] = (block[2 * i] * g * 32767f).toInt().toShort()
                    pcm[2 * i + 1] = (block[2 * i + 1] * g * 32767f).toInt().toShort()
                }
                track.write(pcm, 0, pcm.size)
                framesOut += BLOCK
                // On a phone write() blocks at the speaker's pace; if it ever returns early (an
                // emulator), don't race ahead and burn the battery.
                val aheadMs = framesOut * 1000 / RATE - (System.nanoTime() - began) / 1_000_000 - 400
                if (aheadMs > 0) Thread.sleep(aheadMs)
            }
        } catch (_: InterruptedException) {
        } catch (_: Throwable) {
            // No audio (busy device, odd hardware): the sit carries on in silence.
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (thread === Thread.currentThread()) state = State.STOPPED
            live.decrementAndGet()
        }
    }

    // --- The user's own recording ---

    private var mediaLevel = 0f
    private var mediaTick: Runnable? = null

    private fun startMedia(uri: Uri) {
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(context, uri)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) { state = State.STOPPED; return }
        media = player
        mediaLevel = 0f
        mediaFade()
    }

    /** MediaPlayer has no ramps of its own: step its volume every 50 ms. */
    private fun mediaFade() {
        mediaTick?.let { main.removeCallbacks(it) }
        val target = fadeTarget
        val steps = maxOf(1, fadeMs / 50)
        val delta = (target - mediaLevel) / steps
        var n = 0
        val tick = object : Runnable {
            override fun run() {
                val p = media ?: return
                n++
                mediaLevel = if (n >= steps) target else mediaLevel + delta
                applyMediaVolume()
                if (n < steps) main.postDelayed(this, 50)
                else if (target == 0f && state == State.PAUSED) runCatching { p.pause() }
            }
        }
        mediaTick = tick
        main.post(tick)
    }

    private fun applyMediaVolume() {
        val duck = if (SystemClock.elapsedRealtime() < duckUntil) DUCK_LEVEL else 1f
        val g = mediaLevel * volume * duck
        runCatching { media?.setVolume(g, g) }
    }

    private fun releaseMedia() {
        mediaTick?.let { main.removeCallbacks(it) }
        media?.let { runCatching { it.stop() }; runCatching { it.release() } }
        media = null
        if (thread == null) state = State.STOPPED
    }

    companion object {
        const val RATE = 32_000
        private const val BLOCK = 1024
        const val FADE_IN_MS = 8_000
        const val PAUSE_FADE_MS = 1_200
        const val RESUME_FADE_MS = 2_500
        const val END_FADE_MS = 6_000
        const val DUCK_MS = 5_000L
        const val DUCK_LEVEL = 0.35f

        /** Synth threads alive right now: tests check none are left behind. */
        val live = AtomicInteger(0)
    }
}
