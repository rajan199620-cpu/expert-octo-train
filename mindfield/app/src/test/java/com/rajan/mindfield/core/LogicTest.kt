package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

private val library = TestLibrary.library
private val day0: LocalDate = LocalDate.of(2026, 10, 2)

private fun entry(id: String, concept: String, day: LocalDate, mode: Mode = Mode.SPOTTED, updated: Long = 1, deleted: Boolean = false) =
    Entry(id, concept, day, mode, "note $id", if (mode == Mode.USED) Outcome.WORKED else null, 1, updated, deleted)

class CurriculumTest {
    @Test
    fun `first day is the frequency illusion, then library order`() {
        var s = AppState()
        val ids = (0L until 10L).map { d ->
            s = Curriculum.assign(day0.plusDays(d), library, s, d)
            s.assignments.getValue(day0.plusDays(d)).conceptId
        }
        assertEquals(library.all.take(10).map { it.id }, ids)
    }

    @Test
    fun `a day keeps its concept, and assigning twice changes nothing`() {
        val s1 = Curriculum.assign(day0, library, AppState(), 1)
        val s2 = Curriculum.assign(day0, library, s1, 2)
        assertTrue(s1 === s2)
        val changedFocus = s1.copy(settings = s1.settings.copy(focus = setOf(Category.GROUPS)))
        assertEquals(Curriculum.FIRST, Curriculum.pick(day0, library, changedFocus))
    }

    @Test
    fun `focus areas get two days in three`() {
        var s = AppState(settings = Settings(focus = setOf(Category.HABITS)))
        repeat(30) { d -> s = Curriculum.assign(day0.plusDays(d.toLong()), library, s, d.toLong()) }
        val cats = s.assignments.toSortedMap().values.drop(1).map { library[it.conceptId]!!.category }
        val habits = cats.count { it == Category.HABITS }
        assertTrue("habits on $habits of ${cats.size} days", habits in 17..21)
        assertTrue(cats.any { it != Category.HABITS })
    }

    @Test
    fun `stress - years of irregular use never repeat a concept until all are seen`() {
        val rnd = Random(42)
        repeat(20) { run ->
            var s = AppState()
            var day = day0
            val shown = ArrayList<String>()
            repeat(library.size + 40) { i ->
                day = day.plusDays(1L + rnd.nextInt(0, 4)) // gaps of up to 3 days
                if (rnd.nextInt(10) == 0) {
                    val focus = Category.entries.filter { rnd.nextBoolean() }.toSet()
                    s = s.copy(settings = s.settings.copy(focus = focus))
                }
                s = Curriculum.assign(day, library, s, i.toLong())
                val id = s.assignments.getValue(day).conceptId
                if (rnd.nextInt(3) == 0) s = s.upsert(entry("r$run-$i", id, day))
                shown += id
            }
            val firstPass = shown.take(library.size)
            assertEquals("run $run repeated before finishing", library.size, firstPass.toSet().size)
            assertEquals(library.all.map { it.id }.toSet(), firstPass.toSet())
        }
    }

    @Test
    fun `after the whole guide, revisits favour concepts never spotted`() {
        var s = AppState()
        library.all.forEachIndexed { i, c -> s = s.copy(assignments = s.assignments + (day0.plusDays(i.toLong()) to Assignment(c.id, i.toLong()))) }
        // Spot everything except one concept.
        val skip = library.all[37].id
        library.all.filter { it.id != skip }.forEachIndexed { i, c -> s = s.upsert(entry("e$i", c.id, day0)) }
        assertEquals(skip, Curriculum.pick(day0.plusDays(1000), library, s))
    }

    @Test
    fun `a removed concept is replaced rather than crashing`() {
        val s = AppState(assignments = mapOf(day0 to Assignment("no-longer-exists", 1)))
        val next = Curriculum.assign(day0, library, s, 2)
        assertTrue(library.contains(next.assignments.getValue(day0).conceptId))
    }
}

