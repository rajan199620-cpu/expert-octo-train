package com.earmark.core.annotations

import com.earmark.core.model.Book
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AnnotationKind { BOOKMARK, HIGHLIGHT, NOTE }

@Serializable
enum class BookmarkScope { SENTENCE, PAGE, CHAPTER }

@Serializable
enum class HighlightColor { YELLOW, GREEN, BLUE, PINK }

@Serializable
enum class AnnotationSource { VOICE, TOUCH, HEADSET }

/**
 * Where an annotation points. Sentence indices shift if a newer parser splits text
 * differently, so the quote and its prefix are kept to re-find the spot (the approach web
 * annotation tools such as Hypothesis use).
 */
@Serializable
data class TextAnchor(
    val sentenceIndex: Int,
    val chapter: Int,
    val page: Int,
    val quote: String,
    val prefix: String = "",
)

@Serializable
data class Annotation(
    val id: String,
    val bookId: String,
    val kind: AnnotationKind,
    val start: TextAnchor,
    val end: TextAnchor = start,
    val scope: BookmarkScope = BookmarkScope.SENTENCE,
    val label: String? = null,
    val note: String? = null,
    val color: HighlightColor = HighlightColor.YELLOW,
    val createdAtMillis: Long,
    val source: AnnotationSource = AnnotationSource.TOUCH,
    /** True when re-anchoring could not find the text in a re-imported book. */
    val orphaned: Boolean = false,
) {
    val range: IntRange get() = start.sentenceIndex..end.sentenceIndex
}

fun Book.anchorAt(index: Int): TextAnchor {
    val i = clampIndex(index)
    val s = sentences[i]
    val prefix = if (i > 0) sentences[i - 1].text.takeLast(PREFIX_CHARS) else ""
    return TextAnchor(i, s.chapter, s.page, s.text.take(QUOTE_CHARS), prefix)
}

internal const val QUOTE_CHARS = 120
internal const val PREFIX_CHARS = 40

/**
 * In-memory annotation list with the behaviours voice control needs: idempotent bookmarks
 * (saying "bookmark this" twice must not create two), merging overlapping highlights, and a
 * real undo stack ("undo that" after a misheard command).
 */
