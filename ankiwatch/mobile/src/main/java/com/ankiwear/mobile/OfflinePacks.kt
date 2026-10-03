package com.ankiwear.mobile

import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.PackCard
import com.ankiwatch.core.Wire
import kotlin.random.Random

/**
 * A deck's whole due queue, in AnkiDroid's order, for reviewing on the watch while the
 * phone is away: what AnkiDroid would show next, up to [limit] cards (its daily limits
 * usually stop it sooner). Each card keeps AnkiDroid's labels for its answer buttons, which
 * the watch uses to time learning cards.
 */
internal fun buildOfflinePack(
    anki: AnkiDroidHelper,
    deckId: Long,
    deckName: String,
    limit: Int = Wire.OFFLINE_CARD_LIMIT,
    now: Long = System.currentTimeMillis()
): OfflinePack {
    anki.setSelectedDeck(deckId)
    val cards = anki.getScheduledCards(deckId, limit)
    return OfflinePack(
        id = Random.nextLong(),
        deckId = deckId,
        deckName = deckName,
        createdAt = now,
        cards = cards.map { it.fitted().toPackCard() }
    )
}

internal fun CardData.toPackCard() = PackCard(
    noteId = noteId,
    cardOrd = cardOrd,
    buttonCount = buttonCount,
    nextReviewTimes = nextReviewTimes,
    question = question,
    answer = answer,
    clozeContent = cloze?.content.orEmpty(),
    clozeNumber = cloze?.clozeNumber ?: 0,
    extras = cloze?.extras.orEmpty(),
    modelName = cloze?.modelName.orEmpty()
)
