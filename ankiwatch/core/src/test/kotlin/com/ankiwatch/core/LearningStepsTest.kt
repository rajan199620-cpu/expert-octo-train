package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Offline, a card that comes back for another go follows its deck's learning steps and shows
 * the time each button gives, as AnkiDroid would. Checked against a separate model of Anki's
 * rules over random decks and random runs of grades. Scales with -Dstress.iterations=N.
 */
class LearningStepsTest {

    private val iterations = (System.getProperty("stress.iterations") ?: "2500").toInt()
    private val minute = IntervalLabel.MINUTE_MS
    private val day = IntervalLabel.DAY_MS

    /** AnkiDroid's default options: steps 1m 10m, graduating 1d, easy 4d, relearning 10m. */
    private val defaults = DeckSteps(listOf(minute, 10 * minute), 1, 4, listOf(10 * minute), 1, 0.0)

    private fun card(note: Long, labels: List<String>, steps: DeckSteps? = defaults, buttons: Int = 4) =
        PackCard(note, 0, buttons, labels, "", "", "x{{c1::s$note}}", 1, emptyList(), "Cloze", steps)

    private fun pack(vararg cards: PackCard) = OfflinePack(1, 1, "Deck", 0, cards.toList())

    private fun OfflineQueue.waitingFor(key: CardKey) = state.waiting.single { it.key == key }

    // ── The case that was reported ───────────────────────────────────────────────────────

    @Test
    fun aNewCardComingBackShowsItsTimesAndGraduatesOnTheLastStep() {
        val a = card(1, listOf("1m", "6m", "10m", "4d"))
        val q = OfflineQueue(pack(a))
        assertEquals("first sight: AnkiDroid's own", listOf("1m", "6m", "10m", "4d"), q.labels(a.key))

        q.answer(a.key, 1, 0) // Again: 1 minute, first step
        assertEquals(minute, q.waitingFor(a.key).delayMs)
        assertEquals(listOf("1m", "6m", "10m", "4d"), q.labels(a.key))

        q.answer(a.key, 3, minute) // Good: second step, 10 minutes
        assertEquals(10 * minute, q.waitingFor(a.key).delayMs)
        assertEquals("on the last step Good graduates: 1 day", listOf("1m", "10m", "1d", "4d"), q.labels(a.key))

        q.answer(a.key, 3, 11 * minute) // Good: graduated, gone for today
        assertTrue(a.key in q.state.done)
        assertEquals(OfflineQueue.Next.Finished, q.next(day))
    }

