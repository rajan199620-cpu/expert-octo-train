package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Where you stand" moves with your practice: it rises when you sit more or longer, eases when
 * you sit less, and answers "16 minutes a day for two weeks: where am I?" with numbers that
 * add up.
 */
class ProgressTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 9)

    private fun sit(daysAgo: Long, minutes: Int) =
        SessionRecord(today.minusDays(daysAgo).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), minutes * 60, minutes * 60)

    /** A sit of [minutes] on each of the last [days] days, today included, except [skip] (days ago). */
    private fun daily(days: Int, minutes: Int, skip: Set<Long> = emptySet(), from: Long = 0) =
        (from until from + days).filter { it !in skip }.map { sit(it, minutes) }

    private fun report(records: List<SessionRecord>) = Progress.report(records, zone, today)!!

    @Test
    fun `sixteen minutes a day for two weeks - where you stand`() {
        val r = report(daily(14, 16))
        assertEquals(16.0, r.perDay, 1e-9)
        assertEquals(14, r.windowDays)
        assertEquals(Trend.STEADY, r.trend)
        assertEquals(14, r.runDays)
        assertEquals(16.0, r.runPerDay, 1e-9)
        assertEquals(224L, r.lifetimeMinutes)
        assertEquals(22.6, r.nextMark!!.hours, 1e-9)
        // (22.6 h − 224 min) at 16 min a day: 70.75 days.
        assertEquals(71, r.daysToNext)
        assertEquals("16 min a day", Progress.headline(r))
        assertEquals("your average since you started, 14 days ago  ·  this week 16 min a day", Progress.windowLine(r))
        assertEquals(
            "2 weeks in, at 16 min a day: above the 13 a day of a trial in which beginners' attention, memory and " +
                "mood improved after 8 weeks, though not yet at 4. 6 weeks to go to match it.",
            Progress.runLine(r),
        )
        assertEquals(
            "3.7 hours in all. Next: 22.6 hours, an 8-week MBSR course's practice, about 10 weeks away at this pace.",
            Progress.hoursLine(r),
        )
        assertEquals("Day 14 of your run", Progress.habitLine(r).substringBefore("."))
        // A flat practice draws a flat line, ending where the headline is.
        assertEquals(14, r.curve.size)
        assertTrue(r.curve.all { it == 16.0 })
    }

    @Test
    fun `longer sits this week lift the level and say so`() {
        val r = report(daily(7, 30) + daily(21, 16, from = 7))
        assertEquals((7 * 30 + 21 * 16) / 28.0, r.perDay, 1e-9)
        assertEquals(30.0, r.thisWeekPerDay!!, 1e-9)
        assertEquals(Trend.BUILDING, r.trend)
        assertTrue("the line climbs to today", r.curve.last() > r.curve[r.curve.size - 8])
    }

    @Test
    fun `skipping days eases the level, but a few missed days don't end the run`() {
        val steady = report(daily(28, 16))
        // The last four days missed: no sit since four days ago.
        val r = report(daily(28, 16, skip = setOf(0, 1, 2, 3)))
        assertTrue(r.perDay < steady.perDay)
        assertEquals(Trend.EASING, r.trend)
        assertEquals("the run goes on: a missed day or two doesn't break it", 28, r.runDays)
        assertTrue(r.curve.last() < r.curve.first())
        // A single missed day in the middle doesn't move the trend word.
        assertEquals(Trend.STEADY, report(daily(28, 16, skip = setOf(10))).trend)
    }

    @Test
    fun `a full week without a sit ends the run, and the next sit starts a new one`() {
        val lapsed = report(daily(20, 16, from = 8))
        assertEquals(0, lapsed.runDays)
        assertTrue(Progress.runLine(lapsed).startsWith("Your last sit was over a week ago."))
        // Six empty days between sits keeps the run; seven ends it.
        assertEquals(15, report(daily(8, 16, from = 7) + sit(0, 16)).runDays)
        assertEquals(1, report(daily(8, 16, from = 8) + sit(0, 16)).runDays)
        // Lifetime hours never go down.
        assertEquals(20 * 16L, lapsed.lifetimeMinutes)
    }

    @Test
    fun `the trial comparison follows the dose and the weeks`() {
        assertTrue(Progress.runLine(report(daily(14, 10))).contains("below the 13 a day"))
        val eight = report(daily(60, 20))
        assertEquals(20.0, eight.runPerDay, 1e-9)
        assertTrue(Progress.runLine(eight).startsWith("8 weeks in, at 20 min a day: past the 8 weeks of 13 a day"))
        assertTrue(Progress.runLine(report(daily(50, 14))).endsWith("1 week to go to match it."))
        assertTrue(Progress.runLine(report(daily(3, 15))).startsWith("3 days in, at 15 min a day"))
    }

    @Test
    fun `lifetime markers step up as the hours grow, then run out`() {
        assertEquals(160.0, report(daily(100, 30)).nextMark!!.hours, 1e-9)
        val veteran = report(listOf(sit(0, 60), SessionRecord(sit(1, 1).startedAtMs, 720_000 * 60, 720_000 * 60)))
        assertNull(veteran.nextMark)
        assertTrue(Progress.hoursLine(veteran).contains("past every marker"))
        // No sits lately: no "away at this pace" to promise.
        val idle = report(daily(5, 30, from = 40))
        assertNull(idle.daysToNext)
        assertEquals("2.5 hours in all. Next: 22.6 hours, an 8-week MBSR course's practice.", Progress.hoursLine(idle))
        assertEquals("0 min a day", Progress.headline(idle))
    }

    @Test
    fun `the curve covers up to twelve weeks and future sits are ignored`() {
        val r = report(daily(200, 10) + sit(-3, 500))
        assertEquals(Progress.CURVE_DAYS, r.curve.size)
        assertEquals(10.0, r.perDay, 1e-9)
        assertEquals(2000L, r.lifetimeMinutes)
        assertNull(Progress.report(emptyList(), zone, today))
        assertNull(Progress.report(listOf(sit(-1, 20)), zone, today))
    }

    @Test
    fun `wording of numbers and times`() {
        assertEquals("5 days", Progress.eta(5))
        assertEquals("about 10 weeks", Progress.eta(71))
        assertEquals("about 19 months", Progress.eta(586))
        assertEquals("about 3 years", Progress.eta(1000))
        assertEquals("under 1 min", Progress.minutes(0.3))
        assertEquals("1,095 hours", Progress.hours(1095.0))
        assertEquals(Trend.STEADY, Progress.trend(17.0, 16.0))
        assertEquals(Trend.BUILDING, Progress.trend(19.0, 16.0))
        assertEquals(Trend.EASING, Progress.trend(13.0, 16.0))
        assertEquals("small numbers need a whole minute's change", Trend.STEADY, Progress.trend(0.5, 1.0))
    }

    @Test
    fun `every research landmark names its source`() {
        for (d in Progress.DOSES) assertTrue(d.source, d.source.matches(Regex(".+ · .+ · (19|20)\\d\\d")))
        for (h in Progress.HOURS) assertTrue(h.source, h.source.contains(Regex("(19|20)\\d\\d")))
        assertEquals(Progress.DOSES.sortedBy { it.minutesPerDay }, Progress.DOSES)
        assertEquals(Progress.HOURS.sortedBy { it.hours }, Progress.HOURS)
    }
}
