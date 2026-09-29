package com.earmark.core

import com.earmark.core.annotations.AnnotationKind
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.annotations.Exporters
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.commands.CommandParser
import com.earmark.core.model.Book
import com.earmark.core.model.SourceFormat
import com.earmark.core.parse.BookAssembler
import com.earmark.core.parse.DocumentParseException
import com.earmark.core.parse.DocumentParser
import com.earmark.core.parse.PdfTextCleaner
import com.earmark.core.parse.RawBlock
import com.earmark.core.parse.RawDocument
import com.earmark.core.parse.RawSection
import com.earmark.core.parse.ZipFixtures
import com.earmark.core.player.FakeEngine
import com.earmark.core.player.PlaybackController
import com.earmark.core.player.PlaybackStatus
import com.earmark.core.qa.PassageIndex
import com.earmark.core.qa.QaKind
import com.earmark.core.qa.QaPromptBuilder
import com.earmark.core.qa.QaRequest
import com.earmark.core.storage.JsonCodec
import com.earmark.core.text.SentenceSegmenter
import com.earmark.core.text.SpeechNormalizer
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stress, fuzz and property tests across the whole core. */
class StressTest {
    private fun report(label: String, ms: Long) = println("STRESS $label: $ms ms")

    /** A book whose every sentence is unique and numbered, so leaks can be detected exactly. */
    private fun numberedBook(sentences: Int, perParagraph: Int = 4, perChapter: Int = 400): Book {
        val sections = ArrayList<RawSection>()
        var n = 0
        while (n < sentences) {
            val blocks = ArrayList<RawBlock>()
            val chapterEnd = minOf(sentences, n + perChapter)
            while (n < chapterEnd) {
                val para = (0 until minOf(perParagraph, chapterEnd - n)).joinToString(" ") { "Marker token q${n + it}x appears in this line." }
                n += minOf(perParagraph, chapterEnd - n)
                blocks += RawBlock(para)
            }
            sections += RawSection("Chapter ${sections.size + 1}", blocks)
        }
        return BookAssembler.assemble("numbered", "Numbered", SourceFormat.TEXT, RawDocument("Numbered", null, sections))
    }

