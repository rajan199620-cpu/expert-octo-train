package com.rajan.meditationtimer

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Sessions shorter than this (e.g. a mis-tap followed by End) are not logged. */
const val MIN_LOGGED_SEC = 60

data class SessionRecord(
    /** Wall-clock start, epoch millis. The session counts towards the day it started on. */
    val startedAtMs: Long,
    val plannedSec: Int,
    /** Time actually sat; less than [plannedSec] when the session was ended early. */
    val actualSec: Int,
    /** Post-sit reflection: 1 (restless) .. 5 (deeply settled); 0 = not rated. */
    val rating: Int = 0,
    val note: String = "",
    /** Check-in just before the sit: 1 (tense) .. 5 (calm), see [CHECK_IN_LABELS]; 0 = skipped. */
    val before: Int = 0,
    /** The same check-in just after the sit; 0 = skipped. */
    val after: Int = 0,
    /** Times you noticed the mind had wandered and tapped; -1 = not counted this sit. */
    val noticed: Int = -1,
) {
    val completed: Boolean get() = actualSec >= plannedSec

    fun day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()

    // The note is URL-encoded so commas and newlines in it can't break the line format.
    fun encode(): String =
        "$startedAtMs,$plannedSec,$actualSec,$rating,${URLEncoder.encode(note, "UTF-8")},$before,$after,$noticed"

    companion object {
        /**
         * Returns null for a corrupt line so one bad write never loses the whole history.
         * Accepts the original 3-field lines (before reflections existed), 5-field ones (before
         * check-ins and the distraction count) and the current 8-field ones.
         */
        fun decode(line: String): SessionRecord? {
            val parts = line.trim().split(',')
            if (parts.size != 3 && parts.size != 5 && parts.size != 8) return null
            return SessionRecord(
                startedAtMs = parts[0].toLongOrNull() ?: return null,
                plannedSec = parts[1].toIntOrNull() ?: return null,
                actualSec = parts[2].toIntOrNull() ?: return null,
                rating = if (parts.size >= 5) parts[3].toIntOrNull() ?: return null else 0,
                note = if (parts.size >= 5) runCatching { URLDecoder.decode(parts[4], "UTF-8") }.getOrNull() ?: return null else "",
                before = if (parts.size == 8) parts[5].toIntOrNull()?.takeIf { it in 0..5 } ?: return null else 0,
                after = if (parts.size == 8) parts[6].toIntOrNull()?.takeIf { it in 0..5 } ?: return null else 0,
                noticed = if (parts.size == 8) parts[7].toIntOrNull()?.takeIf { it >= -1 } ?: return null else -1,
            )
        }
    }
}

/** How you feel right now, asked just before and just after a sit. */
val CHECK_IN_LABELS = listOf("Tense", "Restless", "Okay", "Settled", "Calm")

/** What sits did to how you felt: after minus before, over the sits where both were answered. */
data class CheckInSummary(val sits: Int, val averageShift: Double, val better: Int, val same: Int, val worse: Int)

/** One counted sit: how often you caught the mind wandering, per 10 minutes so sits compare. */
data class NoticingPoint(val startedAtMs: Long, val count: Int, val perTenMin: Double)

data class MoodPoint(val startedAtMs: Long, val date: LocalDate, val rating: Int, val rollingAverage: Double)

data class DayEntry(val date: LocalDate, val totalSec: Int, val sessions: List<SessionRecord>)

/**
 * A streak that forgives: up to [REST_DAYS_PER_WEEK] missed day in any Monday–Sunday week is a
 * rest day and doesn't break it; a second miss in the same week does. [days] counts days sat
 * (rest days add nothing); [restsUsed] is how many rest days it has used, [restThisWeek] whether
 * this week's rest day is already spent.
 */
data class Streak(val days: Int, val restsUsed: Int, val restThisWeek: Boolean)

/** This week against the weekly goal, and how many weeks running the goal has been met. */
data class WeekGoal(val goal: Int, val daysThisWeek: Int, val met: Boolean, val weeksRunning: Int) {
    val daysLeft: Int get() = (goal - daysThisWeek).coerceAtLeast(0)
}

