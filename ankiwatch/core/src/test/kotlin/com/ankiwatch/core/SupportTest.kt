package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePlannerTest {

    private fun cloze(plan: NotePlanner.Plan) = plan as NotePlanner.Plan.Cloze

    @Test
    fun enhancedClozeLawNoteType() {
        val plan = cloze(
            NotePlanner.plan(
                NotePlanner.MODEL_TYPE_CLOZE,
                listOf("Content", "Note", "Extra"),
                listOf("{{c1::a}} {{c2::b}}", "plain words", "<br>"),
                cardOrd = 1
            )
        )
        assertEquals("Content", plan.contentField)
        assertEquals(2, plan.clozeNumber)
        assertEquals(listOf("Note" to "plain words"), plan.extras) // blank Extra dropped
    }

    @Test
    fun enhancedCloze21v2WithMnemonics() {
        val plan = cloze(
            NotePlanner.plan(
                NotePlanner.MODEL_TYPE_CLOZE,
                listOf("Content", "Note", "Mnemonics", "Extra"),
                listOf("{{c1::a}}", "", "hook", "&nbsp;"),
                cardOrd = 0
            )
        )
        assertEquals(listOf("Mnemonics" to "hook"), plan.extras)
    }

    @Test
    fun stockClozeUsesTextAndBackExtra() {
        val plan = cloze(NotePlanner.plan(1, listOf("Text", "Back Extra"), listOf("{{c1::x}}", "more"), 0))
        assertEquals("Text", plan.contentField)
        assertEquals(listOf("Back Extra" to "more"), plan.extras)
    }

    @Test
    fun basicNoteIsText() {
        assertTrue(NotePlanner.plan(0, listOf("Front", "Back"), listOf("q", "a"), 0) is NotePlanner.Plan.Text)
        // Even if a basic field happens to contain cloze syntax.
        assertTrue(NotePlanner.plan(0, listOf("Front", "Back"), listOf("{{c1::q}}", "a"), 0) is NotePlanner.Plan.Text)
    }

    @Test
    fun unknownTypeFallsBackToContentSniffing() {
        assertTrue(NotePlanner.plan(null, listOf("Front"), listOf("{{c1::q}}"), 0) is NotePlanner.Plan.Cloze)
        assertTrue(NotePlanner.plan(null, listOf("Front"), listOf("q"), 0) is NotePlanner.Plan.Text)
    }

    @Test
    fun oddFieldNamesPickTheFieldWithTheTestedCloze() {
        val plan = cloze(NotePlanner.plan(1, listOf("Header", "Body"), listOf("{{c1::h}}", "{{c2::b}}"), 1))
        assertEquals("Body", plan.contentField)
    }

    @Test
    fun clozeTypeWithoutAnyClozeIsText() {
        assertTrue(NotePlanner.plan(1, listOf("Content"), listOf("no clozes"), 0) is NotePlanner.Plan.Text)
    }

    @Test
    fun mismatchedFieldCountsAreTolerated() {
        assertTrue(NotePlanner.plan(1, listOf("Content", "Note"), listOf("{{c1::x}}"), 0) is NotePlanner.Plan.Cloze)
        assertTrue(NotePlanner.plan(1, emptyList(), emptyList(), 0) is NotePlanner.Plan.Text)
        assertTrue(NotePlanner.plan(1, listOf("Content"), listOf("{{c1::x}}"), -1) is NotePlanner.Plan.Text)
    }

    @Test
    fun splitFields() {
        assertEquals(listOf("a", "", "b"), NotePlanner.splitFields("a\u001f\u001fb"))
        assertEquals(emptyList<String>(), NotePlanner.splitFields(null))
    }
}

class BoundedOrderedSetTest {

    @Test
    fun evictsOldestFirst() {
        val set = BoundedOrderedSet(3)
        listOf("a", "b", "c", "d").forEach(set::add)
        assertEquals(listOf("b", "c", "d"), set.toList())
        assertFalse("a" in set)
    }

