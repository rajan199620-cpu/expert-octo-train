package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.random.Random

class FsrsTest {
    private val day0 = LocalDate.of(2026, 10, 1)

    private fun gap(c: Card, today: LocalDate) = ChronoUnit.DAYS.between(today, c.due)

    @Test
    fun `the forgetting curve passes 90 percent at the stability, and the gap equals it`() {
        for (s in listOf(0.5, 1.0, 3.173, 10.0, 100.0, 1000.0)) {
            assertEquals(0.9, Fsrs.retrievability(s, s), 1e-9)
            assertEquals(1.0, Fsrs.retrievability(0.0, s), 1e-12)
            assertTrue(Fsrs.retrievability(2 * s, s) < 0.9)
            assertEquals(maxOf(1, Math.round(s).toInt()), Fsrs.interval(s))
        }
        assertEquals(Fsrs.MAX_INTERVAL, Fsrs.interval(1e9))
    }

    @Test
    fun `a first review sets the memory from the rating, and better ratings mean longer gaps`() {
        val new = Spacing.newCard("x", day0)
        val today = new.due
        val again = Spacing.grade(new, Rating.AGAIN, today)
        val hard = Spacing.grade(new, Rating.HARD, today)
        val good = Spacing.grade(new, Rating.GOOD, today)
        assertEquals(Fsrs.W[0], again.stability!!, 1e-9)
        assertEquals(Fsrs.W[2], good.stability!!, 1e-9)
        assertEquals(1L, gap(again, today))
        assertEquals(1L, gap(hard, today))
        assertEquals(3L, gap(good, today))
        assertTrue(again.difficulty!! > hard.difficulty!! && hard.difficulty!! > good.difficulty!!)
        // Later on, too: from the same card, Hard < Good, and Again comes back tomorrow.
        val later = good.due
        val h = Spacing.grade(good, Rating.HARD, later)
        val g = Spacing.grade(good, Rating.GOOD, later)
        val a = Spacing.grade(good, Rating.AGAIN, later)
        assertTrue(gap(h, later) < gap(g, later))
        assertEquals(1L, gap(a, later))
        assertTrue(a.stability!! <= good.stability!!)
        assertEquals(1, a.wrong)
        assertEquals(2, h.right)
    }

    @Test
    fun `reviewing late, after more forgetting, grows the memory more when you still recall it`() {
        val c = Spacing.grade(Spacing.newCard("x", day0), Rating.GOOD, day0.plusDays(1))
        val onTime = Spacing.grade(c, Rating.GOOD, c.due)
        val late = Spacing.grade(c, Rating.GOOD, c.due.plusDays(10))
        assertTrue(late.stability!! > onTime.stability!!)
    }

    @Test
    fun `a card from the old fixed schedule carries on from the gap it had reached`() {
        // Box 3 meant a 16-day gap; recalled on time, the next one is longer still.
        val old = Card("x", 3, day0.plusDays(16), day0, 4, 0)
        val next = Spacing.grade(old, Rating.GOOD, old.due)
        assertTrue(gap(next, old.due).toString(), gap(next, old.due) > 16)
        assertNotNull(next.stability)
        // A miss brings it back tomorrow; a history of misses starts it harder.
        assertEquals(1L, gap(Spacing.grade(old, Rating.AGAIN, old.due), old.due))
        val shaky = Spacing.grade(old.copy(right = 0, wrong = 4), Rating.GOOD, old.due)
        assertTrue(shaky.difficulty!! > next.difficulty!!)
        // A card decoded from an old backup with a box it can't have had still works.
        val odd = Spacing.grade(Card("x", 99, day0, day0.minusDays(3), 1, 1), Rating.GOOD, day0)
        assertTrue(odd.due > day0)
    }

    @Test
    fun `a second review on the same day is handled, not divided by zero`() {
        val c = Spacing.grade(Spacing.newCard("x", day0), Rating.GOOD, day0.plusDays(1))
        val again = Spacing.grade(c, Rating.GOOD, day0.plusDays(1))
        assertTrue(again.stability!!.isFinite() && again.stability!! > 0)
        assertTrue(again.due > day0.plusDays(1))
    }

    @Test
    fun `the old one-argument grade still means right is Good and wrong is Again`() {
        val c = Spacing.newCard("x", day0)
        assertEquals(Spacing.grade(c, Rating.GOOD, day0.plusDays(1)), Spacing.grade(c, true, day0.plusDays(1)))
        assertEquals(Spacing.grade(c, Rating.AGAIN, day0.plusDays(1)), Spacing.grade(c, false, day0.plusDays(1)))
    }

