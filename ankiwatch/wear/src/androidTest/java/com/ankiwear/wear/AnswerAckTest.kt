package com.ankiwear.wear

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.Wire
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Grades stop counting as "waiting for your phone" as soon as the phone acknowledges them,
 * on the watch's real Data Layer. The phone deletes the grades too, but Android can take
 * half an hour to tell the watch, so offline grades used to look stuck after they were in
 * AnkiDroid. Here the acks are written locally, as the phone writes them.
 */
@RunWith(AndroidJUnit4::class)
class AnswerAckTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = Wearable.getDataClient(context)

    private fun uri(path: String): Uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    private suspend fun stored(prefix: String): List<String> {
        val buffer = data.getDataItems(uri(prefix), DataClient.FILTER_PREFIX).await()
        try {
            return buffer.map { it.uri.path!! }
        } finally {
            buffer.release()
        }
    }

    private suspend fun queuedNames(): List<String> = stored(Wire.PATH_ANSWER_PREFIX).map { Wire.answerName(it)!! }

    /** What the phone's DataLayerManager.acknowledge writes. */
    private suspend fun ack(names: List<String>) {
        for (chunk in Wire.ackChunks(names)) {
            val request = PutDataMapRequest.create("${Wire.PATH_ANSWER_ACK_PREFIX}${UUID.randomUUID()}").apply {
                dataMap.putStringArray(Wire.KEY_ACKED, chunk.toTypedArray())
                dataMap.putLong(Wire.KEY_TIMESTAMP, System.currentTimeMillis())
            }
            request.setUrgent()
            data.putDataItem(request.asPutDataRequest()).await()
        }
    }

    private suspend fun clear() {
        data.deleteDataItems(uri(Wire.PATH_ANSWER_PREFIX), DataClient.FILTER_PREFIX).await()
        data.deleteDataItems(uri(Wire.PATH_ANSWER_ACK_PREFIX), DataClient.FILTER_PREFIX).await()
    }

    @Before
    fun setUp() {
        runBlocking {
            val available = try {
                data.dataItems.await().release()
                true
            } catch (e: Exception) {
                Log.i(TAG, "ack test skipped, no Data Layer here: ${e.message}")
                false
            }
            assumeTrue("The Wear OS Data Layer isn't available on this device", available)
            clear()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                clear()
            } catch (e: Exception) {
                Log.w(TAG, "Clean-up failed: ${e.message}")
            }
        }
    }

    @Test
    fun acknowledgedGradesStopWaitingAtOnce() {
        runBlocking {
            val client = DataLayerClient(context)
            repeat(5) { client.sendAnswer(100L + it, 0, 3, 2_000, 1L, offline = true) }
            assertEquals(5, client.pendingGrades.value)
            val queued = queuedNames()
            assertEquals(5, queued.size)

            // Three acknowledged, plus one name the watch never had: just those three go.
            ack(queued.take(3) + UUID.randomUUID().toString())
            client.refreshPendingGrades()
            assertEquals(2, client.pendingGrades.value)
            assertEquals(queued.drop(3).toSet(), queuedNames().toSet())
            assertEquals("acks are cleared once used", emptyList<String>(), stored(Wire.PATH_ANSWER_ACK_PREFIX))

            // Nothing new: the count stays.
            client.refreshPendingGrades()
            assertEquals(2, client.pendingGrades.value)

            // An answer given after the ack was written isn't touched by it.
            ack(queued.drop(3))
            client.sendAnswer(200L, 0, 1, 2_000, 1L, offline = true)
            assertEquals(1, client.pendingGrades.value)
            val last = queuedNames().single()
            assertTrue(last !in queued)

            // A repeated ack (the phone saw a grade twice) is harmless.
            ack(listOf(last))
            ack(listOf(last))
            client.refreshPendingGrades()
            assertEquals(0, client.pendingGrades.value)
            assertEquals(emptyList<String>(), stored(Wire.PATH_ANSWER_ACK_PREFIX))
        }
    }

    @Test
    fun aBigOfflineSessionIsClearedFromSplitAcks() {
        runBlocking {
            // More grades than fit in one ack, as after a long session without the phone.
            val total = Wire.ACK_CHUNK + 250
            val names = (1..total).map { UUID.randomUUID().toString() }
            for (name in names) {
                val request = PutDataMapRequest.create("${Wire.PATH_ANSWER_PREFIX}$name").apply {
                    dataMap.putString(Wire.KEY_ANSWER_UUID, name)
                    dataMap.putBoolean(Wire.KEY_OFFLINE, true)
                }
                data.putDataItem(request.asPutDataRequest()).await()
            }
            val client = DataLayerClient(context)
            client.refreshPendingGrades()
            assertEquals(total, client.pendingGrades.value)

            // All but the last ten acknowledged, in two acks.
            ack(names.dropLast(10))
            assertEquals(2, stored(Wire.PATH_ANSWER_ACK_PREFIX).size)
            val start = SystemClock.uptimeMillis()
            client.refreshPendingGrades()
            val ms = SystemClock.uptimeMillis() - start
            Log.i(TAG, "acks: cleared ${total - 10} acknowledged grades in $ms ms")
            assertEquals(10, client.pendingGrades.value)
            assertEquals(names.takeLast(10).toSet(), queuedNames().toSet())
            assertEquals(emptyList<String>(), stored(Wire.PATH_ANSWER_ACK_PREFIX))
            assertTrue("$ms ms", ms < 60_000)
        }
    }

    private companion object {
        // The tag CI keeps from logcat.
        const val TAG = "AnkiWatchTest"
    }
}
