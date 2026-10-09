package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.random.Random

class ReadingCompareSettleTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 5)

    private fun sit(daysAgo: Long, minutes: Int = 20, hour: Int = 7) = SessionRecord(
        today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        plannedSec = minutes * 60,
        actualSec = minutes * 60,
    )

    // --- How you compare ---

    @Test
    fun `survey bands add up to everyone and run in order without gaps`() {
        for (survey in listOf(Compare.EXPERIENCED, Compare.INDIA)) {
            assertEquals(survey.who, 1.0, survey.bands.sumOf { it.share }, 1e-9)
            assertEquals(0.0, survey.bands.first().fromPerWeek, 0.0)
            assertEquals(7.0, survey.bands.last().toPerWeek, 0.0)
            survey.bands.zipWithNext().forEach { (a, b) ->
                assertTrue("${a.label} → ${b.label}", b.fromPerWeek >= a.fromPerWeek && b.fromPerWeek <= a.toPerWeek + 1e-9)
            }
        }
        // The published figures themselves.
        assertEquals(listOf(2, 4, 12, 11, 30, 41), Compare.EXPERIENCED.bands.map { Math.round(it.share * 100).toInt() })
        assertEquals(32, Math.round(Compare.INDIA.bands.last().share * 100).toInt())
        assertEquals(48, Math.round(Compare.INDIA.bands.drop(1).sumOf { it.share } * 100).toInt())
    }

    @Test
    fun `sitting more often never ranks you lower, and the top band never claims to beat everyone`() {
        for (survey in listOf(Compare.EXPERIENCED, Compare.INDIA)) {
            var last = -1
            var perWeek = 0.0
            while (perWeek <= 7.0001) {
                val p = Compare.place(survey, perWeek)
                assertTrue("${survey.who} at $perWeek", p.percentile in 0..99)
                assertTrue("${survey.who} at $perWeek: ${p.percentile} < $last", p.percentile >= last)
                last = p.percentile
                perWeek += 0.01
            }
            val top = Compare.place(survey, 7.0)
            assertTrue(top.inTopBand)
            // In the top band the percentile is a floor: everyone below that band.
            assertEquals(Math.round((1 - survey.bands.last().share) * 100).toInt(), top.percentile)
        }
    }

    @Test
    fun `placements match the published bands at known points`() {
        val e = Compare.EXPERIENCED
        assertEquals("daily", Compare.place(e, 7.0).band.label)
        assertEquals("Top 41%", Compare.headline(Compare.place(e, 7.0)))
        assertEquals("daily", Compare.place(e, 6.0).band.label)
        assertEquals("more than weekly, less than daily", Compare.place(e, 5.99).band.label)
        assertEquals("weekly", Compare.place(e, 1.0).band.label)
        assertEquals(18, Compare.place(e, 1.0).percentile) // 2 + 4 + 12 below
        assertEquals(29, Compare.place(e, 1.5).percentile) // + 11 weekly
        assertEquals(42, Compare.place(e, 3.5).percentile) // 29 + 30 × 2/4.5
        assertEquals("42nd percentile", Compare.headline(Compare.place(e, 3.5)))
        assertEquals("more than monthly, less than weekly", Compare.place(e, 0.25).band.label)
        val i = Compare.INDIA
        assertEquals("daily", Compare.place(i, 6.5).band.label)
        assertEquals("weekly, not daily", Compare.place(i, 2.0).band.label)
        assertEquals("less than weekly or never", Compare.place(i, 0.5).band.label)
    }

    @Test
    fun `standing needs a week of history and a sit in the window`() {
        assertNull(Compare.standing(emptyList(), zone, today))
        assertNull("six days of history", Compare.standing((0L..5L).map { sit(it) }, zone, today))
        assertNotNull("seven days", Compare.standing((0L..6L).map { sit(it) }, zone, today))
        // A long history but nothing in the last four weeks: nothing to rank.
        assertNull(Compare.standing(listOf(sit(40), sit(60)), zone, today))
    }

    @Test
    fun `standing counts distinct days in the last four weeks and minutes in the last thirty`() {
        // Daily for 60 days, twice on some days, and one entry dated tomorrow (a changed clock).
        val records = (0L..59L).map { sit(it) } + (0L..10L).map { sit(it, minutes = 5, hour = 19) } + sit(-1)
        val s = Compare.standing(records, zone, today)!!
        assertEquals(28, s.windowDays)
        assertEquals(28, s.daysSat)
        assertEquals(7.0, s.perWeek, 1e-9)
        assertEquals(30L * 20 + 11 * 5, s.minutesLast30)
        assertTrue(s.experienced.inTopBand)
        assertTrue(Compare.summary(s).startsWith("You sat on 28 of the last 28 days: daily, like the most regular 41%"))
        assertTrue(Compare.indiaLine(s).startsWith("Like you, 32%"))
        assertTrue(Compare.appLine(s).contains("Three in four"))
    }

    @Test
    fun `a newcomer is measured over the days since they started, not a full four weeks`() {
        val s = Compare.standing((0L..9L).filter { it % 2 == 1L }.map { sit(it) }, zone, today)!!
        assertEquals(10, s.windowDays)
        assertEquals(5, s.daysSat)
        assertEquals(3.5, s.perWeek, 1e-9)
        assertEquals("You sat on 5 of the last 10 days, more often than about 42% of experienced meditators.", Compare.summary(s))
    }

    @Test
    fun `app comparison thresholds follow the published median and upper quartile`() {
        fun line(minutes: Int) = Compare.appLine(Compare.standing((0L..6L).map { sit(it, minutes = 0) } + sit(0, minutes), zone, today)!!)
        assertTrue(line(75).contains("Three in four"))
        assertTrue(line(74).contains("more than half"))
        assertTrue(line(17).contains("more than half"))
        assertTrue(line(16).contains("Half of people new to a meditation app do 16 minutes or less"))
        assertTrue(line(1).contains("1 minute in"))
    }

    @Test
    fun `every possible month ranks within its band`() {
        // Brute force: every pattern of days sat in a 28-day window places within its band's bounds.
        val rnd = Random(11)
        repeat(3_000) {
            val days = (0L..27L).filter { rnd.nextInt(100) < rnd.nextInt(5, 100) }
            if (days.isEmpty()) return@repeat
            val records = days.map { sit(it) } + sit(40)
            val s = Compare.standing(records, zone, today)!!
            assertEquals(days.size, s.daysSat)
            val bands = Compare.EXPERIENCED.bands
            val i = bands.indexOf(s.experienced.band)
            val below = (bands.take(i).sumOf { it.share } * 100).toInt()
            val upTo = Math.round(bands.take(i + 1).sumOf { it.share } * 100).toInt()
            assertTrue("${days.size} days → ${s.experienced}", s.experienced.percentile in below..upTo)
        }
    }

    @Test
    fun `ordinals read naturally`() {
        val expected = mapOf(1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 11 to "11th", 12 to "12th", 13 to "13th",
            21 to "21st", 22 to "22nd", 23 to "23rd", 42 to "42nd", 99 to "99th", 101 to "101st", 111 to "111th")
        for ((n, s) in expected) assertEquals(s, Compare.ordinal(n))
    }

    // --- Common problems ---

    @Test
    fun `common problems answer the question, briefly, with checkable sources`() {
        val all = Problems.ALL
        assertTrue(all.size >= 12)
        assertEquals(all.size, all.map { it.title }.toSet().size)
        val source = Regex("""^[A-Z][^·]+ · [^·]+ · (\d{4})$""")
        for (p in all) {
            assertTrue(p.title, p.question.endsWith("?"))
            assertTrue(p.title, p.answer.isNotBlank() && p.answer.length <= 60)
            assertTrue(p.title, p.impact.length in 40..420)
            assertTrue(p.title, p.whatToDo.length in 40..420)
            assertTrue(p.title, p.studies.isNotEmpty())
            for (s in p.studies) {
                val m = source.matchEntire(s.source)
                assertNotNull("${p.title}: ${s.source}", m)
                assertTrue(s.source, m!!.groupValues[1].toInt() in 1950..2026)
                assertTrue(s.finding, s.finding.length in 30..260)
            }
        }
    }

    @Test
    fun `the problems you asked about are there, and their evidence is labelled honestly`() {
        val itch = Problems.ALL.single { it.title == "An itch" }
        assertTrue(itch.question.contains("scratch"))
        // Urge surfing was tested on smoking urges, so it's indirect evidence for an itch.
        assertTrue(itch.studies.all { it.indirect })
        val idea = Problems.ALL.single { "write" in it.title }
        assertTrue(idea.question.contains("stop to write"))
        assertTrue(idea.studies.all { it.indirect })
        // A case report is the weakest kind of evidence and says so.
        val legs = Problems.ALL.single { "Numb" in it.title }
        assertTrue(legs.studies.any { it.evidence == Evidence.CASE })
        assertTrue(Evidence.CASE.label.contains("not how often"))
    }

    @Test
    fun `each day's reading brings the next problem, and the list comes round again`() {
        val first = Reading.problemIndex(-1, null, today)
        assertEquals(0, first)
        assertEquals(0, Reading.problemIndex(first, today.toString(), today)) // same day, same problem
        assertEquals(1, Reading.problemIndex(first, today.minusDays(1).toString(), today))
        assertEquals(Problems.ALL.first(), Problems.forIndex(Problems.ALL.size))
        assertEquals(Problems.ALL.last(), Problems.forIndex(-1))
        // Opening the app every day for a year shows every problem, in turn.
        var index = -1
        var lastDay: String? = null
        val seen = mutableListOf<Problem>()
        for (d in 0L until 365L) {
            val day = today.plusDays(d)
            repeat(3) { // several opens a day
                index = Reading.problemIndex(index, lastDay, day)
                lastDay = day.toString()
            }
            seen += Problems.forIndex(index)
        }
        assertEquals(Problems.ALL.toSet(), seen.toSet())
        assertEquals(Problems.ALL, seen.take(Problems.ALL.size))
    }

    @Test
    fun `the reading is due once a day, until read or skipped`() {
        assertTrue(Reading.due(true, null, today))
        assertTrue(Reading.due(true, today.minusDays(1).toString(), today))
        assertFalse(Reading.due(true, today.toString(), today))
        assertFalse(Reading.due(false, null, today))
        assertFalse(Reading.due(false, today.minusDays(3).toString(), today))
    }

    // --- Settle-in breaths ---

    private fun cfg(min: Int, settle: Int, opening: Int = 5, closing: Int = 10, interval: Int = 0, end: Boolean = false) =
        SessionConfig(min * 60, opening, closing, end, interval, settle)

    @Test
    fun `settle-in is whole breaths and never more than half the sit`() {
        assertEquals(0, Settle.lengthMs(cfg(20, 0)))
        assertEquals(60_000, Settle.lengthMs(cfg(20, 60)))
        assertEquals(300_000, Settle.lengthMs(cfg(20, 300)))
        assertEquals(150_000, Settle.lengthMs(cfg(5, 300))) // half of a 5-minute sit
        assertEquals(30_000, Settle.lengthMs(cfg(1, 60))) // half of a 1-minute sit
        assertEquals(30_000, Settle.lengthMs(SessionConfig(70, 5, 10, false, 0, 60))) // 35 s → 3 whole breaths
        for (min in 1..180) for (s in Settle.CHOICES_SEC) {
            val ms = Settle.lengthMs(cfg(min, s))
            assertEquals(0L, ms % Settle.BREATH_MS)
            assertTrue(ms <= min * 60_000L / 2)
            assertTrue(ms <= s * 1000L)
        }
    }

    @Test
    fun `settle-in alternates four seconds in and six out`() {
        val len = 60_000L
        assertEquals(Settle.State(Settle.Phase.IN, 1, 6, 4_000), Settle.at(0, len))
        assertEquals(Settle.State(Settle.Phase.OUT, 1, 6, 6_000), Settle.at(4_000, len))
        assertEquals(Settle.State(Settle.Phase.OUT, 1, 6, 1), Settle.at(9_999, len))
        assertEquals(Settle.State(Settle.Phase.IN, 2, 6, 4_000), Settle.at(10_000, len))
        assertEquals(Settle.State(Settle.Phase.OUT, 6, 6, 1), Settle.at(59_999, len))
        assertNull(Settle.at(60_000, len))
        assertNull(Settle.at(-1, len))
        assertNull(Settle.at(0, 0))
        val ticks = Settle.ticks(len)
        assertEquals(12, ticks.size)
        assertEquals(ticks.sortedBy { it.first }, ticks)
        assertTrue(ticks.all { it.first in 0 until len })
        ticks.forEachIndexed { i, (_, phase) -> assertEquals(if (i % 2 == 0) Settle.Phase.IN else Settle.Phase.OUT, phase) }
        // Every tick lands exactly where the phase it announces begins.
        for ((at, phase) in ticks) assertEquals(phase, Settle.at(at, len)!!.phase)
    }

    @Test
    fun `the opening bell ends the settle-in`() {
        assertEquals(TimedCue(60_000, Cue.OPENING), BellSchedule.cues(cfg(20, 60)).first())
        assertEquals(TimedCue(5_000, Cue.OPENING), BellSchedule.cues(cfg(20, 0)).first())
        assertEquals(TimedCue(30_000, Cue.OPENING), BellSchedule.cues(cfg(20, 0, opening = 30)).first())
        // Without settle-in nothing changes from before.
        assertEquals(
            BellSchedule.cues(SessionConfig(20 * 60, 5, 10, false, 5)),
            BellSchedule.cues(cfg(20, 0, interval = 5)),
        )
    }

    @Test
    fun `bells with settle-in - every combination stays in the sit, in order, never crowded`() {
        for (minutes in 1..180) for (settle in Settle.CHOICES_SEC) for (opening in listOf(5, 30)) for (closing in listOf(5, 10, 30, 60))
            for (interval in listOf(0, 5, 15)) for (end in listOf(false, true)) {
                val config = cfg(minutes, settle, opening, closing, interval, end)
                val cues = BellSchedule.cues(config)
                val label = "$minutes min, settle $settle, open $opening, close $closing, every $interval, end $end"
                assertTrue(label, cues.all { it.atMs in 0..config.durationMs })
                assertEquals(label, cues.sortedBy { it.atMs }, cues)
                cues.zipWithNext().forEach { (a, b) -> assertTrue("$label: $a then $b", b.atMs - a.atMs >= BellSchedule.MIN_GAP_MS) }
                val opened = cues.single { it.cue == Cue.OPENING }
                // No bell rings during the settle-in; the opening bell comes at its end, or later.
                assertTrue(label, opened.atMs >= Settle.lengthMs(config))
                assertTrue(label, cues.none { it.atMs < Settle.lengthMs(config) })
                if (end) assertTrue(label, cues.last().cue == Cue.END)
            }
    }

    @Test
    fun `settle-in labels`() {
        assertEquals(listOf("Off", "30s", "1 min", "2 min", "5 min"), Settle.CHOICES_SEC.map(Settle::label))
        assertEquals(Settle.DEFAULT_SEC, 60)
        assertTrue(Settle.DEFAULT_SEC in Settle.CHOICES_SEC)
        assertTrue(abs(60_000.0 / Settle.BREATH_MS - 6.0) < 1e-9) // six breaths a minute
    }
}