    @Test
    fun goodFirstThenGoodGraduates() {
        val a = card(1, listOf("1m", "6m", "10m", "4d"))
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 3, 0)
        assertEquals(10 * minute, q.waitingFor(a.key).delayMs)
        assertEquals(listOf("1m", "10m", "1d", "4d"), q.labels(a.key))
        q.answer(a.key, 3, 10 * minute)
        assertEquals(OfflineQueue.Next.Finished, q.next(10 * minute))
    }

    @Test
    fun aDeckWithThreeStepsUsesAllThree() {
        val steps = DeckSteps(listOf(minute, 10 * minute, 60 * minute), 2, 5, listOf(10 * minute), 1, 0.0)
        val a = card(1, listOf("1m", "6m", "10m", "5d"), steps)
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 3, 0)
        assertEquals(10 * minute, q.waitingFor(a.key).delayMs)
        assertEquals(listOf("1m", "10m", "1h", "5d"), q.labels(a.key))
        q.answer(a.key, 3, 10 * minute)
        assertEquals(60 * minute, q.waitingFor(a.key).delayMs)
        assertEquals(listOf("1m", "1h", "2d", "5d"), q.labels(a.key))
        q.answer(a.key, 2, 70 * minute) // Hard on a later step repeats it
        assertEquals(60 * minute, q.waitingFor(a.key).delayMs)
        q.answer(a.key, 3, 130 * minute)
        assertTrue(a.key in q.state.done)
    }

    @Test
    fun underFsrsTheGraduatingTimeIsLeftBlank() {
        // Easy on a new card isn't the deck's easy interval: FSRS decides it, and the
        // interval Good graduates to.
        val a = card(1, listOf("1m", "6m", "10m", "8d"))
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 3, 0)
        assertEquals(listOf("1m", "10m", "", ""), q.labels(a.key))
        q.answer(a.key, 1, 10 * minute)
        assertEquals("steps are still known", listOf("1m", "6m", "10m", ""), q.labels(a.key))
    }

    @Test
    fun anEasyLabelWithAnkisFuzzStillMeansSm2() {
        // AnkiDroid's Easy label on a new card includes Anki's random fuzz: 3d, 4d or 5d for
        // a 4-day easy interval. (Seen on real AnkiDroid in CI.)
        for (easy in listOf("3d", "4d", "5d")) {
            val a = card(1, listOf("1m", "6m", "10m", easy))
            val q = OfflineQueue(pack(a))
            q.answer(a.key, 3, 0)
            assertEquals(easy, listOf("1m", "10m", "1d", "4d"), q.labels(a.key))
        }
        // Past the fuzz: FSRS.
        for (easy in listOf("2d", "6d", "15d")) {
            val a = card(1, listOf("1m", "6m", "10m", easy))
            val q = OfflineQueue(pack(a))
            q.answer(a.key, 3, 0)
            assertEquals(easy, listOf("1m", "10m", "", ""), q.labels(a.key))
        }
        // The fuzz range grows with the interval, as Anki's does.
        assertTrue(LearningPlan.withinFuzz(day, 1))
        assertFalse(LearningPlan.withinFuzz(2 * day, 1))
        assertTrue(LearningPlan.withinFuzz(23 * day, 20))
        assertFalse(LearningPlan.withinFuzz(25 * day, 20))
        assertTrue(LearningPlan.withinFuzz(107 * day, 100))
        assertFalse(LearningPlan.withinFuzz(109 * day, 100))
    }

    @Test
    fun aLapsedReviewCardFollowsTheRelearningSteps() {
        val steps = defaults.copy(relearnMs = listOf(10 * minute, 30 * minute))
        val review = card(1, listOf("10m", "2d", "5d", "9d"), steps)
        val fresh = card(2, listOf("1m", "6m", "10m", "4d"), steps) // says the deck is SM-2
        val q = OfflineQueue(pack(review, fresh))
        q.answer(review.key, 1, 0)
        assertEquals(10 * minute, q.waitingFor(review.key).delayMs)
        assertEquals(listOf("10m", "20m", "30m", ""), q.labels(review.key))
        q.answer(review.key, 3, 10 * minute)
        assertEquals(30 * minute, q.waitingFor(review.key).delayMs)
        assertEquals("SM-2 with a 0 multiplier: the minimum interval", listOf("10m", "30m", "1d", ""), q.labels(review.key))
        q.answer(review.key, 3, 40 * minute)
        assertTrue(review.key in q.state.done)
    }

    @Test
    fun aCardDownloadedHalfwayThroughItsStepsCarriesOn() {
        // AnkiDroid's labels for a card on its second step: Hard repeats 10m, Good graduates.
        val a = card(1, listOf("1m", "10m", "1d", "4d"))
        val q = OfflineQueue(pack(a, card(2, listOf("1m", "6m", "10m", "4d"))))
        q.answer(a.key, 2, 0)
        assertEquals(10 * minute, q.waitingFor(a.key).delayMs)
        assertEquals(1, q.waitingFor(a.key).step)
        q.answer(a.key, 3, 10 * minute)
        assertTrue(a.key in q.state.done)
    }

    @Test
    fun withoutDeckSettingsTheLabelsStillGuide() {
        // A download from an older phone app: the steps the labels show, and no graduating time.
        val a = card(1, listOf("1m", "6m", "10m", "4d"), steps = null)
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 1, 0)
        assertEquals(listOf("1m", "6m", "10m", ""), q.labels(a.key))
        q.answer(a.key, 3, minute)
        assertEquals(listOf("1m", "10m", "", ""), q.labels(a.key))
        q.answer(a.key, 3, 11 * minute)
        assertTrue(a.key in q.state.done)
    }

    @Test
    fun settingsThatDontFitTheLabelsAreIgnored() {
        // The labels say 2m/20m but the settings say 1m/10m (a filtered deck, say): the labels win.
        val a = card(1, listOf("2m", "11m", "20m", "4d"))
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 3, 0)
        assertEquals(20 * minute, q.waitingFor(a.key).delayMs)
        assertEquals(listOf("2m", "20m", "", ""), q.labels(a.key))
    }

    @Test
    fun twoAndThreeButtonCardsGetLabelsForTheirButtons() {
        val two = card(1, listOf("1m", "10m"), buttons = 2)
        val three = card(2, listOf("1m", "10m", "4d"), buttons = 3)
        val q = OfflineQueue(pack(two, three, card(3, listOf("1m", "6m", "10m", "4d"))))
        q.answer(two.key, 1, 0)
        q.answer(three.key, 1, 0)
        assertEquals(listOf("1m", "10m"), q.labels(two.key))
        assertEquals(listOf("1m", "10m", "4d"), q.labels(three.key))
    }

    @Test
    fun aStepOfADayOrMoreEndsTheCardForToday() {
        val steps = DeckSteps(listOf(10 * minute, day.toLong()), 3, 4, listOf(10 * minute), 1, 0.0)
        val a = card(1, listOf("10m", "12h", "1d", "4d"), steps)
        val q = OfflineQueue(pack(a))
        q.answer(a.key, 1, 0)
        assertEquals(listOf("10m", "12.1h", "1d", "4d"), q.labels(a.key))
        q.answer(a.key, 3, 10 * minute)
        assertTrue(a.key in q.state.done)
    }

    @Test
    fun anAmbiguousDeckShowsNoGraduatingTime() {
        // Steps 10m 2d, relearning 10m: a new card's labels (10m, 1d, 2d, 4d) read like a
        // review card's. Rather than guess, the time Good would graduate to stays blank.
        val steps = DeckSteps(listOf(10 * minute, 2 * day), 1, 4, listOf(10 * minute), 1, 0.0)
        val review = card(1, listOf("10m", "3d", "14d", "31d"), steps)
        val fresh = card(2, listOf("10m", "1d", "2d", "4d"), steps)
        val q = OfflineQueue(pack(review, fresh))
        q.answer(review.key, 1, 0)
        assertEquals(listOf("10m", "15m", "", ""), q.labels(review.key))
    }

    // ── Against a model of Anki's rules ──────────────────────────────────────────────────

    /** Anki's learning rules, written out separately from [LearningPlan]. */
    private class Model(val steps: List<Long>, val graduate: Long?, val easy: Long?) {
        var step = 0

        /** The delay [grade] (1–4) gives from the current step, or null for graduating. */
        fun delay(grade: Int, from: Int = step): Long? = when (grade) {
            1 -> steps[0]
            2 -> if (from == 0) {
                if (steps.size >= 2) (steps[0] + steps[1]) / 2 else minOf((steps[0] * 1.5).roundToLong(), steps[0] + DAY)
            } else steps[from]
            3 -> steps.getOrNull(from + 1)
            else -> null
        }

        fun label(grade: Int): Long? = delay(grade) ?: if (grade == 4) easy else graduate

        /** Answers; false once the card is done for today. */
        fun answer(grade: Int): Boolean {
            val d = delay(grade) ?: return false
            step = when (grade) { 1 -> 0; 2 -> step; else -> step + 1 }
            return d < DAY
        }

        companion object {
            const val DAY = 86_400_000L
        }
    }

    private val stepChoices = listOf(1, 3, 5, 10, 15, 25, 40, 60, 90, 180, 360, 720, 1_440, 2_880).map { it * 60_000L }

    private fun label(ms: Long) = LearningPlan.format(ms)

    @Test
    fun randomDecksAndGradesMatchTheModel() {
        val rnd = Random(77)
        var repeats = 0
        var graduated = 0
        repeat(iterations) { run ->
            val learn = stepChoices.shuffled(rnd).take(rnd.nextInt(1, 5))
            var relearn = stepChoices.shuffled(rnd).take(rnd.nextInt(1, 3))
            // A new card whose Hard and Good are both a day or more, on a first step that is
            // also the first relearning step, has a review card's labels: no one can tell
            // them apart (see anAmbiguousDeckShowsNoGraduatingTime). Keep the runs readable.
            if (relearn[0] == learn[0] && learn.size > 1 && learn[1] >= day) relearn = listOf(learn[0] * 2) + relearn.drop(1)
            val steps = DeckSteps(learn, rnd.nextInt(1, 6), rnd.nextInt(6, 12), relearn, rnd.nextInt(1, 4), 0.0)
            val sm2 = rnd.nextBoolean()
            val reviewCard = rnd.nextInt(3) == 0
            // What AnkiDroid shows on first sight, for a new card on its first step or a
            // review card (whose Again starts relearning).
            val newModel = Model(learn, steps.graduatingDays * day, steps.easyDays * day)
            val fuzzedEasyFirst = "${steps.easyDays + rnd.nextInt(-1, 2)}d"
            val firstLabels = if (reviewCard) {
                listOf(label(relearn[0]), "${rnd.nextInt(2, 9)}d", "${rnd.nextInt(10, 20)}d", "${rnd.nextInt(21, 40)}d")
            } else (1..4).map { g ->
                val ms = newModel.label(g)!!
                if (g == 4) (if (sm2) fuzzedEasyFirst else "${steps.easyDays + 3}d") else label(ms) // FSRS: another easy
            }
            val a = card(1, firstLabels, steps)
            // A new card on the deck's settings tells the queue whether they hold (SM-2).
            // Under SM-2, AnkiDroid's Easy label carries Anki's fuzz (a day or more either way).
            val fuzzedEasy = "${steps.easyDays + rnd.nextInt(-1, 2)}d"
            val witness = card(2, (1..4).map { g -> if (g == 4) (if (sm2) fuzzedEasy else "${steps.easyDays + 3}d") else label(newModel.label(g)!!) }, steps)
            val q = OfflineQueue(pack(a, witness))
            val ctx = "run $run: learn=${learn.map { it / minute }} relearn=${relearn.map { it / minute }} sm2=$sm2 review=$reviewCard labels=$firstLabels"
            val model = if (reviewCard) Model(relearn, if (sm2) steps.lapseMinDays * day else null, null)
            else Model(learn, if (sm2) steps.graduatingDays * day else null, if (sm2) steps.easyDays * day else null)

            var now = 0L
            // First answer: a review card can only come back through Again.
            val first = if (reviewCard) 1 else rnd.nextInt(1, 5)
            var alive = if (reviewCard) {
                model.step = 0
                relearn[0] < day
            } else model.answer(first)
            q.answer(a.key, first, now)
            val history = StringBuilder("$first")
            var guard = 0
            while (alive && guard++ < 12) {
                assertFalse("$ctx [$history] should be waiting", a.key in q.state.done)
                val w = q.waitingFor(a.key)
                assertEquals("$ctx [$history] step", model.step, w.step)
                // The buttons on the card as it comes back.
                val expected = (1..4).map { g -> model.label(g)?.let { ms -> firstLabels.firstOrNull { IntervalLabel.millis(it) == ms } ?: label(ms) } ?: "" }
                assertEquals("$ctx [$history] labels", expected, q.labels(a.key))
                repeats++
                now = w.dueAt
                val g = rnd.nextInt(1, 5)
                val expectDelay = model.delay(g)
                alive = model.answer(g)
                q.answer(a.key, g, now)
                history.append(" $g")
                if (alive) assertEquals("$ctx [$history] delay", expectDelay, q.waitingFor(a.key).delayMs)
                else {
                    assertTrue("$ctx [$history] should be done", a.key in q.state.done)
                    graduated++
                }
            }
            if (!alive) assertTrue("$ctx [$history] done", a.key in q.state.done)
        }
        println("learning steps: $iterations decks, $repeats repeat cards checked, $graduated graduated")
        assertTrue(repeats > iterations)
        assertTrue(graduated > iterations / 4)
    }

    @Test
    fun aRunStoredAndReadBackCarriesOnTheSame() {
        // The watch saves after every answer; a restart must not lose the step a card is on.
        val rnd = Random(5)
        repeat(iterations / 5) {
            val steps = DeckSteps(stepChoices.take(rnd.nextInt(1, 5)).shuffled(rnd).filter { it < day }.ifEmpty { listOf(minute) }, 1, 4, listOf(10 * minute), 1, 0.0)
            val model = Model(steps.learnMs, day, 4 * day)
            val labels = (1..4).map { label(model.label(it)!!) }
            val a = card(1, labels, steps)
            val p = pack(a)
            var q = OfflineQueue(p)
            var now = 0L
            q.answer(a.key, 1, now)
            repeat(rnd.nextInt(0, 6)) {
                val w = q.state.waiting.firstOrNull() ?: return@repeat
                q = OfflineQueue(p, OfflineQueue.decodeState(p.id, OfflineQueue.encodeState(p.id, q.state))!!)
                now = w.dueAt
                q.answer(a.key, rnd.nextInt(1, 4), now)
            }
            val restored = OfflineQueue(p, OfflineQueue.decodeState(p.id, OfflineQueue.encodeState(p.id, q.state))!!)
            assertEquals(q.labels(a.key), restored.labels(a.key))
            assertEquals(q.state, restored.state)
        }
    }

    @Test
    fun randomLabelsAndSettingsNeverBreakTheQueue() {
        val rnd = Random(13)
        val pool = listOf("1m", "<1m", "6m", "10m", "1d", "4d", "", "?", "1.5h", "2y", "0s", "30s", "3mo", "1,5 Mo", "∞")
        repeat(iterations) { run ->
            val cards = (1L..rnd.nextLong(1, 6)).map { n ->
                val steps = if (rnd.nextInt(4) == 0) null else DeckSteps(
                    List(rnd.nextInt(0, 4)) { rnd.nextLong(1_000, 3 * day) }, rnd.nextInt(1, 9), rnd.nextInt(1, 9),
                    List(rnd.nextInt(0, 3)) { rnd.nextLong(1_000, 2 * day) }, rnd.nextInt(1, 4), if (rnd.nextBoolean()) 0.0 else rnd.nextDouble(2.0)
                )
                card(n, List(rnd.nextInt(0, 5)) { pool[rnd.nextInt(pool.size)] }, steps, buttons = rnd.nextInt(2, 5))
            }
            val q = OfflineQueue(OfflinePack(run.toLong(), 1, "R", 0, cards))
            var now = 0L
            repeat(40) {
                val next = try { q.next(now) } catch (e: Throwable) { fail("run $run next threw $e"); return@repeat }
                when (next) {
                    is OfflineQueue.Next.Show -> {
                        val labels = q.labels(next.card.key)
                        if (next.repeat) assertEquals("run $run", minOf(4, maxOf(2, next.card.buttonCount)), labels.size)
                        val ease = if (rnd.nextInt(8) == 0) Wire.EASE_BURY else rnd.nextInt(1, next.card.buttonCount.coerceIn(2, 4) + 1)
                        q.answer(next.card.key, ease, now)
                        val w = q.state.waiting.firstOrNull { it.key == next.card.key }
                        if (w != null) assertTrue("run $run: ${w.delayMs}", w.delayMs in 0 until day)
                        now += rnd.nextLong(0, 30 * minute)
                    }
                    is OfflineQueue.Next.Wait -> now = next.until
                    OfflineQueue.Next.Finished -> return@repeat
                }
            }
        }
    }

    // ── Settings, labels and bytes ───────────────────────────────────────────────────────

    @Test
    fun durationsAreWrittenTheWayAnkiDroidDoes() {
        val cases = mapOf(
            30_000L to "<1m", minute to "1m", 6 * minute to "6m", 330_000L to "6m", 10 * minute to "10m",
            59 * minute to "59m", 60 * minute to "1h", 90 * minute to "1.5h", 12 * 60 * minute to "12h",
            day to "1d", 4 * day to "4d", 29 * day to "29d", 45 * day to "1.5mo", 400 * day to "1.1y"
        )
        for ((ms, text) in cases) assertEquals("$ms", text, LearningPlan.format(ms))
        // Every label it writes reads back as about the same time.
        val rnd = Random(1)
        repeat(iterations) {
            val ms = rnd.nextLong(minute, 3_000 * day)
            val back = IntervalLabel.millis(LearningPlan.format(ms))!!
            // Minutes and hours are close; days and more are rounded as AnkiDroid rounds them.
            val slack = if (ms < day) maxOf(31_000L, ms / 15) else (ms * 0.34).toLong()
            assertTrue("$ms → ${LearningPlan.format(ms)} → $back", kotlin.math.abs(back - ms) <= slack)
        }
    }

    @Test
    fun settingsAreCheckedWhenMade() {
        assertEquals(defaults, DeckSteps.of(listOf(1.0, 10.0), 1, 4, listOf(10.0), 1, 0.0))
        assertEquals(listOf(30_000L), DeckSteps.of(listOf(0.5), 1, 4, emptyList(), 1, 0.0)!!.learnMs)
        for (bad in listOf(
            DeckSteps.of(listOf(-1.0), 1, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(0.0), 1, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(Double.NaN), 1, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(1e12), 1, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(List(33) { 1.0 }, 1, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(1.0), 0, 4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(1.0), 1, -4, listOf(10.0), 1, 0.0),
            DeckSteps.of(listOf(1.0), 1, 4, listOf(10.0), 0, 0.0),
            DeckSteps.of(listOf(1.0), 1, 4, listOf(10.0), 1, -0.5),
            DeckSteps.of(listOf(1.0), 1, 4, listOf(10.0), 1, Double.NaN)
        )) assertNull(bad)
    }

    @Test
    fun packsCarryTheSettingsExactly() {
        val other = DeckSteps(listOf(minute, 10 * minute, 60 * minute), 2, 5, listOf(5 * minute), 2, 0.5)
        val cards = (1L..30L).map { card(it, listOf("1m", "6m", "10m", "4d"), listOf(defaults, other, null)[(it % 3).toInt()]) }
        val p = pack(*cards.toTypedArray())
        val bytes = OfflinePackCodec.encode(p)
        assertEquals(p, OfflinePackCodec.decode(bytes))
        // Settings are written once each, not per card.
        val without = OfflinePackCodec.encode(p.copy(cards = cards.map { it.copy(steps = null) }))
        assertTrue("${bytes.size} vs ${without.size}", bytes.size < without.size + 200)
    }

    @Test
    fun aDownloadFromTheOlderFormatStillOpens() {
        // Version 1, as the phone app wrote it before settings were added: one card.
        val raw = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(java.util.zip.GZIPOutputStream(raw)).use { out ->
            out.writeInt(0x41575031); out.writeByte(1)
            out.writeLong(5); out.writeLong(6); out.writeLong(7)
            val strings = listOf("Deck", "1m", "10m", "", "x {{c1::y}}", "Cloze")
            out.writeInt(strings.size)
            strings.forEach { val b = it.toByteArray(); out.writeInt(b.size); out.write(b) }
            out.writeInt(0) // deck name
            out.writeInt(1)
            out.writeLong(9); out.writeInt(0); out.writeInt(2)
            out.writeByte(2); out.writeInt(1); out.writeInt(2)
            out.writeInt(3); out.writeInt(3); out.writeInt(4); out.writeInt(1)
            out.writeByte(0)
            out.writeInt(5)
        }
        val p = OfflinePackCodec.decode(raw.toByteArray())
        assertEquals(OfflinePack(5, 6, "Deck", 7, listOf(PackCard(9, 0, 2, listOf("1m", "10m"), "", "", "x {{c1::y}}", 1, emptyList(), "Cloze"))), p)
        assertNull(p.cards.single().steps)
    }
}
