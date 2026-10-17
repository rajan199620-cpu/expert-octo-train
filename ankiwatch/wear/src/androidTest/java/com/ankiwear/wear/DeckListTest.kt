package com.ankiwear.wear

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.DeckListCodec
import com.ankiwatch.core.PayloadBudget
import com.ankiwatch.core.Wire
import com.ankiwatch.core.WireDeck
import com.ankiwear.wear.model.DeckInfo
import com.ankiwear.wear.model.DeckTreeBuilder
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deck list on the watch's real Data Layer, written as the phone writes it. A big
 * collection used to be cut to what fitted in one DataItem, so whole branches (Mains::GS2,
 * GS3, GS4) were missing from the watch.
 */
@RunWith(AndroidJUnit4::class)
class DeckListTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = Wearable.getDataClient(context)
    private val path: Uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(Wire.PATH_RESPONSE_DECKS).build()

    /** Several thousand decks under Mains::GS1..GS4, many levels deep. */
    private fun collection(count: Int, bump: Int = 0): List<WireDeck> {
        val names = LinkedHashSet<String>()
        var i = 0
        while (names.size < count) {
            val paper = "Mains::GS${1 + i % 4}"
            names += paper
            names += "$paper::ethics & human interface"
            names += "$paper::ethics & human interface::Values"
            names += "$paper::ethics & human interface::Values::Lessons leaders, reformers, administrators — topic $i"
            i++
        }
        return names.take(count).sorted().mapIndexed { n, name -> WireDeck(1_000L + n, name, n % 9 + bump, n % 4, n % 30) }
    }

    /** What the phone's DataLayerManager.sendDecks writes; [asset] null is a phone app from before the Asset. */
    private suspend fun write(decks: List<WireDeck>, time: Long, asset: ByteArray? = DeckListCodec.encode(decks)) {
        val keep = PayloadBudget.decksThatFit(decks.map { it.name }, decks.map { it.newCount + it.learnCount + it.reviewCount > 0 })
        val request = PutDataMapRequest.create(Wire.PATH_RESPONSE_DECKS).apply {
            dataMap.putDataMapArrayList(Wire.KEY_DECKS, ArrayList(keep.map { decks[it] }.map { d ->
                DataMap().apply {
                    putLong(Wire.KEY_DECK_ID, d.id)
                    putString(Wire.KEY_DECK_NAME, d.name)
                    putInt(Wire.KEY_NEW_COUNT, d.newCount)
                    putInt(Wire.KEY_LEARN_COUNT, d.learnCount)
                    putInt(Wire.KEY_REVIEW_COUNT, d.reviewCount)
                }
            }))
            if (asset != null) dataMap.putAsset(Wire.KEY_DECK_LIST, Asset.createFromBytes(asset))
            dataMap.putLong(Wire.KEY_TIMESTAMP, time)
        }
        request.setUrgent()
        data.putDataItem(request.asPutDataRequest()).await()
    }

    private fun DeckInfo.wire() = WireDeck(id, name, newCount, learnCount, reviewCount)

    private suspend fun waitFor(client: DataLayerClient, what: String, ok: (List<DeckInfo>) -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (!ok(client.decks.value)) {
            assertTrue("timed out waiting for $what; have ${client.decks.value.size} decks", SystemClock.uptimeMillis() < until)
            delay(50)
        }
    }

    @Before
    fun setUp() {
        runBlocking {
            val available = try {
                data.dataItems.await().release()
                true
            } catch (e: Exception) {
                Log.i(TAG, "deck list test skipped, no Data Layer here: ${e.message}")
                false
            }
            assumeTrue("The Wear OS Data Layer isn't available on this device", available)
            data.deleteDataItems(path).await()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                data.deleteDataItems(path).await()
            } catch (e: Exception) {
                Log.w(TAG, "Clean-up failed: ${e.message}")
            }
        }
    }

    @Test
    fun aHugeCollectionArrivesWholeWithEveryBranch() {
        runBlocking {
            val decks = collection(4_000)
            val inline = PayloadBudget.decksThatFit(decks.map { it.name }, decks.map { true }).size
            assertTrue("$inline fitted inline, so the old way lost decks", inline < decks.size)
            write(decks, time = 1_000)

            val client = DataLayerClient(context)
            val start = SystemClock.uptimeMillis()
            client.loadCachedData()
            val ms = SystemClock.uptimeMillis() - start
            assertEquals(decks, client.decks.value.map { it.wire() })

            // Every paper is in the tree under Mains, with its own subtree.
            val mains = DeckTreeBuilder.build(client.decks.value).single { it.displayName == "Mains" }
            assertEquals(listOf("GS1", "GS2", "GS3", "GS4"), mains.children.map { it.displayName })
            mains.children.forEach { assertTrue(it.displayName, it.children.isNotEmpty()) }
            Log.i(TAG, "deck list: ${decks.size} decks shown (old way: $inline) in $ms ms")
        }
    }

    @Test
    fun aNewListReplacesTheShownOneButAnOlderOneDoesNot() {
        runBlocking {
            val client = DataLayerClient(context)
            client.register()
            try {
                val first = collection(1_500)
                write(first, time = 2_000)
                waitFor(client, "the first list") { it.size == first.size }

                val second = collection(2_500, bump = 1)
                write(second, time = 3_000)
                waitFor(client, "the newer list") { it.map { d -> d.wire() } == second }

                // A list written earlier but read later (two in flight) must not win.
                write(collection(10), time = 2_500)
                delay(2_000)
                assertEquals(second, client.decks.value.map { it.wire() })
            } finally {
                client.unregister()
            }
        }
    }

    @Test
    fun withoutAReadableAssetTheInlineListIsShown() {
        runBlocking {
            val decks = collection(3_000)
            val inline = PayloadBudget.decksThatFit(decks.map { it.name }, decks.map { true }).map { decks[it] }

            // A phone app from before the Asset.
            write(decks, time = 4_000, asset = null)
            val old = DataLayerClient(context)
            old.loadCachedData()
            assertEquals(inline, old.decks.value.map { it.wire() })

            // A damaged Asset: still the inline list, not nothing.
            write(decks, time = 5_000, asset = byteArrayOf(1, 2, 3, 4))
            val damaged = DataLayerClient(context)
            damaged.loadCachedData()
            assertEquals(inline, damaged.decks.value.map { it.wire() })
        }
    }

    private companion object {
        // The tag CI keeps from logcat.
        const val TAG = "AnkiWatchTest"
    }
}