class SpacingTest {
    @Test
    fun `right answers stretch the gap and a miss starts again`() {
        var c = Spacing.newCard("anchoring", day0)
        assertEquals(day0.plusDays(1), c.due)
        var today = c.due
        val gaps = ArrayList<Long>()
        repeat(7) {
            c = Spacing.grade(c, true, today)
            gaps += java.time.temporal.ChronoUnit.DAYS.between(today, c.due)
            today = c.due
        }
        assertEquals(listOf(3L, 7L, 16L, 35L, 90L, 90L, 90L), gaps)
        c = Spacing.grade(c, false, today)
        assertEquals(0, c.box)
        assertEquals(today.plusDays(1), c.due)
        assertEquals(7, c.right)
        assertEquals(1, c.wrong)
    }

    @Test
    fun `due list is capped, most overdue first, and skips concepts not yet met`() {
        var s = AppState()
        library.all.take(30).forEachIndexed { i, c -> s = s.copy(assignments = s.assignments + (day0.plusDays(i.toLong()) to Assignment(c.id, 0))) }
        val today = day0.plusDays(40)
        val due = Spacing.due(s, library, today)
        assertEquals(Spacing.DAILY_LIMIT, due.size)
        assertEquals(due.sortedBy { it.due }, due)
        assertEquals(library.all.first().id, due.first().conceptId)
        assertTrue(Spacing.due(AppState(), library, today).isEmpty())
    }

    @Test
    fun `stress - random grading never schedules into the past`() {
        val rnd = Random(9)
        repeat(2000) {
            var c = Spacing.newCard("x", day0)
            var today = day0
            repeat(rnd.nextInt(1, 30)) {
                today = today.plusDays(rnd.nextLong(0, 100))
                c = Spacing.grade(c, rnd.nextBoolean(), today)
                assertTrue(c.due > today)
                assertTrue(c.box in 0..Spacing.MAX_BOX)
            }
        }
    }
}

class QuizTest {
    private val everything = library.all.map { it.id }

    @Test
    fun `every concept makes a valid question of both kinds`() {
        for (c in library.all) {
            for (box in 0..1) {
                val q = Quiz.question(Card(c.id, box, day0, null, 0, 0), library, everything, seed = 7)
                assertEquals(if (box == 0) Question.Kind.NAME_IT else Question.Kind.RECALL, q.kind)
                assertEquals(q.options.size, q.options.toSet().size)
                assertTrue(q.answer in q.options.indices)
                if (q.kind == Question.Kind.NAME_IT) {
                    assertEquals(c.title, q.options[q.answer])
                    assertEquals(4, q.options.size)
                    // Related concepts would also fit the story, so they're never offered as wrong answers.
                    val related = c.related.map { library[it]!!.title } + library.all.filter { c.id in it.related }.map { it.title }
                    assertTrue("${c.id}: ${q.options}", q.options.none { it in related })
                } else {
                    assertEquals(c.predict.options[c.predict.answer], q.options[q.answer])
                }
            }
        }
    }

    @Test
    fun `with fewer than four concepts known it asks about the study instead`() {
        val q = Quiz.question(Card("anchoring", 0, day0, null, 0, 0), library, listOf("anchoring", "frequency-illusion"), 1)
        assertEquals(Question.Kind.RECALL, q.kind)
    }

    @Test
    fun `the same seed gives the same question and options move around between seeds`() {
        val card = Card("anchoring", 0, day0, null, 0, 0)
        assertEquals(Quiz.question(card, library, everything, 5), Quiz.question(card, library, everything, 5))
        val positions = (1L..40L).map { Quiz.question(card, library, everything, it).answer }.toSet()
        assertTrue(positions.size >= 3)
    }
}

class StatsTest {
    @Test
    fun `streak survives until a whole day is missed`() {
        val days = setOf(day0, day0.minusDays(1), day0.minusDays(2), day0.minusDays(4))
        assertEquals(3, Stats.streak(days, day0))
        assertEquals(3, Stats.streak(days, day0.plusDays(1)))
        assertEquals(0, Stats.streak(days, day0.plusDays(2)))
        assertEquals(3, Stats.longestStreak(days))
        assertEquals(0, Stats.longestStreak(emptySet()))
    }

