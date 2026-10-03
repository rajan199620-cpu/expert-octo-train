package com.ankiwear.mobile

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * The last thing the watch asked this phone for and what the phone did about it, shown on
 * the status screen. When the watch says the phone didn't answer, this tells the two cases
 * apart: the request never arrived (nothing here, or an old time), or it arrived and failed
 * on the phone (the error).
 */
class ExchangeLog(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun request(what: String) {
        prefs.edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_WHAT, what)
            .putString(KEY_OUTCOME, "working…")
            .commit()
    }

    fun outcome(text: String) {
        prefs.edit().putString(KEY_OUTCOME, text).commit()
    }

    /** "14:03:41 · deck list → sent 214 decks (31 KB)", or null if the watch never asked. */
    fun summary(): String? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))
        return "$time · ${prefs.getString(KEY_WHAT, "?")} → ${prefs.getString(KEY_OUTCOME, "?")}"
    }

    private companion object {
        const val PREFS = "exchange_log"
        const val KEY_AT = "at"
        const val KEY_WHAT = "what"
        const val KEY_OUTCOME = "outcome"
    }
}
