package com.ankiwear.wear

import android.content.Context

/** Review settings the watch remembers between sessions. */
class ReviewPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)

    /** Show only the part of a long note holding the tested cloze (default on). */
    var focusMode: Boolean
        get() = prefs.getBoolean(KEY_FOCUS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FOCUS, value).apply()
        }

    private companion object {
        const val KEY_FOCUS = "focus_mode"
    }
}
