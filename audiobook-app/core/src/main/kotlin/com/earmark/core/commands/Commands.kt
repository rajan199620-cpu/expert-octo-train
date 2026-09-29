package com.earmark.core.commands

import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.text.NumberWords

sealed interface Command {
    /** [offset] 0 = the anchored sentence, -1 = the one before it. */
    data class Bookmark(val scope: BookmarkScope = BookmarkScope.SENTENCE, val offset: Int = 0, val label: String? = null) : Command
    /** Highlights [count] sentences ending at anchor+[offset]. */
    data class Highlight(val offset: Int = 0, val count: Int = 1, val color: HighlightColor = HighlightColor.YELLOW) : Command
    data class AddNote(val text: String) : Command
    data object Undo : Command
    data object Repeat : Command
    data class Rewind(val sentences: Int? = null, val seconds: Int? = null) : Command
    data class Forward(val sentences: Int? = null, val seconds: Int? = null) : Command
    data object NextParagraph : Command
    data object PreviousParagraph : Command
    data object NextChapter : Command
    data object PreviousChapter : Command
    data class GoToChapter(val number: Int) : Command
    data class GoToPage(val number: Int) : Command
    data class GoToStart(val ofChapter: Boolean) : Command
    data class SetSpeed(val speed: Float) : Command
    data class ChangeSpeed(val delta: Float) : Command
    data object Pause : Command
    data object Resume : Command
    data class SleepTimer(val minutes: Int) : Command
    data object SleepAtChapterEnd : Command
    data object CancelSleepTimer : Command
    data object WhereAmI : Command
    data class Recap(val scope: RecapScope) : Command
    data class Define(val term: String) : Command
    data class Explain(val offset: Int = 0) : Command
    data class Ask(val question: String) : Command
    data class ListAnnotations(val kind: String?) : Command
    data object Help : Command
    data class Unknown(val heard: String) : Command
}

enum class RecapScope { RECENT, CHAPTER, BOOK }

/**
 * Maps a speech-recogniser transcript to a [Command].
 *
 * Deliberately rules-based rather than LLM-based: it runs offline in microseconds, so
 * "bookmark this" is instant and free, and only real questions go to the network. The order
 * of rules matters: questions that merely contain a command word ("what does bookmark
 * mean?", "is he going to stop?") must not trigger that command.
 */
object CommandParser {
    private val FILLERS = listOf(
        "hey earmark", "ok earmark", "okay earmark", "earmark", "hey", "okay", "ok", "please", "um", "uh", "so",
        "can you", "could you", "would you", "will you", "i want to", "i want you to", "i'd like to", "i would like to",
        "let's", "lets", "go ahead and", "just", "now", "and",
    )
    private val TRAILING = listOf("please", "for me", "now", "thanks", "thank you")
    private val NUM = NumberWords.PATTERN
    private val BOOKMARKISH = setOf("this", "that", "here", "it", "this spot", "this place", "this page", "this sentence", "this down", "that down", "this one")

    private val QUESTION_START = Regex("""^(who|whom|whose|what|what's|whats|when|where|why|how|which|is|are|was|were|does|do|did|can|could|should|would|will|has|have|had|isn't|aren't|doesn't|didn't)\b""")
    private val COLORS = mapOf(
        "yellow" to HighlightColor.YELLOW, "green" to HighlightColor.GREEN, "blue" to HighlightColor.BLUE,
        "pink" to HighlightColor.PINK, "red" to HighlightColor.PINK, "purple" to HighlightColor.BLUE,
    )

