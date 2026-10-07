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
    fun `after the whole guide, a concept you never spot comes back only every couple of weeks`() {
        var s = AppState()
        library.all.forEachIndexed { i, c -> s = s.copy(assignments = s.assignments + (day0.plusDays(i.toLong()) to Assignment(c.id, i.toLong()))) }
        val skip = library.all[37].id
        library.all.filter { it.id != skip }.forEachIndexed { i, c -> s = s.upsert(entry("e$i", c.id, day0)) }
        val start = day0.plusDays(library.size.toLong())
        val revisited = (0L until 60L).map { d ->
            val day = start.plusDays(d)
            s = Curriculum.assign(day, library, s, d)
            s.assignments.getValue(day).conceptId
        }
        val days = revisited.indices.filter { revisited[it] == skip }
        assertEquals(0, days.first())
        assertTrue("shown on days $days", days.zipWithNext().all { (a, b) -> b - a > Curriculum.REVISIT_GAP_DAYS })
        assertTrue(revisited.toSet().size > 40)
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
            for (reviews in 0..1) {
                val q = Quiz.question(Card(c.id, reviews, day0, null, reviews, 0), library, everything, seed = 7)
                assertEquals(if (reviews == 0) Question.Kind.NAME_IT else Question.Kind.RECALL, q.kind)
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
    fun `mastered concepts still alternate between naming it and recalling the study`() {
        val kinds = (0..5).map { n ->
            Quiz.question(Card("anchoring", Spacing.MAX_BOX, day0, day0, 3 + n, 1), library, everything, seed = 7).kind
        }
        assertEquals(listOf(Question.Kind.NAME_IT, Question.Kind.RECALL), kinds.take(2))
        assertEquals(kinds.take(2) + kinds.take(2) + kinds.take(2), kinds)
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
    fun `streak forgives one missed day a week, not two`() {
        // Friday 2 October back to Monday 28 September, Tuesday missed: one rest, four days.
        val days = setOf(day0, day0.minusDays(1), day0.minusDays(2), day0.minusDays(4))
        assertEquals(4, Stats.streak(days, day0))
        assertEquals(4, Stats.streak(days, day0.plusDays(1))) // Saturday: today isn't a miss yet
        // Sunday: Saturday is that week's rest, so Tuesday's gap now ends the run.
        assertEquals(3, Stats.streak(days, day0.plusDays(2)))
        // Monday after two missed days in a row: broken.
        assertEquals(0, Stats.streak(days, day0.plusDays(3)))
        assertEquals(4, Stats.longestStreak(days))
        assertEquals(0, Stats.longestStreak(emptySet()))
        // A note dated ahead (a wrong clock, or another phone's) can't stretch the best streak.
        val ahead = days + (1L..6L).map { day0.plusDays(it) }
        assertEquals(10, Stats.longestStreak(ahead))
        assertEquals(4, Stats.longestStreak(ahead, day0))
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
            .upsert(entry("c", "anchoring", day0.plusDays(1)))
        val map = Stats.heatmap(s, day0, 12)
        assertEquals(12, map.size)
        assertTrue(map.all { it.size == 7 })
        assertEquals(2, map.last().first { it.first == day0 }.second)
        assertEquals(0, map.last().first { it.first == day0.plusDays(1) }.second) // tomorrow stays empty
    }

    @Test
    fun `on this day looks back to exact dates, and a month's last day covers the days a shorter month lacks`() {
        var s = AppState()
        listOf("2026-01-28", "2026-01-29", "2026-01-31", "2026-02-28", "2025-03-31").forEachIndexed { i, d ->
            s = s.upsert(entry("n$i", "anchoring", LocalDate.parse(d)).copy(note = "x".repeat(i + 1)))
        }
        // 28 February: a month back is 28 January, plus the 29th to 31st that February doesn't have.
        val feb28 = Stats.onThisDay(s, LocalDate.parse("2026-02-28"))
        assertEquals(listOf("A month ago"), feb28.map { it.first })
        assertEquals("2026-01-31", feb28.single().second.day.toString()) // the longest of those days' notes
        // 31 March: February had no 31st, so nothing from a month ago (and the 28th isn't repeated).
        assertEquals(listOf("A year ago"), Stats.onThisDay(s, LocalDate.parse("2026-03-31")).map { it.first })
        assertEquals(listOf("A month ago"), Stats.onThisDay(s, LocalDate.parse("2026-03-28")).map { it.first })
        assertTrue(Stats.onThisDay(s, LocalDate.parse("2026-03-29")).isEmpty())
    }

    @Test
    fun `discovered counts only concepts still in the library, and percentages round`() {
        val s = AppState(assignments = mapOf(day0 to Assignment("anchoring", 1), day0.plusDays(1) to Assignment("gone-now", 2)))
        assertEquals(1, Stats.discovered(s, library))
        assertEquals(67, Stats.Predictions(3, 2).percent)
        assertEquals(0, Stats.Predictions(0, 0).percent)
    }
}

class SyncTest {
    private fun randomState(rnd: Random, tag: String): AppState {
        var s = AppState(settings = Settings(morningMinute = rnd.nextInt(0, 1440)))
        val ids = library.all.shuffled(rnd).take(rnd.nextInt(0, 25)).map { it.id }
        ids.forEachIndexed { i, id -> s = s.copy(assignments = s.assignments + (day0.plusDays(rnd.nextLong(0, 30)) to Assignment(id, rnd.nextLong(0, 5), rnd.nextInt(3) == 0))) }
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
        // Linking on the last welcome step brings the backup in without skipping that step.
        val welcome = AppState()
        assertFalse(Sync.merge(welcome, backup.copy(settings = Settings(onboarded = true))).settings.onboarded)
    }

    @Test
    fun `the concept you worked on keeps its day when two phones picked different ones`() {
        val here = AppState(assignments = mapOf(day0 to Assignment("anchoring", 9))).engaged(day0, "anchoring")
        val there = AppState(assignments = mapOf(day0 to Assignment("reciprocity", 3)))
        assertEquals("anchoring", Sync.merge(here, there).assignments.getValue(day0).conceptId)
        assertEquals("anchoring", Sync.merge(there, here).assignments.getValue(day0).conceptId)
        // Only that day's own concept is marked, and a mark survives the backup file.
        assertTrue(here.engaged(day0, "reciprocity") === here)
        assertFalse(AppState(assignments = mapOf(day0 to Assignment("x", 1))).engaged(day0, "y").assignments.getValue(day0).engaged)
        assertTrue(Codec.decode(Codec.encode(here, 0)).assignments.getValue(day0).engaged)
        assertFalse(Codec.encode(there, 0).contains("engaged"))
    }

    @Test
    fun `a new phone restoring a backup takes the backup's days, not its own first concept`() {
        val today = day0.plusDays(30)
        // The new phone was set up this morning and shown concept one; the other phone is on day 30.
        val newPhone = AppState(assignments = mapOf(today to Assignment(Curriculum.FIRST, 500)), settings = Settings(onboarded = true))
        var other = AppState(settings = Settings(morningMinute = 7 * 60, weeklyGoal = 3, onboarded = true))
        for (d in 0L until 30L) other = Curriculum.assign(day0.plusDays(d), library, other, 1000 + d)
        other = other.upsert(entry("a", Curriculum.FIRST, day0)).copy(guesses = mapOf(Curriculum.FIRST to Guess(0, 1)))
        val restored = Sync.adopt(newPhone, other)
        assertEquals(null, restored.assignments[today]) // chosen afresh from the restored history
        assertEquals(other.assignments, restored.assignments)
        assertEquals(7 * 60, restored.settings.morningMinute)
        assertEquals(3, restored.settings.weeklyGoal)
        assertTrue(restored.settings.onboarded)
        assertTrue(library.contains(Curriculum.pick(today, library, restored)))
        assertFalse(Curriculum.pick(today, library, restored) == Curriculum.FIRST)
        // A phone with its own notes is never treated as fresh: an ordinary merge.
        val used = newPhone.upsert(entry("b", Curriculum.FIRST, today))
        assertEquals(Sync.merge(used, other), Sync.adopt(used, other))
        // And a fresh backup changes nothing special.
        assertEquals(Sync.merge(newPhone, AppState()), Sync.adopt(newPhone, AppState()))
    }

    @Test
    fun `an edit always beats the copy it replaces, even with the clock set back`() {
        assertEquals(1_000L, Versions.next(null, 1_000))
        assertEquals(5_000L, Versions.next(4_000, 5_000))
        assertEquals(4_001L, Versions.next(4_000, 1_000))
        val old = entry("x", "anchoring", day0, updated = 4_000)
        val edited = old.copy(note = "later edit", updatedAt = Versions.next(old.updatedAt, 1_000))
        assertEquals("later edit", Sync.merge(AppState(entries = listOf(old)), AppState(entries = listOf(edited))).entries.single().note)
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

    @Test
    fun `the saved file is compact and a backup file for people is laid out`() {
        val s = AppState(entries = listOf(entry("a", "anchoring", day0)))
        assertFalse(Codec.encode(s, 0).contains("\n"))
        assertTrue(Codec.encode(s, 0, pretty = true).contains("\n"))
        assertEquals(Codec.decode(Codec.encode(s, 0)), Codec.decode(Codec.encode(s, 0, pretty = true)))
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
        // A phone on the 24-hour clock.
        assertEquals("00:00", Schedule.label(0, is24 = true))
        assertEquals("08:05", Schedule.label(8 * 60 + 5, is24 = true))
        assertEquals("21:00", Schedule.label(21 * 60, is24 = true))
    }

    @Test
    fun `a report written in the small hours counts for the day its concept was shown`() {
        val s = AppState(assignments = mapOf(day0 to Assignment("anchoring", 1), day0.plusDays(1) to Assignment("reciprocity", 2)))
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 30).atZone(zone)
        val next = day0.plusDays(1)
        assertEquals(day0, FieldDay.of(s, "anchoring", at(next, 0)))
        assertEquals(day0, FieldDay.of(s, "anchoring", at(next, 3)))
        assertEquals(next, FieldDay.of(s, "anchoring", at(next, 4))) // from 4 am it's a sighting today
        assertEquals(next, FieldDay.of(s, "reciprocity", at(next, 1))) // today's own concept
        assertEquals(day0.plusDays(2), FieldDay.of(s, "anchoring", at(day0.plusDays(2), 1))) // two days back: today
        assertEquals(day0, FieldDay.current(at(next, 2)))
        assertEquals(next, FieldDay.current(at(next, 9)))
        assertEquals(at(next, 4).withMinute(0), FieldDay.expires(at(day0, 21)))
        assertEquals(at(next, 4).withMinute(0), FieldDay.expires(at(next, 1)))
    }

    @Test
    fun `the app's clock follows the phone into a new time zone`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            assertEquals(java.time.ZoneId.of("Europe/London"), SystemZoneClock.zone)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(java.time.ZoneId.of("America/Los_Angeles"), SystemZoneClock.zone)
            assertEquals(java.time.ZoneId.of("America/Los_Angeles"), java.time.ZonedDateTime.now(SystemZoneClock).zone)
            assertTrue(Math.abs(SystemZoneClock.millis() - System.currentTimeMillis()) < 5_000)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}

class TextsTest {
    @Test
    fun `a shared concept carries the idea, the mission, the evidence and the source`() {
        val c = library["anchoring"]!!
        val t = Texts.share(c)
        for (part in listOf(c.title, c.hook, c.missionLine, c.evidence.label, c.source)) assertTrue(part, t.contains(part))
    }

    @Test
    fun `the journal export groups by day, newest first, and skips deleted notes`() {
        var s = AppState()
        s = s.upsert(entry("a", "anchoring", day0, Mode.USED).copy(note = "line one\nline two"))
        s = s.upsert(entry("b", "reciprocity", day0.minusDays(1), Mode.SPOTTED))
        s = s.upsert(entry("c", "reciprocity", day0.minusDays(1), Mode.SPOTTED, deleted = true))
        val t = Texts.journal(s, library, day0)
        assertTrue(t.contains("2 notes"))
        assertTrue(t.indexOf("Anchoring") < t.indexOf("Reciprocity"))
        assertTrue(t.contains("🎯 Used it · Anchoring (worked)"))
        assertTrue(t.contains("  line one\n  line two"))
        assertFalse(t.contains("note c"))
        assertEquals(2, Regex("^## ", RegexOption.MULTILINE).findAll(t).count())
    }

    @Test
    fun `delivery looks blocked only after two missed mornings`() {
        val m = 8 * 60
        assertFalse(Delivery.looksBlocked(true, day0.minusDays(1), day0, 9 * 60, m))
        assertFalse(Delivery.looksBlocked(true, day0.minusDays(2), day0, 7 * 60, m))
        assertTrue(Delivery.looksBlocked(true, day0.minusDays(2), day0, 9 * 60, m))
        assertTrue(Delivery.looksBlocked(true, day0.minusDays(5), day0, 6 * 60, m))
        assertFalse(Delivery.looksBlocked(false, day0.minusDays(5), day0, 9 * 60, m))
        assertFalse(Delivery.looksBlocked(true, null, day0, 9 * 60, m))
        // An hour's slack on the day itself: Android may deliver an inexact alarm that late.
        assertFalse(Delivery.looksBlocked(true, day0.minusDays(2), day0, 8 * 60 + 20, m))
        assertFalse(Delivery.looksBlocked(true, day0.minusDays(2), day0, 8 * 60 + 50, m))
    }

    @Test
    fun `text limits never split an emoji and never cut a note that was already long`() {
        assertEquals("ab", Texts.clip("abc", 2))
        assertEquals("a", Texts.clip("a😀b", 2)) // the emoji's two halves stay together
        assertEquals("a😀", Texts.clip("a😀b", 3))
        assertEquals("", Texts.clip("😀", 1))
        val long = "x".repeat(2500)
        assertEquals(long, Texts.cap(long, long + "y", 2000)) // can't grow past the limit...
        assertEquals(long.dropLast(1), Texts.cap(long, long.dropLast(1), 2000)) // ...but can shrink
        assertEquals("x".repeat(2000), Texts.cap("", long, 2000)) // a paste is cut to fit
        assertEquals("hello", Texts.cap("hell", "hello", 2000))
    }
}
