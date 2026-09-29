package com.earmark.core.player

import com.earmark.core.model.Book
import com.earmark.core.text.WordAligner

/**
 * Turns "the engine is at character 37 of the speech text of sentence 12" into "highlight
 * characters 30..35 of sentence 12 as displayed". Alignments are cached for the few sentences
 * around the playhead.
 */
class WordTracker(private val book: Book, private val speechTextFor: (Int) -> String) {
    private val cache = object : LinkedHashMap<Int, WordAligner>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WordAligner>?) = size > 24
    }

    @Synchronized
    fun displayRange(sentence: Int, speechOffset: Int): IntRange? {
        val s = book.sentences.getOrNull(sentence) ?: return null
        val aligner = cache.getOrPut(sentence) { WordAligner.align(s.text, speechTextFor(sentence)) }
        return aligner.displayRangeFor(speechOffset)?.takeIf { it.first >= 0 && it.last < s.text.length }
    }
}
