package com.ankiwear.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.CardKey
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.OfflinePackCodec
import com.ankiwatch.core.OfflineQueue
import com.ankiwatch.core.PackCard
import com.ankiwear.wear.offline.OfflineStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * The watch's store of downloaded decks, on the watch's own storage: where a review stands
 * survives restarts, a download replaces the deck's previous one, and damaged files are
 * dropped instead of crashing the app.
 */
@RunWith(AndroidJUnit4::class)
class OfflineStoreTest {

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "offline-test-${System.nanoTime()}"
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun card(note: Long, ord: Int = 0) =
        PackCard(note, ord, 4, listOf("1m", "6m", "10m", "4d"), "", "", "t{{c${ord + 1}::$note}}", ord + 1)

    private fun pack(deckId: Long, id: Long, vararg notes: Long) =
        OfflinePack(id, deckId, "Deck $deckId", 1_000L + id, notes.map { card(it) })

    private fun bytes(pack: OfflinePack) = OfflinePackCodec.encode(pack)

    @Test
    fun aReviewKeepsItsPlaceAcrossRestarts() {
        val store = OfflineStore(dir)
        assertEquals(3, store.import(bytes(pack(1, 10, 1, 2, 3))).total)
        val q = store.open(1)!!
        q.answer(CardKey(1, 0), 3, 0) // Good: back in 10 minutes
        q.answer(CardKey(2, 0), 4, 0) // Easy: done
        store.save(q)
        val restarted = OfflineStore(dir)
        assertEquals(q.state, restarted.open(1)!!.state)
        val summary = restarted.summaries().single()
        assertEquals(2, summary.remaining) // one fresh, one coming back
        assertEquals(2, summary.answers)
        assertEquals("Deck 1", summary.deckName)
    }

    @Test
    fun theSameDownloadTwiceKeepsTheReviewAndANewOneStartsOver() {
        val store = OfflineStore(dir)
        val first = bytes(pack(1, 10, 1, 2))
        store.import(first)
        store.open(1)!!.also { it.answer(CardKey(1, 0), 4, 0); store.save(it) }
        assertEquals(1, store.import(first).answers) // e.g. found again at the next start
        assertEquals(1, store.open(1)!!.state.answers)
        val second = store.import(bytes(pack(1, 11, 1, 2, 3)))
        assertEquals(0, second.answers)
        assertEquals(3, second.remaining)
    }

    @Test
    fun aDamagedDownloadIsRefusedAndTheOldOneKept() {
        val store = OfflineStore(dir)
        store.import(bytes(pack(1, 10, 1, 2)))
        for (junk in listOf(ByteArray(0), ByteArray(100) { it.toByte() }, bytes(pack(1, 11, 1)).copyOf(20))) {
            try {
                store.import(junk)
                fail("accepted ${junk.size} bytes of junk")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(10, store.summaries().single().packId)
    }

    @Test
    fun aDamagedFileIsDroppedNotACrash() {
        OfflineStore(dir).import(bytes(pack(1, 10, 1, 2)))
        File(dir, "pack-1.bin").writeBytes(ByteArray(64) { 7 })
        val store = OfflineStore(dir)
        assertNull(store.open(1))
        assertTrue(store.summaries().isEmpty())
        assertFalse(File(dir, "pack-1.bin").exists())
    }

    @Test
    fun aDamagedStateStartsTheDeckAfresh() {
        val store = OfflineStore(dir)
        store.import(bytes(pack(1, 10, 1, 2)))
        store.open(1)!!.also { it.answer(CardKey(1, 0), 4, 0); store.save(it) }
        File(dir, "state-1.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(OfflineQueue.State(), OfflineStore(dir).open(1)!!.state)
    }

    @Test
    fun aCardAnsweredInOneDeckIsDoneInOverlappingOnes() {
        val store = OfflineStore(dir)
        store.import(bytes(pack(1, 10, 1, 2, 3))) // a parent deck
        store.import(bytes(pack(2, 20, 2, 3))) // and its subdeck, sharing cards
        store.markDone(CardKey(2, 0), exceptDeckId = 1)
        assertTrue(CardKey(2, 0) in store.open(2)!!.state.done)
        assertFalse(CardKey(2, 0) in store.open(1)!!.state.done)
        store.markDone(CardKey(3, 0)) // answered online: done everywhere
        assertTrue(CardKey(3, 0) in store.open(1)!!.state.done)
        assertTrue(CardKey(3, 0) in store.open(2)!!.state.done)
        store.markDone(CardKey(99, 0)) // in no download: nothing to do
        assertEquals(2, store.summaries().size)
    }

    @Test
    fun removingForgetsTheDeckAndUnfinishedWritesAreCleanedUp() {
        val store = OfflineStore(dir)
        store.import(bytes(pack(1, 10, 1)))
        store.import(bytes(pack(2, 20, 2)))
        store.remove(1)
        assertNull(store.open(1))
        assertEquals(listOf(2L), store.summaries().map { it.deckId })
        File(dir, "pack-3.bin.tmp").writeBytes(byteArrayOf(1))
        OfflineStore(dir)
        assertFalse(File(dir, "pack-3.bin.tmp").exists())
    }

    @Test
    fun newestDownloadsComeFirst() {
        val store = OfflineStore(dir)
        store.import(bytes(pack(1, 10, 1)))
        store.import(bytes(pack(2, 30, 2)))
        store.import(bytes(pack(3, 20, 3)))
        assertEquals(listOf(2L, 3L, 1L), store.summaries().map { it.deckId })
    }

    @Test
    fun stressManyDownloadsAndAnswers() {
        val rnd = Random(21)
        val store = OfflineStore(dir)
        val expected = HashMap<Long, OfflineQueue.State>()
        var packId = 100L
        repeat(300) { step ->
            val deckId = rnd.nextLong(1, 6)
            when (rnd.nextInt(6)) {
                0 -> {
                    val notes = LongArray(rnd.nextInt(0, 40)) { rnd.nextLong(1, 60) }
                    store.import(bytes(pack(deckId, ++packId, *notes)))
                    expected[deckId] = OfflineQueue.State()
                }
                1 -> {
                    store.remove(deckId)
                    expected.remove(deckId)
                }
                else -> {
                    val q = store.open(deckId) ?: return@repeat
                    repeat(rnd.nextInt(1, 5)) {
                        val next = q.next(rnd.nextLong(0, 10_000_000))
                        if (next is OfflineQueue.Next.Show) q.answer(next.card.key, rnd.nextInt(0, 5), rnd.nextLong(0, 10_000_000))
                    }
                    store.save(q)
                    expected[deckId] = q.state
                }
            }
            // A fresh store sees exactly what was saved: as after the watch restarting.
            val reopened = OfflineStore(dir)
            assertEquals("step $step", expected.keys, reopened.summaries().map { it.deckId }.toSet())
            for ((id, state) in expected) assertEquals("step $step deck $id", state, reopened.open(id)!!.state)
        }
    }
}