    fun parse(transcript: String): Command {
        val original = transcript.trim()
        val t = normalize(original)
        if (t.isEmpty()) return Command.Unknown(original)
        rule(t, """^(?:add |take |make |write )?(?:a )?note(?: down)?(?:[:,]| that| saying)?(?: (.+))?$""")?.let {
            val body = it.groups[1]?.value.orEmpty()
            if (body in BOOKMARKISH) return Command.Bookmark()
            return Command.AddNote(noteText(original))
        }
        rule(t, """^remember (?:that )?(.+)$""")?.let {
            if (it.groupValues[1] !in BOOKMARKISH) return Command.AddNote(noteText(original))
        }
        if (rule(t, """^(?:undo|undo that|cancel that|never mind|nevermind|scratch that|oops|that's wrong|that was wrong|(?:delete|remove) (?:that|the last|my last)(?: (?:bookmark|highlight|note))?)$""") != null) return Command.Undo

        if (rule(t, """^(?:where am i|where are we|what page(?: is this| am i on| are we on)?|what chapter(?: is this| am i in| am i on| are we in)?|how far (?:am i|along am i)|how much is left)$""") != null) return Command.WhereAmI
        if (rule(t, """^(?:help|what can (?:i|you) (?:say|do)|commands|what are the commands)$""") != null) return Command.Help

        rule(t, """^(?:what does|what do) (?:the (?:word|term|phrase) )?(.+?) mean$""")?.let { m ->
            val term = m.groupValues[1]
            return if (term in setOf("this", "that", "it", "this sentence", "that sentence", "he", "she", "they")) Command.Explain() else Command.Define(term)
        }
        rule(t, """^(?:define|definition of|meaning of|what is the meaning of|what's the meaning of|what is the definition of) (?:the (?:word|term) )?(.+)$""")?.let {
            return Command.Define(it.groupValues[1])
        }
        rule(t, """^(?:explain|clarify|simplify|break down)(?: (this|that|the last|the previous))?(?: (?:sentence|line|part|passage|bit|paragraph))?(?: to me)?(?: again)?$""")?.let {
            return Command.Explain(if (it.groupValues[1] in setOf("the last", "the previous")) -1 else 0)
        }
        if (rule(t, """^(?:i don't understand|i didn't understand|i didn't get that|i don't get it|what did (?:that|this|he|she|they|it) mean|what does that mean|say that in simple words)$""") != null) return Command.Explain()

        rule(t, """^(?:recap|summari[sz]e|give me a (?:recap|summary)|summary|catch me up|remind me what happened|what happened|what has happened|what's happened)(?: (?:of |in )?(?:so far|this chapter|the chapter|the book|the story|the last (?:few )?(?:minutes|pages|bit)|until now|up to now|recently))*$""")?.let {
            val scope = when {
                t.contains("book") || t.contains("story") || t.contains("so far") || t.contains("until now") || t.contains("up to now") -> RecapScope.BOOK
                t.contains("chapter") -> RecapScope.CHAPTER
                t.contains("last") || t.contains("recently") -> RecapScope.RECENT
                else -> RecapScope.CHAPTER
            }
            return Command.Recap(scope)
        }
        rule(t, """^(?:read|list|show|what are|tell me|read out|go through) (?:me )?(?:my |all |all my |all the )?(bookmarks|highlights|notes|annotations)$""")?.let {
            return Command.ListAnnotations(it.groupValues[1].removeSuffix("s"))
        }

        if (rule(t, """^(?:repeat|repeat that|repeat the last (?:sentence|line)|say that again|read that again|again|one more time|come again|pardon|sorry|what|replay|replay that|what did (?:he|she|it|they|you) (?:just )?say|what was that)$""") != null) return Command.Repeat

        // Real questions go to the assistant before command keywords are considered.
        if (QUESTION_START.containsMatchIn(t) && !isCommandPhrasedAsQuestion(t)) return Command.Ask(original)

        bookmark(t, original)?.let { return it }
        highlight(t)?.let { return it }

        sleep(t)?.let { return it }
        navigation(t)?.let { return it }
        speed(t)?.let { return it }

        if (rule(t, """^(?:pause|stop|stop reading|stop playing|hold on|hold it|wait|shut up|be quiet|quiet|silence|pause (?:it|reading|playback))$""") != null) return Command.Pause
        if (rule(t, """^(?:resume|play|continue|go on|keep going|keep reading|carry on|start|start reading|read|unpause|go)$""") != null) return Command.Resume

        return if (t.split(' ').size >= 3) Command.Ask(original) else Command.Unknown(original)
    }

    private fun isCommandPhrasedAsQuestion(t: String): Boolean =
        Regex("""^(?:can|could|would|will) (?:you )?(?:please )?(bookmark|highlight|pause|stop|repeat|go|skip|rewind|read|note|remember|explain|define|summari[sz]e|recap|slow|speed)""").containsMatchIn(t)

    private fun bookmark(t: String, original: String): Command? {
        rule(t, """^(?:add |make |set |drop |place |create |put )?(?:a )?bookmark (?:on|for|to|at) (?:this|the) (page|chapter)$""")?.let {
            return Command.Bookmark(if (it.groupValues[1] == "page") BookmarkScope.PAGE else BookmarkScope.CHAPTER)
        }
        val m = rule(t, """^(?:add |make |set |drop |place |create |put )?(?:a )?(?:bookmark|save|mark|flag|star|dog ?ear|earmark|remember)(?: (?:this|that|it|here|there|this spot|this place|the))?(?: (?:the )?(last|previous|this|that|current))?(?: (sentence|line|page|chapter|spot|one))?(?: (?:as|called|named|labelled|labeled|with the label|with label|titled)[: ]+(.+))?$""")
            ?: return null
        val scope = when (m.groupValues[2]) {
            "page" -> BookmarkScope.PAGE
            "chapter" -> BookmarkScope.CHAPTER
            else -> BookmarkScope.SENTENCE
        }
        val offset = if (m.groupValues[1] in setOf("last", "previous")) -1 else 0
        val label = m.groups[3]?.value?.let { labelFromOriginal(original, it) }
        return Command.Bookmark(scope, offset, label)
    }

