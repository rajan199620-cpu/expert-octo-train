package com.rajan.meditationtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Plays the singing-bowl bell. Uses the alarm stream so the bell still sounds when the
 * phone is on silent or in Do Not Disturb (alarms are allowed through DND by default).
 * Each strike gets its own MediaPlayer so overlapping bells ring out naturally.
 */
class BellPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Ducks (rather than stops) any ambient music the user is playing.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val active = mutableSetOf<MediaPlayer>()

    /** Called once the last ringing bell has faded out. */
    var onAllFinished: (() -> Unit)? = null

    val isPlaying: Boolean get() = active.isNotEmpty()

    /** [volume] is the 0..1 slider value; squared so the slider feels even to the ear. */
    fun play(volume: Float) {
        val player = MediaPlayer.create(context, R.raw.bell, attributes, audioManager.generateAudioSessionId()) ?: return
        if (active.isEmpty()) audioManager.requestAudioFocus(focusRequest)
        val gain = volume * volume
        player.setVolume(gain, gain)
        player.setOnCompletionListener { done(it) }
        active += player
        player.start()
    }

    fun release() {
        active.forEach { it.release() }
        active.clear()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun done(player: MediaPlayer) {
        active -= player
        player.release()
        if (active.isEmpty()) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            onAllFinished?.invoke()
        }
    }
}
