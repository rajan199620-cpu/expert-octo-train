package com.earmark.core.text

import com.earmark.core.TestBooks
import com.earmark.core.player.WordTracker
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WordAlignerTest {
    private fun spokenWordAt(display: String, spoken: String, spokenWord: String, occurrence: Int = 0): String? {
        var idx = -1
        repeat(occurrence + 1) { idx = spoken.indexOf(spokenWord, idx + 1) }
        val r = WordAligner.align(display, spoken).displayRangeFor(idx) ?: return null
        return display.substring(r.first, r.last + 1)
    }

    @Test
    fun `identical text maps word to word`() {
        val t = "Mira climbed the spiral stairs anyway."
        assertEquals("spiral", spokenWordAt(t, t, "spiral"))
        assertEquals("anyway", spokenWordAt(t, t, "anyway"), "trailing punctuation is not highlighted")
        assertEquals("Mira", spokenWordAt(t, t, "Mira"))
    }

    @Test
    fun `expanded symbols and abbreviations map to the written form`() {
        val display = "Dr. Rao paid ₹1,00,000 under § 302 on p. 12."
        val spoken = SpeechNormalizer().normalize(display)
        assertEquals("Doctor Rao paid 100000 rupees under section 302 on page 12.", spoken)
        assertEquals("Dr", spokenWordAt(display, spoken, "Doctor"))
        assertEquals("₹1,00,000", spokenWordAt(display, spoken, "100000"))
        assertEquals("₹1,00,000", spokenWordAt(display, spoken, "rupees"))
        assertEquals("§", spokenWordAt(display, spoken, "section"))
        assertEquals("302", spokenWordAt(display, spoken, "302"))
        assertEquals("12", spokenWordAt(display, spoken, "12"))
    }

    @Test
    fun `dropped citations and offsets on spaces`() {
        val display = "Stars shine [12] brightly tonight."
        val spoken = SpeechNormalizer().normalize(display)
        assertEquals("brightly", spokenWordAt(display, spoken, "brightly"))
        val a = WordAligner.align(display, spoken)
        val onSpace = spoken.indexOf(' ')
        assertEquals("shine", a.displayRangeFor(onSpace)!!.let { display.substring(it.first, it.last + 1) }, "an offset on a space highlights the next word")
    }

    @Test
    fun `empty inputs are safe`() {
        assertEquals(null, WordAligner.align("", "").displayRangeFor(0))
        assertEquals(null, WordAligner.align("Hello", "").displayRangeFor(0))
        assertNotNull(WordAligner.align("Hello", "Hello").displayRangeFor(999))
    }

    @Test
    fun `fuzz - every spoken offset maps inside the display text`() {
        val rnd = Random(31)
        val words = listOf("Dr.", "Rao", "₹500", "§", "302", "[12]", "e.g.", "the", "CHAPTER", "IV", "u/s", "420", "—", "Mira", "“Run!”", "10–20")
        val n = SpeechNormalizer()
        repeat(1500) {
            val display = (0 until rnd.nextInt(1, 20)).joinToString(" ") { words[rnd.nextInt(words.size)] }
            val spoken = n.normalize(display)
            val a = WordAligner.align(display, spoken)
            for (off in spoken.indices) {
                val r = a.displayRangeFor(off) ?: continue
                assertTrue(r.first >= 0 && r.last < display.length && r.first <= r.last, "bad range $r for [$display] / [$spoken]")
            }
        }
    }

    @Test
    fun `tracker maps engine offsets for a real book and caches`() {
        val book = TestBooks.novel()
        val n = SpeechNormalizer()
        var calls = 0
        val tracker = WordTracker(book) { calls++; n.normalize(book.sentences[it].text) }
        val s = book.sentences[4].text // "His name was Tobin."
        val r = tracker.displayRange(4, n.normalize(s).indexOf("Tobin"))!!
        assertEquals("Tobin", s.substring(r.first, r.last + 1))
        tracker.displayRange(4, 0)
        assertEquals(1, calls)
        assertEquals(null, tracker.displayRange(9999, 0))
    }
}