    private fun highlight(t: String): Command? {
        val m = rule(t, """^(?:highlight|underline|mark up)(?: (?:this|that|it))?(?: (?:the )?(last|previous|this|that|current))?(?: $NUM)?(?: (?:sentence|sentences|line|lines|one))?(?: in (\w+))?$""") ?: return null
        val which = m.groupValues[1]
        val numText = m.groupValues[2].trim()
        val colorWord = m.groupValues[3]
        // "highlight the last two sentences" -> 2 sentences ending at the anchor.
        var count = 1
        var colorFromNum: HighlightColor? = null
        if (numText.isNotEmpty()) {
            val n = NumberWords.parseInt(numText)
            if (n != null) count = n.coerceIn(1, 20)
            else colorFromNum = COLORS[numText.substringAfterLast(' ')] ?: return null
        }
        val offset = if (which in setOf("last", "previous") && count == 1) -1 else 0
        val color = COLORS[colorWord] ?: colorFromNum ?: HighlightColor.YELLOW
        return Command.Highlight(offset = offset, count = count, color = color)
    }

    private fun sleep(t: String): Command? {
        if (rule(t, """^(?:cancel|turn off|stop|disable|remove) (?:the )?sleep(?: timer)?$""") != null) return Command.CancelSleepTimer
        if (rule(t, """^(?:stop|pause|sleep)(?: reading| playing)? (?:at|after) the end of (?:the |this )?chapter$""") != null ||
            rule(t, """^(?:finish|read to the end of) (?:the |this )?chapter(?: and stop)?$""") != null) return Command.SleepAtChapterEnd
        val m = rule(t, """^(?:set (?:a |the )?)?(?:sleep(?: timer)?|stop(?: reading| playing)?|pause|turn off) (?:for |in |after )?$NUM (minutes?|mins?|hours?|hrs?)$""")
            ?: rule(t, """^(?:set (?:a |the )?)?sleep timer (?:for |to )?$NUM (minutes?|mins?|hours?|hrs?)$""")
            ?: return null
        val n = NumberWords.parse(m.groupValues[1]) ?: return null
        val minutes = if (m.groupValues[2].startsWith("h")) n * 60 else n
        return Command.SleepTimer(minutes.toInt().coerceIn(1, 24 * 60))
    }

    private fun navigation(t: String): Command? {
        if (rule(t, """^(?:(?:go |skip |jump )?(?:to )?(?:the )?next chapter|skip (?:this |the )?chapter)$""") != null) return Command.NextChapter
        if (rule(t, """^(?:(?:go |skip |jump )?(?:back )?(?:to )?(?:the )?(?:previous|last) chapter|go back (?:a|one) chapter)$""") != null) return Command.PreviousChapter
        if (rule(t, """^(?:(?:go |skip |jump )?(?:to )?(?:the )?next paragraph|skip (?:this |the )?paragraph)$""") != null) return Command.NextParagraph
        if (rule(t, """^(?:(?:go |jump )?(?:back )?(?:to )?(?:the )?(?:previous|last) paragraph|go back (?:a|one) paragraph)$""") != null) return Command.PreviousParagraph
        rule(t, """^(?:(?:go|jump|skip|take me|turn|open|read) (?:back )?(?:to )?)?(chapter|page|part) (\d+|[ivxlcdm]+|${NUM.removePrefix("(")}$""")?.let { m ->
            val n = NumberWords.parseInt(m.groupValues[2]) ?: com.earmark.core.text.RomanNumerals.toInt(m.groupValues[2]) ?: return null
            return if (m.groupValues[1] == "page") Command.GoToPage(n) else Command.GoToChapter(n)
        }
        rule(t, """^(?:(?:go|jump|skip) (?:back )?to |start (?:from |at )?|restart )(?:the )?(?:beginning|start|top)?(?: of)?(?: (?:the |this )?(book|chapter))?$""")?.let { m ->
            if (t == "start") return null
            return Command.GoToStart(ofChapter = m.groupValues[1] != "book")
        }
        if (t == "restart" || t == "start over" || t == "restart chapter" || t == "restart the chapter") return Command.GoToStart(ofChapter = true)

        rule(t, """^(?:go |skip |jump |move )?(?:back|backward|backwards)(?: by)?(?: a (?:bit|little))?(?: $NUM)?(?: (sentences?|lines?|seconds?|secs?|minutes?|mins?))?$""")?.let { m ->
            return timeOrSentences(m.groupValues[1], m.groupValues[2], forward = false, defaultSentences = 1)
        }
        rule(t, """^(?:rewind|previous sentence|last sentence|back up)(?: by)?(?: a (?:bit|little))?(?: $NUM)?(?: (sentences?|lines?|seconds?|secs?|minutes?|mins?))?$""")?.let { m ->
            if (t.startsWith("previous") || t.startsWith("last")) return Command.Rewind(sentences = 1)
            return timeOrSentences(m.groupValues[1], m.groupValues[2], forward = false, defaultSeconds = 15)
        }
        rule(t, """^(?:skip|skip ahead|skip forward|go forward|jump forward|jump ahead|fast forward|forward|next sentence|next line|ahead)(?: by)?(?: a (?:bit|little))?(?: $NUM)?(?: (sentences?|lines?|seconds?|secs?|minutes?|mins?))?$""")?.let { m ->
            if (t.startsWith("next")) return Command.Forward(sentences = 1)
            return timeOrSentences(m.groupValues[1], m.groupValues[2], forward = true, defaultSeconds = 30)
        }
        return null
    }

