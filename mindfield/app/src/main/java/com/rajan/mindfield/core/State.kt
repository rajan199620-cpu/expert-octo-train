package com.rajan.mindfield.core

import java.time.LocalDate

/** How a concept showed up in your day. */
enum class Mode(val key: String, val label: String, val short: String, val emoji: String, val prompt: String) {
    SPOTTED("spotted", "Spotted it", "Spotted", "👀", "Where did you see it? Who was doing it?"),
    MYSELF("myself", "Caught myself", "In me", "🙋", "What were you thinking or doing when it happened?"),
    USED("used", "Used it", "Used", "🎯", "What did you try, and how did it go?"),
    NOT_TODAY("none", "Not today", "Not today", "🌙", "Anything close? Why didn't it come up?");

    /** A real sighting, as opposed to "it didn't come up today". */
    val isSighting: Boolean get() = this != NOT_TODAY

    companion object {
        fun of(key: String): Mode? = entries.firstOrNull { it.key == key }
    }
}

enum class Outcome(val key: String, val label: String) {
    WORKED("worked", "Worked"),
    MIXED("mixed", "Mixed"),
    BACKFIRED("backfired", "Backfired");

    companion object {
        fun of(key: String?): Outcome? = entries.firstOrNull { it.key == key }
    }
}

/** One field report: how a concept showed up on a day, in your words. */
data class Entry(
    val id: String,
    val conceptId: String,
    val day: LocalDate,
    val mode: Mode,
    val note: String,
    val outcome: Outcome?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Deleted entries stay as tombstones so a sync can't bring them back from another copy. */
    val deleted: Boolean = false,
)

/**
 * Which concept a day showed. Fixed once made, so a day's concept never changes under you.
 * [engaged] is set once you predict, plan or log on that day's concept, so when two phones picked
 * different concepts for the same day, a sync keeps the one you actually worked on.
 */
data class Assignment(val conceptId: String, val assignedAt: Long, val engaged: Boolean = false)

/** Your "Predict first" answer for a concept: the first guess is the one that counts. */
data class Guess(val choice: Int, val at: Long)

/** An if-then plan for today's mission ("At the 3 pm meeting, I'll ..."). */
data class Plan(val text: String, val updatedAt: Long)

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Phone setting"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class Settings(
    val morningOn: Boolean = true,
    val morningMinute: Int = 8 * 60,
    val eveningOn: Boolean = true,
    val eveningMinute: Int = 21 * 60,
    /** A surprise mid-day "seen it yet?" nudge, at a different time each day. */
    val spotCheckOn: Boolean = false,
    /** Empty means every category. */
    val focus: Set<Category> = emptySet(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Show undiscovered concepts in the Field Guide instead of locking them. */
    val showAll: Boolean = false,
    val onboarded: Boolean = false,
    /** Days a week (Monday–Sunday) you mean to file a field report; 0 = no goal. */
    val weeklyGoal: Int = 5,
)

data class AppState(
    val assignments: Map<LocalDate, Assignment> = emptyMap(),
    val entries: List<Entry> = emptyList(),
    val cards: Map<String, Card> = emptyMap(),
    val guesses: Map<String, Guess> = emptyMap(),
    val plans: Map<LocalDate, Plan> = emptyMap(),
    val settings: Settings = Settings(),
) {
    val liveEntries: List<Entry> by lazy { entries.filter { !it.deleted } }

    /** Concepts you have been shown, with the first day each appeared. */
    val unlocked: Map<String, LocalDate> by lazy {
        val first = HashMap<String, LocalDate>()
        for ((day, a) in assignments) {
            val seen = first[a.conceptId]
            if (seen == null || day < seen) first[a.conceptId] = day
        }
        first
    }

    val startDay: LocalDate? get() = assignments.keys.minOrNull()

    val isEmpty: Boolean get() = assignments.isEmpty() && entries.isEmpty() && guesses.isEmpty()

    /**
     * Nothing made here yet: no notes, predictions, plans or reviews. A new phone that has only been
     * shown a concept or two is fresh; those days are placeholders a restored backup should replace.
     */
    val isFresh: Boolean get() = entries.isEmpty() && guesses.isEmpty() && plans.isEmpty() && cards.isEmpty()

    fun entriesFor(conceptId: String) = liveEntries.filter { it.conceptId == conceptId }

    fun entriesOn(day: LocalDate) = liveEntries.filter { it.day == day }

    /**
     * True once that day's own concept has a field report. A note about an older concept doesn't
     * count: the evening report, the widget and today's path are about the day's concept. (Streaks
     * count any report: checking in is the habit.)
     */
    fun reportedOn(day: LocalDate): Boolean {
        val id = assignments[day]?.conceptId ?: return false
        return liveEntries.any { it.day == day && it.conceptId == id }
    }

    /** Marks [day]'s concept as worked on (see [Assignment.engaged]), if it is [conceptId] (any, when null). */
    fun engaged(day: LocalDate, conceptId: String? = null): AppState {
        val a = assignments[day] ?: return this
        if (a.engaged || (conceptId != null && a.conceptId != conceptId)) return this
        return copy(assignments = assignments + (day to a.copy(engaged = true)))
    }

    fun upsert(entry: Entry): AppState {
        val i = entries.indexOfFirst { it.id == entry.id }
        val next = if (i < 0) entries + entry else entries.toMutableList().also { it[i] = entry }
        return copy(entries = next)
    }
}
