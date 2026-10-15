package com.ankiwear.wear

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.CardData
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Undo, on the watch's real Data Layer: the latest answer stays on the watch while it can be
 * undone, goes out when the next one comes, the hold runs out or the review is left, and an
 * undone answer never reaches the phone. Each answer goes out exactly once.
 */
@RunWith(AndroidJUnit4::class)
class HeldAnswerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = Wearable.getDataClient(context)

    private fun uri(path: String): Uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    /** (note, offline flag) of every answer queued for the phone. */
    private suspend fun sent(): List<Pair<Long, Boolean>> {
        val buffer = data.getDataItems(uri(Wire.PATH_ANSWER_PREFIX), DataClient.FILTER_PREFIX).await()
        try {
            return buffer.map {
                val map = DataMapItem.fromDataItem(it).dataMap
                map.getLong(Wire.KEY_NOTE_ID) to map.getBoolean(Wire.KEY_OFFLINE)
            }
        } finally {
            buffer.release()
        }
    }

    private fun answer(note: Long, offline: Boolean = false) = DataLayerClient.HeldAnswer(
        note, 0, 3, 1_000, 1L, offline, if (offline) null else CardData(note, 0, "", "", 4)
    )

    @Before
    fun setUp() {
        runBlocking {
            val available = withTimeoutOrNull(20_000) {
                try {
                    data.dataItems.await().release()
                    true
                } catch (e: Exception) {
                    Log.i(TAG, "held-answer test skipped, no Data Layer here: ${e.message}")
                    false
                }
            } ?: false
            assumeTrue("The Wear OS Data Layer isn't available on this device", available)
            data.deleteDataItems(uri(Wire.PATH_ANSWER_PREFIX), DataClient.FILTER_PREFIX).await()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                data.deleteDataItems(uri(Wire.PATH_ANSWER_PREFIX), DataClient.FILTER_PREFIX).await()
            } catch (e: Exception) {
                Log.w(TAG, "Clean-up failed: ${e.message}")
            }
        }
    }

    @Test
    fun theLatestAnswerWaitsAndCanBeUndone() {
        runBlocking {
            val client = DataLayerClient(context)
            assertFalse(client.holdAnswer(answer(1)))
            assertEquals(emptyList<Pair<Long, Boolean>>(), sent())
            assertEquals(1L, client.heldAnswer.value?.noteId)

            // The next answer sends the first, as a live one (its reply brings cards).
            assertTrue(client.holdAnswer(answer(2)))
            assertEquals(listOf(1L to false), sent())

            // Undo takes the second back: it never goes out.
            val back = client.undoHeldAnswer()
            assertEquals(2L, back?.noteId)
            assertEquals(2L, back?.card?.noteId)
            assertNull(client.heldAnswer.value)
            assertNull(client.undoHeldAnswer()) // nothing left to undo
            client.flushHeldAnswer()
            assertEquals(listOf(1L to false), sent())

            // Leaving the review sends what is held, asking for no cards back.
            assertFalse(client.holdAnswer(answer(3)))
            client.flushHeldAnswer()
            assertEquals(setOf(1L to false, 3L to true), sent().toSet())
            assertNull(client.undoHeldAnswer())

            // Offline answers never ask for cards.
            client.holdAnswer(answer(4, offline = true))
            assertFalse(client.holdAnswer(answer(5, offline = true)))
            client.flushHeldAnswer()
            assertEquals(setOf(1L to false, 3L to true, 4L to true, 5L to true), sent().toSet())
        }
    }

    @Test
    fun theHoldRunsOutOnItsOwn() {
        runBlocking {
            val client = DataLayerClient(context, holdMs = 300)
            client.holdAnswer(answer(7))
            assertEquals(emptyList<Pair<Long, Boolean>>(), sent())
            var waited = 0
            while (sent().isEmpty() && waited < 10_000) {
                delay(100)
                waited += 100
            }
            assertEquals(listOf(7L to true), sent())
            assertNull(client.heldAnswer.value)
            // Too late to undo.
            assertNull(client.undoHeldAnswer())
            // An answer undone before its time never goes out, timer or not.
            client.holdAnswer(answer(8))
            assertEquals(8L, client.undoHeldAnswer()?.noteId)
            delay(1_000)
            assertEquals(listOf(7L to true), sent())
        }
    }

    /**
     * 400 random answers, undos, flushes and hold expiries, some at the same moment. What
     * reaches the phone must be exactly the answers not undone, each once.
     */
    @Test
    fun stressRandomAnswersAndUndos() {
        runBlocking {
            val rnd = Random(404)
            val client = DataLayerClient(context, holdMs = 40)
            val expected = HashSet<Long>()
            var held: Long? = null
            var note = 100L
            repeat(400) { step ->
                when (rnd.nextInt(10)) {
                    in 0..5 -> {
                        val n = ++note
                        client.holdAnswer(answer(n, offline = rnd.nextBoolean()))
                        held?.let { expected += it }
                        held = n
                    }
                    6, 7 -> {
                        val back = client.undoHeldAnswer()
                        if (back != null) {
                            assertEquals("step $step", held, back.noteId)
                        } else if (held != null) {
                            expected += held!! // the hold had run out: it went
                        }
                        held = null
                    }
                    8 -> {
                        client.flushHeldAnswer()
                        held?.let { expected += it }
                        held = null
                    }
                    else -> {
                        // Let the hold run out (or not quite).
                        delay(rnd.nextLong(0, 80))
                        if (client.heldAnswer.value == null) {
                            held?.let { expected += it }
                            held = null
                        }
                    }
                }
            }
            client.flushHeldAnswer()
            held?.let { expected += it }
            val notes = sent().map { it.first }
            assertEquals("each answer once", notes.size, notes.toSet().size)
            assertEquals(expected, notes.toSet())
            Log.i(TAG, "held answers: ${expected.size} sent of $note given")
        }
    }

    private companion object {
        // The tag CI keeps from logcat.
        const val TAG = "AnkiWatchTest"
    }
}