    private fun timeOrSentences(numText: String, unit: String, forward: Boolean, defaultSentences: Int? = null, defaultSeconds: Int? = null): Command? {
        val n = if (numText.isBlank()) null else (NumberWords.parse(numText) ?: return null)
        val (sentences, seconds) = when {
            unit.startsWith("sec") -> null to (n ?: 15.0).toInt()
            unit.startsWith("min") -> null to ((n ?: 1.0) * 60).toInt()
            unit.startsWith("sentence") || unit.startsWith("line") -> (n ?: 1.0).toInt() to null
            n != null -> n.toInt() to null // "go back three" -> sentences
            defaultSeconds != null -> null to defaultSeconds
            else -> (defaultSentences ?: 1) to null
        }
        return if (forward) Command.Forward(sentences, seconds) else Command.Rewind(sentences, seconds)
    }

    private fun speed(t: String): Command? {
        if (rule(t, """^(?:(?:normal|regular|default|original) speed|reset (?:the )?speed|speed (?:back )?to normal|back to normal speed)$""") != null) return Command.SetSpeed(1.0f)
        rule(t, """^(?:(?:set |change )?(?:the )?(?:speed|rate|playback speed)(?: to)?|play at|read at|go at) $NUM(?: ?(?:x|times))?(?: speed)?$""")?.let { m ->
            val n = NumberWords.parse(m.groupValues[1]) ?: return null
            return Command.SetSpeed(n.toFloat())
        }
        rule(t, """^$NUM ?(?:x|times)(?: speed)?$""")?.let { m ->
            val n = NumberWords.parse(m.groupValues[1]) ?: return null
            return Command.SetSpeed(n.toFloat())
        }
        rule(t, """^(?:(much|a lot|a little|a bit|slightly) )?(?:read |speak |talk |go )?(faster|quicker|speed up|slower|slow down)(?: (?:a little|a bit|please))?$""")?.let { m ->
            val amount = when (m.groupValues[1]) { "much", "a lot" -> 0.5f; "a little", "a bit", "slightly" -> 0.1f; else -> 0.25f }
            val faster = m.groupValues[2] in setOf("faster", "quicker", "speed up")
            return Command.ChangeSpeed(if (faster) amount else -amount)
        }
        return null
    }

    private fun normalize(s: String): String {
        var t = s.lowercase()
            .replace('’', '\'')
            .replace(Regex("""[?!.,;]+$"""), "")
            .replace(Regex("""[,;]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            // Recognisers often split these: "book mark this", "high light that".
            .replace(Regex("""\bbook mark(s?)\b"""), "bookmark$1")
            .replace(Regex("""\b(?:high|hi) ?light\b"""), "highlight")
        var changed = true
        while (changed) {
            changed = false
            for (f in FILLERS) {
                if (t == f) continue
                if (t.startsWith("$f ")) { t = t.removePrefix("$f ").trim(); changed = true }
            }
            for (f in TRAILING) {
                if (t.endsWith(" $f")) { t = t.removeSuffix(" $f").trim(); changed = true }
            }
        }
        return t
    }

    private val NOTE_BODY = Regex("""^.*?\b(?:note(?: down)?|remember)\b[\s:,]*(?:that\s+|saying\s+)?(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Note text keeps the transcript's own casing and punctuation. */
    private fun noteText(original: String): String {
        var body = NOTE_BODY.find(original)?.groupValues?.get(1)?.trim().orEmpty()
        for (f in TRAILING) if (body.lowercase().endsWith(" $f")) body = body.dropLast(f.length + 1).trim()
        return body.trimEnd(',', ';').trim()
    }

    private fun labelFromOriginal(original: String, lowerLabel: String): String {
        val idx = original.lowercase().lastIndexOf(lowerLabel)
        return (if (idx >= 0) original.substring(idx, idx + lowerLabel.length) else lowerLabel).trim()
    }

    private fun rule(t: String, pattern: String): MatchResult? = Regex(pattern).matchEntire(t)
}