    @Test
    fun reAddMakesNewest() {
        val set = BoundedOrderedSet(3)
        listOf("a", "b", "c").forEach(set::add)
        set.add("a")
        set.add("d")
        assertEquals(listOf("c", "a", "d"), set.toList())
    }

    @Test
    fun roundTripKeepsOrder() {
        val set = BoundedOrderedSet(200)
        val ids = (1..250).map { "uuid-$it" }
        ids.forEach(set::add)
        val restored = BoundedOrderedSet.deserialize(200, set.serialize())
        assertEquals(ids.takeLast(200), restored.toList())
        // The most recent answer is always remembered — the one a redelivery would repeat.
        assertTrue("uuid-250" in restored)
        assertFalse("uuid-50" in restored)
    }

    @Test
    fun ignoresUnstorableValues() {
        val set = BoundedOrderedSet(5)
        set.add("")
        set.add("a\nb")
        assertEquals(0, set.size)
        assertEquals(0, BoundedOrderedSet.deserialize(5, null).size)
        assertEquals(listOf("x"), BoundedOrderedSet.deserialize(5, "\n\nx\n").toList())
    }
}

class EntitiesTest {

    @Test
    fun namedNumericAndWindows1252() {
        assertEquals("§ – — … ‘’ “” ⇒ ≤ ✓", Entities.decode("&sect; &ndash; &mdash; &hellip; &lsquo;&rsquo; &ldquo;&rdquo; &rArr; &le; &check;"))
        assertEquals("A A –", Entities.decode("&#65; &#x41; &#150;"))
    }

    @Test
    fun malformedIsLeftAlone() {
        assertEquals("&nosuch; & &; &#; &#xZZ; &amp", Entities.decode("&nosuch; & &; &#; &#xZZ; &amp"))
    }

    @Test
    fun invalidCodePointsBecomeReplacementChar() {
        assertEquals("���", Entities.decode("&#0;&#xD800;&#x110000;"))
    }
}

class PayloadBudgetTest {

    private fun card(contentBytes: Int) = PayloadBudget.CardText("", "", "a ".repeat(contentBytes / 2), emptyList())

    @Test
    fun countsCardsThatFit() {
        val cards = listOf(card(40_000), card(40_000), card(40_000))
        assertEquals(2, PayloadBudget.cardsThatFit(cards))
        assertEquals(1, PayloadBudget.cardsThatFit(listOf(card(200_000), card(10))))
        assertEquals(0, PayloadBudget.cardsThatFit(emptyList()))
    }

    @Test
    fun shrinkPrefersDroppingExtras() {
        val big = PayloadBudget.CardText("", "", "x ".repeat(20_000), listOf("Note" to "n ".repeat(60_000)))
        val shrunk = PayloadBudget.shrink(big)
        assertTrue(shrunk.byteSize() <= PayloadBudget.MAX_BYTES)
        assertEquals(big.content, shrunk.content)
    }

    @Test
    fun utf8Length() {
        assertEquals(1, PayloadBudget.utf8Length("a"))
        assertEquals(2, PayloadBudget.utf8Length("§"))
        assertEquals(3, PayloadBudget.utf8Length("दं".substring(0, 1)))
        assertEquals(4, PayloadBudget.utf8Length("😀"))
    }

    @Test
    fun truncateNeverSplitsSurrogatesOrTags() {
        val html = "😀 <b>bold</b> " + "😀".repeat(50)
        for (limit in 0..150) {
            val cut = SafeTruncate.truncate(html, limit)
            assertTrue(PayloadBudget.utf8Length(cut) <= limit || cut.isEmpty())
            val body = cut.removeSuffix(PayloadBudget.TRUNCATION_NOTE)
            assertFalse(body.isNotEmpty() && Character.isHighSurrogate(body.last()))
            assertEquals(body.count { it == '<' }, body.count { it == '>' })
        }
    }
}
