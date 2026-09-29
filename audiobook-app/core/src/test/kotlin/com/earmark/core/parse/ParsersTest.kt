package com.earmark.core.parse

import com.earmark.core.model.SourceFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    @Test
    fun `epub3 - metadata, nav titles, spine order, skips footnotes, scripts and non-linear items`() {
        val book = DocumentParser.parse(ZipFixtures.epub3(), SourceFormat.EPUB, "x.epub")
        assertEquals("The Lighthouse Keeper", book.title)
        assertEquals("Asha Verma", book.author)
        assertEquals(listOf("One: Arrival", "Two: The Storm"), book.chapters.map { it.title })
        val all = book.sentences.joinToString(" | ") { it.text }
        assertFalse(all.contains("alert"), all)
        assertFalse(all.contains("Footnote text"), all)
        assertFalse(all.contains("Dawn is early"), "non-linear notes must be skipped: $all")
        assertFalse(Regex("""dawn\.1""").containsMatchIn(all), "footnote call-out glued to text: $all")
        assertContains(all, "Mira reached the harbour at dawn.")
        assertContains(all, "An old sailor waved at her & smiled. His name was Tobin.".substringBefore(" His"))
        assertContains(all, "By noon the sky turned black.")
        assertContains(all, "First item")
        assertTrue(book.sentences.first().isHeading, "h1 is a heading")
        assertTrue(book.pagesAreVirtual)
    }

    @Test
    fun `epub2 - ncx titles and dot-dot relative paths`() {
        val book = DocumentParser.parse(ZipFixtures.epub2(), SourceFormat.EPUB, "old.epub")
        assertEquals("Old Format", book.title)
        assertEquals(listOf("Prologue"), book.chapters.map { it.title })
        assertEquals(listOf("It was a dark night.", "Nobody slept."), book.sentences.map { it.text })
    }

    @Test
    fun `epub - DRM, corrupt and missing-container files fail with a readable message`() {
        val drm = ZipFixtures.zip(
            "META-INF/container.xml" to ZipFixtures.CONTAINER,
            "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/text/ch1.xhtml"/></CipherData></EncryptedData></encryption>""",
            "OEBPS/content.opf" to "<package/>",
        )
        assertContains(assertThrows<DocumentParseException> { EpubParser.parse(ByteArrayInputStream(drm)) }.message!!, "DRM")
        assertThrows<DocumentParseException> { EpubParser.parse(ByteArrayInputStream("not a zip at all".toByteArray())) }
        assertThrows<DocumentParseException> { EpubParser.parse(ByteArrayInputStream(ZipFixtures.zip("a.xhtml" to "<p>x</p>"))) }
        val truncated = ZipFixtures.epub3().copyOf(200)
        assertThrows<DocumentParseException> { EpubParser.parse(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `epub - font obfuscation in encryption xml is not mistaken for DRM`() {
        val bytes = ZipFixtures.zip(
            *listOf(
                "META-INF/container.xml" to ZipFixtures.CONTAINER,
                "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/fonts/a.otf"/></CipherData></EncryptedData></encryption>""",
                "OEBPS/content.opf" to """<package><metadata/><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""",
                "OEBPS/a.xhtml" to ZipFixtures.xhtml("a", "<p>Readable text.</p>"),
            ).toTypedArray(),
        )
        assertEquals("Readable text.", DocumentParser.parse(bytes, SourceFormat.EPUB, "a.epub").sentences.single().text)
    }

    @Test
    fun `href resolution`() {
        assertEquals("OEBPS/text/chapter 1.xhtml", EpubParser.resolve("OEBPS", "text/chapter%201.xhtml"))
        assertEquals("OPS/xhtml/a.html", EpubParser.resolve("OPS/pkg", "../xhtml/a.html"))
        assertEquals("a.html", EpubParser.resolve("", "./a.html"))
        assertEquals("root.html", EpubParser.resolve("OEBPS", "/root.html"))
        assertEquals("OEBPS/a+b.html", EpubParser.resolve("OEBPS", "a+b.html"))
    }

    @Test
    fun `docx - styles become chapters, runs join, tabs become spaces, empty paragraphs skipped`() {
        val book = DocumentParser.parse(ZipFixtures.docx(), SourceFormat.DOCX, "notes.docx")
        assertEquals("Case Notes", book.title)
        assertEquals("R. Singh", book.author)
        assertEquals(listOf("Case Notes", "Evidence"), book.chapters.map { it.title })
        assertEquals(listOf("Case Notes", "The accused was arrested on Monday.", "Evidence", "Item one was a knife."), book.sentences.map { it.text })
    }

    @Test
    fun `plain text - hard wraps joined, gutenberg boilerplate removed, chapter headings detected`() {
        val txt = """
            The Project Gutenberg eBook of Something
            Lots of licence text here.
            *** START OF THE PROJECT GUTENBERG EBOOK SOMETHING ***

            CHAPTER I.

            It was the best of times, it was the worst
            of times, it was the age of wisdom.

            It was the spring of hope.

            CHAPTER II

            A new chapter begins
            here.
            *** END OF THE PROJECT GUTENBERG EBOOK SOMETHING ***
            More licence text.
        """.trimIndent()
        val book = DocumentParser.parse(txt.toByteArray(), SourceFormat.TEXT, "tale.txt")
        assertEquals(listOf("CHAPTER I.", "CHAPTER II"), book.chapters.map { it.title })
        assertEquals("It was the best of times, it was the worst of times, it was the age of wisdom.", book.sentences[1].text)
        assertFalse(book.sentences.any { it.text.contains("licence") || it.text.contains("Gutenberg", ignoreCase = true) })
        assertEquals("tale", book.title)
    }

    @Test
    fun `markdown - headings, links, emphasis, lists and code fences`() {
        val md = """
            # My Notes

            Intro with a [link](http://x.y) and **bold** and _italics_.

            ## Part Two

            - first point
            - second point

            ```kotlin
            val code = "never read"
            ```

            ![image](a.png) Final words.
        """.trimIndent()
        val book = DocumentParser.parse(md.toByteArray(), SourceFormat.MARKDOWN, "notes.md")
        assertEquals("My Notes", book.title)
        assertEquals(listOf("My Notes", "Part Two"), book.chapters.map { it.title })
        val texts = book.sentences.map { it.text }
        assertContains(texts, "Intro with a link and bold and italics.")
        assertContains(texts, "first point")
        assertContains(texts, "Final words.")
        assertFalse(texts.any { it.contains("never read") || it.contains("**") || it.contains("http") })
    }

    @Test
    fun `html - title, author meta, nav skipped`() {
        val html = """<html><head><title>Article</title><meta name="author" content="Jo"></head>
            <body><nav>Home | About</nav><article><p>First para.</p><p>Second para.</p></article></body></html>"""
        val book = DocumentParser.parse(html.toByteArray(), SourceFormat.HTML, "a.html")
        assertEquals("Article", book.title)
        assertEquals("Jo", book.author)
        assertEquals(listOf("First para.", "Second para."), book.sentences.map { it.text })
    }

    @Test
    fun `format detection by magic bytes, extension and mime type`() {
        assertEquals(SourceFormat.PDF, DocumentParser.detectFormat("book.epub", null, "%PDF-1.7".toByteArray()))
        assertEquals(SourceFormat.EPUB, DocumentParser.detectFormat("book.epub", null, byteArrayOf(0x50, 0x4B, 3, 4)))
        assertEquals(SourceFormat.EPUB, DocumentParser.detectFormat(null, "application/epub+zip", ByteArray(0)))
        assertEquals(SourceFormat.DOCX, DocumentParser.detectFormat("a.docx", null, byteArrayOf(0x50, 0x4B, 3, 4)))
        assertEquals(SourceFormat.MARKDOWN, DocumentParser.detectFormat("a.md", "text/plain", "# hi".toByteArray()))
        assertEquals(SourceFormat.TEXT, DocumentParser.detectFormat("notes", null, "plain words".toByteArray()))
        assertNull(DocumentParser.detectFormat("book.mobi", null, "BOOKMOBI".toByteArray()))
        assertNull(DocumentParser.detectFormat("x.bin", null, byteArrayOf(0, 1, 2, 3, 0, 0, 5)))
    }

    @Test
    fun `text decoding - utf8, windows-1252 fallback, utf16 with bom`() {
        assertEquals("café", DocumentParser.decodeText("café".toByteArray(Charsets.UTF_8)))
        assertEquals("café “quoted”", DocumentParser.decodeText("café “quoted”".toByteArray(charset("windows-1252"))))
        assertEquals("hi", DocumentParser.decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)))
        assertEquals("x", DocumentParser.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'x'.code.toByte())))
    }

    @Test
    fun `empty document yields an empty book with a warning, not a crash`() {
        val book = DocumentParser.parse("   \n\n  ".toByteArray(), SourceFormat.TEXT, "blank.txt")
        assertTrue(book.isEmpty)
        assertTrue(book.warnings.any { it.contains("No readable text") })
        assertEquals(0, book.pageCount)
    }

    @Test
    fun `virtual pages advance every ~275 words`() {
        val para = (1..100).joinToString(" ") { "Sentence number $it has six words." }
        val book = DocumentParser.parse(para.toByteArray(), SourceFormat.TEXT, "p.txt")
        assertEquals(1, book.sentences.first().page)
        assertEquals(3, book.sentences.last().page) // 600 words -> page 3
        assertEquals(book.pageCount, book.sentences.last().page)
        assertEquals(46, book.firstSentenceOfPage(2)) // 46 * 6 = 276 words before it
    }
}