/** One missed day a week keeps a streak alive; broken streaks discourage, repairable ones less so. */
const val REST_DAYS_PER_WEEK = 1

data class HistorySummary(
    /** Forgiving: see [Streak]. */
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
            currentStreak = streak(activeDays, today).days,
            longestStreak = longestForgivingStreak(activeDays),
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

    /**
     * Minutes sat per day for the last [weeks] weeks, as columns of Monday..Sunday.
     * The last column is the current week; days after [today] are null.
     */
    fun heatmap(records: List<SessionRecord>, zone: ZoneId, today: LocalDate, weeks: Int = 12): List<List<Int?>> {
        val minutesByDay = records.groupBy { it.day(zone) }.mapValues { (_, l) -> l.sumOf { it.actualSec } / 60 }
        val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L)
        return (0 until weeks).map { w ->
            (0 until 7).map { d ->
                val date = firstMonday.plusDays(w * 7L + d)
                if (date.isAfter(today)) null else minutesByDay[date] ?: 0
            }
        }
    }

    /**
     * Monday to Sunday of the current week: true if you sat that day, false if not, null for days
     * still to come. Resets every Monday, so a missed day never follows you for long.
     */
    fun week(activeDays: Set<LocalDate>, today: LocalDate): List<Pair<LocalDate, Boolean?>> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..6L).map { monday.plusDays(it) }.map { it to if (it.isAfter(today)) null else it in activeDays }
    }

    /**
     * How many weeks the calendar shows: back to the week of your first sit, at least [min] and at
     * most [max], so a new practice isn't drowned in empty squares.
     */
    fun weeksToShow(firstDay: LocalDate?, today: LocalDate, min: Int = 4, max: Int = 12): Int {
        if (firstDay == null) return min
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstMonday = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstMonday, thisMonday).toInt() + 1
        return weeks.coerceIn(min, max)
    }

    /**
     * Rated sits in the last [days] days, oldest first, each with a rolling average of the last
     * [window] ratings. Single sits are noisy; the rolling line is what shows a trend.
     */
    fun moodTrend(
        records: List<SessionRecord>,
        zone: ZoneId,
        today: LocalDate,
        days: Long = 84,
        window: Int = 5,
    ): List<MoodPoint> {
        val from = today.minusDays(days - 1)
        val rated = records
            .filter { it.rating in 1..5 && !it.day(zone).isBefore(from) && !it.day(zone).isAfter(today) }
            .sortedBy { it.startedAtMs }
        return rated.mapIndexed { i, r ->
            val recent = rated.subList(maxOf(0, i - window + 1), i + 1)
            MoodPoint(r.startedAtMs, r.day(zone), r.rating, recent.map { it.rating }.average())
        }
    }

    /** Before/after shift over the last [limit] sits that have both check-ins; null if none. */
    fun checkInSummary(records: List<SessionRecord>, limit: Int = 30): CheckInSummary? {
        val paired = records.filter { it.before in 1..5 && it.after in 1..5 }.sortedBy { it.startedAtMs }.takeLast(limit)
        if (paired.isEmpty()) return null
        val shifts = paired.map { it.after - it.before }
        return CheckInSummary(
            sits = paired.size,
            averageShift = shifts.average(),
            better = shifts.count { it > 0 },
            same = shifts.count { it == 0 },
            worse = shifts.count { it < 0 },
        )
    }

    /** Counted sits in the last [days] days, oldest first. Very short sits are left out. */
    fun noticing(records: List<SessionRecord>, zone: ZoneId, today: LocalDate, days: Long = 84): List<NoticingPoint> {
        val from = today.minusDays(days - 1)
        return records
            .filter { it.noticed >= 0 && it.actualSec >= MIN_LOGGED_SEC && !it.day(zone).isBefore(from) && !it.day(zone).isAfter(today) }
            .sortedBy { it.startedAtMs }
            .map { NoticingPoint(it.startedAtMs, it.noticed, it.noticed * 600.0 / it.actualSec) }
    }

    /** The feeling rated most often; ties go to the more recent one. Null with no ratings. */
    fun mostCommonRating(points: List<MoodPoint>): Int? {
        val counts = points.groupingBy { it.rating }.eachCount()
        val top = counts.values.maxOrNull() ?: return null
        return points.last { counts[it.rating] == top }.rating
    }

    private const val SETTINGS_PREFIX = "#settings,"

    /**
     * Settings ride along in the backup as one first line ("#settings,key=value;…") so a reinstall
     * followed by Restore brings back your usual sit too. Older readers skip the line harmlessly.
     */
    fun settingsLine(settings: Map<String, String>): String =
        SETTINGS_PREFIX + settings.entries.joinToString(";") { (k, v) -> "$k=$v" }

    fun settingsFrom(text: String): Map<String, String>? =
        text.lineSequence().firstOrNull { it.startsWith(SETTINGS_PREFIX) }
            ?.removePrefix(SETTINGS_PREFIX)?.trim()
            ?.split(';')?.mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap()?.takeIf { it.isNotEmpty() }

    private const val DELETED_PREFIX = "#deleted,"

    /**
     * Sits deleted on purpose, as start minutes (epoch minutes), so a backup made on another
     * phone can't bring them back. A spreadsheet, or an older version of the app, reads the line
     * as a row without a date and skips it.
     */
    fun deletedLine(deleted: Set<Long>): String? =
        deleted.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(";", prefix = DELETED_PREFIX)

    fun deletedFrom(text: String): Set<Long> =
        text.lineSequence().firstOrNull { it.startsWith(DELETED_PREFIX) }
            ?.removePrefix(DELETED_PREFIX)?.trim()
            ?.split(';')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
            ?: emptySet()

    /** Spreadsheet-friendly export, oldest first; optional settings and deleted-sits lines first. */
    fun toCsv(
        records: List<SessionRecord>,
        zone: ZoneId,
        settings: Map<String, String>? = null,
        deleted: Set<Long> = emptySet(),
    ): String {
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val rows = records.sortedBy { it.startedAtMs }.map { r ->
            val start = Instant.ofEpochMilli(r.startedAtMs).atZone(zone)
            listOf(
                start.toLocalDate().toString(),
                start.format(time),
                "%.1f".format(java.util.Locale.ROOT, r.plannedSec / 60.0),
                "%.1f".format(java.util.Locale.ROOT, r.actualSec / 60.0),
                if (r.rating > 0) r.rating.toString() else "",
                csvField(r.note),
                if (r.before > 0) r.before.toString() else "",
                if (r.after > 0) r.after.toString() else "",
                if (r.noticed >= 0) r.noticed.toString() else "",
            ).joinToString(",")
        }
        val header = listOfNotNull(
            settings?.let(::settingsLine),
            deletedLine(deleted),
            "date,start,planned_min,actual_min,rating,note,before,after,noticed",
        )
        return (header + rows).joinToString("\n", postfix = "\n")
    }

    /**
     * Reads a file written by [toCsv] back into records (for restoring onto a new phone or after
     * a reinstall). Rows that don't parse are skipped rather than failing the whole import.
     * Precision is what the CSV holds: start to the minute, durations to 0.1 min.
     */
    fun fromCsv(text: String, zone: ZoneId): List<SessionRecord> = csvRows(text).mapNotNull { row ->
        if (row.size < 4) return@mapNotNull null
        val date = runCatching { LocalDate.parse(row[0].trim()) }.getOrNull() ?: return@mapNotNull null
        val time = runCatching { LocalTime.parse(row[1].trim()) }.getOrNull() ?: return@mapNotNull null
        val planned = row[2].trim().toDoubleOrNull() ?: return@mapNotNull null
        val actual = row[3].trim().toDoubleOrNull() ?: return@mapNotNull null
        SessionRecord(
            startedAtMs = date.atTime(time).atZone(zone).toInstant().toEpochMilli(),
            plannedSec = Math.round(planned * 60).toInt(),
            actualSec = Math.round(actual * 60).toInt(),
            rating = row.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it in 1..5 } ?: 0,
            note = row.getOrNull(5) ?: "",
            before = row.getOrNull(6)?.trim()?.toIntOrNull()?.takeIf { it in 1..5 } ?: 0,
            after = row.getOrNull(7)?.trim()?.toIntOrNull()?.takeIf { it in 1..5 } ?: 0,
            noticed = row.getOrNull(8)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: -1,
        )
    }

    /** Minimal RFC 4180 reader: quoted fields may contain commas, quotes ("") and newlines. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row += field.toString(); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    rows += row; row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun weekOf(day: LocalDate): LocalDate = day.with(DayOfWeek.MONDAY)

    /**
     * The forgiving streak ending today (or yesterday, if today has no sit yet: today isn't
     * missed until it's over). Walks back day by day; a missed day spends that week's rest day,
     * a second miss in the same week ends the streak. Rest days count only when sits come before
     * them, so a streak never "starts" with a rest.
     */
    fun streak(activeDays: Set<LocalDate>, today: LocalDate, restsPerWeek: Int = REST_DAYS_PER_WEEK): Streak {
        if (activeDays.isEmpty()) return Streak(0, 0, false)
        val first = activeDays.min()
        var day = if (today in activeDays) today else today.minusDays(1)
        var count = 0
        var committedRests = 0
        val restsByWeek = HashMap<LocalDate, Int>()
        val pending = ArrayList<LocalDate>()
        while (!day.isBefore(first)) {
            if (day in activeDays) {
                count++
                // Rests between two sits are real rest days.
                for (r in pending) restsByWeek.merge(weekOf(r), 1, Int::plus)
                committedRests += pending.size
                pending.clear()
            } else {
                val week = weekOf(day)
                val used = (restsByWeek[week] ?: 0) + pending.count { weekOf(it) == week }
                if (used >= restsPerWeek) break
                pending += day
            }
            day = day.minusDays(1)
        }
        val thisWeek = weekOf(today)
        return Streak(count, committedRests, (restsByWeek[thisWeek] ?: 0) > 0)
    }

    /** The longest forgiving streak ever: the best [streak] ending on any day that was sat. */
    fun longestForgivingStreak(activeDays: Set<LocalDate>, restsPerWeek: Int = REST_DAYS_PER_WEEK): Int {
        // Only the last day of each strict run can end a longest streak; check those.
        return activeDays.filter { it.plusDays(1) !in activeDays }
            .maxOfOrNull { streak(activeDays, it, restsPerWeek).days } ?: 0
    }

    /**
     * This week (Monday–Sunday) against a goal of [goal] days, and how many weeks in a row it has
     * been met: finished weeks back from last week, plus this week once it's met.
     */
    fun weekGoal(activeDays: Set<LocalDate>, today: LocalDate, goal: Int): WeekGoal {
        val monday = weekOf(today)
        fun daysIn(weekStart: LocalDate) = (0L..6L).count { weekStart.plusDays(it) in activeDays && !weekStart.plusDays(it).isAfter(today) }
        val now = daysIn(monday)
        val met = goal > 0 && now >= goal
        var running = if (met) 1 else 0
        if (goal > 0) {
            var week = monday.minusWeeks(1)
            val first = activeDays.minOrNull()
            while (first != null && !week.plusDays(6).isBefore(first) && daysIn(week) >= goal) {
                running++
                week = week.minusWeeks(1)
            }
        }
        return WeekGoal(goal, now, met, running)
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

/** [String.take] for user text: never leaves half an emoji (a lone surrogate) at the cut. */
fun String.limitText(max: Int): String {
    if (length <= max) return this
    val cut = take(max)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** "3 of 5 days this week", "5 of 5 days this week ✓", or without a goal "3 days this week". */
fun weekLine(daysSat: Int, goal: Int): String = when {
    goal <= 0 -> if (daysSat == 1) "1 day this week" else "$daysSat days this week"
    daysSat >= goal -> "$daysSat of $goal days this week ✓"
    else -> "$daysSat of $goal days this week"
}
