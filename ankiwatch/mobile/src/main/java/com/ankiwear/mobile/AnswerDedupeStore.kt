package com.ankiwear.mobile

import android.content.Context
import com.ankiwatch.core.BoundedOrderedSet

/**
 * Persistent, bounded record of review-answer UUIDs the phone has already applied to
 * AnkiDroid.
 *
 * Answers arrive as guaranteed-delivery DataItems (see [WearListenerService]). The
 * Wearable Data Layer may redeliver an item — most commonly because the phone service is
 * killed mid-processing — so without a dedupe the same grade could be applied to a card
 * twice. This is backed by SharedPreferences because it must survive the frequent service
 * restarts that an in-memory set would not.
 *
 * Stored as one ordered string rather than a string set: SharedPreferences string sets
 * don't keep order, so trimming them to the "most recent" entries actually dropped random
 * ones.
 */
class AnswerDedupeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): BoundedOrderedSet = BoundedOrderedSet.deserialize(MAX, prefs.getString(KEY, null))

    fun isProcessed(uuid: String): Boolean = uuid.isNotEmpty() && uuid in load()

    fun markProcessed(uuid: String) {
        if (uuid.isEmpty()) return
        val set = load()
        set.add(uuid)
        // commit(), not apply(): the service can be killed right after this returns, and a
        // lost write here means a redelivered answer would be applied twice.
        prefs.edit().putString(KEY, set.serialize()).commit()
    }

    companion object {
        private const val PREFS = "answer_dedupe"
        private const val KEY = "processed_uuids_ordered"
        private const val MAX = 200
    }
}