class AnnotationStore(
    initial: List<Annotation> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val items = initial.toMutableList()
    private val undo = ArrayDeque<Change>()

    private sealed interface Change {
        data class Added(val annotation: Annotation) : Change
        data class Merged(val added: Annotation, val removed: List<Annotation>) : Change
        data class Removed(val annotation: Annotation) : Change
        data class Updated(val before: Annotation, val after: Annotation) : Change
    }

    data class Result(val annotation: Annotation, val created: Boolean)

    var onChange: (() -> Unit)? = null

    @Synchronized
    fun all(): List<Annotation> = items.toList()

    @Synchronized
    fun forBook(bookId: String): List<Annotation> =
        items.filter { it.bookId == bookId }.sortedWith(compareBy({ it.start.sentenceIndex }, { it.createdAtMillis }))

    @Synchronized
    fun covering(bookId: String, sentenceIndex: Int): List<Annotation> =
        items.filter { it.bookId == bookId && !it.orphaned && sentenceIndex in it.range }

    @Synchronized
    fun bookmark(
        book: Book,
        sentenceIndex: Int,
        scope: BookmarkScope = BookmarkScope.SENTENCE,
        label: String? = null,
        source: AnnotationSource = AnnotationSource.TOUCH,
    ): Result {
        val target = when (scope) {
            BookmarkScope.SENTENCE -> book.clampIndex(sentenceIndex)
            BookmarkScope.PAGE -> book.firstSentenceOfPage(book.sentences[book.clampIndex(sentenceIndex)].page) ?: sentenceIndex
            BookmarkScope.CHAPTER -> book.chapterOf(sentenceIndex)?.firstSentence ?: sentenceIndex
        }
        val existing = items.firstOrNull {
            it.bookId == book.id && it.kind == AnnotationKind.BOOKMARK && it.scope == scope && it.start.sentenceIndex == target
        }
        if (existing != null) {
            if (label != null && label != existing.label) {
                val updated = existing.copy(label = label)
                replace(existing, updated)
                undo.addLast(Change.Updated(existing, updated))
                changed()
                return Result(updated, created = false)
            }
            return Result(existing, created = false)
        }
        val a = Annotation(
            id = newId(), bookId = book.id, kind = AnnotationKind.BOOKMARK, start = book.anchorAt(target),
            scope = scope, label = label, createdAtMillis = clock(), source = source,
        )
        items += a
        undo.addLast(Change.Added(a))
        changed()
        return Result(a, created = true)
    }

    @Synchronized
    fun highlight(
        book: Book,
        from: Int,
        to: Int = from,
        color: HighlightColor = HighlightColor.YELLOW,
        source: AnnotationSource = AnnotationSource.TOUCH,
    ): Annotation {
        var lo = book.clampIndex(minOf(from, to))
        var hi = book.clampIndex(maxOf(from, to))
        val overlapping = items.filter {
            it.bookId == book.id && it.kind == AnnotationKind.HIGHLIGHT && it.color == color && !it.orphaned &&
                it.start.sentenceIndex <= hi + 1 && it.end.sentenceIndex >= lo - 1
        }
        var note: String? = null
        for (o in overlapping) {
            lo = minOf(lo, o.start.sentenceIndex)
            hi = maxOf(hi, o.end.sentenceIndex)
            note = listOfNotNull(note, o.note).joinToString("\n").ifEmpty { null }
        }
        val a = Annotation(
            id = newId(), bookId = book.id, kind = AnnotationKind.HIGHLIGHT, start = book.anchorAt(lo), end = book.anchorAt(hi),
            color = color, note = note, createdAtMillis = clock(), source = source,
        )
        items.removeAll(overlapping)
        items += a
        undo.addLast(if (overlapping.isEmpty()) Change.Added(a) else Change.Merged(a, overlapping))
        changed()
        return a
    }

    @Synchronized
    fun addNote(book: Book, sentenceIndex: Int, text: String, source: AnnotationSource = AnnotationSource.TOUCH): Annotation {
        val a = Annotation(
            id = newId(), bookId = book.id, kind = AnnotationKind.NOTE, start = book.anchorAt(sentenceIndex),
            note = text.trim(), createdAtMillis = clock(), source = source,
        )
        items += a
        undo.addLast(Change.Added(a))
        changed()
        return a
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val a = items.firstOrNull { it.id == id } ?: return false
        items.remove(a)
        undo.addLast(Change.Removed(a))
        changed()
        return true
    }

    /** Reverts the most recent change; returns a short description of what was undone. */
    @Synchronized
    fun undoLast(): String? {
        val change = undo.removeLastOrNull() ?: return null
        val description = when (change) {
            is Change.Added -> { items.removeAll { it.id == change.annotation.id }; "Removed the ${describe(change.annotation)}." }
            is Change.Merged -> {
                items.removeAll { it.id == change.added.id }
                items += change.removed
                "Restored the previous highlights."
            }
            is Change.Removed -> { items += change.annotation; "Restored the ${describe(change.annotation)}." }
            is Change.Updated -> { replace(change.after, change.before); "Undid the label change." }
        }
        changed()
        return description
    }

    /** After a book is re-imported with a different parser, move annotations to their text. */
    @Synchronized
    fun reanchor(book: Book): Reanchorer.Report {
        val report = Reanchorer.Report()
        val index = Reanchorer.Index(book)
        val updated = items.map { a ->
            if (a.bookId != book.id) return@map a
            val start = Reanchorer.locate(a.start, index)
            val end = if (a.end == a.start) start else Reanchorer.locate(a.end, index)
            when {
                start == null -> { report.orphaned++; a.copy(orphaned = true) }
                start == a.start.sentenceIndex && (end ?: start) == a.end.sentenceIndex -> { report.unchanged++; a.copy(orphaned = false) }
                else -> {
                    report.moved++
                    val e = maxOf(end ?: start, start)
                    a.copy(start = book.anchorAt(start), end = book.anchorAt(e), orphaned = false)
                }
            }
        }
        items.clear()
        items += updated
        undo.clear()
        changed()
        return report
    }

    private fun replace(old: Annotation, new: Annotation) {
        val i = items.indexOfFirst { it.id == old.id }
        if (i >= 0) items[i] = new
    }

    private fun changed() = onChange?.invoke()

    companion object {
        fun describe(a: Annotation): String = when (a.kind) {
            AnnotationKind.BOOKMARK -> when (a.scope) {
                BookmarkScope.SENTENCE -> "bookmark"
                BookmarkScope.PAGE -> "page bookmark"
                BookmarkScope.CHAPTER -> "chapter bookmark"
            }
            AnnotationKind.HIGHLIGHT -> "highlight"
            AnnotationKind.NOTE -> "note"
        }
    }
}

