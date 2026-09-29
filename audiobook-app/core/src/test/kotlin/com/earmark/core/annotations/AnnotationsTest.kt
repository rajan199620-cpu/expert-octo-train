package com.earmark.core.annotations

import com.earmark.core.TestBooks
import com.earmark.core.model.SourceFormat
import com.earmark.core.parse.BookAssembler
import com.earmark.core.parse.RawBlock
import com.earmark.core.parse.RawDocument
import com.earmark.core.parse.RawSection
import com.earmark.core.storage.JsonCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationsTest {
    private val book = TestBooks.novel()
    private var ids = 0
    private var now = 1000L
    private fun store(initial: List<Annotation> = emptyList()) = AnnotationStore(initial, clock = { now++ }, newId = { "a${ids++}" })

    @Test
    fun `bookmarking the same sentence twice is idempotent`() {
        val s = store()
        val first = s.bookmark(book, 4)
        val second = s.bookmark(book, 4)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(1, s.forBook(book.id).size)
    }

    @Test
    fun `a label on an existing bookmark updates it and can be undone`() {
        val s = store()
        s.bookmark(book, 4)
        val r = s.bookmark(book, 4, label = "Key point")
        assertEquals("Key point", r.annotation.label)
        s.undoLast()
        assertNull(s.forBook(book.id).single().label)
    }

    @Test
    fun `page and chapter bookmarks anchor at the first sentence of the page or chapter`() {
        val s = store()
        val chapter = s.bookmark(book, 14, BookmarkScope.CHAPTER).annotation
        assertEquals(book.chapters[1].firstSentence, chapter.start.sentenceIndex)
        val page = s.bookmark(book, 14, BookmarkScope.PAGE).annotation
        assertEquals(book.firstSentenceOfPage(book.sentences[14].page), page.start.sentenceIndex)
        // A sentence bookmark at the same spot is a different bookmark.
        assertTrue(s.bookmark(book, chapter.start.sentenceIndex).created)
    }

    @Test
    fun `overlapping and adjacent highlights of the same colour merge, undo restores them`() {
        val s = store()
        s.highlight(book, 2, 3)
        s.highlight(book, 5, 6)
        val merged = s.highlight(book, 4, 4) // touches both
        assertEquals(2..6, merged.range)
        assertEquals(1, s.forBook(book.id).count { it.kind == AnnotationKind.HIGHLIGHT })
        s.undoLast()
        assertEquals(listOf(2..3, 5..6), s.forBook(book.id).map { it.range })
    }

    @Test
    fun `different colours do not merge`() {
        val s = store()
        s.highlight(book, 2, 3, HighlightColor.YELLOW)
        s.highlight(book, 3, 4, HighlightColor.GREEN)
        assertEquals(2, s.forBook(book.id).size)
    }

    @Test
    fun `reversed and out-of-range highlight bounds are normalised`() {
        val s = store()
        val a = s.highlight(book, 500, -3)
        assertEquals(0..book.lastIndex, a.range)
    }

    @Test
    fun `undo stack handles add, remove and empty`() {
        val s = store()
        assertNull(s.undoLast())
        val n = s.addNote(book, 3, "  Tobin is lying  ")
        assertEquals("Tobin is lying", n.note)
        assertTrue(s.remove(n.id))
        assertFalse(s.remove("missing"))
        assertContains(s.undoLast()!!, "Restored")
        assertEquals(1, s.forBook(book.id).size)
        assertContains(s.undoLast()!!, "Removed")
        assertTrue(s.forBook(book.id).isEmpty())
    }

    @Test
    fun `covering finds annotations spanning a sentence and ignores other books`() {
        val s = store()
        s.highlight(book, 2, 5)
        s.bookmark(TestBooks.novel().copy(id = "other"), 3)
        assertEquals(1, s.covering(book.id, 4).size)
        assertTrue(s.covering(book.id, 6).isEmpty())
    }

    @Test
    fun `re-anchoring after the parser splits sentences differently`() {
        val s = store()
        val hl = s.highlight(book, 12, 13)
        val bm = s.bookmark(book, 20).annotation
        // New edition: an extra intro paragraph shifts every index by 3.
        val shifted = TestBooks.book(
            "Preface" to listOf("A new preface was added. It has three sentences. This shifts indices."),
            "The Arrival" to listOf(
                "Mira reached the harbour at dawn. The fog hid the boats. She counted the bells.",
                "An old sailor waved at her. His name was Tobin. He sold maps to travellers.",
                "Mira bought a map of the northern islands. It cost two silver coins. Tobin smiled.",
            ),
            "The Storm" to listOf(
                "By noon the sky turned black. Thunder rolled over the water. The boats rocked hard.",
                "Tobin warned her about the lighthouse. Nobody had lit it for years. Ships were lost there.",
                "Mira climbed the lighthouse stairs. The lamp was cracked. She lit it anyway.",
            ),
            "The Secret" to listOf(
                "The next morning a ship arrived safely. Its captain was Mira's missing brother. He had been lost for ten years.",
                "Tobin revealed that he had kept the lamp broken on purpose. He wanted the wreckers to profit. Mira was furious.",
                "She reported Tobin to the harbour guard. The guard arrested him. The lighthouse burned every night after that.",
            ),
        )
        val report = s.reanchor(shifted)
        assertEquals(2, report.moved)
        val byId = s.forBook(book.id).associateBy { it.id }
        assertEquals(15..16, byId.getValue(hl.id).range)
        assertEquals(23, byId.getValue(bm.id).start.sentenceIndex)
        assertEquals(book.sentences[20].text, shifted.sentences[23].text)
    }

    @Test
    fun `re-anchoring picks the right copy of a repeated sentence using its prefix`() {
        val b = TestBooks.book("C" to listOf("Alpha one. He nodded.", "Beta two. He nodded.", "Gamma three. He nodded."))
        val s = store()
        val bm = s.bookmark(b, 3).annotation // "He nodded." after "Beta two."
        val edited = TestBooks.book("C" to listOf("Intro. Alpha one. He nodded.", "Beta two. He nodded.", "Gamma three. He nodded."))
        s.reanchor(edited)
        val moved = s.forBook(b.id).single { it.id == bm.id }
        assertEquals("Beta two.", edited.sentences[moved.start.sentenceIndex - 1].text)
    }

    @Test
    fun `re-anchoring survives small edits and orphans deleted text`() {
        val b = TestBooks.book("C" to listOf("The quick brown fox jumps over the lazy dog near the river bank.", "Another sentence entirely."))
        val s = store()
        val keep = s.bookmark(b, 0).annotation
        val gone = s.bookmark(b, 1).annotation
        val edited = TestBooks.book("C" to listOf("The quick brown fox jumped over the lazy dog near the river bank!", "Completely new text here."))
        val report = s.reanchor(edited)
        val byId = s.forBook(b.id).associateBy { it.id }
        assertFalse(byId.getValue(keep.id).orphaned)
        assertTrue(byId.getValue(gone.id).orphaned)
        assertEquals(1, report.orphaned)
        assertTrue(s.covering(b.id, 1).isEmpty(), "orphans are not shown on the page")
    }

    @Test
    fun `markdown export groups by chapter and includes notes and labels`() {
        val s = store()
        s.bookmark(book, 1, label = "Fog")
        s.highlight(book, 5, 6)
        s.addNote(book, 20, "Tobin is the villain")
        val md = Exporters.markdown(book, s.forBook(book.id))
        assertContains(md, "# Test Book")
        assertContains(md, "## The Arrival")
        assertContains(md, "## The Secret")
        assertContains(md, "**Fog**")
        assertContains(md, "> ${book.sentences[5].text} ${book.sentences[6].text}")
        assertContains(md, "Tobin is the villain")
        assertContains(md, "approx. page")
        assertContains(Exporters.markdown(book, emptyList()), "No bookmarks")
    }

    @Test
    fun `csv export escapes commas, quotes and newlines`() {
        assertEquals("plain", Exporters.csvField("plain"))
        assertEquals("\"a,b\"", Exporters.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Exporters.csvField("say \"hi\""))
        assertEquals("\"line1\nline2\"", Exporters.csvField("line1\nline2"))
        val b = TestBooks.book("C" to listOf("He said, \"stop\", twice."))
        val s = store()
        s.highlight(b, 0)
        val csv = Exporters.csv(b, s.forBook(b.id))
        val row = csv.lines()[1]
        assertTrue(row.startsWith("\"He said, \"\"stop\"\", twice.\",Test Book,"), row)
        assertEquals(2, csv.trim().lines().size)
    }

    @Test
    fun `anki export is tab separated with html escaped and no raw tabs or newlines`() {
        val b = TestBooks.book("C" to listOf("Use a < b\tand & ok."))
        val s = store()
        s.highlight(b, 0)
        s.addNote(b, 0, "line one\nline two")
        s.bookmark(b, 0)
        val tsv = Exporters.ankiTsv(b, s.forBook(b.id))
        val rows = tsv.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(2, rows.size, "bookmarks are not cards")
        rows.forEach { assertEquals(3, it.split('\t').size, it) }
        assertContains(tsv, "Use a &lt; b and &amp; ok.")
        assertContains(tsv, "line one<br>line two")
    }

    @Test
    fun `annotations survive a json round trip and corrupt files fail softly`() {
        val s = store()
        s.bookmark(book, 1, label = "x")
        s.highlight(book, 2, 4, HighlightColor.PINK)
        val json = JsonCodec.encodeAnnotations(s.all())
        val decoded = JsonCodec.decodeAnnotations(json)
        assertIs<JsonCodec.Decoded.Ok<List<Annotation>>>(decoded)
        assertEquals(s.all(), decoded.value)
        assertIs<JsonCodec.Decoded.Failed>(JsonCodec.decodeAnnotations("{not json"))
        assertIs<JsonCodec.Decoded.Failed>(JsonCodec.decodeAnnotations(""))
        // A file written by a newer version with extra fields still loads.
        val future = json.replace("\"orphaned\":false", "\"orphaned\":false,\"syncedAt\":123")
        assertIs<JsonCodec.Decoded.Ok<List<Annotation>>>(JsonCodec.decodeAnnotations(future))
    }

    @Test
    fun `books survive a json round trip`() {
        val raw = RawDocument("T", "A", listOf(RawSection("S", listOf(RawBlock("One. Two.", page = 3)))), hasRealPages = true)
        val b = BookAssembler.assemble("id", "t", SourceFormat.PDF, raw)
        val back = JsonCodec.decodeBook(JsonCodec.encodeBook(b))
        assertIs<JsonCodec.Decoded.Ok<com.earmark.core.model.Book>>(back)
        assertEquals(b, back.value)
    }
}
