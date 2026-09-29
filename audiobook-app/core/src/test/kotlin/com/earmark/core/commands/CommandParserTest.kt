package com.earmark.core.commands

import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.commands.Command.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandParserTest {
    data class Case(val said: String, val expected: Command) {
        override fun toString() = said
    }

    companion object {
        private infix fun String.means(c: Command) = Case(this, c)

        @JvmStatic
        fun cases() = listOf(
            // Bookmarks
            "bookmark this" means Bookmark(),
            "Bookmark this." means Bookmark(),
            "Hey Earmark, bookmark this please" means Bookmark(),
            "can you bookmark that" means Bookmark(),
            "bookmark" means Bookmark(),
            "add a bookmark" means Bookmark(),
            "save this" means Bookmark(),
            "mark this spot" means Bookmark(),
            "remember this" means Bookmark(),
            "note this" means Bookmark(),
            "dog ear this page" means Bookmark(BookmarkScope.PAGE),
            "bookmark this page" means Bookmark(BookmarkScope.PAGE),
            "bookmark the page" means Bookmark(BookmarkScope.PAGE),
            "add a bookmark on this page" means Bookmark(BookmarkScope.PAGE),
            "bookmark this chapter" means Bookmark(BookmarkScope.CHAPTER),
            "bookmark the last sentence" means Bookmark(offset = -1),
            "bookmark the previous sentence" means Bookmark(offset = -1),
            "bookmark this as Tobin's lie" means Bookmark(label = "Tobin's lie"),
            "book mark this" means Bookmark(),
            "high light that" means Highlight(),
            "go to the next chapter" means NextChapter,
            "go to page forty-two" means GoToPage(42),
            "speed one point two five" means SetSpeed(1.25f),
            "1.5 x" means SetSpeed(1.5f),
            "set the speed to 2" means SetSpeed(2f),
            "slower please" means ChangeSpeed(-0.25f),
            "stop reading at the end of this chapter" means SleepAtChapterEnd,
            "what chapter am I in" means WhereAmI,
            // Highlights
            "highlight this" means Highlight(),
            "highlight that sentence" means Highlight(),
            "highlight" means Highlight(),
            "highlight the last sentence" means Highlight(offset = -1),
            "highlight the last two sentences" means Highlight(count = 2),
            "highlight the last 3 sentences" means Highlight(count = 3),
            "highlight that in green" means Highlight(color = HighlightColor.GREEN),
            "underline this" means Highlight(),
            // Notes
            "note: the author contradicts chapter two" means AddNote("the author contradicts chapter two"),
            "Add a note that Tobin lied about the lamp" means AddNote("Tobin lied about the lamp"),
            "take a note, check this against Section 302" means AddNote("check this against Section 302"),
            "remember that the guard was bribed" means AddNote("the guard was bribed"),
            "note" means AddNote(""),
            // Undo
            "undo" means Undo,
            "undo that" means Undo,
            "delete that bookmark" means Undo,
            "never mind" means Undo,
            "remove the last highlight" means Undo,
            // Repeat
            "repeat that" means Repeat,
            "say that again" means Repeat,
            "what did he say" means Repeat,
            "what?" means Repeat,
            "come again" means Repeat,
            // Navigation
            "go back" means Rewind(sentences = 1),
            "go back 30 seconds" means Rewind(seconds = 30),
            "go back three sentences" means Rewind(sentences = 3),
            "go back a couple of sentences" means Rewind(sentences = 2),
            "rewind" means Rewind(seconds = 15),
            "rewind a bit" means Rewind(seconds = 15),
            "rewind 2 minutes" means Rewind(seconds = 120),
            "previous sentence" means Rewind(sentences = 1),
            "skip ahead" means Forward(seconds = 30),
            "skip forward 10 seconds" means Forward(seconds = 10),
            "next sentence" means Forward(sentences = 1),
            "next paragraph" means NextParagraph,
            "skip this paragraph" means NextParagraph,
            "next chapter" means NextChapter,
            "skip this chapter" means NextChapter,
            "previous chapter" means PreviousChapter,
            "go back a chapter" means PreviousChapter,
            "go to chapter 5" means GoToChapter(5),
            "chapter twelve" means GoToChapter(12),
            "go to chapter IV" means GoToChapter(4),
            "jump to page 42" means GoToPage(42),
            "page one hundred and twenty" means GoToPage(120),
            "go back to the beginning of the chapter" means GoToStart(ofChapter = true),
            "start from the beginning of the book" means GoToStart(ofChapter = false),
            "restart the chapter" means GoToStart(ofChapter = true),
            // Speed
            "faster" means ChangeSpeed(0.25f),
            "read faster please" means ChangeSpeed(0.25f),
            "slow down" means ChangeSpeed(-0.25f),
            "a bit slower" means ChangeSpeed(-0.1f),
            "much faster" means ChangeSpeed(0.5f),
            "speed 1.5" means SetSpeed(1.5f),
            "set speed to one and a half" means SetSpeed(1.5f),
            "1.25x" means SetSpeed(1.25f),
            "two times speed" means SetSpeed(2f),
            "normal speed" means SetSpeed(1f),
            // Playback
            "pause" means Pause,
            "stop" means Pause,
            "hold on" means Pause,
            "resume" means Resume,
            "continue" means Resume,
            "keep reading" means Resume,
            "play" means Resume,
            // Sleep
            "sleep in 20 minutes" means SleepTimer(20),
            "set a sleep timer for 30 minutes" means SleepTimer(30),
            "stop in an hour" means SleepTimer(60),
            "stop at the end of the chapter" means SleepAtChapterEnd,
            "finish this chapter and stop" means SleepAtChapterEnd,
            "cancel the sleep timer" means CancelSleepTimer,
            // Info & assistant
            "where am I" means WhereAmI,
            "what page is this" means WhereAmI,
            "how much is left" means WhereAmI,
            "help" means Help,
            "what can I say" means Help,
            "recap" means Recap(RecapScope.CHAPTER),
            "summarize this chapter" means Recap(RecapScope.CHAPTER),
            "what happened so far" means Recap(RecapScope.BOOK),
            "catch me up" means Recap(RecapScope.CHAPTER),
            "summarize the last few minutes" means Recap(RecapScope.RECENT),
            "what does ephemeral mean" means Define("ephemeral"),
            "what does the word wrecker mean" means Define("wrecker"),
            "define culpable homicide" means Define("culpable homicide"),
            "what does that mean" means Explain(),
            "explain that" means Explain(),
            "explain the last sentence" means Explain(-1),
            "I don't understand" means Explain(),
            "read my bookmarks" means ListAnnotations("bookmark"),
            "list all my highlights" means ListAnnotations("highlight"),
        )

        @JvmStatic
        fun questions() = listOf(
            "Who is Tobin?",
            "why did Tobin break the lamp",
            "what does the author mean by wreckers in this context and why does it matter",
            "Is the captain going to stop the ship?",
            "does bookmarking cost money",
            "how old is Mira",
            "What happened to the keeper?",
            "tell me more about the lighthouse",
            "compare this with the previous chapter",
            "explain the difference between murder and culpable homicide",
            "can you tell me who the guard is",
        )
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun parses(case: Case) {
        assertEquals(case.expected, CommandParser.parse(case.said))
    }

    @ParameterizedTest
    @MethodSource("questions")
    fun `questions that contain command words are still questions`(q: String) {
        val c = CommandParser.parse(q)
        assertIs<Ask>(c, "'$q' -> $c")
    }

    @Test
    fun `mumbles are unknown, not questions`() {
        assertIs<Unknown>(CommandParser.parse("uh"))
        assertIs<Unknown>(CommandParser.parse(""))
        assertIs<Unknown>(CommandParser.parse("banana"))
    }

    @Test
    fun `fuzz - never throws`() {
        val rnd = Random(3)
        val words = listOf("bookmark", "highlight", "go", "back", "to", "chapter", "page", "the", "last", "two", "x", "speed", "note", "what", "does", "mean", "?", "1.5", "minutes", "in", "a", "of", "undo", "sleep", "stop", "-", "", ":", "ⅸ", "🔖")
        repeat(5000) {
            val s = (0 until rnd.nextInt(0, 9)).joinToString(" ") { words[rnd.nextInt(words.size)] }
            CommandParser.parse(s)
        }
    }
}
