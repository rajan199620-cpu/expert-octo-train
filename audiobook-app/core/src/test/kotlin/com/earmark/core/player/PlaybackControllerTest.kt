package com.earmark.core.player

import com.earmark.core.TestBooks
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records engine calls and lets tests play the role of the TTS engine's callbacks. */
class FakeEngine : SpeechEngine {
    val queue = ArrayList<Utterance>()
    val calls = ArrayList<String>()
    var lastSpeed = 1f

    @Synchronized
    override fun speak(utterances: List<Utterance>, flush: Boolean) {
        if (flush) queue.clear()
        queue += utterances
        calls += (if (flush) "flush" else "add") + utterances.map { it.sentenceIndex }
    }

    @Synchronized
    override fun stop() { queue.clear(); calls += "stop" }

    override fun setSpeed(speed: Float) { lastSpeed = speed }

    /** Speaks the head of the queue: start + done callbacks. */
    fun speakNext(controller: PlaybackController): Utterance? {
        val u = synchronized(this) { queue.removeFirstOrNull() } ?: return null
        controller.onUtteranceStarted(u.id)
        controller.onUtteranceDone(u.id)
        return u
    }
}

class PlaybackControllerTest {
    private val book = TestBooks.novel() // 3 chapters x 9 sentences + headings
    private var now = 1_000_000L

    private fun controller(engine: FakeEngine, start: Int = 0, lookahead: Int = 3) =
        PlaybackController(book, engine, clock = { now }, lookahead = lookahead, startPosition = start)

