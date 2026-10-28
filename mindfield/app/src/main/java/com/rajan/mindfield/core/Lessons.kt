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

    /** The lesson a card is for, if it's a lesson's card and the lesson is still there. */
    fun of(state: AppState, cardId: String): Lesson? =
        if (!isLesson(cardId)) null else cardId.removePrefix(PREFIX).let { id -> state.liveLessons.firstOrNull { it.id == id } }
}
