package com.earmark.core

import com.earmark.core.commands.CommandParser
import com.earmark.core.model.SourceFormat
import com.earmark.core.parse.DocumentParseException
import com.earmark.core.parse.DocumentParser
import com.earmark.core.parse.EpubParser
import com.earmark.core.parse.PdfTextCleaner
import com.earmark.core.parse.ZipFixtures
import com.earmark.core.qa.PassageIndex
import com.earmark.core.qa.QaKind
import com.earmark.core.qa.QaPromptBuilder
import com.earmark.core.qa.QaRequest
import com.earmark.core.storage.JsonCodec
import com.earmark.core.text.SentenceSegmenter
import com.earmark.core.text.SpeechNormalizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.ByteArrayInputStream
import java.time.Duration
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Hostile and huge inputs. Timings are printed so they can be compared with a phone
 * (expect a mid-range Android device to be 3-5x slower than this JVM).
 */
class RobustnessTest {
    private fun report(label: String, ms: Long) = println("PERF $label: $ms ms")

    private fun novelText(targetChars: Int): String {
        val rnd = Random(1)
        val words = "the harbour lighthouse Mira Tobin storm maps ship guard captain brother lamp wreckers night sea boats fog bells island winter".split(' ')
        val sb = StringBuilder()
        var chapter = 1
        while (sb.length < targetChars) {
            if (sb.length / 60_000 >= chapter) { sb.append("\n\nCHAPTER ").append(chapter + 1).append("\n\n"); chapter++ }
            val sentence = (0 until rnd.nextInt(6, 25)).joinToString(" ") { words[rnd.nextInt(words.size)] }
            sb.append(sentence.replaceFirstChar { it.uppercase() }).append(if (rnd.nextInt(10) == 0) "?" else ".").append(' ')
            if (rnd.nextInt(5) == 0) sb.append("\n\n")
        }
        return sb.toString()
    }

    @Test
    fun `a 1_5 million character book parses, indexes and answers quickly`() {
        val text = novelText(1_500_000)
        lateinit var book: com.earmark.core.model.Book
        val parse = measureTimeMillis { book = DocumentParser.parse(text.toByteArray(), SourceFormat.TEXT, "big.txt") }
        lateinit var index: PassageIndex
        val indexMs = measureTimeMillis { index = PassageIndex(book) }
        val searchMs = measureTimeMillis { repeat(20) { index.search("why did Tobin break the lighthouse lamp", 5, maxSentence = book.lastIndex / 2) } }
        val promptMs = measureTimeMillis { QaPromptBuilder(book, index).build(QaRequest(QaKind.RECAP_CHAPTER, "", book.lastIndex / 2)) }
        val normalizer = SpeechNormalizer()
        val normMs = measureTimeMillis { book.sentences.forEach { normalizer.normalize(it.text) } }
        var json = ""
        val jsonMs = measureTimeMillis { json = JsonCodec.encodeBook(book) }
        report("parse 1.5M chars (${book.sentences.size} sentences, ${book.chapters.size} chapters)", parse)
        report("index", indexMs)
        report("20 searches", searchMs)
        report("recap prompt", promptMs)
        report("normalize every sentence", normMs)
        report("encode book json (${json.length / 1024} KiB)", jsonMs)
        assertTrue(book.chapters.size >= 20)
        assertTrue(parse < 15_000 && indexMs < 15_000 && searchMs < 5_000 && normMs < 30_000, "too slow")
    }

    @Test
    fun `a 2 MB paragraph with no punctuation is split in linear time`() {
        val blob = ("word " .repeat(400_000))
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val ms = measureTimeMillis {
                val parts = SentenceSegmenter().split(blob)
                assertTrue(parts.all { it.length <= 400 })
            }
            report("split 2 MB unpunctuated paragraph", ms)
        }
    }

    @Test
    fun `deeply nested html does not overflow the stack`() {
        val depth = 5000
        val html = "<html><body>" + "<div><span>".repeat(depth) + "Deep text." + "</span></div>".repeat(depth) + "</body></html>"
        val t = Thread(null, {
            val book = DocumentParser.parse(html.toByteArray(), SourceFormat.HTML, "deep.html")
            check(book.sentences.any { it.text == "Deep text." })
        }, "small-stack", 256 * 1024) // Android background threads have small stacks
        var error: Throwable? = null
        t.setUncaughtExceptionHandler { _, e -> error = e }
        t.start(); t.join()
        assertTrue(error == null, "failed with $error")
    }

    @Test
    fun `zip bomb entries are rejected`() {
        val huge = "A".repeat(25 * 1024 * 1024)
        val bytes = ZipFixtures.zip("META-INF/container.xml" to ZipFixtures.CONTAINER, "OEBPS/big.xhtml" to huge)
        assertTrue(bytes.size < 1_000_000, "compresses well: ${bytes.size}")
        val e = assertThrows<DocumentParseException> { EpubParser.parse(ByteArrayInputStream(bytes)) }
        assertContains(e.message!!, "unreasonably large")
    }

    @Test
    fun `an epub with 800 chapters parses`() {
        val manifest = (1..800).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val spine = (1..800).joinToString("") { """<itemref idref="c$it"/>""" }
        val files = (1..800).map { "OEBPS/c$it.xhtml" to ZipFixtures.xhtml("c$it", "<h1>Part $it</h1><p>Body of part $it. It ends.</p>") }
        val bytes = ZipFixtures.zip(
            "META-INF/container.xml" to ZipFixtures.CONTAINER,
            "OEBPS/content.opf" to "<package><metadata/><manifest>$manifest</manifest><spine>$spine</spine></package>",
            *files.toTypedArray(),
        )
        val ms = measureTimeMillis { assertTrue(DocumentParser.parse(bytes, SourceFormat.EPUB, "many.epub").chapters.size == 800) }
        report("800-chapter epub", ms)
    }

    @Test
    fun `a pdf page with ten thousand lines is handled`() {
        val page = (1..10_000).joinToString("\n") { "Line $it of a very long table of contents entry" }
        val ms = measureTimeMillis { PdfTextCleaner.clean(listOf(PdfTextCleaner.Page(1, page))) }
        report("10k-line pdf page", ms)
    }

    @Test
    fun `long or hostile transcripts parse instantly`() {
        val inputs = listOf(
            "a ".repeat(5000),
            "bookmark ".repeat(2000),
            "go back " + "one ".repeat(3000) + "seconds",
            "highlight the last " + "two ".repeat(3000) + "sentences",
            "note " + "x".repeat(50_000),
        )
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { inputs.forEach { CommandParser.parse(it) } }
    }

    @Test
    fun `normalizer handles a 200 KB paragraph without regex blow-up`() {
        val s = "Dr. Rao paid ₹1,00,000 [12] u/s 302 on 12.03.2020 — see https://x.y/z. ".repeat(3000)
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            report("normalize 200 KB", measureTimeMillis { SpeechNormalizer().normalize(s) })
        }
    }
}
