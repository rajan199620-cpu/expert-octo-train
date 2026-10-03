package com.rajan.meditationtimer

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A journal note from this date some time ago, e.g. "A month ago today". */
data class Memory(val label: String, val record: SessionRecord)

/** One month of practice, summed up. Optional parts are null when that month has no such data. */
data class MonthRecap(
    val month: YearMonth,
    val sits: Int,
    val totalSec: Long,
    val daysSat: Int,
    /** Days of the month that have happened: the whole month once it's over, up to today before. */
    val daysSoFar: Int,
    val longestSec: Int,
    /** First day (Monday, or the 1st) of the week in this month with the most time sat, and that time. */
    val bestWeekStart: LocalDate,
    val bestWeekSec: Long,
    /** Average after-minus-before check-in over the sits where both were answered. */
    val averageShift: Double?,
    val shiftSits: Int,
    /** The post-sit feeling rated most often (1..5); ties go to the more recent. */
    val commonRating: Int?,
    /** Times the mind was caught wandering, per 10 minutes, over the counted sits. */
    val noticingPerTenMin: Double?,
    val notes: Int,
    /** Total time sat in the month before, or null if nothing was sat then. */
    val previousTotalSec: Long?,
    val inProgress: Boolean,
)

object Recap {
    /** Longest ago first: a note from a year back means more than one from last week. */
    private val LOOKBACKS = listOf(
        Period.ofYears(1) to "A year ago today",
        Period.ofMonths(6) to "Six months ago today",
        Period.ofMonths(3) to "Three months ago today",
        Period.ofMonths(1) to "A month ago today",
        Period.ofWeeks(1) to "A week ago today",
    )

    /**
     * A note you wrote on this date a year, six months, three months, a month or a week ago.
     * Only sits with a note count: the point is to hear from your past self, not to list minutes.
     */
    fun onThisDay(records: List<SessionRecord>, zone: ZoneId, today: LocalDate): Memory? {
        val notesByDay = records.filter { it.note.isNotBlank() }.groupBy { it.day(zone) }
        if (notesByDay.isEmpty()) return null
        val lastOfMonth = today.dayOfMonth == today.lengthOfMonth()
        for ((period, label) in LOOKBACKS) {
            val day = today.minus(period)
            // On 30 April, "a month ago" is 30 March; 31 March would otherwise never come round.
            val days = if (lastOfMonth && period.days == 0) generateSequence(day) { it.plusDays(1) }
                .takeWhile { it.month == day.month }.toList() else listOf(day)
            days.flatMap { notesByDay[it].orEmpty() }.maxByOrNull { it.startedAtMs }?.let { return Memory(label, it) }
        }
        return null
    }

    /** Months that have at least one sit, newest first, never later than [today]'s month. */
    fun months(records: List<SessionRecord>, zone: ZoneId, today: LocalDate): List<YearMonth> {
        val now = YearMonth.from(today)
        return records.map { YearMonth.from(it.day(zone)) }.filter { it <= now }.distinct().sortedDescending()
    }

    /**
     * The month the recap opens on: the last finished month with sits, so on the 1st of October
     * you see September; a brand-new user with only this month's sits sees this month so far.
     */
    fun defaultMonth(months: List<YearMonth>, today: LocalDate): YearMonth? {
        val now = YearMonth.from(today)
        return months.firstOrNull { it < now } ?: months.firstOrNull()
    }

    fun month(records: List<SessionRecord>, zone: ZoneId, month: YearMonth, today: LocalDate): MonthRecap? {
        val inMonth = records.filter { YearMonth.from(it.day(zone)) == month }.sortedBy { it.startedAtMs }
        if (inMonth.isEmpty()) return null
        val inProgress = month == YearMonth.from(today)

        val byWeek = inMonth.groupBy { it.day(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .mapValues { (_, l) -> l.sumOf { it.actualSec.toLong() } }
        // Ties go to the later week: the more recent effort is the one worth naming.
        val best = byWeek.entries.sortedByDescending { it.key }.maxBy { it.value }

        val paired = inMonth.filter { it.before in 1..5 && it.after in 1..5 }
        val rated = inMonth.filter { it.rating in 1..5 }
        val ratingCounts = rated.groupingBy { it.rating }.eachCount()
        val topCount = ratingCounts.values.maxOrNull()
        val counted = inMonth.filter { it.noticed >= 0 && it.actualSec >= MIN_LOGGED_SEC }

        val previous = records.filter { YearMonth.from(it.day(zone)) == month.minusMonths(1) }

        return MonthRecap(
            month = month,
            sits = inMonth.size,
            totalSec = inMonth.sumOf { it.actualSec.toLong() },
            daysSat = inMonth.map { it.day(zone) }.distinct().size,
            daysSoFar = if (inProgress) today.dayOfMonth else month.lengthOfMonth(),
            longestSec = inMonth.maxOf { it.actualSec },
            bestWeekStart = maxOf(best.key, month.atDay(1)),
            bestWeekSec = best.value,
            averageShift = if (paired.isEmpty()) null else paired.map { it.after - it.before }.average(),
            shiftSits = paired.size,
            commonRating = topCount?.let { top -> rated.last { ratingCounts[it.rating] == top }.rating },
            noticingPerTenMin = if (counted.isEmpty()) null
            else counted.sumOf { it.noticed } * 600.0 / counted.sumOf { it.actualSec },
            notes = inMonth.count { it.note.isNotBlank() },
            previousTotalSec = if (previous.isEmpty()) null else previous.sumOf { it.actualSec.toLong() },
            inProgress = inProgress,
        )
    }

    /**
     * Shown on the Sit screen for the first days of a month: last month's recap is ready.
     * Null outside those days or when last month has no sits.
     */
    fun recapReady(records: List<SessionRecord>, zone: ZoneId, today: LocalDate, days: Int = 3): YearMonth? {
        if (today.dayOfMonth > days) return null
        val last = YearMonth.from(today).minusMonths(1)
        return last.takeIf { m -> records.any { YearMonth.from(it.day(zone)) == m } }
    }
}