    @Test
    fun `stress - any history of ratings and delays keeps the memory state sane`() {
        val rnd = Random(55)
        repeat(3000) {
            var c = if (rnd.nextBoolean()) Spacing.newCard("x", day0) else Card("x", rnd.nextInt(-2, 9), day0, day0.minusDays(rnd.nextLong(0, 200)), rnd.nextInt(0, 9), rnd.nextInt(0, 9))
            var today = day0
            repeat(rnd.nextInt(1, 40)) {
                today = today.plusDays(rnd.nextLong(0, 400))
                c = Spacing.grade(c, Rating.entries.random(rnd), today)
                val s = c.stability!!
                val d = c.difficulty!!
                assertTrue("$c", s.isFinite() && s >= Fsrs.MIN_STABILITY)
                assertTrue("$c", d in 1.0..10.0)
                assertTrue("$c", c.due > today && gap(c, today) <= Fsrs.MAX_INTERVAL)
                assertTrue("$c", c.box in 0..Spacing.MAX_BOX)
            }
        }
    }

    @Test
    fun `stress - a year of honest reviews at 90 percent recall keeps the daily load small`() {
        // Simulate a learner who recalls each card with the probability FSRS predicts: the number of
        // reviews per card over a year should be modest, far below one review a week.
        val rnd = Random(3)
        var total = 0
        repeat(200) {
            var c = Spacing.newCard("x", day0)
            var today = c.due
            val end = day0.plusDays(365)
            while (today <= end) {
                val r = c.stability?.let { s -> Fsrs.retrievability(ChronoUnit.DAYS.between(c.lastReviewed, today).toDouble(), s) } ?: 0.8
                c = Spacing.grade(c, if (rnd.nextDouble() < r) Rating.GOOD else Rating.AGAIN, today)
                total++
                today = c.due
            }
        }
        val perCard = total / 200.0
        assertTrue("$perCard reviews per card in a year", perCard in 4.0..20.0)
    }
}

class LessonsTest {
    private val library = TestLibrary.library
    private val day0 = LocalDate.of(2026, 10, 1)

    private fun lesson(id: String, day: LocalDate = day0, deleted: Boolean = false, updated: Long = 1) =
        Lesson(id, "Snapped at a colleague after a long day", "Pause before replying when tired", day, 1, updated, deleted)

    @Test
    fun `a lesson becomes a card the day after it's written, and leaves when deleted`() {
        val s = AppState().upsert(lesson("a")).upsert(lesson("b", deleted = true))
        val cards = Spacing.cards(s, library)
        assertEquals(listOf(Lessons.cardId("a")), cards.map { it.conceptId })
        assertTrue(Spacing.due(s, library, day0).isEmpty())
        assertEquals(listOf(Lessons.cardId("a")), Spacing.due(s, library, day0.plusDays(1)).map { it.conceptId })
        assertEquals("a", Lessons.of(s, Lessons.cardId("a"))?.id)
        assertNull(Lessons.of(s, Lessons.cardId("b")))
        assertNull(Lessons.of(s, "anchoring"))
        // A reviewed lesson keeps its card; deleting the lesson takes the card out of review.
        val graded = Spacing.grade(cards.single(), Rating.GOOD, day0.plusDays(1))
        val s2 = s.copy(cards = s.cards + (graded.conceptId to graded))
        assertEquals(graded, Spacing.cards(s2, library).single())
        val gone = s2.upsert(lesson("a", deleted = true, updated = 2))
        assertTrue(Spacing.cards(gone, library).isEmpty())
    }

    @Test
    fun `lessons and concepts share the daily limit, most overdue first`() {
        var s = AppState()
        library.all.take(8).forEachIndexed { i, c -> s = s.copy(assignments = s.assignments + (day0.plusDays(i.toLong()) to Assignment(c.id, 0))) }
        repeat(6) { s = s.upsert(lesson("l$it", day0.minusDays(it.toLong()))) }
        val due = Spacing.due(s, library, day0.plusDays(30))
        assertEquals(Spacing.DAILY_LIMIT, due.size)
        assertEquals(due.sortedBy { it.due }, due)
        assertEquals(Lessons.cardId("l5"), due.first().conceptId)
    }

    @Test
    fun `lessons merge newest first, a deletion wins over an older edit, and they survive a backup`() {
        val a = AppState(lessons = listOf(lesson("x", updated = 1)))
        val edited = AppState(lessons = listOf(lesson("x", updated = 3).copy(lesson = "edited")))
        val deleted = AppState(lessons = listOf(lesson("x", deleted = true, updated = 2)))
        assertEquals("edited", Sync.merge(Sync.merge(a, deleted), edited).lessons.single().lesson)
        assertTrue(Sync.merge(a, deleted).lessons.single().deleted)
        assertFalse(AppState(lessons = a.lessons).isFresh)
        val back = Codec.decode(Codec.encode(edited, 0))
        assertEquals(edited.lessons, back.lessons)
        // A backup from before lessons has none, and a damaged lesson is skipped.
        assertTrue(Codec.decode(Codec.encode(AppState(), 0).replace(",\"lessons\":[]", "")).lessons.isEmpty())
        val broken = Codec.encode(edited, 0).replace("\"day\":\"$day0\"", "\"day\":\"soon\"")
        assertTrue(Codec.decode(broken).lessons.isEmpty())
    }

