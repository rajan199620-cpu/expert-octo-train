package com.earmark.core.assistant

import com.earmark.core.annotations.Annotation
import com.earmark.core.annotations.AnnotationKind
import com.earmark.core.annotations.AnnotationSource
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.commands.Command
import com.earmark.core.commands.RecapScope
import com.earmark.core.model.Book
import com.earmark.core.player.PlaybackController
import com.earmark.core.qa.QaKind
import com.earmark.core.qa.QaRequest
import com.earmark.core.qa.QuestionAnswerer
import java.util.Locale

data class AssistantReply(
    /** Spoken back to the listener; empty means "just do it" (navigation needs no chatter). */
    val speech: String,
    /** Resume reading once [speech] has been spoken. */
    val resume: Boolean,
    /** Sentences the answer is based on, offered as "jump to source" in the UI. */
    val sources: List<Int> = emptyList(),
    val annotation: Annotation? = null,
)

/**
 * Executes a parsed voice [Command] against the player, annotations and Q&A, and decides
 * what to say back. Blocking (Q&A does network I/O): call it off the main thread.
 */
class Assistant(
    private val book: Book,
    private val player: PlaybackController,
    private val annotations: AnnotationStore,
    private val answerer: QuestionAnswerer,
    private val spoilerSafe: () -> Boolean = { true },
) {
    fun handle(command: Command, anchor: Int, wasPlaying: Boolean): AssistantReply {
        val at = book.clampIndex(anchor)
        return try {
            execute(command, at, wasPlaying)
        } catch (e: Exception) {
            AssistantReply("Sorry, something went wrong: ${e.message ?: e.javaClass.simpleName}", resume = false)
        }
    }

    private fun execute(c: Command, at: Int, wasPlaying: Boolean): AssistantReply = when (c) {
        is Command.Bookmark -> {
            val idx = book.clampIndex(at + c.offset)
            val result = annotations.bookmark(book, idx, c.scope, c.label, AnnotationSource.VOICE)
            val what = when (c.scope) {
                BookmarkScope.SENTENCE -> if (c.offset < 0) "the previous sentence" else ""
                BookmarkScope.PAGE -> "page ${book.sentences[idx].page}"
                BookmarkScope.CHAPTER -> "chapter ${(book.chapterOf(idx)?.index ?: 0) + 1}"
            }
            val speech = when {
                result.created -> listOf("Bookmarked", what, c.label?.let { "as $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ") + "."
                c.label != null -> "Bookmark labelled ${c.label}."
                else -> "That's already bookmarked."
            }
            AssistantReply(speech, wasPlaying, annotation = result.annotation)
        }
        is Command.Highlight -> {
            val end = book.clampIndex(at + c.offset)
            val start = book.clampIndex(end - c.count + 1)
            val a = annotations.highlight(book, start, end, c.color, AnnotationSource.VOICE)
            val n = end - start + 1
            AssistantReply(if (n == 1) "Highlighted." else "Highlighted $n sentences.", wasPlaying, annotation = a)
        }
        is Command.AddNote -> if (c.text.isBlank()) {
            AssistantReply("What should the note say? Say note, followed by your note.", resume = false)
        } else {
            val a = annotations.addNote(book, at, c.text, AnnotationSource.VOICE)
            AssistantReply("Note saved.", wasPlaying, annotation = a)
        }
        Command.Undo -> AssistantReply(annotations.undoLast() ?: "There's nothing to undo.", wasPlaying)
        Command.Repeat -> { player.seekTo(at); AssistantReply("", resume = true) }
        is Command.Rewind -> {
            player.seekTo(player.state.position)
            if (c.seconds != null) player.rewindSeconds(c.seconds) else player.skipSentences(-(c.sentences ?: 1))
            AssistantReply("", resume = true)
        }
        is Command.Forward -> {
            if (c.seconds != null) player.forwardSeconds(c.seconds) else player.skipSentences(c.sentences ?: 1)
            AssistantReply("", resume = true)
        }
        Command.NextParagraph -> { player.nextParagraph(); AssistantReply("", true) }
        Command.PreviousParagraph -> { player.previousParagraph(); AssistantReply("", true) }
        Command.NextChapter -> {
            val before = book.chapterOf(player.state.position)?.index
            player.nextChapter()
            if (book.chapterOf(player.state.position)?.index == before) AssistantReply("This is the last chapter.", wasPlaying)
            else AssistantReply("", true)
        }
        Command.PreviousChapter -> { player.previousChapter(); AssistantReply("", true) }
        is Command.GoToChapter -> if (player.goToChapter(c.number)) AssistantReply("", true)
        else AssistantReply("There's no chapter ${c.number}. This book has ${book.chapters.size} ${plural(book.chapters.size, "chapter")}.", wasPlaying)
        is Command.GoToPage -> if (player.goToPage(c.number)) AssistantReply("Page ${c.number}.", true)
        else AssistantReply("There's no page ${c.number}. This book has ${book.pageCount} ${plural(book.pageCount, "page")}.", wasPlaying)
        is Command.GoToStart -> {
            player.seekTo(if (c.ofChapter) book.chapterOf(at)?.firstSentence ?: 0 else 0)
            AssistantReply("", true)
        }
        is Command.SetSpeed -> { player.setSpeed(c.speed); AssistantReply("Speed ${speedWords(player.state.speed)}.", wasPlaying) }
        is Command.ChangeSpeed -> {
            player.setSpeed(player.state.speed + c.delta)
            AssistantReply("Speed ${speedWords(player.state.speed)}.", wasPlaying)
        }
        Command.Pause -> AssistantReply("", resume = false)
        Command.Resume -> AssistantReply("", resume = true)
        is Command.SleepTimer -> { player.setSleepTimer(c.minutes); AssistantReply("Sleep timer set for ${c.minutes} ${plural(c.minutes, "minute")}.", wasPlaying) }
        Command.SleepAtChapterEnd -> { player.sleepAtEndOfChapter(); AssistantReply("I'll stop at the end of this chapter.", wasPlaying) }
        Command.CancelSleepTimer -> { player.cancelSleepTimer(); AssistantReply("Sleep timer off.", wasPlaying) }
        Command.WhereAmI -> AssistantReply(whereAmI(at), wasPlaying)
        is Command.Recap -> ask(
            when (c.scope) { RecapScope.RECENT -> QaKind.RECAP_RECENT; RecapScope.CHAPTER -> QaKind.RECAP_CHAPTER; RecapScope.BOOK -> QaKind.RECAP_BOOK },
            "", at, at, wasPlaying,
        )
        is Command.Define -> ask(QaKind.DEFINE, c.term, at, at, wasPlaying)
        is Command.Explain -> ask(QaKind.EXPLAIN, "", at, book.clampIndex(at + c.offset), wasPlaying)
        is Command.Ask -> ask(QaKind.QUESTION, c.question, at, at, wasPlaying)
        is Command.ListAnnotations -> AssistantReply(listAnnotations(c.kind), resume = false)
        Command.Help -> AssistantReply(HELP, resume = false)
        is Command.Unknown -> AssistantReply("Sorry, I didn't catch that. Say help to hear what I can do.", wasPlaying)
    }

    private fun ask(kind: QaKind, text: String, position: Int, target: Int, wasPlaying: Boolean): AssistantReply {
        val answer = answerer.answer(QaRequest(kind, text, position, target, spoilerSafe()))
        return AssistantReply(answer.speech, wasPlaying, answer.sourceSentences)
    }

    private fun whereAmI(at: Int): String {
        val s = book.sentences[at]
        val chapter = book.chapterOf(at)
        val pct = if (book.lastIndex <= 0) 0 else (at * 100L / book.lastIndex).toInt()
        val remainingChars = book.sentences.subList(at, book.sentences.size).sumOf { it.text.length.toLong() }
        val minutesLeft = (remainingChars / 15.0 / 60.0 / player.state.speed).toInt()
        val pageWord = if (book.pagesAreVirtual) "about page" else "page"
        val left = when {
            minutesLeft >= 90 -> "about ${Math.round(minutesLeft / 60.0)} hours left"
            minutesLeft >= 1 -> "about $minutesLeft ${plural(minutesLeft, "minute")} left"
            else -> "almost done"
        }
        val chapterPart = chapter?.let { ", chapter ${it.index + 1} of ${book.chapters.size}, ${it.title}" }.orEmpty()
        return "You're on $pageWord ${s.page} of ${book.pageCount}$chapterPart. That's $pct percent through, $left."
    }

    private fun listAnnotations(kind: String?): String {
        val wanted = when (kind) {
            "bookmark" -> setOf(AnnotationKind.BOOKMARK)
            "highlight" -> setOf(AnnotationKind.HIGHLIGHT)
            "note" -> setOf(AnnotationKind.NOTE)
            else -> AnnotationKind.values().toSet()
        }
        val items = annotations.forBook(book.id).filter { it.kind in wanted && !it.orphaned }
        val noun = kind ?: "annotation"
        if (items.isEmpty()) return "You have no ${noun}s in this book yet."
        val recent = items.sortedByDescending { it.createdAtMillis }.take(5).sortedBy { it.start.sentenceIndex }
        val parts = recent.joinToString(" ") { a ->
            val quote = (if (a.kind == AnnotationKind.NOTE) a.note.orEmpty() else a.start.quote).take(80).trimEnd()
            val label = a.label?.let { "$it: " }.orEmpty()
            "Page ${a.start.page}: $label$quote."
        }
        val more = if (items.size > recent.size) " Those are the latest ${recent.size}." else ""
        return "You have ${items.size} ${plural(items.size, noun)}. $parts$more"
    }

    companion object {
        const val HELP = "You can say: bookmark this, bookmark this page, highlight that, note followed by your note, " +
            "undo, repeat that, go back 30 seconds, next chapter, go to page 40, faster, slower, sleep in 20 minutes, " +
            "where am I, recap this chapter, what does a word mean, explain that, or ask any question about the book."

        fun speedWords(speed: Float): String {
            val s = if (speed == Math.floor(speed.toDouble()).toFloat()) speed.toInt().toString() else String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
            return if (s == "1") "normal" else "$s times"
        }

        private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
    }
}
