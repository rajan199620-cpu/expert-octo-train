package com.earmark.core.text

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSegmenterTest {
    private val seg = SentenceSegmenter()

    data class Case(val input: String, val expected: List<String>) {
        override fun toString() = input.take(60)
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case("Hello world. How are you?", listOf("Hello world.", "How are you?")),
            Case("Mr. Smith went to Washington. He said hi.", listOf("Mr. Smith went to Washington.", "He said hi.")),
            Case("Dr. Rao and Prof. Iyer met St. Paul. Then they left.", listOf("Dr. Rao and Prof. Iyer met St. Paul.", "Then they left.")),
            Case("It costs 3.50 dollars. Cheap.", listOf("It costs 3.50 dollars.", "Cheap.")),
            Case("See e.g. the appendix. Or i.e. the notes.", listOf("See e.g. the appendix.", "Or i.e. the notes.")),
            Case("\"Stop!\" he shouted. Nobody moved.", listOf("\"Stop!\" he shouted.", "Nobody moved.")),
            Case("“Where?” she asked. “There.” He pointed.", listOf("“Where?” she asked.", "“There.”", "He pointed.")),
            Case("He said \"Go.\" Then he left.", listOf("He said \"Go.\"", "Then he left.")),
            Case("J. K. Rowling wrote it. It sold well.", listOf("J. K. Rowling wrote it.", "It sold well.")),
            Case("See Fig. 3 and No. 12 in Vol. 2. Next part.", listOf("See Fig. 3 and No. 12 in Vol. 2.", "Next part.")),
            Case("Punishment under Sec. 302 is severe. Read s. 304 too.", listOf("Punishment under Sec. 302 is severe.", "Read s. 304 too.")),
            Case("State v. Kumar was decided in 2019. It is binding.", listOf("State v. Kumar was decided in 2019.", "It is binding.")),
            Case("Smith et al. found this. Others disagreed.", listOf("Smith et al. found this.", "Others disagreed.")),
            Case("He waited... Then he ran.", listOf("He waited...", "Then he ran.")),
            Case("He waited... and waited.", listOf("He waited... and waited.")),
            Case("Wait?! Really!!! Yes.", listOf("Wait?!", "Really!!!", "Yes.")),
            Case("1. Introduction to the topic. This chapter explains.", listOf("1. Introduction to the topic.", "This chapter explains.")),
            Case("IV. Results were clear. We won.", listOf("IV. Results were clear.", "We won.")),
            Case("He lives in the U.S. and works there.", listOf("He lives in the U.S. and works there.")),
            Case("We met at 5 p.m. Then we ate.", listOf("We met at 5 p.m.", "Then we ate.")),
            Case("Visit example.com. It is free.", listOf("Visit example.com.", "It is free.")),
            Case("(See chapter 2.) Next we turn to law.", listOf("(See chapter 2.)", "Next we turn to law.")),
            Case("राम घर गया। सीता बाज़ार गई। वे खुश थे।", listOf("राम घर गया।", "सीता बाज़ार गई।", "वे खुश थे।")),
            Case("我们走吧。他说好的！真的吗？", listOf("我们走吧。", "他说好的！", "真的吗？")),
            Case("It was the end.12 Then a new page began.", listOf("It was the end.12 Then a new page began.")),
            Case("   Multiple\n\nlines\tand   spaces.   Second one.  ", listOf("Multiple lines and spaces.", "Second one.")),
            Case("No terminal punctuation at all", listOf("No terminal punctuation at all")),
            Case("Ends with a quote.”", listOf("Ends with a quote.”")),
            Case("soft­hyphen and zero​width. Done.", listOf("softhyphen and zerowidth.", "Done.")),
            Case("", emptyList()),
            Case("   ", emptyList()),
        )
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun splits(case: Case) {
        assertEquals(case.expected, seg.split(case.input))
    }

    @Test
    fun `long legal sentences are capped at clause boundaries`() {
        val clause = "whoever, being legally bound to furnish information on any subject to any public servant, intentionally omits to furnish such information"
        val sentence = (1..8).joinToString("; ") { "$clause $it" } + "."
        val parts = SentenceSegmenter(maxChars = 400).split(sentence)
        assertTrue(parts.size > 1, "should be split")
        assertTrue(parts.all { it.length <= 400 }, "every part within limit: ${parts.map { it.length }}")
        assertTrue(parts.dropLast(1).all { it.endsWith(";") }, "prefers semicolons: $parts")
        assertEquals(norm(sentence), norm(parts.joinToString(" ")))
    }

    @Test
    fun `a single enormous word is hard-split rather than looping forever`() {
        val blob = "x".repeat(2000)
        val parts = SentenceSegmenter(maxChars = 100).split(blob)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(blob, parts.joinToString(""))
    }

    @Test
    fun `fuzz - never loses or invents non-space characters and never emits empty or oversized sentences`() {
        val rnd = Random(42)
        val alphabet = "abcdefghijk ABCDEF .....!?;:,\"'()[]“”…।。 123\n\t-—Mr.Dr.e.g.".toCharArray()
        val s = SentenceSegmenter(maxChars = 80)
        repeat(3000) {
            val text = String(CharArray(rnd.nextInt(0, 400)) { alphabet[rnd.nextInt(alphabet.size)] })
            val parts = s.split(text)
            assertTrue(parts.none { it.isBlank() }, "blank part for: $text")
            assertTrue(parts.all { it.length <= 80 }, "oversized part for: $text")
            assertEquals(norm(SentenceSegmenter.normalizeWhitespace(text)), norm(parts.joinToString(" ")), "content changed for: $text")
        }
    }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }
}
