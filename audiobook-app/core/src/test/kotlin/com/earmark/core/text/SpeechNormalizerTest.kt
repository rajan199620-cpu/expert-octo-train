package com.earmark.core.text

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechNormalizerTest {
    private val n = SpeechNormalizer()

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "Punishment under § 302 is death.|Punishment under section 302 is death.",
            "See §§ 10-12.|See sections 10-12.",
            "It rose 15% last year.|It rose 15 percent last year.",
            "The fine is ₹1,00,000 now.|The fine is 100000 rupees now.",
            "It cost Rs. 500 only.|It cost 500 rupees only.",
            "It cost $3.50 today.|It cost 3 dollars and 50 cents today.",
            "Just $1 left.|Just 1 dollar left.",
            "Raised $5 million.|Raised 5 million dollars.",
            "Paid £0.99 for it.|Paid 99 pence for it.",
            "Over 1,000,000 people.|Over 1000000 people.",
            "Read sections 302,304 and 307.|Read sections 302,304 and 307.",
            "Water boils at 100°C.|Water boils at 100 degrees Celsius.",
            "This was shown [12] before [3, 4].|This was shown before.",
            "Stars shine[citation needed] brightly.|Stars shine brightly.",
            "It ended there.12 Then came more.|It ended there. Then came more.",
            "Energy is mc² indeed.|Energy is mc indeed.",
            "Fruit, e.g. apples, is good.|Fruit, for example, apples, is good.",
            "One thing, i.e., this.|One thing, that is, this.",
            "Dr. Rao met Mr. Das.|Doctor Rao met Mister Das.",
            "Apples, pears, etc. are fruit.|Apples, pears, et cetera are fruit.",
            "State v. Kumar is famous.|State versus Kumar is famous.",
            "Chapter IV begins here.|Chapter 4 begins here.",
            "World War II ended.|World War 2 ended.",
            "I think I will go.|I think I will go.",
            "See Fig. 3 on p. 12.|See figure 3 on page 12.",
            "Charged u/s 420 r/w 120B.|Charged under section 420 read with 120B.",
            "The Hon'ble Court held so.|The Honourable Court held so.",
            "Pages 10–20 matter.|Pages 10 to 20 matter.",
            "He paused — then spoke.|He paused, then spoke.",
            "He paused—then spoke.|He paused, then spoke.",
            "Visit https://www.example.com/path?q=1 today.|Visit example.com today.",
            "See https://example.org/a.|See example.org.",
            "Salt & pepper.|Salt and pepper.",
            "CHAPTER ONE: THE BEGINNING OF THE END|Chapter one: the beginning of the end",
            "The GOVERNMENT said no.|The Government said no.",
            "The ﬁnal ﬂow.|The final flow.",
            "A 3×4 grid.|A 3 times 4 grid.",
        ],
    )
    fun normalizes(input: String, expected: String) {
        assertEquals(expected, n.normalize(input))
    }

    @Test
    fun `lexicon applies before other rules and is case-insensitive whole-word by default`() {
        val lex = Lexicon(listOf(LexiconEntry("BNSS", "B N S S"), LexiconEntry("Nietzsche", "Neecha")))
        val out = SpeechNormalizer(lex).normalize("The BNSS replaced the CrPC; nietzsche would approve. subBNSS stays.")
        assertEquals("The B N S S replaced the CrPC; Neecha would approve. subBNSS stays.", out)
    }

    @Test
    fun `devanagari text passes through untouched`() {
        val s = "यह एक वाक्य है। दूसरा वाक्य।"
        assertEquals(s, n.normalize(s))
    }

    @Test
    fun `fuzz - never throws and never returns leading punctuation or double spaces`() {
        val rnd = Random(7)
        val alphabet = "abc XYZ 0123456789 ,.;:!?$₹£€%§°×–—-[]()&/ http://a.b e.g. Mr. Dr. ² IVX".toCharArray()
        repeat(3000) {
            val text = String(CharArray(rnd.nextInt(0, 200)) { alphabet[rnd.nextInt(alphabet.size)] })
            val out = n.normalize(text)
            assertFalse(out.contains("  "), "double space for [$text] -> [$out]")
            assertTrue(out.isEmpty() || out.first() !in ",;", "leading punctuation for [$text] -> [$out]")
        }
    }
}
