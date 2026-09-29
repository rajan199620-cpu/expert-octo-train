package com.earmark.core.parse

import com.earmark.core.model.SourceFormat
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real PDFs generated with PDFBox, extracted with PDFTextStripper page by page (exactly what
 * the Android app does with pdfbox-android), then cleaned.
 */
class PdfIntegrationTest {
    private val chapter1 = listOf(
        "Mira reached the harbour at dawn and waited for the ferry. The fog was thick and impor-",
        "tant ships stayed in port. It was self-evident to everyone that nobody would sail.",
        "",
        "An old sailor named Tobin sold maps. He claimed his maps were self-",
        "evident works of genius, and he charged accordingly for every single one.",
        "Mira bought one anyway because she needed to find the island before winter came",
        "and the harbour froze over for another long season of waiting.",
    )
    private val chapter1b = listOf(
        "The map showed a lighthouse on the northern cliff. Nobody had lit it for years.",
        "Tobin said the keeper had vanished.",
    )
    private val chapter2 = listOf(
        "Chapter 2",
        "",
        "By noon the sky turned black and thunder rolled across the water. The boats",
        "rocked hard against the pier.",
    )

    private fun wrapPage(body: List<String>, pageNo: Int) = PageSpec(header = "THE LIGHTHOUSE KEEPER", body = body, footer = "$pageNo")

    data class PageSpec(val header: String?, val body: List<String>, val footer: String?)

    private fun makePdf(pages: List<PageSpec>, outline: List<Pair<String, Int>> = emptyList()): ByteArray {
        PDDocument().use { doc ->
            val font = PDType1Font.TIMES_ROMAN
            for (spec in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    spec.header?.let { cs.beginText(); cs.setFont(font, 9f); cs.newLineAtOffset(200f, 800f); cs.showText(it); cs.endText() }
                    cs.beginText()
                    cs.setFont(font, 11f)
                    cs.setLeading(15f)
                    cs.newLineAtOffset(50f, 760f)
                    for (line in spec.body) {
                        if (line.isNotEmpty()) cs.showText(line)
                        cs.newLine()
                    }
                    cs.endText()
                    spec.footer?.let { cs.beginText(); cs.setFont(font, 9f); cs.newLineAtOffset(290f, 30f); cs.showText(it); cs.endText() }
                }
            }
            if (outline.isNotEmpty()) {
                val root = PDDocumentOutline()
                doc.documentCatalog.documentOutline = root
                for ((title, pageNo) in outline) {
                    val item = PDOutlineItem()
                    item.title = title
                    item.destination = PDPageFitDestination().apply { page = doc.getPage(pageNo - 1) }
                    root.addLast(item)
                }
            }
            doc.documentInformation.title = "The Lighthouse Keeper"
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** Mirrors the app's PdfExtractor (pdfbox-android has the same API). */
    private fun extract(bytes: ByteArray): Triple<List<PdfTextCleaner.Page>, List<PdfTextCleaner.OutlineEntry>, String?> {
        PDDocument.load(bytes).use { doc ->
            val stripper = PDFTextStripper()
            val pages = (1..doc.numberOfPages).map { n ->
                stripper.startPage = n
                stripper.endPage = n
                PdfTextCleaner.Page(n, stripper.getText(doc))
            }
            val outline = ArrayList<PdfTextCleaner.OutlineEntry>()
            var item = doc.documentCatalog.documentOutline?.firstChild
            while (item != null) {
                val page = item.findDestinationPage(doc)
                if (page != null) outline += PdfTextCleaner.OutlineEntry(item.title ?: "", doc.pages.indexOf(page) + 1)
                item = item.nextSibling
            }
            return Triple(pages, outline, doc.documentInformation.title)
        }
    }

    private fun sampleBook(withOutline: Boolean): com.earmark.core.model.Book {
        val pages = listOf(
            wrapPage(chapter1, 1),
            wrapPage(chapter1b, 2),
            wrapPage(listOf("Mira packed her bag and slept early that night, dreaming of the sea."), 3),
            wrapPage(chapter2, 4),
        )
        val bytes = makePdf(pages, if (withOutline) listOf("Arrival" to 1, "The Storm" to 4) else emptyList())
        val (p, o, title) = extract(bytes)
        val raw = PdfTextCleaner.clean(p, o, title)
        return BookAssembler.assemble("pdf", "fallback", SourceFormat.PDF, raw)
    }

    @Test
    fun `running headers and page numbers are not read aloud`() {
        val book = sampleBook(withOutline = true)
        val all = book.sentences.joinToString(" | ") { it.text }
        assertFalse(all.contains("LIGHTHOUSE KEEPER"), all)
        assertFalse(book.sentences.any { it.text.trim().matches(Regex("""\d+""")) }, all)
        assertFalse(Regex("""\bwinter came \d""").containsMatchIn(all), all)
    }

    @Test
    fun `line-break hyphens are joined but real hyphenated words are kept`() {
        val all = sampleBook(withOutline = true).sentences.joinToString(" ") { it.text }
        assertContains(all, "important ships")
        assertContains(all, "self-evident works")
    }

    @Test
    fun `paragraphs are stitched across lines and sentences keep their start page`() {
        val book = sampleBook(withOutline = true)
        val s = book.sentences.first { it.text.startsWith("Mira bought one anyway") }
        assertEquals("Mira bought one anyway because she needed to find the island before winter came and the harbour froze over for another long season of waiting.", s.text)
        assertEquals(1, s.page)
        assertEquals(4, book.sentences.first { it.text.startsWith("By noon") }.page)
        assertEquals(4, book.pageCount)
        assertFalse(book.pagesAreVirtual)
    }

    @Test
    fun `outline drives chapters when present`() {
        val book = sampleBook(withOutline = true)
        assertEquals(listOf("Arrival", "The Storm"), book.chapters.map { it.title })
        assertEquals("The Lighthouse Keeper", book.title)
    }

    @Test
    fun `chapter heading lines drive chapters when there is no outline`() {
        val book = sampleBook(withOutline = false)
        assertEquals(2, book.chapters.size, book.chapters.toString())
        assertEquals("Chapter 2", book.chapters[1].title)
        assertTrue(book.sentences[book.chapters[1].firstSentence].isHeading)
    }

    @Test
    fun `a scanned pdf without a text layer is flagged`() {
        val pages = (1..3).map { PageSpec(null, emptyList(), null) }
        val (p, o, t) = extract(makePdf(pages))
        val raw = PdfTextCleaner.clean(p, o, t)
        val book = BookAssembler.assemble("scan", "Scan", SourceFormat.PDF, raw)
        assertTrue(book.isEmpty)
        assertTrue(book.warnings.any { it.contains("scan") }, book.warnings.toString())
    }

    @Test
    fun `mixed scanned pages are reported`() {
        val pages = listOf(wrapPage(chapter1, 1)) + (2..3).map { PageSpec(null, emptyList(), null) }
        val (p, o, t) = extract(makePdf(pages))
        val raw = PdfTextCleaner.clean(p, o, t)
        assertTrue(raw.warnings.any { it.contains("2 of 3 pages") }, raw.warnings.toString())
    }
}
