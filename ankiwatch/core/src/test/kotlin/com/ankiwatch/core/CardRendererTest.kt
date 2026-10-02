package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRendererTest {

    private fun render(html: String, ord: Int = 0, answer: Boolean = false, toggled: Set<Int> = emptySet()) =
        CardRenderer.render(html, RenderOptions(activeOrd = ord, showAnswer = answer, toggled = toggled))

    private fun RenderedField.text() = plainText()

    // ── Basic cloze behaviour ────────────────────────────────────────────────────────────

    @Test
    fun genuineClozeShowsHintOnFrontAndAnswerOnBack() {
        val html = "Punishment: {{c1::seven years::term}} and {{c2::fine::money}}."
        assertEquals("Punishment: [term] and [money].", render(html, ord = 1).text())
        assertEquals("Punishment: seven years and [money].", render(html, ord = 1, answer = true).text())
    }

    @Test
    fun missingHintShowsEllipsis() {
        assertEquals("a […] b", render("a {{c1::x}} b", ord = 1).text())
        assertEquals("a […] b", render("a {{c1::x::   }} b", ord = 1).text())
    }

    @Test
    fun pseudoClozeTapToReveal() {
        val html = "{{c1::one::h1}} {{c2::two::h2}}"
        assertEquals("[h1] [h2]", render(html, ord = 1).text())
        // Ids are document order: c1 → 0, c2 → 1.
        assertEquals("[h1] two", render(html, ord = 1, toggled = setOf(1)).text())
    }

    @Test
    fun genuineCanBePeekedOnFrontAndHiddenOnBack() {
        val html = "{{c1::one}} {{c1::uno}}"
        assertEquals("one […]", render(html, ord = 1, toggled = setOf(0)).text())
        assertEquals("[…] uno", render(html, ord = 1, answer = true, toggled = setOf(0)).text())
    }

    @Test
    fun anchorIsAlwaysVisibleOnSiblingCardsButTestedOnItsOwn() {
        val html = "{{c1::#Anchor statement::which act?}} then {{c2::detail::d}}"
        assertEquals("Anchor statement then [d]", render(html, ord = 2).text())
        assertEquals("[which act?] then [d]", render(html, ord = 1).text())
        assertEquals("Anchor statement then [d]", render(html, ord = 1, answer = true).text())
        val anchorRun = render(html, ord = 2).blocks[0].runs.first { it.cloze != null }
        assertEquals(ClozeState.CONTEXT, anchorRun.cloze!!.state)
        assertFalse(anchorRun.cloze!!.toggleable)
    }

    @Test
    fun pseudoWrappingTheTestedClozeStaysOpen() {
        val html = "{{c1::outer {{c2::inner::ih}} tail::oh}}"
        assertEquals("outer [ih] tail", render(html, ord = 2).text())
        assertEquals("outer inner tail", render(html, ord = 2, answer = true).text())
        assertEquals("[oh]", render(html, ord = 1).text())
    }

    @Test
    fun coveredClozeDropsItsTagsLikeTheTemplate() {
        // The template replaces the whole span, so the </li><li> inside a covered cloze vanishes.
        val html = "<ul><li>a {{c1::b</li><li>c}} d</li></ul>"
        assertEquals("• a […] d", render(html, ord = 1).text())
        val shown = render(html, ord = 1, answer = true).text()
        assertTrue(shown, shown.contains("b"))
        assertTrue(shown, shown.contains("c"))
    }

    @Test
    fun genuineBlocksAreMarked() {
        val r = render("<p>{{c1::a}}</p><p>plain</p><p>x {{c2::b}}</p>", ord = 2)
        assertEquals(listOf(false, false, true), r.blocks.map { it.containsGenuine })
        assertEquals(2, r.firstGenuineBlock())
    }

    // ── The real layouts ─────────────────────────────────────────────────────────────────

    @Test
    fun lawSectionFront() {
        val r = render(Fixtures.LAW_SECTION, ord = 3)
        val text = r.text()
        assertTrue(text, text.startsWith("Act §999 — Example offence"))
        assertTrue(text, text.contains("A person commits the example offence")) // # anchor shown
        assertTrue(text, text.contains("1. First limb: [first mode]"))
        assertTrue(text, text.contains("2. Second limb: [second mode]; [condition]"))
        assertTrue(text, text.contains("3. Third limb: [third mode]"))
        assertTrue(text, text.contains("[punishment]"))
        for ((ord, secrets) in Fixtures.LAW_SECRETS) for (s in secrets) {
            assertFalse("c$ord secret '$s' leaked:\n$text", text.contains(s))
        }
    }

    @Test
    fun lawSectionBackRevealsOnlyTheTestedCloze() {
        val text = render(Fixtures.LAW_SECTION, ord = 3, answer = true).text()
        assertTrue(text.contains("by agreeing with others and acting on it"))
        assertTrue(text.contains("an act in pursuance must follow"))
        assertFalse(text.contains("persuading"))
        assertFalse(text.contains("seven years"))
    }

    @Test
    fun lawSectionHeadingsAndAccents() {
        val r = render(Fixtures.LAW_SECTION, ord = 5)
        val heading = r.blocks.first()
        assertEquals(BlockKind.HEADING, heading.kind)
        assertEquals(Accent.BLUE, heading.accent)
        val punishment = r.blocks.first { it.text == "Punishment" }
        assertEquals(BlockKind.SUBHEADING, punishment.kind)
        assertEquals(Accent.RED, punishment.accent)
        val proviso = r.blocks.first { it.text == "Proviso" }
        assertEquals(Accent.YELLOW, proviso.accent)
        assertTrue(r.blocks.first { it.text.contains("A person commits") }.boxed)
    }

    @Test
    fun lawFocusShowsHeadingColumnTitleAndClozeBlock() {
        val r = render(Fixtures.LAW_SECTION, ord = 5)
        val focus = r.focusIndices()!!.map { r.blocks[it].text }
        assertEquals(listOf("Act §999 — Example offence", "Punishment", "[punishment]"), focus)
    }

    @Test
    fun anthroFocusShowsParentListItem() {
        val r = render(Fixtures.ANTHRO_SKELETON, ord = 4)
        val focus = r.focusIndices()!!.map { b -> r.blocks[b].let { (it.label?.plus(" ") ?: "") + it.text } }
        assertEquals(listOf("Theories of the origin of the institution", "2. Occupational theory", "2. Critique: […]"), focus)
    }

    @Test
    fun anthroNestedListDepthsAndLabels() {
        val r = render(Fixtures.ANTHRO_SKELETON, ord = 1)
        val items = r.blocks.filter { it.label != null }.map { Triple(it.depth, it.label, it.text) }
        assertEquals(
            listOf(
                Triple(0, "1.", "Racial theory"),
                Triple(1, "1.", "[…] — stated in […]"),
                Triple(1, "2.", "Evidence: […]"),
                Triple(0, "2.", "Occupational theory"),
                Triple(1, "1.", "[…] argued […]"),
                Triple(1, "2.", "Critique: […]")
            ),
            items
        )
    }

    @Test
    fun focusIsNullWithoutTestedCloze() {
        assertNull(render("<p>a</p><p>{{c2::b}}</p>", ord = 1).focusIndices())
        assertNull(render("plain text").focusIndices())
    }

    // ── HTML handling ────────────────────────────────────────────────────────────────────

    @Test
    fun inlineStyles() {
        val r = render("<b>B</b><i>I</i><u>U</u><s>S</s>x<sup>2</sup><code>c</code><span style=\"font-weight: 700\">W</span>")
        val styles = r.blocks.single().runs.associate { it.text to it.style }
        assertEquals(Style.BOLD, styles["B"])
        assertEquals(Style.ITALIC, styles["I"])
        assertEquals(Style.UNDERLINE, styles["U"])
        assertEquals(Style.STRIKE, styles["S"])
        assertEquals(Style.SUPERSCRIPT, styles["2"])
        assertEquals(Style.CODE, styles["c"])
        assertEquals(Style.BOLD, styles["W"])
    }

    @Test
    fun hiddenElementsAreSkipped() {
        val html = "a<span style=\"display: none\">SECRET</span>b<div hidden>SECRET</div>c" +
            "<span style='visibility:hidden'>SECRET</span>d"
        // The hidden <div> is a block, so "c" starts a new line; the hidden <span> is inline.
        assertEquals("ab\ncd", render(html).text())
    }

    @Test
    fun enhancedClozeTemplateQuestionDoesNotLeak() {
        // What AnkiDroid's rendered question looks like for the Enhanced Cloze template.
        val q = """<style>.card{}</style><div id="card-body"><span id="enhanced-clozes"></span>
            <div id="note" class="content" style="display:none">NOTE-SECRET</div></div>
            <span id="enhanced-cloze-content" style="display:none">{{c1::RAW-SECRET::h}}</span>
            <span style="display:none;" id="edit-clozes">CLOZE-SECRET</span>
            <script>var x = 1</script>"""
        val text = render(q).text()
        assertFalse(text, text.contains("SECRET"))
    }

    @Test
    fun brAndParagraphsMakeBlocks() {
        assertEquals("line one\nline two\npara", render("line one<br>line two<p>para</p>").text())
    }

    @Test
    fun whitespaceCollapses() {
        assertEquals("a b c", render("  a \n\t b   <b> c </b>  ").text())
    }

    @Test
    fun orderedListTypesAndStart() {
        val r = render("<ol type=\"a\" start=\"3\"><li>x</li><li>y</li></ol><ol type=\"I\"><li>z</li><li value=\"9\">w</li></ol>")
        assertEquals(listOf("c.", "d.", "I.", "IX."), r.blocks.map { it.label })
    }

    @Test
    fun unclosedListItemsCloseEachOther() {
        val r = render("<ul><li>a<li>b<li>c</ul>")
        assertEquals(listOf("a", "b", "c"), r.blocks.map { it.text })
        assertTrue(r.blocks.all { it.context.isEmpty() })
    }

    @Test
    fun continuationLinesInsideListItem() {
        val r = render("<ol><li>head<br>more {{c1::x}}</li></ol>", ord = 1)
        assertEquals("1.", r.blocks[0].label)
        assertTrue(r.blocks[1].continuation)
        assertEquals(listOf(0), r.blocks[1].context)
        assertEquals(listOf(0, 1), r.focusIndices())
    }

    @Test
    fun tablesBecomeRows() {
        assertEquals("a │ b\nc │ d", render("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>").text())
    }

    @Test
    fun imagesBecomePlaceholders() {
        assertEquals("see [image] and [image: chart of sections]", render("see <img src=\"x.png\"> and <img alt=\"chart of sections\" src=\"data:...\">").text())
    }

    @Test
    fun answerDividerIsFound() {
        val r = render("Question<hr id=answer>Answer")
        assertEquals(2, r.answerStartBlock())
        assertEquals("Answer", r.blocks[2].text)
    }

    @Test
    fun preKeepsLines() {
        assertEquals("  a\n b", render("<pre>  a\n b</pre>").text())
    }

    @Test
    fun emptyAndWhitespaceOnly() {
        assertTrue(render("").blocks.isEmpty())
        assertTrue(render("   <br> &nbsp; <div> </div>").blocks.isEmpty())
    }

    @Test
    fun contextIndicesAlwaysPointBackwards() {
        val r = render(Fixtures.LAW_SECTION + Fixtures.ANTHRO_SKELETON, ord = 2)
        for (b in r.blocks) for (c in b.context) assertTrue("block ${b.index} context $c", c < b.index)
        assertEquals(r.blocks.indices.toList(), r.blocks.map { it.index })
    }

    @Test
    fun plainClozesForExtras() {
        val r = CardRenderer.render("Memory: {{c1::I-C-A}}", RenderOptions(plainClozes = true))
        assertEquals("Memory: I-C-A", r.plainText())
        assertNotNull(r.blocks.single())
    }
}
