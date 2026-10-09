package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class AlertMode(val label: String) {
    BELL("Bell"),
    BELL_AND_VIBRATE("Bell + vibrate"),
    VIBRATE("Vibrate only"),
}

/** One cue = the bell, a soft vibration, or both. Vibrate-only is for sitting next to someone. */
class Chime(context: Context) {
    val bell = BellPlayer(context)

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val isPlaying: Boolean get() = bell.isPlaying

    fun ring(volume: Float, mode: AlertMode) {
        if (mode != AlertMode.VIBRATE) bell.play(volume)
        if (mode != AlertMode.BELL) vibrate()
    }

    fun release() = bell.release()

    private val breath = BreathBuzzer(context)

    /** A settle-in cue at the start of each breath: one tap to breathe in, two to breathe out. */
    fun breathTick(phase: Settle.Phase) {
        breathTicks.incrementAndGet()
        breath.play(BreathBuzz.of(phase))
    }

    companion object {
        /** Settle-in buzzes given so far: tests check the pacing with it. */
        val breathTicks = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** Two soft pulses, like a tap on the shoulder. Alarm usage so DND lets it through. */
    private fun vibrate() {
        if (!vibrator.hasVibrator()) return
        val timings = longArrayOf(0, 180, 220, 180)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, 110, 0, 70), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, alarmAttributes)
    }
}
