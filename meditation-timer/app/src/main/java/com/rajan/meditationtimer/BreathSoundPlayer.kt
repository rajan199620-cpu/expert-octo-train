package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Plays [BreathVoice] in step with a breath being paced: a Breathe-tab exercise, or the settle-in
 * breaths at the start of a sit. It streams from the service that paces the breath, so it carries
 * on with the phone locked, and it follows the exercise's clock rather than its own: each block
 * of sound is made for the moment it will actually be heard, so it never drifts from the breath
 * on screen, even over Bluetooth's longer delay.
 *
 * It plays as media, so the volume keys set its level and earphones keep it private (alarm sounds,
 * like the bell, play through the speaker even with earphones in). All calls are cheap and safe
 * from the main thread, in any order; if sound can't be played, [start] says so and the caller
 * paces by vibration instead.
 */
class BreathSoundPlayer(context: Context) {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false
    /** Another app has the speaker (a call): stay quiet until it's done. */
    @Volatile private var muted = false
    @Volatile private var focus: AudioFocusRequest? = null

    /** Whether the media volume is all the way down, so the breath can't be heard. */
    val silenced: Boolean
        get() = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)

    /**
     * Starts the sound of [pattern], whose breath time 0 is [anchor] (SystemClock.elapsedRealtime),
     * until [endMs] of breath time. With [takeFocus], music in other apps pauses for it, as for any
     * guided audio; without, it plays alongside a sit's own background sound. False if it can't play.
     */
    fun start(pattern: BreathPattern, anchor: Long, endMs: Long, takeFocus: Boolean): Boolean {
        stopNow()
        if (takeFocus && !requestFocus()) return false
        stopping = false
        muted = false
        val t = Thread({ run(pattern, anchor, endMs) }, "breath-sound").apply { isDaemon = true }
        thread = t
        starts.incrementAndGet()
        t.start()
        return true
    }

    /** Fades out over a moment, then frees everything. Calling it again changes nothing. */
    fun stop() {
        stopping = true
        releaseFocus()
    }

    /** Immediately, no fade: before a new start, or when the service goes. */
    fun stopNow() {
        stopping = true
        releaseFocus()
        thread?.let { it.interrupt(); runCatching { it.join(500) } }
        thread = null
    }

    private fun requestFocus(): Boolean {
        val manager = audio ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATTRIBUTES)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> muted = false
                    // Ducking for a navigation prompt is Android's job; anything else silences the breath.
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> muted = true
                }
            }, main)
            .build()
        val granted = runCatching { manager.requestAudioFocus(request) }.getOrNull()
        // Refused (a call is on): pace by vibration rather than over the call.
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return false
        focus = request
        return true
    }

    private fun releaseFocus() {
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
        focus = null
    }

    private fun run(pattern: BreathPattern, anchor: Long, endMs: Long) {
        live.incrementAndGet()
        var track: AudioTrack? = null
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val voice = BreathVoice(pattern, RATE, SystemClock.elapsedRealtimeNanos(), endMs.toDouble())
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

            // Started part-way through a breath (a resumed sit): ease in rather than click.
            val gain = GainRamp(RATE, 0f).apply { to(1f, FADE_IN_MS) }
            var heading = 1f
            val block = FloatArray(BLOCK * 2)
            val pcm = ShortArray(BLOCK * 2)
            val stamp = AudioTimestamp()
            // Breath time of the next frame to be written; corrected below to when it will be heard.
            var clock = (SystemClock.elapsedRealtime() - anchor).toDouble()
            var written = 0L
            var checkedAt = 0L
            val began = System.nanoTime()
            while (!Thread.currentThread().isInterrupted) {
                val target = if (stopping || muted) 0f else 1f
                if (target != heading) { heading = target; gain.to(target, if (target == 0f) FADE_OUT_MS else FADE_IN_MS) }
                if (stopping && gain.settled && gain.value == 0f) break
                if (clock >= endMs + TAIL_MS) break

                // Every half second, line the sound up with when the speaker will actually play it.
                if (written - checkedAt >= RATE / 2 && track.getTimestamp(stamp) && stamp.framePosition > 0 && stamp.nanoTime > 0) {
                    checkedAt = written
                    val offsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                    val heardNs = stamp.nanoTime + (written - stamp.framePosition) * 1_000_000_000L / RATE + offsetNs
                    val error = heardNs / 1e6 - anchor - clock
                    // Big gaps (the system starved the stream) are closed at once, small drift gently;
                    // a reading many seconds out is a faulty clock, not the speaker, and is ignored.
                    if (abs(error) < IMPLAUSIBLE_MS) clock += if (abs(error) > JUMP_MS) error else error * 0.25
                }

                voice.render(block, BLOCK, clock)
                for (i in 0 until BLOCK) {
                    val g = gain.next()
                    pcm[2 * i] = (block[2 * i] * g * 32767f).toInt().toShort()
                    pcm[2 * i + 1] = (block[2 * i + 1] * g * 32767f).toInt().toShort()
                }
                track.write(pcm, 0, pcm.size)
                written += BLOCK
                clock += BLOCK * 1000.0 / RATE
                // On a phone write() blocks at the speaker's pace; if it ever returns early (an
                // emulator), don't race ahead and burn the battery.
                val aheadMs = written * 1000 / RATE - (System.nanoTime() - began) / 1_000_000 - 400
                if (aheadMs > 0) Thread.sleep(aheadMs)
            }
        } catch (_: InterruptedException) {
        } catch (_: Throwable) {
            // No audio (busy device, odd hardware): the breath carries on, paced by the screen.
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (thread === Thread.currentThread()) {
                thread = null
                releaseFocus()
            }
            live.decrementAndGet()
        }
    }

    companion object {
        private const val RATE = 32_000
        private const val BLOCK = 512
        private const val FADE_IN_MS = 150
        private const val FADE_OUT_MS = 250
        /** Played past the end so the last out-breath's fade isn't cut. */
        private const val TAIL_MS = 300L
        private const val JUMP_MS = 250.0
        private const val IMPLAUSIBLE_MS = 10_000.0

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Sound threads alive right now, and starts so far: tests check the pacing with them. */
        val live = AtomicInteger(0)
        val starts = AtomicInteger(0)
    }
}
