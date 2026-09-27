package com.rajan.meditationtimer

import android.content.Context
import androidx.core.content.edit

/** Remembers the last-used settings so the next sit is one tap away. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val durationMin: Int get() = sp.getInt(KEY_DURATION, 20)
    val openingBellSec: Int get() = sp.getInt(KEY_OPENING, 5)
    val closingBellSec: Int get() = sp.getInt(KEY_CLOSING, 10)
    val bellAtEnd: Boolean get() = sp.getBoolean(KEY_END, false)
    val volume: Float get() = sp.getFloat(KEY_VOLUME, 0.6f)

    fun save(durationMin: Int, openingBellSec: Int, closingBellSec: Int, bellAtEnd: Boolean, volume: Float) {
        sp.edit {
            putInt(KEY_DURATION, durationMin)
            putInt(KEY_OPENING, openingBellSec)
            putInt(KEY_CLOSING, closingBellSec)
            putBoolean(KEY_END, bellAtEnd)
            putFloat(KEY_VOLUME, volume)
        }
    }

    private companion object {
        const val KEY_DURATION = "duration_min"
        const val KEY_OPENING = "opening_bell_sec"
        const val KEY_CLOSING = "closing_bell_sec"
        const val KEY_END = "bell_at_end"
        const val KEY_VOLUME = "volume"
    }
}
