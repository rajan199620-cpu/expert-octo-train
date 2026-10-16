package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the background sound under a sit: a recording looped by MediaPlayer (the bundled rain, or
 * the user's own), generated birdsong streamed to an AudioTrack, or both at once for rain and
 * birds. It fades in, fades out on pause, dips under
 * every bell so the bell is always heard, and fades away at the end instead of cutting off.
 *
 * It also gives way, as Android asks media to: a phone call or an app speaking for a moment
 * pauses it until they're done, and another app starting music or a video stops it for the rest
 * of the sit. (Navigation prompts and notification sounds just dip it, which Android does itself.)
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
    @Volatile private var media: MediaPlayer? = null
    /** How loud the recording sits in the mix: a little lower under birdsong, so the sum isn't louder. */
    private var mediaMix = 1f
    private val main = Handler(Looper.getMainLooper())

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    /** Another app has the speaker for a moment (a call ringing, a voice message). */
    @Volatile private var focusLostForNow = false
    /** Paused by that, not by you: carry on when it's over. */
    @Volatile private var interrupted = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
        .build()

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusLostForNow = true
                if (state == State.PLAYING) {
                    interrupted = true
                    pauseNow()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLostForNow = false
                if (interrupted) {
                    interrupted = false
                    resumeNow()
                }
            }
            // Music or a video started elsewhere: give way for the rest of the sit.
            AudioManager.AUDIOFOCUS_LOSS -> stop(PAUSE_FADE_MS)
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: other apps' prompts are ducked by Android itself,
            // and our own bells already dip the sound (see [duck]).
        }
    }

    fun start(kind: Ambience, volume: Float, customUri: Uri? = null, fadeInMs: Int = FADE_IN_MS) {
        stopNow()
        if (kind == Ambience.OFF) return
        // Refused (a call is on, say): the sit goes on in silence rather than over the call.
        val granted = runCatching { audio?.requestAudioFocus(focusRequest) }.getOrNull()
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return
        focusLostForNow = false
        interrupted = false
        this.volume = sliderGain(volume)
        stopping = false
        state = State.PLAYING
        fade(1f, fadeInMs)
        val playing = when (kind) {
            Ambience.CUSTOM -> customUri?.let { startMedia(it, mix = 1f, randomStart = false) } ?: false
            Ambience.RAIN -> startMedia(rainUri(context), mix = 1f, randomStart = true)
            Ambience.RAIN_AND_BIRDS -> startMedia(rainUri(context), mix = RAIN_UNDER_BIRDS, randomStart = true) or startSynth(kind, BIRDS_OVER_RAIN)
            else -> startSynth(kind, 1f)
        }
        // Nothing to play (a deleted recording): don't keep other apps quiet for nothing.
        if (!playing) {
            state = State.STOPPED
            releaseFocus()
        }
    }

    fun setVolume(value: Float) {
        volume = sliderGain(value)
        media?.let { applyMediaVolume() }
    }

    /** You paused the sit: stays paused until you resume, whatever happens meanwhile. */
    fun pause() {
        interrupted = false
        pauseNow()
    }

    /** You resumed the sit. If a call is still going, the sound waits for it to end. */
    fun resume() {
        if (state != State.PAUSED) return
        if (focusLostForNow) {
            interrupted = true
            return
        }
        resumeNow()
    }

    private fun pauseNow() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        fade(0f, PAUSE_FADE_MS)
    }

    private fun resumeNow() {
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
        releaseFocus()
        fade(0f, fadeOutMs)
        synchronized(lock) { lock.notifyAll() }
        // The synth thread ends itself once silent; a recording is released after its fade, unless
        // a new sound has started since (ending a sit and starting the next within the fade).
        media?.let { fading -> main.postDelayed({ if (media === fading) releaseMedia() }, fadeOutMs.toLong() + 100) }
    }

    /** Immediately, no fade: for tearing down before a new start. */
    fun stopNow() {
        stopping = true
        releaseFocus()
        synchronized(lock) { lock.notifyAll() }
        thread?.let { it.interrupt(); runCatching { it.join(500) } }
        thread = null
        releaseMedia()
        state = State.STOPPED
    }

    private fun releaseFocus() {
        interrupted = false
        focusLostForNow = false
        runCatching { audio?.abandonAudioFocusRequest(focusRequest) }
    }

    private fun fade(target: Float, ms: Int) {
        fadeTarget = target
        fadeMs = ms
        fadeVersion++
        media?.let { mediaFade() }
    }

    // --- Generated sound ---

    private fun startSynth(kind: Ambience, mix: Float): Boolean {
        val t = Thread({ runSynth(kind, mix) }, "ambience").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        thread = t
        t.start()
        return true
    }

    private fun runSynth(kind: Ambience, mix: Float) {
        live.incrementAndGet()
        var track: AudioTrack? = null
        try {
            val scape = Soundscape.create(kind, RATE) ?: return
            val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
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
                val v = volume * mix
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
            // Only if still the current sound: an old thread ending late mustn't touch a new start.
            // With rain playing alongside, whichever of the two ends last marks the sound stopped
            // (see [releaseMedia]; both fields are volatile, so one of them always sees the other gone).
            if (thread === Thread.currentThread()) {
                thread = null
                if (media == null) {
                    state = State.STOPPED
                    releaseFocus()
                }
            }
            live.decrementAndGet()
        }
    }

    // --- Recordings: the bundled rain, or the user's own ---

    private var mediaLevel = 0f
    private var mediaTick: Runnable? = null

    /**
     * Rain starts at a random point in its four-minute loop, so the same thunder roll doesn't
     * open every sit. (The file is cut to loop seamlessly: see tools/make_rain.py.)
     */
    private fun startMedia(uri: Uri, mix: Float, randomStart: Boolean): Boolean {
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(ATTRIBUTES)
                setDataSource(context, uri)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                if (randomStart && duration > 0) seekTo((Math.random() * duration).toInt())
                start()
            }
        }.getOrNull() ?: return false
        media = player
        mediaMix = mix
        mediaLevel = 0f
        mediaFade()
        return true
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
        val g = mediaLevel * volume * duck * mediaMix
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
        /** Rain & birds: each a little lower than alone, so together they're no louder. */
        const val RAIN_UNDER_BIRDS = 0.7f
        const val BIRDS_OVER_RAIN = 0.9f

        fun rainUri(context: Context): Uri = Uri.parse("android.resource://${context.packageName}/${R.raw.rain}")

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Synth threads alive right now: tests check none are left behind. */
        val live = AtomicInteger(0)
    }
}
