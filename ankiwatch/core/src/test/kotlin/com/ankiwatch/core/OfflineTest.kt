package com.ankiwatch.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Reviewing without the phone: AnkiDroid's interval labels, the downloaded pack's bytes, and
 * the order the watch shows cards in. Randomised parts scale with -Dstress.iterations=N and
 * print their seed on failure.
 */
class OfflineTest {

    private val iterations = (System.getProperty("stress.iterations") ?: "2500").toInt()

    private val minute = IntervalLabel.MINUTE_MS
    private val day = IntervalLabel.DAY_MS

    // AnkiDroid's labels with default deck options: new cards step 1m 10m, relearning 10m.
    private val newLabels = listOf("1m", "6m", "10m", "4d")
    private val reviewLabels = listOf("10m", "2d", "5d", "9d")

    private fun card(note: Long, ord: Int = 0, labels: List<String> = reviewLabels, buttons: Int = 4, content: String = "x{{c${ord + 1}::s$note}}") =
        PackCard(note, ord, buttons, labels, "", "", content, ord + 1, listOf("Extra" to "e$note"), "Cloze")

    private fun pack(cards: List<PackCard>, id: Long = 7) = OfflinePack(id, 1L, "Law", 1_000L, cards)

    private fun OfflineQueue.shown(now: Long): OfflineQueue.Next.Show {
        val next = next(now)
        assertTrue("expected a card at $now, got $next", next is OfflineQueue.Next.Show)
        return next as OfflineQueue.Next.Show
    }

    // ── Interval labels ──────────────────────────────────────────────────────────────────

    @Test
    fun ankiDroidLabelsBecomeDelays() {
        val cases = mapOf(
            "1m" to minute, "<1m" to minute, "10m" to 10 * minute, "6m" to 6 * minute, "30s" to 30_000L,
            "1.5h" to 90 * minute, "1,5h" to 90 * minute, "4d" to 4 * day, "1.2mo" to Math.round(1.2 * 30 * day),
            "2y" to 2 * 365 * day, "1w" to 7 * day, " 4d " to 4 * day, "4d." to 4 * day, "~3d" to 3 * day,
            "≈2d" to 2 * day, "1 min" to minute, "10 mins" to 10 * minute, "2 days" to 2 * day, "3 hrs" to 180 * minute,
            "1 Month" to 30 * day, "0s" to 0L,
            // Fluent's isolation marks and the spaces translations use.
            "\u20681\u2069m" to minute, "4\u00A0d" to 4 * day, "10\u202Fmin" to 10 * minute, "\u200E3d" to 3 * day
        )
        for ((label, ms) in cases) assertEquals(label, ms, IntervalLabel.millis(label))
    }

    @Test
    fun anythingElseIsNoInterval() {
        for (label in listOf("", "d", "m", "10", "abc", "10 parsecs", "-1d", "1e9d", "∞", "１０m", "1.2.3d", "d4", "1 d 2", "99999999999d")) {
            assertNull(label, IntervalLabel.millis(label))
        }
    }

    @Test
    fun randomLabelsNeverThrow() {
        val rnd = Random(11)
        val alphabet = "0123456789.,<~≈ dhmswoyMINSEC-+eé日\u0000\t"
        repeat(iterations * 20) {
            val label = String(CharArray(rnd.nextInt(0, 12)) { alphabet[rnd.nextInt(alphabet.length)] })
            val ms = try {
                IntervalLabel.millis(label)
            } catch (e: Throwable) {
                fail("'$label' threw $e"); null
            }
            if (ms != null) assertTrue("'$label' → $ms", ms >= 0)
        }
    }

    // ── The pack's bytes ─────────────────────────────────────────────────────────────────

    private val pool = listOf("", "a", "Act §999", "दंड संहिता", "قانون", "😀 emoji", "<b>bold</b>", "\u0000", "x".repeat(70_000))

    private fun randomString(rnd: Random): String = when (rnd.nextInt(4)) {
        0 -> pool[rnd.nextInt(pool.size)]
        1 -> String(CharArray(rnd.nextInt(0, 40)) { (rnd.nextInt(32, 0x2FFF)).toChar() })
        else -> "w" + rnd.nextInt(0, 50)
    }

