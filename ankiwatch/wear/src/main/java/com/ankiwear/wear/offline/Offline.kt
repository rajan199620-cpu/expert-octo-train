package com.ankiwear.wear.offline

import android.content.Context
import com.ankiwatch.core.PackCard
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.ClozeCard

/** Where an offline download stands, for the watch's Offline screen. */
sealed class Download {
    object Idle : Download()

    /** Asked the phone for [deckName]'s cards at [since] (epoch millis). */
    data class Waiting(val deckName: String, val since: Long) : Download()

    data class Ready(val summary: OfflineStore.Summary) : Download()

    data class Failed(val message: String) : Download()
}

/**
 * Numbers the watch's answers in the order they were given, across restarts, so the phone
 * can apply a whole offline session's grades in that order. Never below the clock in
 * milliseconds, so the numbers keep rising even if the stored one were lost.
 */
class AnswerSequence(context: Context) {
    private val prefs = context.getSharedPreferences("answer_sequence", Context.MODE_PRIVATE)

    @Synchronized
    fun next(): Long {
        val next = maxOf(prefs.getLong(KEY, 0L) + 1, System.currentTimeMillis())
        // commit(), not apply(): a number handed out twice could put two grades out of order.
        prefs.edit().putLong(KEY, next).commit()
        return next
    }

    private companion object {
        const val KEY = "last"
    }
}

/**
 * The card as the review screen shows it. A card coming back for another go has no
 * labels: AnkiDroid's ones were for its first answer.
 */
fun PackCard.toCardData(withLabels: Boolean = true): CardData = CardData(
    noteId = noteId,
    cardOrd = cardOrd,
    question = question,
    answer = answer,
    buttonCount = buttonCount,
    nextReviewTimes = if (withLabels) nextReviewTimes else emptyList(),
    cloze = if (clozeNumber > 0) ClozeCard(clozeContent, clozeNumber, extras, modelName) else null
)
