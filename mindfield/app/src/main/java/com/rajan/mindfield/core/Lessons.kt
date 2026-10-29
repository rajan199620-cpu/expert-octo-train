package com.rajan.mindfield.core

import java.time.LocalDate

/**
 * A lesson of your own: what happened (the cue), and what you took from it, in your own words.
 * Each one becomes a review card ([Lessons.cardId]): the situation is shown, you recall the lesson,
 * then check it. Writing a lesson yourself makes it stick better than reading one (the generation
 * effect: Slamecka & Graf 1978; Bertsch et al., Memory & Cognition 2007), and recalling it from the
 * situation it came from is practice at using it there (cued retrieval practice).
 */
data class Lesson(
    val id: String,
    val situation: String,
    val lesson: String,
    val day: LocalDate,
    val createdAt: Long,
    val updatedAt: Long,
    /** Deleted lessons stay as tombstones so a sync can't bring them back from another copy. */
    val deleted: Boolean = false,
)

object Lessons {
    const val PREFIX = "lesson:"
    const val SITUATION_MAX = 400
    const val LESSON_MAX = 300

    fun cardId(lessonId: String) = PREFIX + lessonId

    fun isLesson(cardId: String) = cardId.startsWith(PREFIX)

    /**
     * Text shared into the app (from Gemini, a chat, a note) as a lesson to finish and save: "what
     * happened" and "the lesson", split where the text labels them (`Lesson:`, `**Lesson:**`,
     * `Takeaway -`...), with any "What happened:" label dropped. Unlabelled text is all "what
     * happened", for you to draw the lesson from. Both are cut to fit.
     */
    fun fromShared(text: String): Pair<String, String> {
        val t = text.replace("\r\n", "\n").trim()
        val label = LESSON_LABEL.find(t) ?: return Texts.clip(unlabel(t), SITUATION_MAX) to ""
        val before = unlabel(t.substring(0, label.range.first))
        val after = t.substring(label.range.last + 1).trim()
        return Texts.clip(before, SITUATION_MAX) to Texts.clip(after, LESSON_MAX)
    }

    /**
     * Adds shared text to a lesson already being written, rather than throwing that away: each
     * part goes on a new line after what's there.
     */
    fun addShared(situation: String, lesson: String, text: String): Pair<String, String> {
        val (s, l) = fromShared(text)
        fun join(a: String, b: String, max: Int) = Texts.clip(listOf(a.trimEnd(), b).filter { it.isNotBlank() }.joinToString("\n"), max)
        return join(situation, s, SITUATION_MAX) to join(lesson, l, LESSON_MAX)
    }

    private val LESSON_LABEL = Regex("""(?im)(?:^|(?<=\s))\**\s*(?:the\s+)?(?:lessons?(?:\s+(?:learned|learnt))?|takeaway)\s*\**\s*[:\-–—]\s*\**""")
    private val SITUATION_LABEL = Regex("""(?i)^\s*\**\s*(?:what\s+happened|situation|incident|experience)\s*\**\s*[:\-–—]\s*\**\s*""")

    private fun unlabel(s: String) = s.trim().replace(SITUATION_LABEL, "").trim()

    /** The lesson a card is for, if it's a lesson's card and the lesson is still there. */
    fun of(state: AppState, cardId: String): Lesson? =
        if (!isLesson(cardId)) null else cardId.removePrefix(PREFIX).let { id -> state.liveLessons.firstOrNull { it.id == id } }
}