    private fun randomPack(rnd: Random, maxCards: Int = 60): OfflinePack {
        val notes = (0 until rnd.nextInt(1, 20)).map { rnd.nextLong(1, Long.MAX_VALUE) to randomString(rnd) }
        val cards = List(rnd.nextInt(0, maxCards)) {
            val (note, text) = notes[rnd.nextInt(notes.size)]
            PackCard(
                noteId = note, cardOrd = rnd.nextInt(0, 30), buttonCount = rnd.nextInt(2, 5),
                nextReviewTimes = List(rnd.nextInt(0, 5)) { randomString(rnd) },
                question = if (rnd.nextBoolean()) "" else randomString(rnd),
                answer = if (rnd.nextBoolean()) "" else randomString(rnd),
                clozeContent = text, clozeNumber = rnd.nextInt(0, 30),
                extras = List(rnd.nextInt(0, 4)) { randomString(rnd) to randomString(rnd) },
                modelName = randomString(rnd)
            )
        }
        return OfflinePack(rnd.nextLong(), rnd.nextLong(), randomString(rnd), rnd.nextLong(), cards)
    }

    @Test
    fun packsSurviveTheTripExactly() {
        val rnd = Random(3)
        repeat(iterations / 10) { i ->
            val pack = randomPack(rnd)
            val bytes = OfflinePackCodec.encode(pack)
            assertEquals("pack $i", pack, OfflinePackCodec.decode(bytes))
            assertArrayEquals("encoding is deterministic", bytes, OfflinePackCodec.encode(pack))
        }
        val empty = OfflinePack(1, 2, "", 3, emptyList())
        assertEquals(empty, OfflinePackCodec.decode(OfflinePackCodec.encode(empty)))
    }

    @Test
    fun aNotesClozeCardsShareItsText() {
        val text = (1..400).joinToString(" ") { "statute word$it {{c${it % 10 + 1}::answer$it}}" }
        fun size(cards: Int) = OfflinePackCodec.encode(pack((0 until cards).map { card(1, it, content = text) })).size
        val one = size(1)
        val ten = size(10)
        assertTrue("10 sibling cards take $ten bytes, one takes $one", ten < one * 1.3)
    }

    @Test
    fun aRealisticDayFitsComfortably() {
        // 600 cards from 150 long law notes: what a big day might download.
        val rnd = Random(5)
        val notes = (0 until 150).map { n ->
            (1..120).joinToString(" ") { w -> "word${rnd.nextInt(5_000)} {{c${w % 4 + 1}::ans$n-$w}}" }
        }
        val cards = (0 until 600).map { PackCard(it / 4L, it % 4, 4, newLabels, "", "", notes[it / 4], it % 4 + 1, listOf("Note" to "n$it"), "Enhanced Cloze") }
        val start = System.nanoTime()
        val bytes = OfflinePackCodec.encode(pack(cards))
        val decoded = OfflinePackCodec.decode(bytes)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(600, decoded.cards.size)
        assertTrue("600 cards take ${bytes.size} bytes", bytes.size < 600_000)
        assertTrue("encode+decode took ${ms}ms", ms < 5_000)
    }