    @Test
    fun `play queues the current sentence plus lookahead and tops up as sentences start`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        assertEquals(listOf(0, 1, 2, 3), e.queue.map { it.sentenceIndex })
        e.speakNext(c) // sentence 0 starts and ends: the highlight moves on and the queue tops up
        assertEquals(1, c.state.position)
        assertEquals(listOf(1, 2, 3, 4), e.queue.map { it.sentenceIndex })
        e.speakNext(c)
        assertEquals(2, c.state.position)
        assertEquals(listOf(2, 3, 4, 5), e.queue.map { it.sentenceIndex })
    }

    @Test
    fun `reads the whole book to the end and finishes`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        val spoken = ArrayList<Int>()
        while (true) spoken += (e.speakNext(c) ?: break).sentenceIndex
        assertEquals(book.sentences.indices.toList(), spoken)
        assertEquals(PlaybackStatus.FINISHED, c.state.status)
    }

    @Test
    fun `stale callbacks from before a seek are ignored`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        val stale = e.queue.first()
        c.seekTo(20)
        c.onUtteranceStarted(stale.id)
        c.onUtteranceDone(stale.id)
        assertEquals(20, c.state.position)
        assertEquals(20, e.queue.first().sentenceIndex)
    }

    @Test
    fun `pause keeps position and resume restarts the same sentence`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        e.speakNext(c); e.speakNext(c)
        val q = e.queue.first()
        c.onUtteranceStarted(q.id) // sentence 2 is playing
        c.pause()
        assertEquals(2, c.state.position)
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        assertTrue(e.queue.isEmpty())
        c.play()
        assertEquals(2, e.queue.first().sentenceIndex)
    }

    @Test
    fun `mic anchor points at the previous sentence during the first moments of a new one`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        e.speakNext(c)
        val u = e.queue.first()
        c.onUtteranceStarted(u.id) // sentence 1 started at `now`
        now += 500
        assertEquals(0, c.anchorForInteraction())
        now += 2000
        assertEquals(1, c.anchorForInteraction())
        c.pause()
        assertEquals(1, c.anchorForInteraction(), "no grace when paused")
    }

    @Test
    fun `sleep timer stops at a sentence boundary after the deadline`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        c.setSleepTimer(1)
        e.speakNext(c)
        now += 61_000
        val u = e.speakNext(c)!!
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        assertEquals(u.sentenceIndex + 1, c.state.position)
        assertNull(c.state.sleepDeadlineMillis)
        assertTrue(e.queue.isEmpty())
    }

    @Test
    fun `stop at end of chapter never queues the next chapter and follows seeks`() {
        val e = FakeEngine()
        val c = controller(e, lookahead = 50)
        c.play()
        c.sleepAtEndOfChapter()
        val ch0 = book.chapters[0]
        assertTrue(e.queue.all { it.sentenceIndex <= ch0.lastSentence })
        while (e.speakNext(c) != null) Unit
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        assertEquals(book.chapters[1].firstSentence, c.state.position)

        c.sleepAtEndOfChapter()
        c.play()
        c.seekTo(book.chapters[2].firstSentence)
        assertTrue(e.queue.all { it.sentenceIndex in book.chapters[2].firstSentence..book.chapters[2].lastSentence })
    }

    @Test
    fun `speed is clamped, rounded and re-speaks the current sentence immediately`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        e.speakNext(c)
        c.setSpeed(9f)
        assertEquals(3.0f, c.state.speed)
        assertEquals(3.0f, e.lastSpeed)
        assertEquals(c.state.position, e.queue.first().sentenceIndex)
        c.setSpeed(0.1f)
        assertEquals(0.5f, c.state.speed)
        c.setSpeed(1.333f)
        assertEquals(1.33f, c.state.speed)
    }

    @Test
    fun `chapter, page and paragraph navigation`() {
        val e = FakeEngine()
        val c = controller(e)
        c.nextChapter()
        assertEquals(book.chapters[1].firstSentence, c.state.position)
        c.skipSentences(5)
        c.previousChapter()
        assertEquals(book.chapters[1].firstSentence, c.state.position, "restarts current chapter when well into it")
        c.previousChapter()
        assertEquals(book.chapters[0].firstSentence, c.state.position, "goes back a chapter when at its start")
        assertFalse(c.goToChapter(99))
        assertTrue(c.goToChapter(3))
        assertEquals(book.chapters[2].firstSentence, c.state.position)
        assertFalse(c.goToPage(0))
        assertFalse(c.goToPage(book.pageCount + 1))
        assertTrue(c.goToPage(1))
        c.seekTo(2)
        c.nextParagraph()
        assertEquals(book.sentences[2].paragraph + 1, book.sentences[c.state.position].paragraph)
        c.seekTo(-50)
        assertEquals(0, c.state.position)
        c.seekTo(10_000)
        assertEquals(book.lastIndex, c.state.position)
        c.nextChapter() // already in last chapter: no-op
        assertEquals(book.lastIndex, c.state.position)
    }

    @Test
    fun `time-based skipping moves roughly the right number of sentences`() {
        val e = FakeEngine()
        val c = controller(e, start = 20)
        c.rewindSeconds(5)
        val back = 20 - c.state.position
        assertTrue(back in 1..3, "5 s is 1-3 short sentences, moved $back")
        c.rewindSeconds(10_000)
        assertEquals(0, c.state.position)
        c.forwardSeconds(10_000)
        assertEquals(book.lastIndex, c.state.position)
    }

    @Test
    fun `utterance error pauses on the failed sentence instead of skipping it`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        val u = e.queue[1]
        c.onUtteranceStarted(e.queue[0].id)
        c.onUtteranceError(u.id)
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        assertEquals(1, c.state.position)
    }

    @Test
    fun `speech text is normalized and headings get a pause`() {
        val raw = com.earmark.core.parse.RawDocument(
            "T", null,
            listOf(com.earmark.core.parse.RawSection("Ch", listOf(
                com.earmark.core.parse.RawBlock("CHAPTER IV THE ARRIVAL", isHeading = true),
                com.earmark.core.parse.RawBlock("Dr. Rao paid ₹500 [12]."),
            ))),
        )
        val b = com.earmark.core.parse.BookAssembler.assemble("h", "h", com.earmark.core.model.SourceFormat.TEXT, raw)
        val c = PlaybackController(b, FakeEngine())
        assertEquals("Chapter 4 the arrival.", c.speechTextFor(0))
        assertEquals("Doctor Rao paid 500 rupees.", c.speechTextFor(1))
    }

    @Test
    fun `play after finishing starts over`() {
        val e = FakeEngine()
        val c = controller(e, start = book.lastIndex)
        c.play()
        while (e.speakNext(c) != null) Unit
        assertEquals(PlaybackStatus.FINISHED, c.state.status)
        c.play()
        assertEquals(0, e.queue.first().sentenceIndex)
    }

    @Test
    fun `garbage and out-of-range utterance ids are ignored`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        c.onUtteranceStarted("nonsense")
        c.onUtteranceStarted("1:99999")
        c.onUtteranceDone(":")
        assertEquals(0, c.state.position)
    }

    @Test
    fun `concurrent callbacks and user actions do not corrupt state`() {
        val e = FakeEngine()
        val c = controller(e)
        c.play()
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(4000)
        repeat(4000) { i ->
            pool.execute {
                try {
                    when (i % 8) {
                        0 -> c.seekTo(i % book.sentences.size)
                        1 -> e.speakNext(c)
                        2 -> c.togglePlayPause()
                        3 -> c.setSpeed(1f + (i % 5) / 4f)
                        4 -> c.anchorForInteraction()
                        5 -> c.skipSentences(if (i % 2 == 0) 3 else -3)
                        6 -> c.nextParagraph()
                        else -> e.speakNext(c)
                    }
                } finally { latch.countDown() }
            }
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        val s = c.state
        assertTrue(s.position in book.sentences.indices)
        assertTrue(s.speed in 0.5f..3f)
    }

    @Test
    fun `highlight keeps moving with engines that never report sentence starts`() {
        val e = FakeEngine()
        val c = controller(e)
        val seen = ArrayList<Int>()
        c.listener = { seen += it.position }
        c.play()
        while (true) {
            val u = synchronized(e) { e.queue.removeFirstOrNull() } ?: break
            c.onUtteranceDone(u.id) // no onUtteranceStarted at all
        }
        assertEquals(PlaybackStatus.FINISHED, c.state.status)
        assertEquals(book.sentences.indices.toList(), seen.distinct().filter { it in book.sentences.indices }.sorted().distinct())
    }

    @Test
    fun `word ranges are forwarded, recover a lost start, and stale ones are dropped`() {
        val e = FakeEngine()
        val c = controller(e)
        val ranges = ArrayList<Triple<Int, Int, Int>>()
        c.rangeListener = { s, a, b -> ranges += Triple(s, a, b) }
        c.onUtteranceRange("1:0", 0, 3) // not playing yet: ignored
        c.play()
        val first = e.queue[0]
        val second = e.queue[1]
        c.onUtteranceStarted(first.id)
        c.onUtteranceRange(first.id, 5, 9)
        c.onUtteranceRange(second.id, 0, 4) // start of sentence 1 was never reported
        assertEquals(1, c.state.position)
        c.seekTo(10)
        c.onUtteranceRange(first.id, 12, 15) // from before the seek
        assertEquals(listOf(Triple(0, 5, 9), Triple(1, 0, 4)), ranges)
    }

    @Test
    fun `utterances carry heading, paragraph and chapter boundaries for pauses`() {
        val e = FakeEngine()
        val c = controller(e, lookahead = 50)
        c.play()
        val byIndex = e.queue.associateBy { it.sentenceIndex }
        val lastOfFirstParagraph = book.paragraphRange(0).last
        assertTrue(byIndex.getValue(lastOfFirstParagraph).endsParagraph)
        assertFalse(byIndex.getValue(0).endsParagraph)
        assertTrue(byIndex.getValue(book.chapters[0].lastSentence).endsChapter)
    }

    @Test
    fun `empty book is inert`() {
        val empty = TestBooks.book("Empty" to listOf("   "))
        val e = FakeEngine()
        val c = PlaybackController(empty, e)
        c.play(); c.seekTo(5); c.nextChapter(); c.rewindSeconds(10)
        assertEquals(PlaybackStatus.FINISHED, c.state.status)
        assertTrue(e.calls.isEmpty())
    }
}
