package com.earmark.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextUtilsTest {
    @Test
    fun `roman numerals round-trip and reject non-canonical forms`() {
        for (i in 1..3999) assertEquals(i, RomanNumerals.toInt(RomanNumerals.fromInt(i)))
        assertEquals(4, RomanNumerals.toInt("iv"))
        assertNull(RomanNumerals.toInt("IIII"))
        assertNull(RomanNumerals.toInt("VX"))
        assertNull(RomanNumerals.toInt("ABC"))
        assertNull(RomanNumerals.toInt(""))
    }

    @Test
    fun `spoken numbers`() {
        val cases = mapOf(
            "3" to 3.0, "1.5" to 1.5, "1.5x" to 1.5, "two" to 2.0, "twenty five" to 25.0, "twenty-five" to 25.0,
            "a" to 1.0, "an" to 1.0, "one and a half" to 1.5, "one point five" to 1.5, "one point two five" to 1.25,
            "half" to 0.5, "a hundred" to 100.0, "one hundred and twenty" to 120.0, "two thousand" to 2000.0,
            "third" to 3.0, "twenty first" to 21.0, "a couple" to 2.0, "three quarters" to 0.75, "2 times" to 2.0,
        )
        for ((text, expected) in cases) assertEquals(expected, NumberWords.parse(text), text)
        assertNull(NumberWords.parse("banana"))
        assertNull(NumberWords.parse(""))
        assertNull(NumberWords.parse("point five"))
    }

    @Test
    fun `number pattern matches what the parser understands`() {
        val re = Regex("^${NumberWords.PATTERN}$")
        listOf("3", "1.5", "twenty five", "one and a half", "one point five", "a", "couple").forEach {
            assert(re.matches(it)) { "pattern should match '$it'" }
        }
        listOf("sentence", "green", "chapter").forEach { assert(!re.matches(it)) { "pattern should not match '$it'" } }
    }

    @Test
    fun `lexicon prefers longest match and never re-replaces its own output`() {
        val lex = Lexicon(listOf(LexiconEntry("New York", "Noo York"), LexiconEntry("New York City", "The Big Apple"), LexiconEntry("York", "Yawk")))
        assertEquals("The Big Apple and Noo York and Yawk", lex.apply("New York City and New York and York"))
    }

    @Test
    fun `lexicon escapes regex metacharacters and respects case sensitivity`() {
        val lex = Lexicon(listOf(LexiconEntry("C++", "C plus plus", wholeWord = false), LexiconEntry("US", "U S", caseSensitive = true)))
        assertEquals("I like C plus plus. U S and us.", lex.apply("I like C++. US and us."))
    }
}
