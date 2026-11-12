package com.ankiwear.mobile

import com.ankiwatch.core.DeckSteps
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.PackCard
import com.ankiwatch.core.Wire
import kotlin.random.Random

/**
 * A deck's whole due queue, in AnkiDroid's order, for reviewing on the watch while the
 * phone is away: what AnkiDroid would show next, up to [limit] cards (its daily limits
 * usually stop it sooner). Each card keeps AnkiDroid's labels for its answer buttons and its
 * deck's learning steps, which the watch uses to time learning cards.
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
    // Each card's deck's learning steps (a parent deck's subdecks may use other options),
    // so the watch can time and label a card that comes back the way AnkiDroid would.
    val stepsByDeck = anki.getDeckSteps()
    // Usually every deck shares one preset: then no card needs looking up.
    val shared = stepsByDeck.values.distinct().let { if (it.size <= 1) it.firstOrNull() else null }
    return OfflinePack(
        id = Random.nextLong(),
        deckId = deckId,
        deckName = deckName,
        createdAt = now,
        cards = cards.map { card ->
            val steps = if (stepsByDeck.size <= 1 || shared != null) shared
            else stepsByDeck[anki.getCardDeckId(card.noteId, card.cardOrd) ?: deckId] ?: stepsByDeck[deckId]
            card.fitted().toPackCard(steps)
        }
    )
}

internal fun CardData.toPackCard(steps: DeckSteps? = null) = PackCard(
    noteId = noteId,
    cardOrd = cardOrd,
    buttonCount = buttonCount,
    nextReviewTimes = nextReviewTimes,
    question = question,
    answer = answer,
    clozeContent = cloze?.content.orEmpty(),
    clozeNumber = cloze?.clozeNumber ?: 0,
    extras = cloze?.extras.orEmpty(),
    modelName = cloze?.modelName.orEmpty(),
    steps = steps
)