    @Test
    fun `week runs Monday to Sunday with future days blank`() {
        val thursday = LocalDate.of(2026, 10, 1)
        val week = Stats.week(setOf(thursday.minusDays(1)), thursday)
        assertEquals(java.time.DayOfWeek.MONDAY, week.first().first.dayOfWeek)
        assertEquals(listOf(false, false, true, false, null, null, null), week.map { it.second })
    }

    @Test
    fun `counts ignore deleted entries and not-today check-ins where they should`() {
        var s = AppState()
        s = s.upsert(entry("a", "anchoring", day0, Mode.SPOTTED))
        s = s.upsert(entry("b", "anchoring", day0, Mode.USED))
        s = s.upsert(entry("c", "reciprocity", day0, Mode.NOT_TODAY))
        s = s.upsert(entry("d", "reciprocity", day0, Mode.MYSELF, deleted = true))
        assertEquals(setOf("anchoring"), Stats.lifeList(s))
        assertEquals(2, Stats.categoryCounts(s, library).getValue(Category.THINKING))
        assertEquals(0, Stats.categoryCounts(s, library).getValue(Category.INFLUENCE))
        assertEquals(1, Stats.modeCounts(s).getValue(Mode.NOT_TODAY))
        assertEquals(setOf(day0), Stats.checkInDays(s))
        assertEquals(Stats.Outcomes(1, 0, 0), Stats.outcomes(s))
        assertEquals(listOf("anchoring" to 2), Stats.topConcepts(s))
    }

    @Test
    fun `predictions count right and surprised`() {
        val a = library["anchoring"]!!
        val s = AppState(guesses = mapOf("anchoring" to Guess(a.predict.answer, 1), "reciprocity" to Guess(99, 1), "gone" to Guess(0, 1)))
        assertEquals(Stats.Predictions(2, 1), Stats.predictions(s, library))
    }

    @Test
    fun `on this day finds notes from a week and a year ago`() {
        var s = AppState()
        s = s.upsert(entry("w", "anchoring", day0.minusWeeks(1)))
        s = s.upsert(entry("y", "anchoring", day0.minusYears(1)))
        s = s.upsert(entry("x", "anchoring", day0.minusDays(3)))
        assertEquals(listOf("A year ago", "A week ago"), Stats.onThisDay(s, day0).map { it.first })
    }

    @Test
    fun `heatmap has whole weeks ending this week`() {
        val s = AppState().upsert(entry("a", "anchoring", day0)).upsert(entry("b", "anchoring", day0))
        val map = Stats.heatmap(s, day0, 12)
        assertEquals(12, map.size)
        assertTrue(map.all { it.size == 7 })
        assertEquals(2, map.last().first { it.first == day0 }.second)
    }
}

class SyncTest {
    private fun randomState(rnd: Random, tag: String): AppState {
        var s = AppState(settings = Settings(morningMinute = rnd.nextInt(0, 1440)))
        val ids = library.all.shuffled(rnd).take(rnd.nextInt(0, 25)).map { it.id }
        ids.forEachIndexed { i, id -> s = s.copy(assignments = s.assignments + (day0.plusDays(rnd.nextLong(0, 30)) to Assignment(id, rnd.nextLong(0, 5)))) }
        repeat(rnd.nextInt(0, 15)) { i ->
            // Shared ids between copies, so merges really have conflicts to resolve.
            val id = "e${rnd.nextInt(0, 12)}"
            s = s.upsert(
                Entry(
                    id, ids.randomOrNull(rnd) ?: "anchoring", day0.plusDays(rnd.nextLong(0, 30)),
                    Mode.entries.random(rnd), "$tag note $i, with \"quotes\"\nand a new line ✨", Outcome.entries.random(rnd),
                    rnd.nextLong(0, 5), rnd.nextLong(0, 5), rnd.nextInt(5) == 0,
                ),
            )
        }
        ids.take(rnd.nextInt(0, ids.size + 1)).forEach { id ->
            s = s.copy(
                cards = s.cards + (id to Card(id, rnd.nextInt(0, 6), day0.plusDays(rnd.nextLong(0, 90)), day0.plusDays(rnd.nextLong(-5, 5)), rnd.nextInt(0, 3), rnd.nextInt(0, 3))),
                guesses = s.guesses + (id to Guess(rnd.nextInt(0, 3), rnd.nextLong(0, 4))),
            )
        }
        repeat(rnd.nextInt(0, 4)) { s = s.copy(plans = s.plans + (day0.plusDays(rnd.nextLong(0, 5)) to Plan("$tag plan", rnd.nextLong(0, 4)))) }
        return s
    }

