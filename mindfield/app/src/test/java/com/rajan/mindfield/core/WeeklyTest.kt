package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

class WeeklyTest {
    private val library = TestLibrary.library
    private val ids = library.all.map { it.id }
    private val monday = LocalDate.of(2026, 10, 19)
    private val sunday = monday.plusDays(6)
    private var n = 0

    private fun entry(day: LocalDate, mode: Mode = Mode.SPOTTED, concept: String = ids[0], note: String = "note", outcome: Outcome? = null, deleted: Boolean = false, created: Long = n.toLong()) =
        Entry("e${n++}", concept, day, mode, note, outcome, created, created, deleted)

    private fun state(vararg entries: Entry) = AppState(entries = entries.toList())

    private fun reflection(plan: String = "When X, I'll Y", noteId: String? = null, updated: Long = 1) =
        Reflection(plan, null, noteId, null, updated)

    @Test
    fun `a review looks back on this week from Sunday, and on last week until then`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(monday, Weekly.weekFor(sunday))
        for (d in 0L..5L) assertEquals(monday, Weekly.weekFor(monday.plusWeeks(1).plusDays(d)))
        assertEquals(monday.plusWeeks(1), Weekly.weekFor(sunday.plusWeeks(1)))
    }

    @Test
    fun `a review is due once a week has two reports, until it is done`() {
        val one = state(entry(monday))
        assertNull("one report is too little to look back on", Weekly.due(one, sunday))
        val two = one.upsert(entry(sunday))
        assertEquals(monday, Weekly.due(two, sunday))
        // Still waiting through the next week as a catch-up, then the next Sunday moves on.
        assertEquals(monday, Weekly.due(two, sunday.plusDays(1)))
        assertEquals(monday, Weekly.due(two, sunday.plusDays(6)))
        assertNull(Weekly.due(two, sunday.plusDays(7)))
        // Not before the Sunday: the week isn't over.
        assertNull(Weekly.due(two, sunday.minusDays(1)))
        val done = two.copy(reflections = mapOf(monday to reflection()))
        assertNull(Weekly.due(done, sunday))
        assertNull(Weekly.due(done, sunday.plusDays(3)))
    }

    @Test
    fun `deleted reports and reports from other weeks don't make a week reviewable`() {
        val s = state(entry(monday), entry(sunday, deleted = true), entry(monday.minusDays(1)), entry(sunday.plusDays(1)))
        assertNull(Weekly.due(s, sunday))
    }

    @Test
    fun `this week's plan comes from last week's review, or this week's once done on Sunday`() {
        val last = monday.minusWeeks(1)
        val s = AppState(reflections = mapOf(last to reflection("old plan")))
        for (d in 0L..6L) assertEquals("old plan", Weekly.activePlan(s, monday.plusDays(d))?.second?.plan)
        val reviewed = s.copy(reflections = s.reflections + (monday to reflection("new plan")))
        assertEquals("new plan", Weekly.activePlan(reviewed, sunday)?.second?.plan)
        assertEquals("new plan", Weekly.activePlan(reviewed, sunday.plusDays(1))?.second?.plan)
        // A plan is for one week: without a newer review it lapses.
        assertNull(Weekly.activePlan(s, sunday.plusDays(1)))
        assertNull(Weekly.activePlan(AppState(reflections = mapOf(last to reflection(" "))), monday))
        assertEquals("old plan", Weekly.previousPlan(s, monday))
        assertNull(Weekly.previousPlan(s, last))
    }

    @Test
    fun `the blind spot waits for ten sightings, and counts only sightings`() {
        val nine = (0 until 9).map { entry(monday.minusDays(it.toLong()), Mode.SPOTTED) }
        val noise = listOf(entry(monday, Mode.USED, outcome = Outcome.WORKED), entry(monday, Mode.NOT_TODAY), entry(monday, Mode.MYSELF, deleted = true), entry(sunday.plusDays(1), Mode.MYSELF))
        assertNull(Weekly.blindSpot(state(*(nine + noise).toTypedArray()), library, sunday))
        val b = Weekly.blindSpot(state(*(nine + noise + entry(monday, Mode.MYSELF, concept = ids[1])).toTypedArray()), library, sunday)!!
        assertEquals(9, b.others)
        assertEquals(1, b.self)
    }

    @Test
    fun `the blind spot names a concept spotted in others but never in yourself`() {
        val spotted = (0 until 4).map { entry(monday.plusDays(it.toLong()), Mode.SPOTTED, concept = ids[0]) }
        val balanced = (0 until 3).flatMap { listOf(entry(monday, Mode.SPOTTED, concept = ids[1]), entry(monday, Mode.MYSELF, concept = ids[1])) }
        val b = Weekly.blindSpot(state(*(spotted + balanced).toTypedArray()), library, sunday)!!
        assertEquals(ids[0], b.conceptId)
        assertEquals(4, b.conceptOthers)
        assertEquals(0, b.conceptSelf)
        // Once you've caught it in yourself often enough, it isn't a blind spot.
        val caught = spotted + (0 until 2).map { entry(monday, Mode.MYSELF, concept = ids[0]) } + balanced
        assertNull(Weekly.blindSpot(state(*caught.toTypedArray()), library, sunday)!!.conceptId)
        // Twice spotted is too few to call.
        val few = spotted.take(2) + balanced + (0 until 3).map { entry(monday, Mode.SPOTTED, concept = ids[2 + it]) }
        assertNull(Weekly.blindSpot(state(*few.toTypedArray()), library, sunday)!!.conceptId)
    }

    @Test
    fun `without a single concept to name, the blind spot names an area`() {
        val area = library.all.groupBy { it.category }.maxBy { it.value.size }
        val inArea = area.value.map { it.id }
        val elsewhere = library.all.filter { it.category != area.key }.map { it.id }
        // Four concepts in one area, each spotted once in others; caught yourself only elsewhere.
        val spotted = (0 until 4).map { entry(monday, Mode.SPOTTED, concept = inArea[it]) }
        val self = (0 until 6).map { entry(monday, Mode.MYSELF, concept = elsewhere[it]) }
        val b = Weekly.blindSpot(state(*(spotted + self).toTypedArray()), library, sunday)!!
        assertNull(b.conceptId)
        assertEquals(area.key, b.area)
        assertEquals(4, b.areaOthers)
        assertEquals(0, b.areaSelf)
        // A concept that stands out comes first, and then no area is named.
        val again = (0 until 2).map { entry(monday, Mode.SPOTTED, concept = inArea[0]) }
        val c = Weekly.blindSpot(state(*(spotted + again + self).toTypedArray()), library, sunday)!!
        assertEquals(inArea[0], c.conceptId)
        assertNull(c.area)
        // Three in an area is too few.
        assertNull(Weekly.blindSpot(state(*(spotted.take(3) + self + entry(monday, Mode.MYSELF, concept = elsewhere[7])).toTypedArray()), library, sunday)!!.area)
    }

    @Test
    fun `the blind spot compares the last four weeks with before, when each has enough`() {
        val earlier = (0 until 5).map { entry(sunday.minusDays(40), if (it == 0) Mode.MYSELF else Mode.SPOTTED) }
        val recent = (0 until 5).map { entry(sunday.minusDays(27), if (it < 3) Mode.MYSELF else Mode.SPOTTED) }
        val b = Weekly.blindSpot(state(*(earlier + recent).toTypedArray()), library, sunday)!!
        assertEquals(60, b.recentSelfPercent)
        assertEquals(20, b.earlierSelfPercent)
        val thin = (0 until 9).map { entry(sunday, Mode.SPOTTED) } + entry(sunday.minusDays(28), Mode.MYSELF)
        val t = Weekly.blindSpot(state(*thin.toTypedArray()), library, sunday)!!
        assertEquals(0, t.recentSelfPercent)
        assertNull("one earlier sighting is too few to compare", t.earlierSelfPercent)
    }

    @Test
    fun `the moment to learn from is what backfired, then mixed, then caught yourself`() {
        val worked = entry(monday, Mode.USED, outcome = Outcome.WORKED)
        val tried = entry(monday.plusDays(1), Mode.USED)
        val caught = entry(monday.plusDays(2), Mode.MYSELF)
        val mixed = entry(monday.plusDays(1), Mode.USED, outcome = Outcome.MIXED)
        val backfired = entry(monday, Mode.USED, outcome = Outcome.BACKFIRED)
        val outside = entry(monday.minusDays(1), Mode.USED, outcome = Outcome.BACKFIRED)
        val others = entry(sunday, Mode.SPOTTED)
        assertNull("others' behaviour isn't yours to change", Weekly.moment(state(others, entry(sunday, Mode.NOT_TODAY), outside), monday))
        assertEquals(worked.id, Weekly.moment(state(others, worked), monday)?.id)
        assertEquals(tried.id, Weekly.moment(state(worked, tried), monday)?.id)
        assertEquals(caught.id, Weekly.moment(state(worked, tried, caught), monday)?.id)
        assertEquals(mixed.id, Weekly.moment(state(worked, tried, caught, mixed), monday)?.id)
        assertEquals(backfired.id, Weekly.moment(state(worked, tried, caught, mixed, backfired, outside), monday)?.id)
        val later = entry(monday.plusDays(4), Mode.MYSELF)
        assertEquals("newest first", later.id, Weekly.moment(state(caught, later), monday)?.id)
    }

    @Test
    fun `the old note is the one closest to a month ago, never the last two weeks, never twice`() {
        val month = entry(sunday.minusDays(28), note = "short")
        val near = entry(sunday.minusDays(30), note = "a longer note from a bit further back")
        val recent = entry(sunday.minusDays(13), note = "too recent to look back on")
        val blank = entry(sunday.minusDays(28), note = " ")
        val gone = entry(sunday.minusDays(28), note = "deleted", deleted = true)
        val s = state(near, recent, blank, gone, month)
        assertEquals(month.id, Weekly.lookBack(s, sunday)?.id)
        val seen = s.copy(reflections = mapOf(monday.minusWeeks(1) to reflection(noteId = month.id)))
        assertEquals(near.id, Weekly.lookBack(seen, sunday)?.id)
        assertNull(Weekly.lookBack(state(recent, blank, gone), sunday))
        val twin = entry(sunday.minusDays(28), note = "the same day, but longer")
        assertEquals("the longer on a tie", twin.id, Weekly.lookBack(state(month, twin), sunday)?.id)
        val quiet = entry(sunday.minusDays(28), Mode.NOT_TODAY, note = "didn't come up, though I looked for it all day")
        assertEquals("a not-today note has nothing to judge", month.id, Weekly.lookBack(state(quiet, month), sunday)?.id)
        val first = entry(sunday.minusDays(14), note = "two weeks")
        assertEquals("with only young notes, two weeks back will do", first.id, Weekly.lookBack(state(first, recent), sunday)?.id)
    }

    @Test
    fun `reviews merge newest first and survive a backup, filed under their Monday`() {
        val a = AppState(reflections = mapOf(monday to Reflection("old", Kept.YES, null, null, 1)))
        val b = AppState(reflections = mapOf(monday to Reflection("new", null, "e1", Verdict.CHANGED, 2)))
        assertEquals("new", Sync.merge(a, b).reflections.getValue(monday).plan)
        assertEquals("new", Sync.merge(b, a).reflections.getValue(monday).plan)
        val text = Codec.encode(b, 0)
        assertEquals(b.reflections, Codec.decode(text).reflections)
        // A backup from before weekly reviews has none; a damaged or misfiled one is mended or skipped.
        assertTrue(Codec.decode(Codec.encode(AppState(), 0).replace(",\"reflections\":[]", "")).reflections.isEmpty())
        val odd = text.replace("\"week\":\"$monday\"", "\"week\":\"${monday.plusDays(3)}\"")
            .replace("\"verdict\":\"changed\"", "\"verdict\":\"unheard-of\"")
        val mended = Codec.decode(odd).reflections.getValue(monday)
        assertNull(mended.verdict)
        assertEquals("new", mended.plan)
        val broken = text.replace("\"week\":\"$monday\"", "\"week\":\"not a date\"")
        assertTrue(Codec.decode(broken).reflections.isEmpty())
    }

    @Test
    fun `a phone with a weekly review isn't fresh, and the journal export includes reviews`() {
        val note = entry(monday.minusDays(28), note = "old words")
        val s = state(note).copy(reflections = mapOf(monday to Reflection("When tired, I'll pause", Kept.PARTLY, note.id, Verdict.STILL_TRUE, 1)))
        assertFalse(AppState(reflections = s.reflections).isFresh)
        val text = Texts.journal(s, library, sunday)
        assertTrue(text, text.contains("# Weekly reviews"))
        assertTrue(text, text.contains("Plan for the week after: When tired, I'll pause"))
        assertTrue(text, text.contains("Kept the plan before it: partly"))
        assertTrue(text, text.contains("Looked back at a note from 21 September: still true"))
        assertFalse(Texts.journal(state(note), library, sunday).contains("Weekly reviews"))
    }

    @Test
    fun `stress - every pick is consistent with its rules over random journals`() {
        val rnd = Random(4)
        repeat(400) {
            val today = monday.plusDays(rnd.nextLong(-60, 60))
            val entries = (0 until rnd.nextInt(0, 60)).map {
                val mode = Mode.entries.random(rnd)
                entry(
                    today.minusDays(rnd.nextLong(-3, 90)), mode, ids[rnd.nextInt(0, 6)],
                    if (rnd.nextInt(4) == 0) "" else "n$it", if (mode == Mode.USED) Outcome.entries.random(rnd).takeIf { rnd.nextBoolean() } else null,
                    deleted = rnd.nextInt(8) == 0,
                )
            }
            var s = state(*entries.toTypedArray())
            repeat(rnd.nextInt(0, 3)) { s = s.copy(reflections = s.reflections + (Progress.weekOf(today.minusWeeks(rnd.nextLong(0, 4))) to reflection(noteId = entries.randomOrNull(rnd)?.id))) }
            val due = Weekly.due(s, today)
            if (due != null) {
                assertEquals(DayOfWeek.MONDAY, due.dayOfWeek)
                assertTrue(due !in s.reflections)
                assertTrue(!due.plusDays(6).isAfter(today.plusDays(if (today.dayOfWeek == DayOfWeek.SUNDAY) 0 else 6)))
                Weekly.moment(s, due)?.let { m ->
                    assertFalse(m.deleted)
                    assertTrue(m.day >= due && m.day <= due.plusDays(6))
                    assertTrue(m.mode == Mode.USED || m.mode == Mode.MYSELF)
                }
            }
            Weekly.lookBack(s, today)?.let { e ->
                assertFalse(e.deleted)
                assertTrue(e.note.isNotBlank() && e.mode.isSighting)
                assertTrue(e.day <= today.minusDays(Weekly.LOOK_BACK_MIN_DAYS))
                assertTrue(s.reflections.values.none { it.noteId == e.id })
            }
            Weekly.blindSpot(s, library, today)?.let { b ->
                assertTrue(b.others + b.self >= Weekly.MIN_SIGHTINGS)
                b.recentSelfPercent?.let { assertTrue(it in 0..100) }
                if (b.conceptId != null) assertTrue(b.conceptOthers >= Weekly.MIN_CONCEPT_SPOTTED && b.conceptOthers > b.conceptSelf * 2 && b.area == null)
                if (b.area != null) assertTrue(b.areaOthers >= Weekly.MIN_AREA_SPOTTED && b.areaOthers > b.areaSelf * 2)
            }
            assertNotNull(Weekly.weekFor(today))
        }
    }
}
