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
