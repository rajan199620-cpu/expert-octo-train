package com.ankiwear.wear

import com.ankiwear.wear.model.CardData
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cards the phone sends while an answer is held leave out the held card, and only that one. */
class HeldAnswerFilterTest {

    private fun card(note: Long, ord: Int) = CardData(note, ord, "", "", 4)

    private fun held(note: Long, ord: Int, offline: Boolean = false) =
        DataLayerClient.HeldAnswer(note, ord, 3, 1_000, 1L, offline, card(note, ord))

    private val cards = listOf(card(1, 0), card(1, 1), card(2, 0), card(3, 0))

    @Test
    fun theHeldCardIsLeftOut() {
        assertEquals(listOf(card(1, 0), card(2, 0), card(3, 0)), withoutHeld(cards, held(1, 1)))
        // Same note, other cloze: a different card, kept.
        assertEquals(cards, withoutHeld(cards, held(1, 2)))
    }

    @Test
    fun nothingHeldOrAnOfflineAnswerChangesNothing() {
        assertEquals(cards, withoutHeld(cards, null))
        // Offline answers come from a download; the phone's live cards are another matter.
        assertEquals(cards, withoutHeld(cards, held(2, 0, offline = true)))
        assertEquals(emptyList<CardData>(), withoutHeld(emptyList(), held(1, 1)))
        assertEquals(emptyList<CardData>(), withoutHeld(listOf(card(1, 1)), held(1, 1)))
    }
}
