package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

class StreakGoalBreathTest {
    private val today = LocalDate.of(2026, 10, 7) // a Wednesday
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }.toSet()

    @Test
    fun oneMissedDayAWeekIsARestNotABreak() {
        // Sat every day for two weeks except last Thursday: still one streak of 13 sits.
        val sat = (0L..13L).filter { it != 6L }.map { today.minusDays(it) }.toSet()
        val s = History.streak(sat, today)
        assertEquals(13, s.days)
        assertEquals(1, s.restsUsed)
        // A second miss in that same week breaks it there.
        val twice = sat - today.minusDays(5)
        assertEquals(5, History.streak(twice, today).days)
    }

    @Test
    fun todayIsNeverAMissUntilItsOver() {
        assertEquals(3, History.streak(days(1, 2, 3), today).days)
        assertEquals(0, History.streak(days(1, 2, 3), today).restsUsed)
        assertEquals(0, History.streak(emptySet(), today).days)
    }

    @Test
    fun aStreakNeverBeginsOrEndsWithAnUnusedRest() {
        // Only sat yesterday: the day before is not a "rest used", nothing comes before it.
        assertEquals(Streak(1, 0, false), History.streak(days(1), today))
        // Missed yesterday (a rest), sat the two days before: the rest counts and is this week's.
        val s = History.streak(days(2, 3), today)
        assertEquals(2, s.days)
        assertEquals(1, s.restsUsed)
        assertTrue(s.restThisWeek)
    }

    /** The definition, checked the slow way: the longest run back from the end in which no week has two misses. */
    private fun bruteStreak(sat: Set<LocalDate>, today: LocalDate): Int {
        val end = if (today in sat) today else today.minusDays(1)
        var best = 0
        for (start in sat.filter { !it.isAfter(end) }) {
            val misses = HashMap<LocalDate, Int>()
            var ok = true
            var d = start
            while (!d.isAfter(end)) {
                if (d !in sat) {
                    val w = d.with(DayOfWeek.MONDAY)
                    misses[w] = (misses[w] ?: 0) + 1
                    if (misses[w]!! > 1) { ok = false; break }
                }
                d = d.plusDays(1)
            }
            if (ok) best = maxOf(best, sat.count { !it.isBefore(start) && !it.isAfter(end) })
        }
        return best
    }

    @Test
    fun forgivingStreaksMatchTheDefinitionOnRandomCalendars() {
        val rnd = Random(21)
        repeat(1_500) {
            val density = rnd.nextDouble(0.3, 1.0)
            val sat = (0L until rnd.nextLong(0, 70)).filter { rnd.nextDouble() < density }.map { today.minusDays(it) }.toSet()
            assertEquals("calendar $sat", bruteStreak(sat, today), History.streak(sat, today).days)
            val longest = History.longestForgivingStreak(sat)
            val bruteLongest = sat.maxOfOrNull { bruteStreak(sat, it) } ?: 0
            assertEquals(bruteLongest, longest)
            assertTrue(longest >= History.longestStreak(sat)) // forgiving never shorter than strict
            assertTrue(History.streak(sat, today).days >= History.currentStreak(sat, today))
        }
    }

    @Test
    fun forgivingStreakIsFastOnYearsOfHistory() {
        val sat = (0L until 5 * 365).filter { it % 9 != 4L }.map { today.minusDays(it) }.toSet()
        val t = System.nanoTime()
        History.streak(sat, today)
        History.longestForgivingStreak(sat)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 2_000)
    }

    @Test
    fun weeklyGoalCountsThisWeekAndWeeksRunning() {
        // Wednesday: Mon and Tue sat this week; the three weeks before had 5, 4 and 6 days.
        val sat = mutableSetOf(today.minusDays(1), today.minusDays(2))
        fun week(back: Long, n: Int) = (0 until n).forEach { sat += today.with(DayOfWeek.MONDAY).minusWeeks(back).plusDays(it.toLong()) }
        week(1, 5); week(2, 4); week(3, 6)
        val g = History.weekGoal(sat, today, goal = 4)
        assertEquals(2, g.daysThisWeek)
        assertFalse(g.met)
        assertEquals(2, g.daysLeft)
        assertEquals(3, g.weeksRunning) // this week isn't over: it doesn't break the run
        val g5 = History.weekGoal(sat, today, goal = 5)
        assertEquals(1, g5.weeksRunning) // the week with 4 ends it
        // Once met, this week counts too.
        val met = History.weekGoal(sat + today, today, goal = 3)
        assertTrue(met.met)
        assertEquals(4, met.weeksRunning)
        assertEquals(0, History.weekGoal(emptySet(), today, 4).weeksRunning)
    }

    @Test
    fun weekGoalNeverCountsTheFuture() {
        val g = History.weekGoal(setOf(today.plusDays(1), today.plusDays(2)), today, goal = 1)
        assertEquals(0, g.daysThisWeek)
    }

    @Test
    fun alternateNostrilAlternatesAndEndsOnAWholeRound() {
        val p = BreathPattern.ALL.first { it.style == BreathStyle.ALTERNATE }
        assertEquals("In · left nostril", p.cue(p.at(0)))
        assertEquals("Out · right nostril", p.cue(p.at(5_000)))
        assertEquals("In · right nostril", p.cue(p.at(p.cycleMs + 100)))
        assertEquals("Out · left nostril", p.cue(p.at(p.cycleMs + 5_000)))
        for (m in listOf(1, 3, 5, 10)) {
            val breaths = p.breathsFor(m)
            assertEquals(0L, breaths % 2)
            assertTrue(breaths * p.cycleMs >= m * 60_000L)
            assertTrue(breaths * p.cycleMs < m * 60_000L + 2 * p.cycleMs)
        }
    }

    @Test
    fun bhramariHumsOnTheOutBreathOnly() {
        val p = BreathPattern.ALL.first { it.style == BreathStyle.HUM }
        assertEquals("Breathe in", p.cue(p.at(1_000)))
        assertEquals("Hum softly", p.cue(p.at(6_000)))
        assertTrue(p.exhaleSec > p.inhaleSec) // a long, slow out-breath
    }

    @Test
    fun everyRhythmIsSafeAndSaysWhatItIs() {
        for (p in BreathPattern.ALL) {
            assertTrue(p.cycleMs in 6_000..20_000)
            // Every moment of a session has a cue, and plain rhythms say the plain phase.
            for (t in 0 until 60_000 step 250) assertTrue(p.cue(p.at(t.toLong())).isNotBlank())
            if (p.style != BreathStyle.PLAIN) {
                assertTrue(p.name, p.evidence != null && p.howTo != null)
                assertEquals("no breath-holding in the new practices", 0.0, p.holdInSec + p.holdOutSec, 0.0)
            }
        }
        assertEquals(BreathPattern.ALL.size, BreathPattern.ALL.map { it.name }.toSet().size)
    }
}
