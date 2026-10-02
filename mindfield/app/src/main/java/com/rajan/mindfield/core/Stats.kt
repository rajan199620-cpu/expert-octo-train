package com.rajan.mindfield.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Numbers for the "You" screen, all derived from the journal so they can never drift. */
object Stats {
    /** Days with at least one field report (a "Not today" counts: checking in is the habit). */
    fun checkInDays(state: AppState): Set<LocalDate> = state.liveEntries.mapTo(HashSet()) { it.day }

    /** Consecutive days with a report, ending today, or yesterday if today has none yet. */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in days) today else today.minusDays(1)
        var n = 0
        while (day in days) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    fun longestStreak(days: Set<LocalDate>): Int {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue
            var n = 0
            var x = d
            while (x in days) {
                n++
                x = x.plusDays(1)
            }
            best = maxOf(best, n)
        }
        return best
    }

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
            (0L..6L).map { i -> monday.plusDays(i).let { it to (perDay[it] ?: 0) } }
        }
    }

    /** Field notes written this many days, weeks or months ago, for "On this day". */
    fun onThisDay(state: AppState, today: LocalDate): List<Pair<String, Entry>> {
        val looks = listOf(
            "A year ago" to today.minusYears(1),
            "Six months ago" to today.minusMonths(6),
            "Three months ago" to today.minusMonths(3),
            "A month ago" to today.minusMonths(1),
            "A week ago" to today.minusWeeks(1),
        )
        return looks.mapNotNull { (label, day) ->
            state.liveEntries.filter { it.day == day && it.note.isNotBlank() }.maxByOrNull { it.note.length }?.let { label to it }
        }
    }

    fun dayNumber(state: AppState, today: LocalDate): Int {
        val start = state.startDay ?: return 1
        return ChronoUnit.DAYS.between(start, today).toInt() + 1
    }
}
