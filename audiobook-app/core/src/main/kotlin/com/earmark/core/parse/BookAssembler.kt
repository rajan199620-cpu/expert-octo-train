package com.earmark.core.parse

import com.earmark.core.model.Book
import com.earmark.core.model.Chapter
import com.earmark.core.model.Sentence
import com.earmark.core.model.SourceFormat
import com.earmark.core.text.SentenceSegmenter

object BookAssembler {
    /** A typical printed page holds ~250-300 words; used for "bookmark this page" in EPUBs. */
    const val WORDS_PER_VIRTUAL_PAGE = 275

    fun assemble(
        id: String,
        fallbackTitle: String,
        format: SourceFormat,
        raw: RawDocument,
        segmenter: SentenceSegmenter = SentenceSegmenter(),
    ): Book {
        val sentences = ArrayList<Sentence>()
        val chapters = ArrayList<Chapter>()
        val warnings = raw.warnings.toMutableList()
        var paragraph = 0
        var words = 0L
        var lastRealPage = 1

        for (section in raw.sections) {
            val first = sentences.size
            val chapterIndex = chapters.size
            var headingTitle: String? = null
            for (block in section.blocks) {
                val parts = segmenter.split(block.text)
                if (parts.isEmpty()) continue
                if (block.isHeading && headingTitle == null) headingTitle = SentenceSegmenter.normalizeWhitespace(block.text)
                if (block.page != null) lastRealPage = block.page
                for (text in parts) {
                    val page = if (raw.hasRealPages) lastRealPage else (words / WORDS_PER_VIRTUAL_PAGE).toInt() + 1
                    sentences += Sentence(
                        index = sentences.size,
                        text = text,
                        chapter = chapterIndex,
                        paragraph = paragraph,
                        page = page,
                        isHeading = block.isHeading,
                    )
                    words += countWords(text)
                }
                paragraph++
            }
            if (sentences.size == first) continue
            val title = section.title?.let { SentenceSegmenter.normalizeWhitespace(it) }?.takeIf { it.isNotEmpty() }
                ?: headingTitle?.take(120)
                ?: "Section ${chapterIndex + 1}"
            chapters += Chapter(chapterIndex, title, first, sentences.size - 1)
        }

        if (sentences.isEmpty()) warnings += "No readable text was found in this document."
        val pageCount = sentences.lastOrNull()?.page ?: 0
        return Book(
            id = id,
            title = raw.title?.let { SentenceSegmenter.normalizeWhitespace(it) }?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
            author = raw.author?.let { SentenceSegmenter.normalizeWhitespace(it) }?.takeIf { it.isNotEmpty() },
            format = format,
            sentences = sentences,
            chapters = chapters,
            pageCount = pageCount,
            pagesAreVirtual = !raw.hasRealPages,
            warnings = warnings.distinct(),
        )
    }

    private fun countWords(s: String): Int {
        var n = 0
        var inWord = false
        for (c in s) {
            if (c.isWhitespace()) inWord = false else if (!inWord) { inWord = true; n++ }
        }
        return n
    }
}
