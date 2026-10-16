package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sections the deck keeps folded ("why", notes) stay folded on the watch until tapped. */
class CollapsiblesTest {

    private fun render(html: String, ord: Int = 1, answer: Boolean = false, toggled: Set<Int> = emptySet()) =
        CardRenderer.render(html, RenderOptions(activeOrd = ord, showAnswer = answer, toggled = toggled))

    /** The id of the covered span showing [label]. */
    private fun idOf(field: RenderedField, label: String): Int {
        val run = field.blocks.flatMap { it.runs }.firstOrNull { it.text == "[$label]" && it.cloze != null }
        assertNotNull("no [$label] in ${field.plainText()}", run)
        return run!!.cloze!!.id
    }

    private val details = "Abetment is {{c1::instigation::mode}}." +
        "<details><summary>why</summary>Because the law punishes the <b>mind</b> behind the act.</details>" +
        "Then {{c2::conspiracy}}."

    @Test
    fun aDetailsSidenoteIsCoveredByItsLabelOnBothSides() {
        for (answer in listOf(false, true)) {
            val r = render(details, answer = answer)
            val text = r.plainText()
            assertTrue(text, text.contains("[why]"))
            assertFalse(text, text.contains("punishes"))
            // The sidenote doesn't touch the tested cloze: covered, then open.
            assertEquals(!answer, text.contains("[mode]"))
            assertEquals(answer, text.contains("instigation"))
        }
    }

    @Test
    fun aTapOpensTheSidenoteAndAnotherClosesIt() {
        val id = idOf(render(details), "why")
        val open = render(details, toggled = setOf(id))
        assertTrue(open.plainText(), open.plainText().contains("Because the law punishes the mind behind the act."))
        assertFalse(open.plainText().contains("[why]"))
        // The text keeps its style and stays tappable, to fold it again.
        val bold = open.blocks.flatMap { it.runs }.first { it.text == "mind" }
        assertTrue(bold.style and Style.BOLD != 0)
        assertEquals(id, bold.cloze?.id)
        assertEquals(ClozeState.PSEUDO_SHOWN, bold.cloze?.state)
        // On its own line, not run on from the sentence before it.
        assertTrue(open.blocks.any { it.text.startsWith("Because") })
        assertTrue(render(details).blocks.any { it.text == "[why]" })
    }

    @Test
    fun theTemplatesOwnPanelPatternFolds() {
        val html = "{{c1::Sanskritisation}} is a process." +
            "<div class=\"header header-red\" onclick=\"showNextElement(this)\">Why ▸</div>" +
            "\n<div class=\"content\" style=\"display: none;\">Status mobility <i>within</i> caste.</div>" +
            "<p>Next point</p>"
        val r = render(html)
        assertTrue(r.plainText(), r.plainText().contains("[Why]"))
        assertFalse(r.plainText().contains("mobility"))
        assertTrue(r.plainText().contains("Next point"))
        val open = render(html, toggled = setOf(idOf(r, "Why")))
        assertTrue(open.plainText().contains("Status mobility within caste."))
    }

    @Test
    fun otherHiddenElementsStayHidden() {
        val html = "Text {{c1::x}}<span style=\"DISPLAY:NONE\">secret aside</span> end"
        val r = render(html)
        assertFalse(r.plainText(), r.plainText().contains("secret"))
        assertFalse(r.plainText().contains("[more]"))
        assertTrue(r.plainText().contains("end"))
    }

    @Test
    fun openDetailsAndPlainClickablesStayAsTheyAre() {
        val open = render("<details open><summary>why</summary>shown reason</details>{{c1::x}}")
        assertTrue(open.plainText().contains("shown reason"))
        assertFalse(open.plainText().contains("[why]"))
        // A clickable element with nothing hidden after it is just text.
        val click = render("<div onclick=\"f()\">Tap me</div><div>visible</div>{{c1::x}}")
        assertTrue(click.plainText().contains("Tap me"))
        assertTrue(click.plainText().contains("visible"))
    }

    @Test
    fun theTestedClozeInsideASidenoteKeepsItOpen() {
        val html = "<details><summary>why</summary>Because {{c1::mens rea::element}} matters.</details>"
        val q = render(html)
        assertTrue(q.plainText(), q.plainText().contains("[element]"))
        assertFalse(q.plainText().contains("[why]"))
        assertTrue(q.hasGenuine)
        assertTrue(render(html, answer = true).plainText().contains("mens rea"))
    }

    @Test
    fun sidenotesAreNotCards() {
        val parsed = ClozeParser.parse(details + "<details><summary>a</summary>b</details>")
        assertEquals(listOf(1, 2), parsed.clozeNumbers())
        assertEquals(4, parsed.clozeCount) // two clozes and two sidenotes, each its own id
    }

    @Test
    fun nestedAndUnclosedSectionsFold() {
        val nested = "<details><summary>why</summary>outer <details><summary>source</summary>inner</details> tail</details>{{c1::x}}"
        val r = render(nested)
        assertEquals(listOf("[why]", "[…]"), r.blocks.flatMap { it.runs }.filter { it.cloze != null }.map { it.text })
        val outer = render(nested, toggled = setOf(idOf(r, "why")))
        assertTrue(outer.plainText(), outer.plainText().contains("outer"))
        assertTrue(outer.plainText().contains("[source]"))
        assertFalse(outer.plainText().contains("inner"))
        // Never closed: the rest of the field is the sidenote, as a browser would show it.
        val unclosed = render("{{c1::x}} <details><summary>why</summary>the rest")
        assertFalse(unclosed.plainText().contains("the rest"))
        assertTrue(unclosed.plainText().contains("[why]"))
        // No summary: labelled "more".
        assertTrue(render("{{c1::x}}<details>body</details>").plainText().contains("[more]"))
    }

