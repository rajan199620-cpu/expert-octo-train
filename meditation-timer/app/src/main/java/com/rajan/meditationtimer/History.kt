package com.rajan.meditationtimer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Sessions shorter than this (e.g. a mis-tap followed by End) are not logged. */
const val MIN_LOGGED_SEC = 60

data class SessionRecord(
    /** Wall-clock start, epoch millis. The session counts towards the day it started on. */
    val startedAtMs: Long,
    val plannedSec: Int,
    /** Time actually sat; less than [plannedSec] when the session was ended early. */
    val actualSec: Int,
) {
    val completed: Boolean get() = actualSec >= plannedSec

    fun day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()

    fun encode(): String = "$startedAtMs,$plannedSec,$actualSec"

    companion object {
        /** Returns null for a corrupt line so one bad write never loses the whole history. */
        fun decode(line: String): SessionRecord? {
            val parts = line.trim().split(',')
            if (parts.size != 3) return null
            return SessionRecord(
                startedAtMs = parts[0].toLongOrNull() ?: return null,
                plannedSec = parts[1].toIntOrNull() ?: return null,
                actualSec = parts[2].toIntOrNull() ?: return null,
            )
        }
    }
}

data class DayEntry(val date: LocalDate, val totalSec: Int, val sessions: List<SessionRecord>)

data class HistorySummary(
    val currentStreak: Int,
    val longestStreak: Int,
    val totalSec: Long,
    val sessionCount: Int,
    val last7DaysSec: Long,
    /** Whether each of the last 7 days (oldest first, ending today) had a session. */
    val last7Days: List<Pair<LocalDate, Boolean>>,
    /** Newest day first; sessions within a day newest first. */
    val days: List<DayEntry>,
)

object History {
    fun summarize(records: List<SessionRecord>, zone: ZoneId, today: LocalDate): HistorySummary {
        val byDay = records.groupBy { it.day(zone) }
        val activeDays = byDay.keys
        val weekStart = today.minusDays(6)
        return HistorySummary(
            currentStreak = currentStreak(activeDays, today),
            longestStreak = longestStreak(activeDays),
            totalSec = records.sumOf { it.actualSec.toLong() },
            sessionCount = records.size,
            last7DaysSec = byDay.filterKeys { it in weekStart..today }.values.flatten().sumOf { it.actualSec.toLong() },
            last7Days = (0L..6L).map { weekStart.plusDays(it) }.map { it to (it in activeDays) },
            days = byDay.entries
                .sortedByDescending { it.key }
                .map { (date, list) -> DayEntry(date, list.sumOf { it.actualSec }, list.sortedByDescending { it.startedAtMs }) },
        )
    }

    /**
     * Consecutive days ending today. If today has no session yet the streak is still alive
     * (counted up to yesterday) — it only breaks once a whole day is missed.
     */
    fun currentStreak(activeDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in activeDays) today else today.minusDays(1)
        var streak = 0
        while (day in activeDays) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    fun longestStreak(activeDays: Set<LocalDate>): Int {
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in activeDays.sorted()) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        return longest
    }
}

/** 45 min, 1h 20m, 3h */
fun formatDuration(sec: Long): String {
    val minutes = sec / 60
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