object Reanchorer {
    class Report(var unchanged: Int = 0, var moved: Int = 0, var orphaned: Int = 0)

    private const val KEY = 24

    /**
     * Normalised sentence texts and a prefix lookup, built once per book. Re-anchoring used to
     * normalise every sentence for every annotation: 10,000 annotations on a 50,000-sentence
     * book took minutes (found by StressTest).
     */
    class Index(val book: Book) {
        internal val normed: Array<String> = Array(book.sentences.size) { norm(book.sentences[it].text) }
        internal val byKey: Map<String, List<Int>> = normed.indices.groupBy { normed[it].take(KEY) }
    }

    fun locate(anchor: TextAnchor, book: Book): Int? = locate(anchor, Index(book))

    /** Finds the sentence an anchor refers to, or null if the text is gone. */
    fun locate(anchor: TextAnchor, index: Index): Int? {
        val book = index.book
        val normed = index.normed
        if (book.isEmpty) return null
        val quote = norm(anchor.quote)
        if (quote.isEmpty()) return book.clampIndex(anchor.sentenceIndex)
        if (normed.getOrNull(anchor.sentenceIndex)?.startsWith(quote) == true) return anchor.sentenceIndex

        // Exact quote matches, closest to the old position wins; the prefix breaks ties
        // for repeated sentences ("He nodded.").
        val candidates = if (quote.length >= KEY) {
            index.byKey[quote.take(KEY)].orEmpty().filter { normed[it].startsWith(quote) }
        } else {
            normed.indices.filter { normed[it].startsWith(quote) }
        }
        if (candidates.isNotEmpty()) {
            val prefix = norm(anchor.prefix)
            val withPrefix = if (prefix.isEmpty()) candidates else candidates.filter { i -> i > 0 && normed[i - 1].endsWith(prefix) }
            return (withPrefix.ifEmpty { candidates }).minBy { kotlin.math.abs(it - anchor.sentenceIndex) }
        }

        // The quote may now span two sentences (parser splits differently) or be slightly edited.
        val probe = quote.take(40)
        val near = maxOf(0, anchor.sentenceIndex - 5000)..minOf(book.lastIndex, anchor.sentenceIndex + 5000)
        (near.firstOrNull { normed[it].contains(probe) } ?: normed.indices.firstOrNull { normed[it].contains(probe) })?.let { return it }
        val lo = maxOf(0, anchor.sentenceIndex - 300)
        val hi = minOf(book.lastIndex, anchor.sentenceIndex + 300)
        var best = -1
        var bestScore = 0.0
        for (i in lo..hi) {
            val score = similarity(quote, normed[i].take(quote.length + 20))
            if (score > bestScore) { bestScore = score; best = i }
        }
        return if (bestScore >= 0.6) best else null
    }

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    internal fun norm(s: String) = s.lowercase().replace(NON_WORD, " ").trim()

    /** Dice coefficient over character trigrams. */
    internal fun similarity(a: String, b: String): Double {
        if (a.length < 3 || b.length < 3) return if (a == b) 1.0 else 0.0
        val ga = a.windowed(3).groupingBy { it }.eachCount()
        val gb = b.windowed(3).groupingBy { it }.eachCount()
        val common = ga.entries.sumOf { (k, v) -> minOf(v, gb[k] ?: 0) }
        return 2.0 * common / (a.length - 2 + b.length - 2)
    }
}
