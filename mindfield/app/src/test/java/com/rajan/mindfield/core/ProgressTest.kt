package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.random.Random

class ProgressTest {
    private val library = TestLibrary.library
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 7) // a Wednesday
    private var n = 0

    private fun entry(day: LocalDate, concept: String = library.all.first().id, mode: Mode = Mode.SPOTTED, deleted: Boolean = false) =
        Entry("e${n++}", concept, day, mode, "note", if (mode == Mode.USED) Outcome.WORKED else null, 1, 1, deleted)

    private fun millis(day: LocalDate) = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    /** The definition, the slow way: the longest run back from the end in which no week has two misses. */
    private fun bruteStreak(days: Set<LocalDate>, today: LocalDate): Int {
        val end = if (today in days) today else today.minusDays(1)
        var best = 0
        for (start in days.filter { !it.isAfter(end) }) {
            val misses = HashMap<LocalDate, Int>()
            var ok = true
            var d = start
            while (!d.isAfter(end)) {
                if (d !in days) {
                    val w = d.with(DayOfWeek.MONDAY)
                    misses[w] = (misses[w] ?: 0) + 1
                    if (misses[w]!! > 1) { ok = false; break }
                }
                d = d.plusDays(1)
            }
            if (ok) best = maxOf(best, days.count { !it.isBefore(start) && !it.isAfter(end) })
        }
        return best
    }

    @Test
    fun `forgiving streaks match the definition on random calendars`() {
        val rnd = Random(5)
        repeat(1_500) {
            val density = rnd.nextDouble(0.3, 1.0)
            val days = (0L until rnd.nextLong(0, 70)).filter { rnd.nextDouble() < density }.map { today.minusDays(it) }.toSet()
            assertEquals("calendar $days", bruteStreak(days, today), Progress.streak(days, today).days)
            assertEquals(days.maxOfOrNull { bruteStreak(days, it) } ?: 0, Progress.longestStreak(days))
        }
    }

    @Test
    fun `a rest only counts between two reports, and says when it was this week`() {
        assertEquals(Streak(1, 0, false), Progress.streak(setOf(today.minusDays(1)), today))
        val s = Progress.streak(setOf(today.minusDays(2), today.minusDays(3)), today) // missed yesterday
        assertEquals(2, s.days)
        assertEquals(1, s.restsUsed)
        assertTrue(s.restThisWeek)
        assertEquals(Streak(0, 0, false), Progress.streak(emptySet(), today))
    }

    @Test
    fun `weekly goal counts this week's days and the weeks it was met`() {
        val monday = today.with(DayOfWeek.MONDAY)
        // Five days in each of the last two weeks, two so far this week.
        val days = (1L..2L).flatMap { w -> (0L..4L).map { monday.minusWeeks(w).plusDays(it) } }.toSet() + setOf(monday, monday.plusDays(1))
        val g = Progress.weekGoal(days, today, 5)
        assertEquals(WeekGoal(5, 2, false, 2), g)
        assertEquals(3, g.daysLeft)
        assertEquals(WeekGoal(2, 2, true, 3), Progress.weekGoal(days, today, 2))
        assertEquals(WeekGoal(0, 2, false, 0), Progress.weekGoal(days, today, 0))
        // Future days never count.
        assertEquals(2, Progress.weekGoal(days + today.plusDays(2), today, 5).daysThisWeek)
    }

    @Test
    fun `the goal line never counts down to a goal the week can no longer reach`() {
        val monday = today.with(DayOfWeek.MONDAY) // today is a Wednesday
        val days = setOf(monday)
        assertEquals(5, Progress.openDays(days, today)) // Wednesday to Sunday
        assertEquals(4, Progress.openDays(days + today, today))
        val reachable = Progress.weekGoal(days, today, 5)
        assertEquals("1 of 5 days this week · 4 to go", Progress.goalLine(reachable, Progress.openDays(days, today)))
        val outOfReach = Progress.weekGoal(days, today, 7)
        val line = Progress.goalLine(outOfReach, Progress.openDays(days, today))
        assertTrue(line, line.startsWith("1 of 7 days this week") && "to go" !in line)
        assertEquals("Goal met ✓ · 1 of 1 days this week", Progress.goalLine(Progress.weekGoal(days, today, 1), 5))
        assertEquals("1 day this week", Progress.goalLine(Progress.weekGoal(days, today, 0), 5))
    }

    @Test
    fun `month in review adds up from the journal`() {
        val sept = YearMonth.of(2026, 9)
        val ids = library.all.map { it.id }
        val assignments = (1..20).associate { d -> LocalDate.of(2026, 9, d) to Assignment(ids[d], 1L) } +
            mapOf(LocalDate.of(2026, 10, 1) to Assignment(ids[30], 1L))
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 3), ids[3], Mode.SPOTTED),
            entry(LocalDate.of(2026, 9, 3), ids[3], Mode.MYSELF),
            entry(LocalDate.of(2026, 9, 5), ids[3], Mode.USED),
            entry(LocalDate.of(2026, 9, 9), ids[9], Mode.NOT_TODAY),
            entry(LocalDate.of(2026, 9, 10), ids[10], Mode.SPOTTED, deleted = true),
            entry(LocalDate.of(2026, 8, 30), ids[1], Mode.SPOTTED),
            entry(LocalDate.of(2026, 10, 2), ids[30], Mode.SPOTTED),
        )
        val guesses = mapOf(
            ids[3] to Guess(library[ids[3]]!!.predict.answer, millis(LocalDate.of(2026, 9, 3))),
            ids[4] to Guess((library[ids[4]]!!.predict.answer + 1) % library[ids[4]]!!.predict.options.size, millis(LocalDate.of(2026, 9, 4))),
            ids[30] to Guess(0, millis(LocalDate.of(2026, 10, 1))),
        )
        val state = AppState(assignments = assignments, entries = entries, guesses = guesses)
        val m = Progress.month(state, library, sept, today, zone)!!
        assertFalse(m.inProgress)
        assertEquals(3, m.daysLogged) // 3rd, 5th, 9th (the 10th was deleted)
        assertEquals(30, m.daysSoFar)
        assertEquals(4, m.reports)
        assertEquals(3, m.sightings) // "not today" isn't a sighting
        assertEquals(20, m.discovered)
        assertEquals(ids[3], m.topConcept)
        assertEquals(library[ids[3]]!!.category, m.topArea)
        assertEquals(2, m.predictions)
        assertEquals(1, m.predictionsRight)
        assertEquals(1, m.previousReports)
        assertEquals(library.all.slice(1..20).count { it.isMyth }, m.mythsMet)
        // October so far; months newest first; the first week shows last month.
        val oct = Progress.month(state, library, YearMonth.of(2026, 10), today, zone)!!
        assertTrue(oct.inProgress)
        assertEquals(7, oct.daysSoFar)
        assertEquals(listOf(YearMonth.of(2026, 10), sept, YearMonth.of(2026, 8)), Progress.months(state, today))
        assertEquals(sept, Progress.defaultMonth(Progress.months(state, today), today))
        assertEquals(YearMonth.of(2026, 10), Progress.defaultMonth(Progress.months(state, today), LocalDate.of(2026, 10, 20)))
        assertNull(Progress.month(state, library, YearMonth.of(2026, 7), today, zone))
        assertNull(Progress.month(state, library, YearMonth.of(2026, 11), today, zone))
    }

    @Test
    fun `the month you started counts from your first day, not the 1st`() {
        val id = library.all.first().id
        val state = AppState(assignments = mapOf(LocalDate.of(2026, 9, 21) to Assignment(id, 1)), entries = listOf(entry(LocalDate.of(2026, 9, 22), id)))
        assertEquals(10, Progress.month(state, library, YearMonth.of(2026, 9), today, zone)!!.daysSoFar) // 21st to 30th
        assertNull(Progress.month(state, library, YearMonth.of(2026, 10), today, zone)) // nothing in October yet
    }

    @Test
    fun `a month with only repeat concepts and no notes isn't listed, so the card never vanishes`() {
        val ids = library.all.map { it.id }
        // Everything was met in August; September only brought revisits.
        val assignments = (1..20).associate { d -> LocalDate.of(2026, 8, d) to Assignment(ids[d], 1L) } +
            (1..5).associate { d -> LocalDate.of(2026, 9, d) to Assignment(ids[d], 2L) }
        val state = AppState(assignments = assignments)
        assertEquals(listOf(YearMonth.of(2026, 8)), Progress.months(state, today))
    }

    @Test
    fun `month review never shows impossible numbers on random journals`() {
        val rnd = Random(9)
        val ids = library.all.map { it.id }
        repeat(300) {
            val entries = List(rnd.nextInt(0, 60)) {
                entry(today.minusDays(rnd.nextLong(0, 120)), ids[rnd.nextInt(ids.size)], Mode.entries[rnd.nextInt(4)], rnd.nextInt(10) == 0)
            }
            val assignments = (0L until rnd.nextLong(0, 120)).associate { today.minusDays(it) to Assignment(ids[rnd.nextInt(ids.size)], it) }
            val state = AppState(assignments = assignments, entries = entries)
            for (ym in Progress.months(state, today)) {
                val m = Progress.month(state, library, ym, today, zone)
                assertNotNull("$ym is listed, so it has a review", m)
                m!!
                assertTrue(m.daysLogged in 0..m.daysSoFar)
                assertTrue(m.sightings <= m.reports)
                assertEquals(m.reports, m.byMode.values.sum())
                assertTrue(m.mythsMet <= m.discovered)
                assertTrue(m.predictionsRight <= m.predictions)
            }
        }
    }

    @Test
    fun `standing compares predictions with chance and needs a handful first`() {
        val ids = library.all.take(10).map { it.id }
        fun state(right: Int, total: Int) = AppState(
            assignments = mapOf(today.minusDays(40) to Assignment(ids[0], 0)),
            entries = listOf(entry(today.minusDays(2))),
            guesses = ids.take(total).mapIndexed { i, id ->
                val p = library[id]!!.predict
                id to Guess(if (i < right) p.answer else (p.answer + 1) % p.options.size, 0)
            }.toMap(),
        )
        val few = Progress.standing(state(2, 3), library, today)
        assertTrue(Progress.predictionLine(few).startsWith("Predict 2 more studies"))
        val good = Progress.standing(state(7, 10), library, today)
        assertEquals(70, good.percent)
        val chance = Math.round(ids.map { 100.0 / library[it]!!.predict.options.size }.average()).toInt()
        assertEquals(chance, good.chancePercent)
        assertTrue(Progress.predictionLine(good).contains("70%") && Progress.predictionLine(good).contains("$chance%"))
        val poor = Progress.standing(state(1, 10), library, today)
        assertTrue(Progress.predictionLine(poor).contains("Surprises are the point"))
        // Well below chance is said as it is, not as "about what blind guessing gets".
        assertTrue(Progress.predictionLine(poor).contains("below what blind guessing gets"))
        val near = Progress.standing(state(3, 10), library, today)
        assertTrue(Progress.predictionLine(near).contains("about what blind guessing gets"))
        assertEquals(41, good.dayNumber)
        assertTrue(good.activeThisWeek)
        assertTrue(Progress.consistencyLine(good).startsWith("Day 41 and still going"))
        assertTrue(Progress.consistencyLine(good).contains("3.3%"))
    }

    @Test
    fun `today's path ticks off predict, plan, report and shows review only when it matters`() {
        val id = library.all.first().id
        var state = AppState(assignments = mapOf(today to Assignment(id, 0)))
        assertEquals(listOf(Step.PREDICT, Step.PLAN, Step.REPORT), Progress.todayPath(state, library, today, id).map { it.step })
        assertTrue(Progress.todayPath(state, library, today, id).none { it.done })
        state = state.copy(guesses = mapOf(id to Guess(0, 0)), plans = mapOf(today to Plan("At lunch, I'll notice it", 0)))
        state = state.upsert(entry(today, id))
        assertTrue(Progress.todayPath(state, library, today, id).all { it.done })
        // A note about another concept today doesn't tick off today's report.
        val other = library.all[5].id
        val elsewhere = AppState(assignments = mapOf(today to Assignment(id, 0))).upsert(entry(today, other))
        assertFalse(Progress.todayPath(elsewhere, library, today, id)[2].done)
        assertFalse(elsewhere.reportedOn(today))
        assertTrue(elsewhere.upsert(entry(today, id)).reportedOn(today))
        // A blank plan isn't a plan.
        assertFalse(Progress.todayPath(state.copy(plans = mapOf(today to Plan("  ", 0))), library, today, id)[1].done)
        // A concept met two days ago is due for review today: the path shows it, not yet done.
        val older = library.all[1].id
        val due = state.copy(assignments = state.assignments + (today.minusDays(2) to Assignment(older, 0)))
        val path = Progress.todayPath(due, library, today, id)
        assertEquals(Step.REVIEW, path.last().step)
        assertFalse(path.last().done)
        // Reviewed today: the step stays, ticked.
        val graded = due.copy(cards = mapOf(older to Spacing.grade(Spacing.newCard(older, today.minusDays(2)), true, today)))
        assertTrue(Progress.todayPath(graded, library, today, id).last().done)
    }

    @Test
    fun `weekly goal survives a backup round trip and bad values are clamped`() {
        val s = AppState(settings = Settings(weeklyGoal = 3))
        assertEquals(3, Codec.decode(Codec.encode(s, 0)).settings.weeklyGoal)
        val json = Codec.encode(s, 0)
        val field = Regex(""""weeklyGoal":\s*3""")
        assertNotNull(field.find(json))
        assertEquals(7, Codec.decode(json.replace(field, "\"weeklyGoal\": 99")).settings.weeklyGoal)
        assertEquals(0, Codec.decode(json.replace(field, "\"weeklyGoal\": -4")).settings.weeklyGoal)
        // An older backup without the field gets the default.
        val old = json.replace(Regex(""",\s*"weeklyGoal":\s*3"""), "")
        assertEquals(Settings().weeklyGoal, Codec.decode(old).settings.weeklyGoal)
    }
}