    @Test
    fun aLongLabelIsShortened() {
        val label = "Why this matters for the exam and the cases that follow it"
        val r = render("{{c1::x}}<details><summary>$label</summary>b</details>")
        val shown = r.blocks.flatMap { it.runs }.first { it.cloze != null && it.text != "[x]" }.text
        assertTrue(shown, shown.length <= 42 + 2)
        assertTrue(shown, shown.endsWith("…]"))
    }

    // A note's own sidenote spans: data-sn is the label, data-sn-c the cloze it explains.
    private val sn = "topic <br><ol><li>frame — {{c3::#ABC}} " +
        "<span class=\"sn\" data-sn=\"why\" data-sn-c=\"3\"><strong>Why ABC.</strong> three heads, a frame first.</span></li>" +
        "<ol><li>{{c1::alpha <u>a</u>nswer}} (PYQ 2023) " +
        "<span class=\"sn\" data-sn=\"why\" data-sn-c=\"1\"><strong>Alpha.</strong> restates the alpha answer. <b>Exam:</b> 2023.</span></li>" +
        "<li>{{c2::beta answer}} <span class=\"sn\" data-sn=\"why\" data-sn-c=\"2\">beta reason</span></li></ol>" +
        "<li>general · <span class=\"sn\" data-sn=\"why\"><strong>One idea runs through this note.</strong> engine and steering.</span></li></ol>"

    private fun whyLabels(r: RenderedField) =
        r.blocks.flatMap { it.runs }.filter { it.text == "[why]" && it.cloze != null }.map { it.cloze!!.id }

    @Test
    fun aSidenoteSpanIsFoldedBehindItsLabel() {
        for (ord in 1..3) for (answer in listOf(false, true)) {
            val r = render(sn, ord = ord, answer = answer)
            val text = r.plainText()
            for (body in listOf("three heads", "restates the alpha", "beta reason", "engine and steering")) {
                assertFalse("c$ord ${if (answer) "answer" else "question"}: $body in $text", text.contains(body))
            }
        }
        // Not cards: the deck still has c1–c3 only.
        assertEquals(listOf(1, 2, 3), ClozeParser.parse(sn).clozeNumbers())
    }

    @Test
    fun aSidenoteForTheTestedClozeWaitsForTheAnswer() {
        // c1's question side: its own sidenote would give the answer away, so it isn't there.
        val q = render(sn, ord = 1)
        assertFalse(q.plainText(), q.plainText().contains("alpha answer"))
        assertEquals(3, whyLabels(q).size) // c3's, c2's and the general one
        // Answer side: there, folded, and a tap opens it, formatting kept.
        val a = render(sn, ord = 1, answer = true)
        val ids = whyLabels(a)
        assertEquals(4, ids.size)
        val opened = ids.map { render(sn, ord = 1, answer = true, toggled = setOf(it)).plainText() }
        assertEquals(1, opened.count { it.contains("Alpha. restates the alpha answer. Exam: 2023.") })
        val open = render(sn, ord = 1, answer = true, toggled = ids.toSet())
        assertTrue(open.plainText(), open.plainText().contains("engine and steering"))
        assertTrue(open.blocks.flatMap { it.runs }.any { it.text.contains("Alpha.") && it.style and Style.BOLD != 0 })
        // Sibling cards: c1's sidenote is there from the start (c1 isn't asked there).
        val c2 = render(sn, ord = 2)
        assertEquals(3, whyLabels(c2).size) // all but c2's own
        assertFalse(c2.plainText().contains("[why] beta"))
    }

    @Test
    fun sidenoteMarkupVariants() {
        // No data-sn-c: always available. Only the class: labelled "more". Other classes beside sn.
        val r = render("{{c1::x}}<span class=\"note sn\">aside</span><span data-sn=\"source\" data-sn-c=\"zz\">src</span>")
        assertTrue(r.plainText(), r.plainText().contains("[more]"))
        assertTrue(r.plainText().contains("[source]"))
        assertFalse(r.plainText().contains("aside"))
        assertFalse(r.plainText().contains("src"))
        // A class merely containing the letters isn't one.
        assertTrue(render("{{c1::x}}<span class=\"snippet\">shown</span>").plainText().contains("shown"))
        // The tested cloze inside its own sidenote is still asked.
        val inside = render("<span class=\"sn\" data-sn=\"why\" data-sn-c=\"1\">because {{c1::reason::cue}}</span>")
        assertTrue(inside.plainText(), inside.plainText().contains("[cue]"))
        assertTrue(inside.hasGenuine)
        // Never closed: the rest of the field is the sidenote.
        val open = render("{{c1::x}} <span class=\"sn\" data-sn=\"why\">the rest")
        assertFalse(open.plainText().contains("the rest"))
    }

    @Test
    fun deepNestingDoesNotOverflow() {
        val depth = 20_000
        val html = "<details><summary>w</summary>".repeat(depth) + "x" + "{{c1::y}}"
        val r = render(html)
        assertTrue(r.blocks.isNotEmpty())
        val clozes = "{{c1::".repeat(depth) + "<details><summary>z</summary>z</details>" + "}}".repeat(depth)
        assertTrue(render(clozes).blocks.isNotEmpty())
    }
}
