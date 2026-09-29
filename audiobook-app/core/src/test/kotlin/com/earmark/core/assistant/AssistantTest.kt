package com.earmark.core.assistant

import com.earmark.core.TestBooks
import com.earmark.core.annotations.AnnotationKind
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.commands.CommandParser
import com.earmark.core.player.FakeEngine
import com.earmark.core.player.PlaybackController
import com.earmark.core.player.PlaybackStatus
import com.earmark.core.qa.QaAnswer
import com.earmark.core.qa.QaKind
import com.earmark.core.qa.QaRequest
import com.earmark.core.qa.QuestionAnswerer
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantTest {
    private val book = TestBooks.novel()
    private val engine = FakeEngine()
    private val player = PlaybackController(book, engine, startPosition = 10)
    private val store = AnnotationStore()
    private val asked = ArrayList<QaRequest>()
    private val answerer = QuestionAnswerer { r -> asked += r; QaAnswer("Answer for ${r.kind}.", listOf(3), true) }
    private val assistant = Assistant(book, player, store, answerer)

    private fun say(text: String, anchor: Int = 10, playing: Boolean = true) = assistant.handle(CommandParser.parse(text), anchor, playing)

    @Test
    fun `voice bookmark, highlight, note, undo round trip`() {
        assertEquals("Bookmarked.", say("bookmark this").speech)
        assertEquals("That's already bookmarked.", say("bookmark this").speech)
        assertEquals(10, store.forBook(book.id).single().start.sentenceIndex)
        assertTrue(say("bookmark this").resume, "listening resumes after a quick command")

        assertEquals("Highlighted 2 sentences.", say("highlight the last two sentences").speech)
        assertEquals(9..10, store.forBook(book.id).single { it.kind == AnnotationKind.HIGHLIGHT }.range)

        assertEquals("Note saved.", say("note: Tobin is suspicious").speech)
        assertEquals("Tobin is suspicious", store.forBook(book.id).single { it.kind == AnnotationKind.NOTE }.note)

        assertContains(say("undo").speech, "note")
        assertFalse(store.forBook(book.id).any { it.kind == AnnotationKind.NOTE })
    }

    @Test
    fun `page bookmark reports the page and previous-sentence offset works`() {
        assertEquals("Bookmarked page ${book.sentences[10].page}.", say("bookmark this page").speech)
        assertEquals(BookmarkScope.PAGE, store.forBook(book.id).single().scope)
        say("bookmark the previous sentence")
        assertTrue(store.forBook(book.id).any { it.scope == BookmarkScope.SENTENCE && it.start.sentenceIndex == 9 })
    }

    @Test
    fun `navigation replies and impossible targets`() {
        say("go to chapter 2")
        assertEquals(book.chapters[1].firstSentence, player.state.position)
        assertContains(say("go to chapter 9").speech, "This book has 3 chapters")
        assertContains(say("jump to page 999").speech, "There's no page 999")
        say("repeat that", anchor = 5)
        assertEquals(5, player.state.position)
        say("go back two sentences")
        assertEquals(3, player.state.position)
        player.seekTo(book.chapters[2].firstSentence + 1)
        assertEquals("This is the last chapter.", say("next chapter").speech)
    }

    @Test
    fun `speed replies are spoken naturally`() {
        assertEquals("Speed 1.5 times.", say("speed 1.5").speech)
        assertEquals("Speed 1.75 times.", say("faster").speech)
        assertEquals("Speed normal.", say("normal speed").speech)
        assertEquals("Speed 3 times.", say("speed ten").speech)
    }

    @Test
    fun `questions go to the answerer with spoiler safety and anchor`() {
        val r = say("Who is Tobin?", anchor = 7)
        assertEquals("Answer for QUESTION.", r.speech)
        assertEquals(listOf(3), r.sources)
        assertEquals(7, asked.single().position)
        assertTrue(asked.single().spoilerSafe)
        say("explain the last sentence", anchor = 7)
        assertEquals(6, asked.last().target)
        say("what does wrecker mean")
        assertEquals(QaKind.DEFINE, asked.last().kind)
        assertEquals("wrecker", asked.last().text)
        say("recap")
        assertEquals(QaKind.RECAP_CHAPTER, asked.last().kind)
    }

    @Test
    fun `where am I, sleep timer, help, unknown and pause`() {
        val where = say("where am I").speech
        assertContains(where, "about page ${book.sentences[10].page}")
        assertContains(where, "chapter 2 of 3, The Storm")
        assertContains(say("sleep in 15 minutes").speech, "15 minutes")
        assertTrue(player.state.sleepDeadlineMillis != null)
        assertEquals("Sleep timer off.", say("cancel the sleep timer").speech)
        assertFalse(say("help").resume)
        assertContains(say("banana").speech, "didn't catch")
        assertFalse(say("pause").resume)
        assertTrue(say("resume", playing = false).resume)
    }

    @Test
    fun `listing annotations reads the latest few`() {
        assertContains(say("read my bookmarks").speech, "no bookmarks")
        for (i in 0..7) store.bookmark(book, i * 3)
        val speech = say("read my bookmarks").speech
        assertContains(speech, "You have 8 bookmarks.")
        assertContains(speech, "Those are the latest 5.")
    }

    @Test
    fun `answerer crashes become a spoken apology, not an app crash`() {
        val broken = Assistant(book, player, store, { throw IllegalStateException("boom") })
        val r = broken.handle(CommandParser.parse("Who is Mira?"), 3, true)
        assertContains(r.speech, "something went wrong")
        assertFalse(r.resume)
    }

    @Test
    fun `a full listening session - play, interrupt, command, resume`() {
        player.play()
        repeat(3) { engine.speakNext(player) }
        val anchor = player.anchorForInteraction()
        player.pause()
        val reply = assistant.handle(CommandParser.parse("highlight that"), anchor, wasPlaying = true)
        assertEquals("Highlighted.", reply.speech)
        if (reply.resume) player.play()
        assertEquals(PlaybackStatus.PLAYING, player.state.status)
        assertEquals(anchor, store.forBook(book.id).single().start.sentenceIndex)
    }
}