    /** Merging is about data; settings deliberately stay with the phone. */
    private fun data(s: AppState) = s.copy(settings = Settings())

    @Test
    fun `stress - merge is order-free, repeatable and groups any way`() {
        val rnd = Random(2026)
        repeat(500) {
            val a = randomState(rnd, "a")
            val b = randomState(rnd, "b")
            val c = randomState(rnd, "c")
            val ab = Sync.merge(a, b)
            assertEquals(data(ab), data(Sync.merge(b, a)))
            assertEquals(data(ab), data(Sync.merge(ab, b)))
            assertEquals(data(ab), data(Sync.merge(ab, ab)))
            assertEquals(data(Sync.merge(ab, c)), data(Sync.merge(a, Sync.merge(b, c))))
        }
    }

    @Test
    fun `a deletion beats the older copy and a newer edit beats a deletion`() {
        val live = entry("x", "anchoring", day0, updated = 1)
        val gone = live.copy(deleted = true, updatedAt = 2)
        assertTrue(Sync.merge(AppState(entries = listOf(live)), AppState(entries = listOf(gone))).entries.single().deleted)
        val revived = live.copy(note = "edited", updatedAt = 3)
        assertEquals("edited", Sync.merge(AppState(entries = listOf(gone)), AppState(entries = listOf(revived))).entries.single().note)
    }

    @Test
    fun `the first guess counts and the earliest assignment wins`() {
        val a = AppState(guesses = mapOf("anchoring" to Guess(2, 50)), assignments = mapOf(day0 to Assignment("anchoring", 9)))
        val b = AppState(guesses = mapOf("anchoring" to Guess(0, 10)), assignments = mapOf(day0 to Assignment("reciprocity", 3)))
        val m = Sync.merge(a, b)
        assertEquals(0, m.guesses.getValue("anchoring").choice)
        assertEquals("reciprocity", m.assignments.getValue(day0).conceptId)
    }

    @Test
    fun `a fresh phone takes the backup's settings but keeps having been set up`() {
        val fresh = AppState(settings = Settings(onboarded = true))
        val backup = AppState(entries = listOf(entry("a", "anchoring", day0)), settings = Settings(morningMinute = 400, onboarded = false))
        val m = Sync.merge(fresh, backup)
        assertEquals(400, m.settings.morningMinute)
        assertTrue(m.settings.onboarded)
        val used = fresh.upsert(entry("b", "anchoring", day0))
        assertEquals(8 * 60, Sync.merge(used, backup).settings.morningMinute)
        assertEquals(1, Sync.restoredEntries(used, Sync.merge(used, backup)))
    }

    @Test
    fun `stress - backup round trip keeps everything, including awkward text`() {
        val rnd = Random(77)
        repeat(300) {
            val s = randomState(rnd, "weird \\ \"q\" ' ₹ 😀 \u0000 \t")
                .copy(settings = Settings(true, 5, false, 1439, true, setOf(Category.SELF, Category.GROUPS), ThemeMode.DARK, true, true))
            val back = Codec.decode(Codec.encode(s, 123))
            assertEquals(s.settings, back.settings)
            assertEquals(s.assignments, back.assignments)
            assertEquals(s.entries.sortedBy { it.id }, back.entries.sortedBy { it.id })
            assertEquals(s.cards, back.cards)
            assertEquals(s.guesses, back.guesses)
            assertEquals(s.plans, back.plans)
        }
    }

