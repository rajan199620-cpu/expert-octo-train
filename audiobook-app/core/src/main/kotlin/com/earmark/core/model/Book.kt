package com.earmark.core.model

import kotlinx.serialization.Serializable

/** Bumped whenever parsing changes in a way that can shift sentence indices. */
const val PARSER_VERSION = 1

@Serializable
enum class SourceFormat { PDF, EPUB, DOCX, HTML, TEXT, MARKDOWN }

/**
 * The unit of everything in the app: playback position, highlighting, bookmarks and Q&A
 * citations all address a sentence by its global [index].
 */
@Serializable
data class Sentence(
    val index: Int,
    val text: String,
    val chapter: Int,
    val paragraph: Int,
    /** 1-based. A real page for PDFs, a virtual ~275-word page for reflowable formats. */
    val page: Int,
    val isHeading: Boolean = false,
)

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val firstSentence: Int,
    /** Inclusive. */
    val lastSentence: Int,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: SourceFormat,
    val sentences: List<Sentence>,
    val chapters: List<Chapter>,
    val pageCount: Int,
    val pagesAreVirtual: Boolean,
    val warnings: List<String> = emptyList(),
    val parserVersion: Int = PARSER_VERSION,
) {
    val isEmpty: Boolean get() = sentences.isEmpty()
    val lastIndex: Int get() = sentences.lastIndex

    fun clampIndex(index: Int): Int = if (sentences.isEmpty()) 0 else index.coerceIn(0, sentences.lastIndex)

    fun chapterOf(sentenceIndex: Int): Chapter? {
        if (chapters.isEmpty()) return null
        val i = clampIndex(sentenceIndex)
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].firstSentence <= i) lo = mid else hi = mid - 1
        }
        return chapters[lo]
    }

    /** First sentence that starts on [page], or the first sentence after it if the page is blank. */
    fun firstSentenceOfPage(page: Int): Int? {
        if (sentences.isEmpty()) return null
        var lo = 0
        var hi = sentences.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sentences[mid].page < page) lo = mid + 1 else hi = mid
        }
        return if (lo < sentences.size) lo else null
    }

    fun paragraphRange(sentenceIndex: Int): IntRange {
        val i = clampIndex(sentenceIndex)
        val p = sentences[i].paragraph
        var start = i
        while (start > 0 && sentences[start - 1].paragraph == p) start--
        var end = i
        while (end < sentences.lastIndex && sentences[end + 1].paragraph == p) end++
        return start..end
    }

    fun textOf(range: IntRange): String =
        range.filter { it in sentences.indices }.joinToString(" ") { sentences[it].text }

    val totalChars: Long get() = sentences.sumOf { it.text.length.toLong() }
}

@Serializable
data class BookSummary(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: SourceFormat,
    val sentenceCount: Int,
    val chapterCount: Int,
    val position: Int = 0,
    val lastOpenedMillis: Long = 0,
) {
    val progress: Float get() = if (sentenceCount <= 1) 0f else position.toFloat() / (sentenceCount - 1)
}

fun Book.summary(position: Int = 0, lastOpenedMillis: Long = 0) = BookSummary(
    id = id, title = title, author = author, format = format, sentenceCount = sentences.size,
    chapterCount = chapters.size, position = position, lastOpenedMillis = lastOpenedMillis,
)
