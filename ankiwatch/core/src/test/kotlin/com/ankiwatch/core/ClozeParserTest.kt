package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeParserTest {

    private fun clozes(html: String): List<Node.Cloze> {
        val out = ArrayList<Node.Cloze>()
        val stack = ArrayList<List<Node>>()
        stack.add(ClozeParser.parse(html).nodes)
        while (stack.isNotEmpty()) {
            for (n in stack.removeAt(stack.size - 1)) if (n is Node.Cloze) {
                out.add(n)
                stack.add(n.content)
            }
        }
        return out.sortedBy { it.id }
    }

    private fun text(nodes: List<Node>): String = nodes.joinToString("") {
        when (it) {
            is Node.Text -> it.text
            is Node.Tag -> if (it.isEnd) "</${it.name}>" else "<${it.name}>"
            is Node.Cloze -> "{c${it.ord}:${text(it.content)}}"
        }
    }

    @Test
    fun answerAndHint() {
        val c = clozes("Whoever {{c2::instigates any person::first mode}} does it").single()
        assertEquals(2, c.ord)
        assertEquals("instigates any person", text(c.content))
        assertEquals("first mode", c.hint)
        assertFalse(c.isAnchor)
    }

    @Test
    fun noHint() {
        val c = clozes("{{c1::answer}}").single()
        assertNull(c.hint)
        assertEquals("answer", text(c.content))
    }

    @Test
    fun hintIsEverythingAfterFirstSeparator_likeTemplateRegex() {
        val c = clozes("{{c1::a::b::c}}").single()
        assertEquals("a", text(c.content))
        assertEquals("b::c", c.hint)
    }

    @Test
    fun anchorHashIsStrippedAndFlagged() {
        val c = clozes("{{c1::#A person abets::which act?}}").single()
        assertTrue(c.isAnchor)
        assertEquals("A person abets", text(c.content))
    }

    @Test
    fun hashInsideTagIsNotAnAnchor_likeTemplate() {
        // The template checks the raw answer HTML, which starts with "<b>", not "#".
        val c = clozes("{{c1::<b>#x</b>}}").single()
        assertFalse(c.isAnchor)
    }

    @Test
    fun encodedHashIsAnAnchor() {
        assertTrue(clozes("{{c1::&#35;x}}").single().isAnchor)
    }

    @Test
    fun htmlInsideAnswer() {
        val c = clozes("{{c3::<b>seven</b> years::punishment}}").single()
        assertEquals("<b>seven</b> years", text(c.content))
        assertEquals("punishment", c.hint)
    }

    @Test
    fun nestedClozes() {
        val all = clozes("{{c1::outer {{c2::inner::ih}} tail::oh}}")
        assertEquals(listOf(1, 2), all.map { it.ord })
        assertEquals("outer {c2:inner} tail", text(all[0].content))
        assertEquals("oh", all[0].hint)
        assertEquals("ih", all[1].hint)
    }

    @Test
    fun separatorBeforeNestedClozeIsLiteral() {
        val outer = clozes("{{c1::a::b {{c2::x}} c}}").first { it.ord == 1 }
        assertNull(outer.hint)
        assertEquals("a::b {c2:x} c", text(outer.content))
    }

    @Test
    fun markersInsideTagsAreIgnored() {
        assertTrue(clozes("<span title=\"{{c1::x}}\">y</span>").isEmpty())
    }

    @Test
    fun closingBracesOutsideClozeAreText() {
        val parsed = ClozeParser.parse("a }} b :: c")
        assertEquals("a }} b :: c", text(parsed.nodes))
    }

    @Test
    fun notAMarker() {
        assertTrue(clozes("{{c::x}} {{cx::y}} {{c1:z}} {c1::w}").isEmpty())
    }

    @Test
    fun hugeOrdinalIsText() {
        assertTrue(clozes("{{c12345678::x}}").isEmpty())
    }

    @Test
    fun unclosedClozeSwallowsRestSoAnswerStaysHidden() {
        val c = clozes("before {{c1::secret answer and more").single()
        assertEquals("secret answer and more", text(c.content))
    }

    @Test
    fun scriptsStylesAndCommentsAreSkipped() {
        val parsed = ClozeParser.parse("a<script>var x = '{{c1::no}}'</script>b<style>p{}</style>c<!-- {{c2::no}} -->d")
        assertEquals("abcd", text(parsed.nodes))
        assertEquals(0, parsed.clozeCount)
    }

    @Test
    fun soundTagsAreDropped() {
        assertEquals("ab", text(ClozeParser.parse("a[sound:x.mp3]b").nodes))
        assertEquals("ab", text(ClozeParser.parse("a[anki:play:q:0]b").nodes))
    }

    @Test
    fun entitiesDecoded() {
        assertEquals("§ 45 – “x” & y <z>", text(ClozeParser.parse("&sect;&nbsp;45 &#150; &ldquo;x&rdquo; &amp; y &lt;z&gt;").nodes).replace(' ', ' '))
    }

    @Test
    fun strayLessThanIsText() {
        assertEquals("a < b <3 <", text(ClozeParser.parse("a < b <3 <").nodes))
    }

    @Test
    fun unterminatedTagIsText() {
        assertEquals("x <b y", text(ClozeParser.parse("x <b y").nodes))
    }

    @Test
    fun quotedGreaterThanInsideAttribute() {
        val nodes = ClozeParser.parse("<span title=\"a>b\">t</span>").nodes
        assertEquals("<span>t</span>", text(nodes))
    }

    @Test
    fun idsFollowDocumentOrderOfOpening() {
        val all = clozes("{{c3::a}} {{c1::b {{c2::c}}}} {{c3::d}}")
        assertEquals(listOf(0, 1, 2, 3), all.map { it.id })
        assertEquals(listOf(3, 1, 2, 3), all.map { it.ord })
    }

    @Test
    fun clozeNumbers() {
        assertEquals(listOf(1, 2, 5), ClozeParser.parse("{{c5::a}}{{c1::b {{c2::c}}}}{{c1::d}}").clozeNumbers())
    }
}
