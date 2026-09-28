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
) {
    val completed: Boolean get() = actualSec >= plannedSec

    fun day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()

    // The note is URL-encoded so commas and newlines in it can't break the line format.
    fun encode(): String = "$startedAtMs,$plannedSec,$actualSec,$rating,${URLEncoder.encode(note, "UTF-8")}"

    companion object {
        /**
         * Returns null for a corrupt line so one bad write never loses the whole history.
         * Accepts the original 3-field lines (before reflections existed) as well as 5-field ones.
         */
        fun decode(line: String): SessionRecord? {
            val parts = line.trim().split(',')
            if (parts.size != 3 && parts.size != 5) return null
            return SessionRecord(
                startedAtMs = parts[0].toLongOrNull() ?: return null,
                plannedSec = parts[1].toIntOrNull() ?: return null,
                actualSec = parts[2].toIntOrNull() ?: return null,
                rating = if (parts.size == 5) parts[3].toIntOrNull() ?: return null else 0,
                note = if (parts.size == 5) runCatching { URLDecoder.decode(parts[4], "UTF-8") }.getOrNull() ?: return null else "",
            )
        }
    }
}

data class MoodPoint(val startedAtMs: Long, val date: LocalDate, val rating: Int, val rollingAverage: Double)

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

    /** Spreadsheet-friendly export, oldest first; optional settings line first. */
    fun toCsv(records: List<SessionRecord>, zone: ZoneId, settings: Map<String, String>? = null): String {
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
            ).joinToString(",")
        }
        val header = listOfNotNull(settings?.let(::settingsLine), "date,start,planned_min,actual_min,rating,note")
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
