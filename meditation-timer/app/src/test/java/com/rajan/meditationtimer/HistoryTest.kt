package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 27)

    private fun at(date: LocalDate, hour: Int = 7, minutes: Int = 20, actualMin: Int = minutes) = SessionRecord(
        startedAtMs = LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
        plannedSec = minutes * 60,
        actualSec = actualMin * 60,
    )

    private fun days(vararg offsets: Long) = offsets.map { today.minusDays(it) }.toSet()

    @Test
    fun currentStreakCountsBackFromToday() {
        assertEquals(3, History.currentStreak(days(0, 1, 2, 4), today))
    }

    @Test
    fun streakSurvivesUntilTodayIsOver() {
        // Sat yesterday and the day before, not yet today -> still a 2-day streak.
        assertEquals(2, History.currentStreak(days(1, 2), today))
    }

    @Test
    fun missingAWholeDayBreaksTheStreak() {
        assertEquals(0, History.currentStreak(days(2, 3, 4), today))
        assertEquals(0, History.currentStreak(emptySet(), today))
    }

    @Test
    fun longestStreakFindsBestRun() {
        assertEquals(4, History.longestStreak(days(0, 1, 5, 6, 7, 8, 12)))
        assertEquals(0, History.longestStreak(emptySet()))
        assertEquals(1, History.longestStreak(days(3)))
    }

    @Test
    fun sessionCountsTowardsTheLocalDayItStarted() {
        // Midnight IST on the 27th is still 18:30 on the 26th in UTC: the local day must win.
        val lateNight = at(today.minusDays(1), hour = 23)
        val justAfterMidnight = at(today, hour = 0)
        assertEquals(today.minusDays(1), lateNight.day(zone))
        assertEquals(today, justAfterMidnight.day(zone))
        assertEquals(today.minusDays(1), justAfterMidnight.day(ZoneId.of("UTC")))
    }

    @Test
    fun summaryTotalsGroupsAndWeek() {
        val records = listOf(
            at(today, hour = 7, minutes = 20),
            at(today, hour = 21, minutes = 10, actualMin = 6), // ended early
            at(today.minusDays(1), minutes = 15),
            at(today.minusDays(9), minutes = 30), // outside the 7-day window
        )
        val s = History.summarize(records, zone, today)
        assertEquals(2, s.currentStreak)
        assertEquals(2, s.longestStreak)
        assertEquals(4, s.sessionCount)
        assertEquals((20 + 6 + 15 + 30) * 60L, s.totalSec)
        assertEquals((20 + 6 + 15) * 60L, s.last7DaysSec)
        assertEquals(listOf(today, today.minusDays(1), today.minusDays(9)), s.days.map { it.date })
        assertEquals(26 * 60, s.days[0].totalSec)
        // Newest first within a day: the 21:00 sit precedes the 07:00 one.
        assertEquals(14 * 3_600_000L, s.days[0].sessions[0].startedAtMs - s.days[0].sessions[1].startedAtMs)
        assertFalse(s.days[0].sessions[0].completed)
        assertTrue(s.days[0].sessions[1].completed)
        assertEquals(7, s.last7Days.size)
        assertEquals(today, s.last7Days.last().first)
        assertEquals(listOf(false, false, false, false, false, true, true), s.last7Days.map { it.second })
    }

    @Test
    fun recordsRoundTripAndBadLinesAreSkipped() {
        val r = SessionRecord(1_790_000_000_000, 1200, 1185)
        assertEquals(r, SessionRecord.decode(r.encode()))
        assertNull(SessionRecord.decode(""))
        assertNull(SessionRecord.decode("12,abc,3"))
        assertNull(SessionRecord.decode("1,2"))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("1 min", formatDuration(60))
        assertEquals("45 min", formatDuration(45 * 60 + 59))
        assertEquals("1h", formatDuration(3600))
        assertEquals("1h 20m", formatDuration(80 * 60))
    }

    @Test
    fun reflectionsRoundTripIncludingAwkwardNotes() {
        val r = SessionRecord(1_790_000_000_000, 1200, 1200, rating = 4, note = "calm, then \"busy\"\nmind — 100% ok")
        assertEquals(r, SessionRecord.decode(r.encode()))
        assertEquals(1, r.encode().lines().size)
        // Lines written before reflections existed still load.
        assertEquals(SessionRecord(5, 60, 60), SessionRecord.decode("5,60,60"))
        assertNull(SessionRecord.decode("5,60,60,x,"))
    }

    @Test
    fun heatmapIsWeeksOfMondayToSunday() {
        // 2026-09-27 is a Sunday, so the last column is a full week ending today.
        val records = listOf(at(today, minutes = 20), at(today, hour = 20, minutes = 5), at(today.minusDays(6), minutes = 10))
        val grid = History.heatmap(records, zone, today, weeks = 2)
        assertEquals(2, grid.size)
        assertEquals(listOf(10, 0, 0, 0, 0, 0, 25), grid[1])
        assertEquals(List(7) { 0 }, grid[0])

        val midweek = History.heatmap(records, zone, LocalDate.of(2026, 9, 23), weeks = 1) // Wednesday
        assertEquals(listOf(10, 0, 0, null, null, null, null), midweek[0])
    }

    @Test
    fun csvExportQuotesNotesAndSortsOldestFirst() {
        val csv = History.toCsv(
            listOf(at(today, minutes = 20).copy(rating = 5, note = "deep, still"), at(today.minusDays(1), minutes = 15, actualMin = 7)),
            zone,
        )
        assertEquals(
            "date,start,planned_min,actual_min,rating,note\n" +
                "2026-09-26,07:00,15.0,7.0,,\n" +
                "2026-09-27,07:00,20.0,20.0,5,\"deep, still\"\n",
            csv,
        )
    }

    @Test
    fun backupRestoresExactlyWhatWasExported() {
        val records = listOf(
            at(today.minusDays(3), minutes = 20).copy(rating = 4, note = "quiet, \"still\"\nand warm"),
            at(today, hour = 21, minutes = 15, actualMin = 9),
        )
        assertEquals(records, History.fromCsv(History.toCsv(records, zone), zone))
    }

    @Test
    fun restoreSkipsHeaderAndBrokenRowsAndAcceptsWindowsLineEndings() {
        val csv = "date,start,planned_min,actual_min,rating,note\r\n" +
            "2026-09-27,07:00,20.0,20.0,,\r\n" +
            "not a date,07:00,20,20,,\r\n" +
            "2026-09-26,06:30,10,10,9,\r\n"
        val restored = History.fromCsv(csv, zone)
        assertEquals(2, restored.size)
        assertEquals(0, restored[1].rating) // out-of-range rating dropped, session kept
        assertEquals(LocalDate.of(2026, 9, 26), restored[1].day(zone))
    }

    @Test
    fun moodTrendKeepsRatedSitsInWindowWithRollingAverage() {
        val records = listOf(
            at(today.minusDays(100), minutes = 10).copy(rating = 1), // outside 84 days
            at(today.minusDays(3), minutes = 10).copy(rating = 2),
            at(today.minusDays(2), minutes = 10), // not rated
            at(today.minusDays(2), hour = 20, minutes = 10).copy(rating = 4),
            at(today, minutes = 10).copy(rating = 3),
        )
        val trend = History.moodTrend(records, zone, today, window = 2)
        assertEquals(listOf(2, 4, 3), trend.map { it.rating })
        assertEquals(listOf(2.0, 3.0, 3.5), trend.map { it.rollingAverage })
        assertEquals(today, trend.last().date)
    }

    @Test
    fun mostCommonRatingPrefersRecentOnTies() {
        val points = History.moodTrend(
            listOf(4, 4, 2, 2).mapIndexed { i, r -> at(today.minusDays(4L - i), minutes = 10).copy(rating = r) },
            zone,
            today,
        )
        assertEquals(2, History.mostCommonRating(points))
        assertNull(History.mostCommonRating(emptyList()))
    }
}
