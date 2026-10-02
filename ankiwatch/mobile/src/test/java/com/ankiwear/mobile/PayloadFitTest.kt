package com.ankiwear.mobile

import com.ankiwatch.core.PayloadBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadFitTest {

    private fun clozeCard(id: Long, contentChars: Int, extraChars: Int = 0) = CardData(
        noteId = id,
        cardOrd = 0,
        question = "",
        answer = "",
        buttonCount = 4,
        cloze = ClozePayload(
            content = "word {{c1::x}} ".repeat(contentChars / 15),
            clozeNumber = 1,
            extras = if (extraChars > 0) listOf("Note" to "n ".repeat(extraChars / 2)) else emptyList(),
            modelName = "Enhanced Cloze"
        )
    )

    private fun size(card: CardData) = PayloadBudget.utf8Length(card.question) + PayloadBudget.utf8Length(card.answer) +
        PayloadBudget.utf8Length(card.cloze?.content.orEmpty()) +
        (card.cloze?.extras ?: emptyList()).sumOf { PayloadBudget.utf8Length(it.second) }

    @Test
    fun smallCardsAllFit() {
        val cards = listOf(clozeCard(1, 2_000), clozeCard(2, 2_000), clozeCard(3, 2_000))
        assertEquals(cards, fitToDataItem(cards))
    }

    @Test
    fun bigCardsAreSentOneAtATime() {
        val cards = listOf(clozeCard(1, 50_000), clozeCard(2, 50_000), clozeCard(3, 50_000))
        val fitted = fitToDataItem(cards)
        assertEquals(listOf(1L), fitted.map { it.noteId })
        assertEquals(cards[0], fitted[0])
    }

    @Test
    fun oversizedCardIsShortenedNotDropped() {
        val huge = clozeCard(1, 150_000, extraChars = 50_000)
        val fitted = fitToDataItem(listOf(huge, clozeCard(2, 100)))
        assertEquals(1, fitted.size)
        assertEquals(1L, fitted[0].noteId)
        assertTrue(size(fitted[0]) <= PayloadBudget.MAX_BYTES)
        assertTrue(fitted[0].cloze!!.content.endsWith(PayloadBudget.TRUNCATION_NOTE))
    }

    @Test
    fun templateCardsShrinkTheirQuestionAndAnswer() {
        val card = CardData(1, 0, "<p>q</p>".repeat(20_000), "<p>a</p>".repeat(20_000), 4)
        val fitted = fitToDataItem(listOf(card)).single()
        assertTrue(size(fitted) <= PayloadBudget.MAX_BYTES)
        assertTrue(fitted.question.isNotEmpty() && fitted.answer.isNotEmpty())
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<CardData>(), fitToDataItem(emptyList()))
    }
}