    @Test
    fun damagedPacksAreRefusedCleanly() {
        val rnd = Random(9)
        repeat(iterations / 5) { i ->
            val pack = randomPack(rnd, maxCards = 20)
            val good = OfflinePackCodec.encode(pack)
            val bad: ByteArray = when (rnd.nextInt(5)) {
                0 -> good.copyOf(rnd.nextInt(0, good.size)) // cut short
                1 -> good.copyOf().also { b -> repeat(rnd.nextInt(1, 4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
                2 -> good + ByteArray(rnd.nextInt(1, 20)) { rnd.nextInt(256).toByte() } // trailing junk
                3 -> ByteArray(rnd.nextInt(0, 300)) { rnd.nextInt(256).toByte() } // noise
                else -> gzip(ByteArray(rnd.nextInt(0, 200)) { rnd.nextInt(256).toByte() }) // valid gzip, junk inside
            }
            try {
                val decoded = OfflinePackCodec.decode(bad)
                // Only a change that leaves the packed data intact (the gzip header's time or
                // OS byte, or junk after the gzip trailer) may still decode, and then to the
                // very same pack.
                assertEquals("case $i decoded to something else", pack, decoded)
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.startsWith("Offline pack is damaged"))
            } catch (e: Throwable) {
                fail("case $i threw $e instead of a clean refusal")
            }
        }
    }

    @Test
    fun aWrongVersionOrMagicIsRefused() {
        fun header(magic: Int, version: Int) = gzip(ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { it.writeInt(magic); it.writeByte(version); it.writeLong(1); it.writeLong(2); it.writeLong(3); it.writeInt(0) }
        }.toByteArray())
        for (bytes in listOf(header(0x41575031, 2), header(0x12345678, 1), ByteArray(0))) {
            try {
                OfflinePackCodec.decode(bytes)
                fail("accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun aPackThatUnpacksHugeIsRefusedEarly() {
        // A well-formed pack whose strings unpack to more than the limit (zeros pack tiny).
        val packed = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(packed)).use { out ->
            out.writeInt(0x41575031); out.writeByte(1); out.writeLong(1); out.writeLong(2); out.writeLong(3)
            val strings = (OfflinePackCodec.MAX_UNPACKED_BYTES / OfflinePackCodec.MAX_STRING_BYTES + 2).toInt()
            out.writeInt(strings)
            val zeros = ByteArray(OfflinePackCodec.MAX_STRING_BYTES)
            repeat(strings) { out.writeInt(zeros.size); out.write(zeros) }
            out.writeInt(0); out.writeInt(0)
        }
        assertTrue("packs to ${packed.size()} bytes", packed.size() < 1_000_000)
        val start = System.nanoTime()
        try {
            OfflinePackCodec.decode(packed.toByteArray())
            fail("accepted a pack that unpacks to more than the limit")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("unpacked"))
        }
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 10_000)
    }

    @Test
    fun oversizedPacksAreNotBuilt() {
        try {
            OfflinePackCodec.encode(pack((0..OfflinePackCodec.MAX_CARDS).map { card(it.toLong()) }))
            fail("encoded more than MAX_CARDS")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(bytes) } }.toByteArray()

    // ── The offline queue ────────────────────────────────────────────────────────────────

    @Test
    fun aNewCardFollowsAnkiDroidsDefaultSteps() {
        val a = card(1, labels = newLabels)
        val fresh = (2L..6L).map { card(it) }
        val q = OfflineQueue(pack(listOf(a) + fresh))
        assertEquals(a, q.shown(0).card)
        q.answer(a.key, 1, 0) // Again → back in 1 minute
        assertEquals(fresh[0], q.shown(0).card) // not due yet: fresh cards go on
        q.answer(fresh[0].key, 3, 0)
        assertEquals(fresh[1], q.shown(30_000).card)
        q.answer(fresh[1].key, 3, 30_000)
        val back = q.shown(61_000)
        assertEquals("due learning cards come before fresh ones", a, back.card)
        assertTrue(back.repeat)
        q.answer(a.key, 3, 61_000) // Good from the first step → second step, 10 minutes
        assertEquals(fresh[2], q.shown(62_000).card)
        q.answer(fresh[2].key, 3, 62_000)
        q.answer(fresh[3].key, 3, 63_000)
        q.answer(fresh[4].key, 3, 64_000)
        // Only the learning card is left; 10 minutes is within the 20-minute learn-ahead.
        assertEquals(a, q.shown(65_000).card)
        q.answer(a.key, 3, 65_000) // Good from the second step: graduated
        assertEquals(OfflineQueue.Next.Finished, q.next(70_000))
        assertEquals(0, q.remaining)
        assertEquals(8, q.state.answers)
    }

    @Test
    fun aLapsedReviewComesBackOnceThenGraduates() {
        val r = card(1)
        val q = OfflineQueue(pack(listOf(r)))
        q.answer(r.key, 1, 0) // relearning step: 10 minutes
        val w = q.next(0, learnAheadMs = 0)
        assertEquals(OfflineQueue.Next.Wait(10 * minute, 1), w)
        assertTrue(q.shown(10 * minute).repeat)
        q.answer(r.key, 3, 10 * minute) // Good label is 5d: graduated
        assertEquals(OfflineQueue.Next.Finished, q.next(day))
    }

    @Test
    fun goodAndEasyOnAReviewCardFinishIt() {
        val cards = (1L..4L).map { card(it) }
        val q = OfflineQueue(pack(cards))
        q.answer(cards[0].key, 3, 0)
        q.answer(cards[1].key, 4, 0)
        q.answer(cards[2].key, 2, 0) // Hard: 2d
        q.answer(cards[3].key, Wire.EASE_BURY, 0)
        assertEquals(OfflineQueue.Next.Finished, q.next(0))
        assertEquals(cards.map { it.key }.toSet(), q.state.done)
    }

    @Test
    fun buryFinishesEvenANewOrLearningCard() {
        // Good would bring these back in minutes; Bury must not.
        val fresh = card(1, labels = newLabels)
        val learning = card(2, labels = newLabels)
        val q = OfflineQueue(pack(listOf(fresh, learning)))
        q.answer(fresh.key, Wire.EASE_BURY, 0)
        q.answer(learning.key, 1, 0)
        q.answer(learning.key, Wire.EASE_BURY, minute)
        assertEquals(OfflineQueue.Next.Finished, q.next(minute))
        assertEquals(setOf(fresh.key, learning.key), q.state.done)
        assertNull(q.showNow())
    }

    @Test
    fun hardRepeatsTheStepItIsOn() {
        val a = card(1, labels = newLabels)
        val q = OfflineQueue(pack(listOf(a)))
        q.answer(a.key, 2, 0) // Hard on a new card: 6 minutes, still the first step
        assertEquals(OfflineQueue.Next.Wait(6 * minute, 1), q.next(0, learnAheadMs = 0))
        q.answer(a.key, 2, 6 * minute) // Hard again: the same 6 minutes
        assertEquals(OfflineQueue.Next.Wait(12 * minute, 1), q.next(6 * minute, learnAheadMs = 0))
        q.answer(a.key, 3, 12 * minute) // Good: second step, 10 minutes
        assertEquals(OfflineQueue.Next.Wait(22 * minute, 1), q.next(12 * minute, learnAheadMs = 0))
        q.answer(a.key, 1, 22 * minute) // Again: back to the start, 1 minute
        assertEquals(OfflineQueue.Next.Wait(23 * minute, 1), q.next(22 * minute, learnAheadMs = 0))
    }

    @Test
    fun learningCardsWaitBeyondTheLearnAheadLimit() {
        val a = card(1, labels = listOf("1m", "30m", "1h", "4d"))
        val q = OfflineQueue(pack(listOf(a)))
        q.answer(a.key, 3, 0) // Good: an hour
        assertEquals(OfflineQueue.Next.Wait(60 * minute, 1), q.next(0))
        assertEquals(OfflineQueue.Next.Wait(60 * minute, 1), q.next(39 * minute))
        assertEquals(a, q.shown(40 * minute).card) // within 20 minutes of due
        assertEquals(a, q.showNow()!!.card)
    }

    @Test
    fun cardsWithoutLabelsStillWork() {
        val a = card(1, labels = emptyList())
        val b = card(2, labels = listOf("soon", "", "later", "?"))
        val q = OfflineQueue(pack(listOf(a, b)))
        q.answer(a.key, 1, 0) // Again: AnkiDroid's default first step, a minute
        assertEquals(b, q.shown(0).card)
        q.answer(b.key, 3, 0) // Good with no readable label: done
        assertEquals(OfflineQueue.Next.Wait(minute, 1), q.next(0, learnAheadMs = 0))
        q.answer(a.key, 3, minute) // Good on a repeat with no labels: done
        assertEquals(OfflineQueue.Next.Finished, q.next(minute))
    }

    @Test
    fun twoAndThreeButtonCardsUseTheirOwnButtons() {
        val two = card(1, labels = listOf("1m", "10m"), buttons = 2)
        val three = card(2, labels = listOf("1m", "10m", "4d"), buttons = 3)
        val q = OfflineQueue(pack(listOf(two, three)))
        q.answer(two.key, 2, 0) // Good on a 2-button card: label 2 (10m)
        q.answer(three.key, 3, 0) // Easy on a 3-button card: label 3 (4d) → done
        assertEquals(OfflineQueue.Next.Wait(10 * minute, 1), q.next(0, learnAheadMs = 0))
        assertTrue(three.key in q.state.done)
    }

    @Test
    fun cardsAnsweredElsewhereAreSkipped() {
        val cards = (1L..3L).map { card(it, labels = newLabels) }
        val q = OfflineQueue(pack(cards))
        q.markDone(cards[1].key)
        q.answer(cards[0].key, 1, 0)
        assertEquals(cards[2], q.shown(0).card)
        q.markDone(cards[0].key) // was waiting
        q.answer(cards[2].key, 4, 0)
        assertEquals(OfflineQueue.Next.Finished, q.next(day))
        q.markDone(CardKey(99, 0)) // not in the pack: nothing happens
        assertEquals(cards.map { it.key }.toSet(), q.state.done)
    }

    @Test
    fun unknownCardsAndNonAnswersChangeNothing() {
        val q = OfflineQueue(pack(listOf(card(1))))
        q.answer(CardKey(2, 0), 3, 0)
        assertEquals(OfflineQueue.State(), q.state)
        for (ease in listOf(-1, 5)) {
            try {
                q.answer(CardKey(1, 0), ease, 0)
                fail("accepted ease $ease")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun duplicateCardsInAPackAreShownOnce() {
        val a = card(1)
        val q = OfflineQueue(pack(listOf(a, a, card(2), a)))
        assertEquals(2, q.cards.size)
        assertEquals(2, q.remaining)
    }

    @Test
    fun aStoredStateBelongsToItsPack() {
        val cards = (1L..5L).map { card(it, labels = newLabels) }
        val q = OfflineQueue(pack(cards, id = 42))
        q.answer(cards[0].key, 1, 0)
        q.answer(cards[1].key, 3, 0)
        q.answer(cards[2].key, Wire.EASE_BURY, 0)
        val bytes = OfflineQueue.encodeState(42, q.state)
        assertEquals(q.state, OfflineQueue.decodeState(42, bytes))
        assertNull("another pack's state", OfflineQueue.decodeState(43, bytes))
        assertNull(OfflineQueue.decodeState(42, bytes.copyOf(bytes.size - 1)))
        assertNull(OfflineQueue.decodeState(42, bytes + 0.toByte()))
        assertNull(OfflineQueue.decodeState(42, ByteArray(0)))
        val rnd = Random(4)
        repeat(iterations) {
            val noise = ByteArray(rnd.nextInt(0, 80)) { rnd.nextInt(256).toByte() }
            try {
                OfflineQueue.decodeState(42, noise)
            } catch (e: Throwable) {
                fail("noise threw $e")
            }
        }
    }

    @Test
    fun aStoredStateIsTidiedAgainstThePack() {
        val a = card(1)
        val stray = CardKey(9, 9)
        val state = OfflineQueue.State(
            seen = setOf(stray),
            done = setOf(stray, a.key),
            waiting = listOf(OfflineQueue.Waiting(a.key, 5, 5, 0), OfflineQueue.Waiting(stray, 5, 5, 0)),
            answers = -3
        )
        val q = OfflineQueue(pack(listOf(a)), state)
        assertEquals(OfflineQueue.State(seen = emptySet(), done = setOf(a.key), waiting = emptyList(), answers = 0), q.state)
    }

    // ── Stress: random sessions ──────────────────────────────────────────────────────────

    private val labelPool = listOf(newLabels, reviewLabels, listOf("1m", "10m", "1d", "4d"), listOf("<1m", "<10m", "1h", "2h"),
        emptyList(), listOf("x", "", "?"), listOf("1m", "10m"), listOf("1m", "10m", "4d"))

    private fun randomQueuePack(rnd: Random): OfflinePack {
        val cards = List(rnd.nextInt(0, 80)) {
            val labels = labelPool[rnd.nextInt(labelPool.size)]
            val buttons = if (labels.size in 2..3) labels.size else 4
            card(rnd.nextLong(1, 30), rnd.nextInt(0, 3), labels, buttons)
        }
        return pack(cards, id = rnd.nextLong())
    }

    @Test
    fun randomSessionsKeepEveryPromise() {
        repeat(iterations / 10) { round ->
            val seed = 1_000L + round
            val rnd = Random(seed)
            val pack = randomQueuePack(rnd)
            var q = OfflineQueue(pack)
            val twin = OfflineQueue(pack)
            var now = rnd.nextLong(0, 1_000_000_000_000)
            repeat(rnd.nextInt(0, 300)) { step ->
                val where = "seed $seed step $step"
                val next = q.next(now)
                assertEquals("$where: twins disagree", next, twin.next(now))
                val fresh = q.cards.count { it.key !in q.state.seen && it.key !in q.state.done }
                assertEquals(where, fresh + q.state.waiting.size, q.remaining)
                when (next) {
                    is OfflineQueue.Next.Show -> {
                        val key = next.card.key
                        assertTrue(where, key in q)
                        assertFalse("$where: showed a finished card", key in q.state.done)
                        assertEquals("$where: repeat flag", q.state.waiting.any { it.key == key }, next.repeat)
                        if (!next.repeat) assertFalse(where, key in q.state.seen)
                        val ease = rnd.nextInt(0, 5)
                        q.answer(key, ease, now)
                        twin.answer(key, ease, now)
                    }
                    is OfflineQueue.Next.Wait -> {
                        assertTrue(where, next.until > now + OfflineQueue.LEARN_AHEAD_MS)
                        assertEquals(where, q.state.waiting.size, next.count)
                        assertEquals(where, 0, fresh)
                        now = next.until
                    }
                    OfflineQueue.Next.Finished -> assertEquals(where, 0, q.remaining)
                }
                when (rnd.nextInt(10)) {
                    0 -> now += rnd.nextLong(0, 30 * minute)
                    1 -> if (q.cards.isNotEmpty()) q.cards[rnd.nextInt(q.cards.size)].key.let { q.markDone(it); twin.markDone(it) }
                    2 -> { // the app restarts: the state comes back from storage
                        val restored = OfflineQueue.decodeState(pack.id, OfflineQueue.encodeState(pack.id, q.state))
                        assertEquals(where, q.state, restored)
                        q = OfflineQueue(pack, restored!!)
                    }
                }
                assertTrue(where, q.state.waiting.map { it.key }.toSet().none { it in q.state.done })
            }
        }
    }

    @Test
    fun aDiligentSessionAlwaysFinishes() {
        repeat(iterations / 10) { round ->
            val seed = 5_000L + round
            val rnd = Random(seed)
            val pack = randomQueuePack(rnd)
            val q = OfflineQueue(pack)
            val shown = HashSet<CardKey>()
            var now = 0L
            var answers = 0
            // Again on a card's first showing at most, Good after that: every card graduates.
            while (true) {
                when (val next = q.next(now)) {
                    is OfflineQueue.Next.Show -> {
                        shown += next.card.key
                        val ease = if (!next.repeat && rnd.nextInt(3) == 0) 1 else 3
                        q.answer(next.card.key, ease, now)
                        answers++
                        now += rnd.nextLong(1_000, 20_000)
                    }
                    is OfflineQueue.Next.Wait -> now = next.until
                    OfflineQueue.Next.Finished -> break
                }
                assertTrue("seed $seed: $answers answers for ${q.cards.size} cards", answers <= 4 * q.cards.size)
            }
            assertEquals("seed $seed: every card shown", q.cards.map { it.key }.toSet(), shown)
            assertEquals(0, q.remaining)
        }
    }
}
