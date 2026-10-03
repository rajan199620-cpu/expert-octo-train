package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.random.Random

class RecapTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 1) // a Thursday

    private fun sit(date: LocalDate, minutes: Int = 20, hour: Int = 7, note: String = "", rating: Int = 0, before: Int = 0, after: Int = 0, noticed: Int = -1) =
        SessionRecord(date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), minutes * 60, minutes * 60, rating, note, before, after, noticed)

    @Test
    fun onThisDayPrefersTheLongestAgoNoteAndIgnoresSitsWithoutOne() {
        val records = listOf(
            sit(today.minusWeeks(1), note = "last week"),
            sit(today.minusMonths(1), note = "a month back"),
            sit(today.minusYears(1)), // no note: not a memory
            sit(today.minusDays(2), note = "not an anniversary"),
        )
        assertEquals(Memory("A month ago today", records[1]), Recap.onThisDay(records, zone, today))
        assertEquals("A year ago today", Recap.onThisDay(records + sit(today.minusYears(1), hour = 9, note = "first sit"), zone, today)!!.label)
        assertNull(Recap.onThisDay(listOf(sit(today.minusDays(2), note = "x")), zone, today))
        assertNull(Recap.onThisDay(emptyList(), zone, today))
        // Several notes that day: the latest one.
        val twice = listOf(sit(today.minusWeeks(1), hour = 6, note = "morning"), sit(today.minusWeeks(1), hour = 21, note = "evening"))
        assertEquals("evening", Recap.onThisDay(twice, zone, today)!!.record.note)
    }

    @Test
    fun monthEndsMapOntoShorterMonths() {
        // 31 March, a month later is 30 April (no 31st): the 31 March note shows then.
        val note = sit(LocalDate.of(2026, 3, 31), note = "end of March")
        assertEquals("A month ago today", Recap.onThisDay(listOf(note), zone, LocalDate.of(2026, 4, 30))!!.label)
        // A leap-day note comes back on 28 February the next year.
        val leap = sit(LocalDate.of(2028, 2, 29), note = "leap day")
        assertEquals("A year ago today", Recap.onThisDay(listOf(leap), zone, LocalDate.of(2029, 2, 28))!!.label)
        // Not on other days: 29 April doesn't pick up 31 March.
        assertNull(Recap.onThisDay(listOf(note), zone, LocalDate.of(2026, 4, 29)))
        // Every note of the year comes back as "a month ago" on some day, whatever its date.
        var day = LocalDate.of(2026, 1, 1)
        while (day.year == 2026) {
            val n = sit(day, note = "n")
            val seen = (1..40L).any { Recap.onThisDay(listOf(n), zone, day.plusDays(it))?.label == "A month ago today" }
            assertTrue("$day never comes back a month later", seen)
            day = day.plusDays(1)
        }
    }

    @Test
    fun recapOpensOnLastFinishedMonthAndBrowsesOnlyMonthsWithSits() {
        val records = listOf(sit(LocalDate.of(2026, 7, 3)), sit(LocalDate.of(2026, 9, 10)), sit(today))
        val months = Recap.months(records, zone, today)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2026, 7)), months)
        assertEquals(YearMonth.of(2026, 9), Recap.defaultMonth(months, today))
        assertEquals(YearMonth.of(2026, 10), Recap.defaultMonth(listOf(YearMonth.of(2026, 10)), today))
        assertNull(Recap.defaultMonth(emptyList(), today))
    }

    @Test
    fun monthRecapAddsUpTheMonth() {
        val sep = YearMonth.of(2026, 9)
        val records = listOf(
            sit(LocalDate.of(2026, 8, 31), minutes = 30), // August: the comparison
            sit(LocalDate.of(2026, 9, 1), minutes = 10, rating = 4, before = 1, after = 4, noticed = 6),
            sit(LocalDate.of(2026, 9, 1), minutes = 20, hour = 20, rating = 2, before = 3, after = 3, noticed = 2),
            sit(LocalDate.of(2026, 9, 15), minutes = 45, rating = 4, note = "long one"),
            sit(LocalDate.of(2026, 9, 16), minutes = 20, rating = 2),
            sit(LocalDate.of(2026, 10, 1), minutes = 60), // October: not counted
        )
        val r = Recap.month(records, zone, sep, today)!!
        assertEquals(4, r.sits)
        assertEquals((10 + 20 + 45 + 20) * 60L, r.totalSec)
        assertEquals(3, r.daysSat)
        assertEquals(30, r.daysSoFar)
        assertEquals(45 * 60, r.longestSec)
        assertEquals(LocalDate.of(2026, 9, 14), r.bestWeekStart)
        assertEquals(65 * 60L, r.bestWeekSec)
        assertEquals(1.5, r.averageShift!!, 1e-9)
        assertEquals(2, r.shiftSits)
        assertEquals(2, r.commonRating) // 4 and 2 tie twice each; 2 is the more recent
        assertEquals(8 * 600.0 / (30 * 60), r.noticingPerTenMin!!, 1e-9)
        assertEquals(1, r.notes)
        assertEquals(30 * 60L, r.previousTotalSec)
        assertTrue(!r.inProgress)
        assertNull(Recap.month(records, zone, YearMonth.of(2026, 6), today))
    }

    @Test
    fun bestWeekStartingInThePreviousMonthIsNamedByThe1st() {
        // 1 Sept 2026 is a Tuesday; its week began on Monday 31 August.
        val r = Recap.month(listOf(sit(LocalDate.of(2026, 9, 1), minutes = 50), sit(LocalDate.of(2026, 9, 20))), zone, YearMonth.of(2026, 9), today)!!
        assertEquals(LocalDate.of(2026, 9, 1), r.bestWeekStart)
    }

    @Test
    fun currentMonthIsSoFarAndOptionalPartsAreNullWithoutData() {
        val r = Recap.month(listOf(sit(today)), zone, YearMonth.from(today), today)!!
        assertTrue(r.inProgress)
        assertEquals(1, r.daysSoFar)
        assertNull(r.averageShift)
        assertNull(r.commonRating)
        assertNull(r.noticingPerTenMin)
        assertNull(r.previousTotalSec)
    }

    @Test
    fun recapReadyNudgeOnlyInTheFirstDaysAndOnlyIfLastMonthHadSits() {
        val sept = listOf(sit(LocalDate.of(2026, 9, 12)))
        assertEquals(YearMonth.of(2026, 9), Recap.recapReady(sept, zone, today))
        assertEquals(YearMonth.of(2026, 9), Recap.recapReady(sept, zone, LocalDate.of(2026, 10, 3)))
        assertNull(Recap.recapReady(sept, zone, LocalDate.of(2026, 10, 4)))
        assertNull(Recap.recapReady(listOf(sit(LocalDate.of(2026, 8, 12))), zone, today))
        // January looks back to December of the year before.
        assertEquals(YearMonth.of(2026, 12), Recap.recapReady(listOf(sit(LocalDate.of(2026, 12, 31))), zone, LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun stressRandomHistoriesGivePossibleNumbers() {
        val rnd = Random(11)
        repeat(1_000) {
            val records = List(rnd.nextInt(0, 120)) {
                sit(
                    today.minusDays(rnd.nextLong(0, 800)), minutes = rnd.nextInt(1, 120), hour = rnd.nextInt(0, 24),
                    note = if (rnd.nextInt(4) == 0) "n" else "", rating = rnd.nextInt(0, 6),
                    before = rnd.nextInt(0, 6), after = rnd.nextInt(0, 6), noticed = rnd.nextInt(-1, 40),
                )
            }
            Recap.onThisDay(records, zone, today)?.let { assertTrue(it.record.note.isNotBlank()) }
            val months = Recap.months(records, zone, today)
            assertEquals(months.sortedDescending(), months)
            var total = 0L
            for (m in months) {
                val r = Recap.month(records, zone, m, today)!!
                total += r.totalSec
                assertTrue(r.daysSat in 1..r.daysSoFar)
                assertTrue(r.bestWeekSec in 1..r.totalSec)
                assertEquals(m, YearMonth.from(r.bestWeekStart))
                assertTrue(r.longestSec <= r.totalSec)
                r.averageShift?.let { assertTrue(it in -4.0..4.0) }
                r.commonRating?.let { assertTrue(it in 1..5) }
                r.noticingPerTenMin?.let { assertTrue(it >= 0 && it.isFinite()) }
            }
            assertEquals(records.sumOf { it.actualSec.toLong() }, total)
        }
    }
}