    @Test
    fun `a damaged memory state in a backup is dropped, and the card carries on`() {
        val c = Spacing.grade(Spacing.newCard("anchoring", day0), Rating.GOOD, day0.plusDays(1))
        val text = Codec.encode(AppState(cards = mapOf("anchoring" to c)), 0)
        assertEquals(c, Codec.decode(text).cards.getValue("anchoring"))
        val nan = text.replace(Regex("\"stability\":[0-9.Ee+-]+"), "\"stability\":-4").replace(Regex("\"difficulty\":[0-9.Ee+-]+"), "\"difficulty\":\"lots\"")
        val read = Codec.decode(nan).cards.getValue("anchoring")
        assertNull(read.stability)
        assertNull(read.difficulty)
        assertTrue(Spacing.grade(read, Rating.GOOD, c.due).due > c.due)
    }

    @Test
    fun `the journal export includes lessons`() {
        val s = AppState().upsert(lesson("a")).upsert(lesson("b", deleted = true).copy(lesson = "secret gone"))
        val text = Texts.journal(s, library, day0)
        assertTrue(text, text.contains("# My lessons"))
        assertTrue(text, text.contains("Lesson: Pause before replying when tired"))
        assertFalse(text.contains("secret gone"))
    }

    @Test
    fun `merge stays order-free with memory states that tie on everything else`() {
        val base = Card("anchoring", 2, day0.plusDays(7), day0, 2, 0)
        val a = AppState(cards = mapOf("anchoring" to base.copy(stability = 5.0, difficulty = 4.0)))
        val b = AppState(cards = mapOf("anchoring" to base.copy(stability = 6.0, difficulty = 3.0)))
        assertEquals(Sync.merge(a, b).cards, Sync.merge(b, a).cards)
        assertTrue(abs(Sync.merge(a, b).cards.getValue("anchoring").stability!! - 6.0) < 1e-9)
    }
}

class SharedLessonTest {
    @Test
    fun `labelled text splits into what happened and the lesson`() {
        assertEquals(
            "Snapped at a colleague after a long day." to "Pause before replying when tired.",
            Lessons.fromShared("What happened: Snapped at a colleague after a long day.\nLesson: Pause before replying when tired."),
        )
        // Gemini's markdown, a dash, other words for it, one line, Windows line ends.
        assertEquals("A" to "B", Lessons.fromShared("**What happened:** A\n\n**Lesson:** B"))
        assertEquals("A" to "B", Lessons.fromShared("**Situation**: A\r\n**Lesson learned** - B"))
        assertEquals("A" to "B", Lessons.fromShared("Incident – A\nTakeaway: B"))
        assertEquals("A." to "B", Lessons.fromShared("What happened: A. Lesson: B"))
        assertEquals("A" to "B", Lessons.fromShared("situation: A\nTHE LESSON: B"))
    }

    @Test
    fun `unlabelled text is all what happened, and the word lesson alone doesn't split it`() {
        assertEquals("Missed the bus again." to "", Lessons.fromShared("  Missed the bus again.  "))
        assertEquals("That was a lesson in patience." to "", Lessons.fromShared("That was a lesson in patience."))
        assertEquals("Lessons from school stay." to "", Lessons.fromShared("Lessons from school stay."))
        assertEquals("" to "", Lessons.fromShared(""))
        assertEquals("" to "B", Lessons.fromShared("Lesson: B"))
    }

    @Test
    fun `long shared text is cut to fit, never through an emoji`() {
        val long = "x".repeat(Lessons.SITUATION_MAX - 1) + "😀" + "tail"
        val (s, _) = Lessons.fromShared(long)
        assertEquals(Lessons.SITUATION_MAX - 1, s.length)
        val (_, l) = Lessons.fromShared("A\nLesson: " + "y".repeat(1000))
        assertEquals(Lessons.LESSON_MAX, l.length)
    }

    @Test
    fun `sharing into a lesson being written adds to it`() {
        assertEquals("Mine\nShared" to "My lesson", Lessons.addShared("Mine", "My lesson", "Shared"))
        assertEquals("Mine\nA" to "Mine too\nB", Lessons.addShared("Mine", "Mine too", "What happened: A\nLesson: B"))
        assertEquals("A" to "B", Lessons.addShared("", " ", "A\nLesson: B"))
        assertEquals(Lessons.SITUATION_MAX, Lessons.addShared("z".repeat(Lessons.SITUATION_MAX), "", "more").first.length)
    }
}