    @Test
    fun `damaged backups lose only the damaged parts`() {
        val text = """
            {"format":"mindfield-backup","version":9,
             "settings":{"morningMinute":99999,"focus":["self","nonsense"],"theme":"neon"},
             "entries":[{"id":"ok","concept":"anchoring","day":"2026-10-02","mode":"spotted","note":"fine","created":1,"updated":1},
                        {"id":"bad-day","concept":"anchoring","day":"yesterday","mode":"spotted"},
                        {"id":"bad-mode","concept":"anchoring","day":"2026-10-02","mode":"dancing"},
                        "not an object"],
             "cards":[{"concept":"anchoring","box":42,"due":"2026-10-05","last":null}],
             "unknownSection":[1,2,3]}
        """.trimIndent()
        val s = Codec.decode(text)
        assertEquals(listOf("ok"), s.entries.map { it.id })
        assertEquals(24 * 60 - 1, s.settings.morningMinute)
        assertEquals(setOf(Category.SELF), s.settings.focus)
        assertEquals(ThemeMode.SYSTEM, s.settings.theme)
        assertEquals(Spacing.MAX_BOX, s.cards.getValue("anchoring").box)
        assertEquals(null, s.cards.getValue("anchoring").lastReviewed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file that isn't a backup is refused`() {
        Codec.decode("""{"format":"something-else"}""")
    }

    @Test
    fun `merged entries come out in a stable order`() {
        val s = Sync.merge(
            AppState(entries = listOf(entry("b", "anchoring", day0.plusDays(1)), entry("a", "anchoring", day0))),
            AppState(),
        )
        assertEquals(listOf("a", "b"), s.entries.map { it.id })
        assertNotEquals(s.entries.first().day, s.entries.last().day)
        assertFalse(s.isEmpty)
    }
}

class ScheduleTest {
    private val zone = java.time.ZoneId.of("Asia/Kolkata")

    @Test
    fun `next is strictly in the future and on the right minute`() {
        val now = java.time.ZonedDateTime.of(2026, 10, 2, 8, 0, 0, 0, zone)
        assertEquals(now.plusDays(1), Schedule.next(8 * 60, now))
        assertEquals(now.plusMinutes(1), Schedule.next(8 * 60 + 1, now))
        assertEquals(now.withHour(21), Schedule.next(21 * 60, now))
    }

    @Test
    fun `daylight saving changes keep the wall-clock time`() {
        val london = java.time.ZoneId.of("Europe/London")
        val beforeChange = java.time.ZonedDateTime.of(2026, 3, 28, 22, 0, 0, 0, london)
        val next = Schedule.next(8 * 60, beforeChange)
        assertEquals(8, next.hour)
        assertEquals(29, next.dayOfMonth)
    }

    @Test
    fun `stress - spot checks fall between noon and six, vary by day, and are always ahead`() {
        val minutes = (0L until 365L).map { Schedule.spotMinute(day0.plusDays(it)) }
        assertTrue(minutes.all { it in Schedule.SPOT_FROM until Schedule.SPOT_TO })
        assertTrue(minutes.toSet().size > 150)
        val rnd = Random(3)
        repeat(2000) {
            val now = java.time.ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).plusMinutes(rnd.nextLong(0, 600_000))
            val at = Schedule.nextSpot(now)
            assertTrue(at.isAfter(now))
            assertTrue(java.time.Duration.between(now, at).toHours() < 48)
        }
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("12:00 am", Schedule.label(0))
        assertEquals("8:05 am", Schedule.label(8 * 60 + 5))
        assertEquals("12:30 pm", Schedule.label(12 * 60 + 30))
        assertEquals("9:00 pm", Schedule.label(21 * 60))
    }
}