    @Test
    fun `annotation store survives concurrent voice, touch and headset edits`() {
        val book = numberedBook(2000)
        val store = AnnotationStore()
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(16_000)
        val failure = AtomicReference<Throwable?>(null)
        repeat(16_000) { i ->
            pool.execute {
                try {
                    val r = Random(i)
                    val at = r.nextInt(book.sentences.size)
                    when (i % 6) {
                        0 -> store.bookmark(book, at)
                        1 -> store.highlight(book, at, at + r.nextInt(3), HighlightColor.values()[r.nextInt(4)])
                        2 -> store.addNote(book, at, "note $i")
                        3 -> store.undoLast()
                        4 -> store.forBook(book.id).firstOrNull()?.let { store.remove(it.id) }
                        else -> store.covering(book.id, at)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        failure.get()?.let { throw it }
        val bookmarks = store.forBook(book.id).filter { it.kind == AnnotationKind.BOOKMARK }
        assertEquals(bookmarks.size, bookmarks.map { it.scope to it.start.sentenceIndex }.distinct().size, "no duplicate bookmarks")
        val hl = store.forBook(book.id).filter { it.kind == AnnotationKind.HIGHLIGHT }
        for (c in HighlightColor.values()) {
            val ranges = hl.filter { it.color == c }.map { it.range }.sortedBy { it.first }
            ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.last + 1 < b.first, "same-colour highlights overlap or touch: $a $b") }
        }
        val round = JsonCodec.decodeAnnotations(JsonCodec.encodeAnnotations(store.all()))
        assertTrue(round is JsonCodec.Decoded.Ok && round.value == store.all())
    }

    @Test
    fun `ten thousand annotations export and re-anchor quickly`() {
        val book = numberedBook(50_000)
        val store = AnnotationStore()
        val r = Random(5)
        repeat(10_000) { i ->
            val at = r.nextInt(book.sentences.size)
            when (i % 3) {
                0 -> store.bookmark(book, at)
                1 -> store.highlight(book, at, at, HighlightColor.values()[i % 4])
                else -> store.addNote(book, at, "Note, with \"quotes\"\tand tabs\nand lines $i")
            }
        }
        val items = store.forBook(book.id)
        var md = ""
        var csv = ""
        var anki = ""
        report("export ${items.size} annotations (md+csv+anki)", measureTimeMillis {
            md = Exporters.markdown(book, items); csv = Exporters.csv(book, items); anki = Exporters.ankiTsv(book, items)
        })
        assertTrue(md.length > 100_000 && csv.lines().size > items.size)
        anki.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { assertEquals(3, it.split('\t').size) }

        // A re-import that shifts every index by 4 (a new preface paragraph).
        val shifted = BookAssembler.assemble(
            "numbered", "Numbered", SourceFormat.TEXT,
            RawDocument("Numbered", null, listOf(RawSection("Preface", listOf(RawBlock("New one. New two. New three. New four.")))) +
                (0 until book.chapters.size).map { c ->
                    val ch = book.chapters[c]
                    RawSection(ch.title, (ch.firstSentence..ch.lastSentence).groupBy { book.sentences[it].paragraph }.values.map { idx -> RawBlock(idx.joinToString(" ") { book.sentences[it].text }) })
                }),
        )
        lateinit var rep: com.earmark.core.annotations.Reanchorer.Report
        val ms = measureTimeMillis { rep = store.reanchor(shifted) }
        report("re-anchor ${items.size} annotations on a 50k-sentence book", ms)
        assertEquals(0, rep.orphaned)
        store.forBook(book.id).forEach { a -> assertTrue(shifted.sentences[a.start.sentenceIndex].text.startsWith(a.start.quote.take(30))) }
        assertTrue(ms < 20_000, "re-anchoring too slow: $ms ms")
    }

    @Test
    fun `unicode torture - emoji, combining marks, RTL and ZWJ never get split mid-character`() {
        val samples = listOf(
            "👩🏽‍⚕️ Dr. Mehta arrived. 🚑 Sirens wailed! Was it 3.5 km? Yes 👍🏿.",
            "Café naïve résumé. Ångström coöperate. Z̤͔ͧ̑̓ä͖̭̈̇lͮ̒ͫǫ̗ text.",
            "مرحبا بالعالم. هذه جملة ثانية؟ نعم!",
            "क्ष त्र ज्ञ श्र — हिंदी में संयुक्ताक्षर। दूसरा वाक्य।",
            "🏳️‍🌈🏳️‍⚧️ flags. 👨‍👩‍👧‍👦 family. 🇮🇳🇯🇵 flags again.",
            "长句子没有空格也没有句号而且非常非常长的中文文本需要在某处被切开因为它超过了最大长度限制所以必须硬切𠀀𠀁𠀂𠀃𠀄𠀅🙂🙂🙂",
        )
        val seg = SentenceSegmenter(maxChars = 40)
        val norm = SpeechNormalizer()
        val rnd = Random(9)
        val pool = samples.joinToString(" ")
        repeat(2000) {
            val start = rnd.nextInt(pool.length)
            val text = pool.substring(start, minOf(pool.length, start + rnd.nextInt(1, 300)))
            val parts = seg.split(text)
            for ((k, part) in parts.withIndex()) {
                if (k > 0) {
                    val first = part.first()
                    val type = Character.getType(part.codePointAt(0))
                    assertFalse(first.isLowSurrogate(), "split inside a surrogate pair: [$part]")
                    assertFalse(first == '\u200D' || first == '\uFE0F', "split inside an emoji sequence: [$part]")
                    assertFalse(type == Character.NON_SPACING_MARK.toInt(), "combining mark separated from its letter: [$part]")
                }
                if (k < parts.lastIndex) assertFalse(part.last().isHighSurrogate(), "split inside a surrogate pair: [$part]")
                norm.normalize(part)
            }
        }
        // Lone surrogates in the input must simply not crash anything.
        val broken = "\uD83D".repeat(3) + " lone high surrogates. And \uDC00 lone low. " + "\uD83D".repeat(200)
        seg.split(broken).forEach { norm.normalize(it) }
    }

    @Test
    fun `random bytes and bit-flipped files only ever fail with a readable parse error`() {
        val rnd = Random(11)
        val originals = mapOf(SourceFormat.EPUB to ZipFixtures.epub3(), SourceFormat.DOCX to ZipFixtures.docx())
        var parsed = 0
        var rejected = 0
        for ((format, bytes) in originals) {
            repeat(400) { i ->
                val mutated = when (i % 3) {
                    0 -> ByteArray(rnd.nextInt(0, 2000)).also { rnd.nextBytes(it) }
                    1 -> bytes.copyOf().also { b -> repeat(1 + rnd.nextInt(20)) { val k = rnd.nextInt(b.size); b[k] = (b[k].toInt() xor (1 shl rnd.nextInt(8))).toByte() } }
                    else -> bytes.copyOf(rnd.nextInt(bytes.size))
                }
                try {
                    DocumentParser.parse(mutated, format, "fuzz.${format.name.lowercase()}")
                    parsed++
                } catch (e: DocumentParseException) {
                    rejected++
                } catch (e: Throwable) {
                    throw AssertionError("$format mutation $i threw ${e.javaClass.name}: ${e.message}", e)
                }
            }
        }
        println("STRESS fuzzed files: $parsed parsed, $rejected rejected cleanly")
    }

    @Test
    fun `text formats accept arbitrary bytes`() {
        val rnd = Random(12)
        repeat(300) {
            val bytes = ByteArray(rnd.nextInt(0, 5000)).also { rnd.nextBytes(it) }
            for (f in listOf(SourceFormat.TEXT, SourceFormat.MARKDOWN, SourceFormat.HTML)) DocumentParser.parse(bytes, f, "x")
        }
    }

    @Test
    fun `pdf cleaner fuzz`() {
        val rnd = Random(13)
        val lines = listOf("12", "Page 3 of 9", "THE BOOK TITLE", "Chapter 4", "impor-", "tant words follow here and continue.", "", "- 7 -", "xii", "ﬁnal ﬂow.", "A short line.", "   ", "I")
        repeat(1500) {
            val pages = (1..rnd.nextInt(0, 12)).map { n -> PdfTextCleaner.Page(n, (0 until rnd.nextInt(0, 30)).joinToString("\n") { lines[rnd.nextInt(lines.size)] }) }
            val outline = if (rnd.nextBoolean()) emptyList() else listOf(PdfTextCleaner.OutlineEntry("Part", rnd.nextInt(-2, 14)))
            val raw = PdfTextCleaner.clean(pages, outline)
            BookAssembler.assemble("p", "p", SourceFormat.PDF, raw)
        }
    }

    @Test
    fun `corrupted json never crashes on load`() {
        val book = numberedBook(50)
        val store = AnnotationStore()
        repeat(20) { store.bookmark(book, it * 2, label = "L$it"); store.highlight(book, it, it) }
        val json = JsonCodec.encodeAnnotations(store.all())
        val bookJson = JsonCodec.encodeBook(book)
        val rnd = Random(14)
        repeat(3000) { i ->
            val src = if (i % 2 == 0) json else bookJson
            val chars = src.toCharArray()
            repeat(1 + rnd.nextInt(5)) { chars[rnd.nextInt(chars.size)] = "{}[]\",:0a-".random(rnd) }
            val cut = String(chars).take(rnd.nextInt(src.length + 1))
            if (i % 2 == 0) JsonCodec.decodeAnnotations(cut) else JsonCodec.decodeBook(cut)
        }
    }

    @Test
    fun `listening marathon - 20k sentences with random seeks, speed and sleep`() {
        val book = numberedBook(20_000)
        val engine = FakeEngine()
        var now = 0L
        val c = PlaybackController(book, engine, clock = { now }, lookahead = 4)
        val rnd = Random(15)
        c.play()
        var spoken = 0
        val ms = measureTimeMillis {
            while (c.state.status != PlaybackStatus.FINISHED && spoken < 200_000) {
                now += 1_000
                when (rnd.nextInt(1000)) {
                    0 -> c.seekTo(rnd.nextInt(book.sentences.size))
                    1 -> c.setSpeed(0.5f + rnd.nextFloat() * 2.5f)
                    2 -> { c.pause(); c.play() }
                    3 -> c.rewindSeconds(rnd.nextInt(1, 120))
                    4 -> c.nextChapter()
                    5 -> c.setSleepTimer(1).also { c.cancelSleepTimer() }
                }
                if (engine.speakNext(c) != null) spoken++
                if (c.state.status == PlaybackStatus.PAUSED) c.play()
                val s = c.state
                assertTrue(s.position in book.sentences.indices)
            }
        }
        report("marathon: $spoken utterances", ms)
        assertEquals(PlaybackStatus.FINISHED, c.state.status)
    }

    @Test
    fun `spoiler safety holds at random positions for random questions`() {
        val book = numberedBook(3000)
        val index = PassageIndex(book)
        val builder = QaPromptBuilder(book, index)
        val rnd = Random(16)
        repeat(150) {
            val pos = rnd.nextInt(book.sentences.size - 1)
            val kind = QaKind.values()[rnd.nextInt(QaKind.values().size)]
            val question = (0 until 6).joinToString(" ") { "q${rnd.nextInt(book.sentences.size)}x" } + " marker token"
            val p = builder.build(QaRequest(kind, question, pos), fullBook = rnd.nextBoolean())
            val all = p.system + p.cachedContext.orEmpty() + p.user
            val leaked = Regex("""q(\d+)x appears""").findAll(all).map { it.groupValues[1].toInt() }.filter { it > pos }.toList()
            assertTrue(leaked.isEmpty(), "$kind at $pos leaked sentences $leaked")
        }
    }

    @Test
    fun `command parser throughput`() {
        val phrases = listOf("bookmark this", "highlight the last two sentences in green", "go back 30 seconds", "who is Tobin?", "set speed to one and a half", "note: remember this bit", "what does ephemeral mean", "sleep in 20 minutes", "banana")
        var n = 0
        val ms = measureTimeMillis { repeat(2_000) { phrases.forEach { CommandParser.parse(it); n++ } } }
        report("$n command parses", ms)
        assertTrue(ms < 10_000, "parser too slow: ${ms / n.toDouble()} ms per command")
    }
}
