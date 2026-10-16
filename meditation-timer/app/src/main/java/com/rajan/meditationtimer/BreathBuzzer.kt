package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays [BreathBuzz] patterns. Alarm usage, so Do Not Disturb and a locked screen let them through. */
class BreathBuzzer(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(kind: BreathBuzz.Kind) {
        BreathBuzz.played.incrementAndGet()
        BreathBuzz.last = kind
        if (!vibrator.hasVibrator()) return
        val p = BreathBuzz.pattern(kind)
        // Without amplitude control every pulse runs at full strength; the rhythm still reads the same.
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(p.timings, p.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(p.timings, -1)
        }
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, attributes)
    }
}
