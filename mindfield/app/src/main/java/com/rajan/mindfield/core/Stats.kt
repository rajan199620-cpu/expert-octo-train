package com.rajan.mindfield.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Numbers for the "You" screen, all derived from the journal so they can never drift. */
object Stats {
    /** Days with at least one field report (a "Not today" counts: checking in is the habit). */
    fun checkInDays(state: AppState): Set<LocalDate> = state.liveEntries.mapTo(HashSet()) { it.day }

    /** Days with a report in a forgiving streak ending today (or yesterday): see [Progress.streak]. */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int = Progress.streak(days, today).days

    /** The longest forgiving streak ever, up to [today] (a note dated ahead can't stretch it). */
    fun longestStreak(days: Set<LocalDate>, today: LocalDate? = null): Int =
        Progress.longestStreak(if (today == null) days else days.filterTo(HashSet()) { !it.isAfter(today) })

    /** Concepts you have met that are in this library. */
    fun discovered(state: AppState, library: Library): Int = state.unlocked.keys.count { library.contains(it) }

    /** This week, Monday first: true = reported, false = missed, null = still to come. */
    fun week(days: Set<LocalDate>, today: LocalDate): List<Pair<LocalDate, Boolean?>> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..6L).map { i ->
            val d = monday.plusDays(i)
            d to if (d > today) null else d in days
        }
    }

    fun modeCounts(state: AppState): Map<Mode, Int> =
        Mode.entries.associateWith { m -> state.liveEntries.count { it.mode == m } }

    /** Real sightings per category, for the map of where you notice psychology. */
    fun categoryCounts(state: AppState, library: Library): Map<Category, Int> {
        val counts = Category.entries.associateWith { 0 }.toMutableMap()
        for (e in state.liveEntries) {
            if (!e.mode.isSighting) continue
            val c = library[e.conceptId]?.category ?: continue
            counts[c] = counts.getValue(c) + 1
        }
        return counts
    }

    /** Concepts seen in the wild at least once: the life list. */
    fun lifeList(state: AppState): Set<String> =
        state.liveEntries.filter { it.mode.isSighting }.mapTo(HashSet()) { it.conceptId }

    fun topConcepts(state: AppState, limit: Int = 5): List<Pair<String, Int>> =
        state.liveEntries.filter { it.mode.isSighting }
            .groupingBy { it.conceptId }.eachCount()
            .entries.sortedWith(compareBy({ -it.value }, { it.key }))
            .take(limit).map { it.key to it.value }

    data class Predictions(val made: Int, val right: Int) {
        val surprised: Int get() = made - right
        /** Rounded, the same way as everywhere else the app shows it. */
        val percent: Int get() = if (made == 0) 0 else (right * 100.0 / made).roundToInt()
    }

    fun predictions(state: AppState, library: Library): Predictions {
        var made = 0
        var right = 0
        for ((id, g) in state.guesses) {
            val c = library[id] ?: continue
            made++
            if (g.choice == c.predict.answer) right++
        }
        return Predictions(made, right)
    }

    data class Outcomes(val worked: Int, val mixed: Int, val backfired: Int) {
        val total: Int get() = worked + mixed + backfired
    }

    /** How deliberate uses went: the honest scorecard for "Used it". */
    fun outcomes(state: AppState): Outcomes {
        val used = state.liveEntries.filter { it.mode == Mode.USED }
        return Outcomes(
            used.count { it.outcome == Outcome.WORKED },
            used.count { it.outcome == Outcome.MIXED },
            used.count { it.outcome == Outcome.BACKFIRED },
        )
    }

    /** Last [weeks] weeks as columns of 7 days (Monday at top): the number of reports each day. */
    fun heatmap(state: AppState, today: LocalDate, weeks: Int = 12): List<List<Pair<LocalDate, Int>>> {
        val perDay = state.liveEntries.groupingBy { it.day }.eachCount()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (weeks - 1 downTo 0).map { w ->
            val monday = thisMonday.minusWeeks(w.toLong())
            // Days still to come stay empty, even if a note is dated ahead.
            (0L..6L).map { i -> monday.plusDays(i).let { d -> d to (if (d.isAfter(today)) 0 else perDay[d] ?: 0) } }
        }
    }

    /**
     * Field notes written a year, six months, three months, a month or a week ago, for "On this day":
     * the longest note from each. A month back means the date that is a month before today, so the
     * 31st has nothing a month after a 30-day month, and a month's last day also brings back the
     * days after it that the shorter month doesn't have (on 28 February, 28 to 31 January).
     */
    fun onThisDay(state: AppState, today: LocalDate): List<Pair<String, Entry>> {
        val byDay = state.liveEntries.filter { it.note.isNotBlank() }.groupBy { it.day }
        fun monthsBack(n: Long): List<LocalDate> {
            val back = today.minusMonths(n)
            if (back.plusMonths(n) != today) return emptyList()
            val last = if (today == today.withDayOfMonth(today.lengthOfMonth())) back.withDayOfMonth(back.lengthOfMonth()) else back
            return generateSequence(back) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        }
        val looks = listOf(
            "A year ago" to monthsBack(12),
            "Six months ago" to monthsBack(6),
            "Three months ago" to monthsBack(3),
            "A month ago" to monthsBack(1),
            "A week ago" to listOf(today.minusWeeks(1)),
        )
        return looks.mapNotNull { (label, days) ->
            days.flatMap { byDay[it].orEmpty() }.maxByOrNull { it.note.length }?.let { label to it }
        }
    }

    fun dayNumber(state: AppState, today: LocalDate): Int {
        val start = state.startDay ?: return 1
        return ChronoUnit.DAYS.between(start, today).toInt() + 1
    }
}
