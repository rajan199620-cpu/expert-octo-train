package com.earmark.core.annotations

import com.earmark.core.model.Book
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Gets highlights out of the app: Markdown for notes apps, Anki for review, Readwise-style CSV. */
object Exporters {
    fun markdown(book: Book, annotations: List<Annotation>): String {
        val sb = StringBuilder()
        sb.append("# ").append(book.title).append('\n')
        book.author?.let { sb.append("*").append(it).append("*\n") }
        sb.append('\n')
        val sorted = annotations.filter { it.bookId == book.id }.sortedBy { it.start.sentenceIndex }
        if (sorted.isEmpty()) return sb.append("_No bookmarks, highlights or notes yet._\n").toString()
        var chapter = -1
        for (a in sorted) {
            if (a.start.chapter != chapter) {
                chapter = a.start.chapter
                val title = book.chapters.getOrNull(chapter)?.title ?: "Chapter ${chapter + 1}"
                sb.append("## ").append(title).append("\n\n")
            }
            val where = pageLabel(book, a.start.page)
            when (a.kind) {
                AnnotationKind.HIGHLIGHT -> {
                    passage(book, a).lines().forEach { sb.append("> ").append(it).append('\n') }
                    sb.append("\n— ").append(where).append('\n')
                    a.note?.takeIf { it.isNotBlank() }?.let { sb.append("\n**Note:** ").append(it).append('\n') }
                }
                AnnotationKind.BOOKMARK -> {
                    sb.append("- 🔖 ").append(a.label?.let { "**$it** — " } ?: "")
                        .append('"').append(quoteFor(book, a)).append("\" (").append(where).append(")\n")
                }
                AnnotationKind.NOTE -> {
                    sb.append("- 📝 **Note:** ").append(a.note.orEmpty()).append(" (").append(where)
                        .append(", on \"").append(quoteFor(book, a)).append("\")\n")
                }
            }
            if (a.orphaned) sb.append("  _(text no longer found in this edition)_\n")
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Anki "Basic" notes as tab-separated text (File > Import in Anki): front is the passage,
     * back is the note plus where it came from.
     */
    fun ankiTsv(book: Book, annotations: List<Annotation>): String {
        val sb = StringBuilder("#separator:tab\n#html:true\n#tags column:3\n")
        val tag = "earmark::" + book.title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').take(40)
        for (a in annotations.filter { it.bookId == book.id && it.kind != AnnotationKind.BOOKMARK }.sortedBy { it.start.sentenceIndex }) {
            val front = if (a.kind == AnnotationKind.NOTE) a.note.orEmpty() else passage(book, a)
            val back = buildString {
                if (a.kind == AnnotationKind.NOTE) append(tsvHtml(quoteFor(book, a))).append("<br>")
                else a.note?.takeIf { it.isNotBlank() }?.let { append(tsvHtml(it)).append("<br>") }
                append("<i>").append(tsvHtml(book.title)).append(", ").append(tsvHtml(pageLabel(book, a.start.page))).append("</i>")
            }
            sb.append(tsvHtml(front)).append('\t').append(back).append('\t').append(tag).append('\n')
        }
        return sb.toString()
    }

    /** Readwise-compatible CSV (Highlight, Title, Author, URL, Note, Location, Date). */
    fun csv(book: Book, annotations: List<Annotation>): String {
        val sb = StringBuilder("Highlight,Title,Author,URL,Note,Location,Date\n")
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC)
        for (a in annotations.filter { it.bookId == book.id }.sortedBy { it.start.sentenceIndex }) {
            val highlight = if (a.kind == AnnotationKind.HIGHLIGHT) passage(book, a) else quoteFor(book, a)
            val note = listOfNotNull(a.label, a.note).joinToString(" — ")
            val row = listOf(highlight, book.title, book.author.orEmpty(), "", note, a.start.page.toString(), fmt.format(Instant.ofEpochMilli(a.createdAtMillis)))
            sb.append(row.joinToString(",") { csvField(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun passage(book: Book, a: Annotation): String =
        if (a.orphaned) a.start.quote else book.textOf(a.range).ifEmpty { a.start.quote }

    private fun quoteFor(book: Book, a: Annotation): String =
        if (a.orphaned) a.start.quote else book.sentences.getOrNull(a.start.sentenceIndex)?.text ?: a.start.quote

    private fun pageLabel(book: Book, page: Int) = if (book.pagesAreVirtual) "approx. page $page" else "page $page"

    internal fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    internal fun tsvHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\t", " ").replace("\r\n", "<br>").replace("\n", "<br>")
}
