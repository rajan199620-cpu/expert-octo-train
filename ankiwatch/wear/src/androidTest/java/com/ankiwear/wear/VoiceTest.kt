package com.ankiwear.wear

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.DeckMatcher
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.OfflinePackCodec
import com.ankiwatch.core.PackCard
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.DeckInfo
import com.ankiwear.wear.offline.Download
import com.ankiwear.wear.offline.OfflineStore
import com.ankiwear.wear.screens.OfflineScreen
import com.ankiwear.wear.screens.OfflineTags
import com.ankiwear.wear.screens.VoiceChooseScreen
import com.ankiwear.wear.screens.VoiceNothingScreen
import com.ankiwear.wear.screens.VoiceOutcome
import com.ankiwear.wear.screens.VoiceTags
import com.ankiwear.wear.screens.parentsOf
import com.ankiwear.wear.theme.AnkiWearTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Downloading by saying a deck's name: what is downloaded straight away, what is asked
 * about, and that several decks asked for at once arrive one after another, all of them.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTest {

    @get:Rule(order = 0)
    val timeout: Timeout = Timeout.builder().withTimeout(180, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val names = listOf(
        "UPSC", "UPSC::Prelims", "UPSC::Prelims::Polity", "UPSC::Prelims::Polity::DPSP",
        "UPSC::Prelims::Polity::Fundamental Rights", "UPSC::Mains", "UPSC::Mains::GS2",
        "UPSC::Mains::GS2::Polity", "UPSC::Mains::GS2::Polity::DPSP", "UPSC::Mains::GS4", "Relations"
    )
    private val decks = names.mapIndexed { i, n -> DeckInfo(100L + i, n, 1, 0, 2) }

    private fun outcome(vararg heard: String) = VoiceOutcome.of(DeckMatcher.match(heard.toList(), names), true)

    @Test
    fun whatIsSaidIsWhatIsDownloaded() {
        assertEquals(listOf("UPSC::Prelims::Polity::DPSP"), outcome("prelims DPSP").download)
        assertEquals(listOf("UPSC::Mains::GS2::Polity::DPSP"), outcome("download mains DPSP").download)
        assertEquals(
            setOf("UPSC::Prelims::Polity::DPSP", "UPSC::Mains::GS2::Polity::DPSP"),
            outcome("download both DPSP from prelims and mains").download.toSet()
        )
        // Not sure which: nothing is downloaded until the user picks.
        val polity = outcome("polity")
        assertEquals(emptyList<String>(), polity.download)
        assertTrue(polity.result is DeckMatcher.Result.Choose)
        assertTrue(outcome("banana").result is DeckMatcher.Result.None)
        assertEquals("UPSC › Prelims › Polity", parentsOf("UPSC::Prelims::Polity::DPSP"))
        assertEquals(null, parentsOf("Relations"))
    }

    @Test
    fun aChoiceIsTickedAndDownloadsWhatIsTicked() {
        val r = DeckMatcher.match(listOf("gs4 and polity"), names) as DeckMatcher.Result.Choose
        assertEquals(setOf("UPSC::Mains::GS4"), r.chosen)
        val downloads = mutableListOf<List<String>>()
        var again = 0
        rule.setContent {
            AnkiWearTheme {
                VoiceChooseScreen(r.heard, r.names, r.chosen, onDownload = { downloads += it }, onSayAgain = { again++ })
            }
        }
        rule.onNodeWithTag(VoiceTags.HEARD, useUnmergedTree = true).assertTextContains("gs4 and polity", substring = true)
        scrollTo(VoiceTags.choice("UPSC::Mains::GS4"))
        rule.onNodeWithTag(VoiceTags.choice("UPSC::Mains::GS4")).assertIsOn()
        scrollTo(VoiceTags.choice("UPSC::Prelims::Polity"))
        rule.onNodeWithTag(VoiceTags.choice("UPSC::Prelims::Polity")).assertIsOff().performClick()
        rule.onNodeWithTag(VoiceTags.choice("UPSC::Prelims::Polity")).assertIsOn()
        scrollTo(VoiceTags.DOWNLOAD)
        rule.onNodeWithTag(VoiceTags.DOWNLOAD).assertTextContains("Download 2 decks", substring = true).performClick()
        // In the order offered.
        assertEquals(listOf(r.names.filter { it == "UPSC::Mains::GS4" || it == "UPSC::Prelims::Polity" }), downloads)

        // Nothing ticked: nothing to download.
        for (n in listOf("UPSC::Mains::GS4", "UPSC::Prelims::Polity")) {
            scrollTo(VoiceTags.choice(n))
            rule.onNodeWithTag(VoiceTags.choice(n)).performClick()
        }
        scrollTo(VoiceTags.DOWNLOAD)
        rule.onNodeWithTag(VoiceTags.DOWNLOAD).assertIsNotEnabled()
        scrollTo(VoiceTags.AGAIN)
        rule.onNodeWithTag(VoiceTags.AGAIN).performClick()
        assertEquals(1, again)
        assertEquals(1, downloads.size)
    }

    @Test
    fun nothingFoundSaysWhy() {
        val case = mutableStateOf<Pair<String?, Boolean>>("banana" to true)
        var picked = 0
        rule.setContent {
            AnkiWearTheme {
                VoiceNothingScreen(case.value.first, case.value.second, onSayAgain = {}, onPick = { picked++ })
            }
        }
        for ((c, text) in listOf(
            ("banana" to true) to "No deck is called “banana”.",
            ("" to true) to "Nothing was heard.",
            ("gs4" to false) to "Your decks haven't come from the phone yet.",
            (null to true) to "This watch has no speech input."
        )) {
            rule.runOnIdle { case.value = c }
            rule.onNodeWithTag(VoiceTags.HEARD, useUnmergedTree = true).assertTextContains(text, substring = true)
        }
        scrollTo(VoiceTags.PICK)
        rule.onNodeWithTag(VoiceTags.PICK).performClick()
        assertEquals(1, picked)
    }

    @Test
    fun theOfflineScreenOffersSayingADeckWhenItCanDownload() {
        val phone = mutableStateOf(true)
        val pending = mutableStateOf(0)
        val download = mutableStateOf<Download>(Download.Idle)
        var said = 0
        rule.setContent {
            AnkiWearTheme {
                OfflineScreen(
                    packs = emptyList(), pendingGrades = pending.value, download = download.value,
                    phoneReachable = phone.value, now = 0L, onReview = {}, onDownload = {}, onRemove = {},
                    onDismissDownload = {}, onVoice = { said++ }
                )
            }
        }
        scrollOffline(OfflineTags.VOICE)
        rule.onNodeWithTag(OfflineTags.VOICE).assertIsEnabled().performClick()
        assertEquals(1, said)
        rule.runOnIdle { phone.value = false }
        rule.onNodeWithTag(OfflineTags.VOICE).assertIsNotEnabled()
        rule.runOnIdle {
            phone.value = true
            pending.value = 2
        }
        rule.onNodeWithTag(OfflineTags.VOICE).assertIsNotEnabled()
        rule.runOnIdle {
            pending.value = 0
            download.value = Download.Failed("boom")
        }
        rule.onNodeWithTag(OfflineTags.VOICE).assertIsEnabled()
    }

    private fun scrollTo(tag: String) {
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(VoiceTags.LIST)), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    private fun scrollOffline(tag: String) {
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(OfflineTags.LIST)), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    // The download queue, on the watch's real Data Layer with a stand-in for the phone.

    private val data = Wearable.getDataClient(context)
    private val packPath: Uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(Wire.PATH_OFFLINE_PACK).build()
    private val dir = File(context.cacheDir, "voice-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        runBlocking {
            try {
                data.deleteDataItems(packPath).await()
            } catch (e: Exception) {
                // No Data Layer here: nothing was written.
            }
        }
    }

    private fun dataLayerHere(): Boolean = runBlocking {
        try {
            data.dataItems.await().release()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** What the phone's DataLayerManager.sendOfflinePack writes for [deck]. */
    private suspend fun sendPack(deck: DeckInfo, packId: Long) {
        val cards = (1..3).map { n ->
            PackCard(deck.id * 10 + n, 0, 4, listOf("1m", "6m", "10m", "4d"), "", "", "x {{c1::$n}}", 1)
        }
        val bytes = OfflinePackCodec.encode(OfflinePack(packId, deck.id, deck.name, System.currentTimeMillis(), cards))
        val request = PutDataMapRequest.create(Wire.PATH_OFFLINE_PACK).apply {
            dataMap.putAsset(Wire.KEY_PACK, Asset.createFromBytes(bytes))
            dataMap.putLong(Wire.KEY_PACK_ID, packId)
            dataMap.putLong(Wire.KEY_DECK_ID, deck.id)
            dataMap.putString(Wire.KEY_DECK_NAME, deck.name)
            dataMap.putInt(Wire.KEY_CARD_COUNT, cards.size)
            dataMap.putLong(Wire.KEY_TIMESTAMP, System.currentTimeMillis())
        }
        request.setUrgent()
        data.putDataItem(request.asPutDataRequest()).await()
    }

    private suspend fun waitUntil(what: String, ok: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (!ok()) {
            assertTrue("timed out waiting for $what", SystemClock.uptimeMillis() < until)
            delay(50)
        }
    }

    @Test
    fun severalDecksDownloadOneAfterAnother() {
        assumeTrue("The Wear OS Data Layer isn't available on this device", dataLayerHere())
        runBlocking {
            data.deleteDataItems(packPath).await()
            val asked = Collections.synchronizedList(mutableListOf<Long>())
            val store = OfflineStore(dir)
            val client = DataLayerClient(context, store, sendToPhone = { path, bytes ->
                if (path == Wire.PATH_REQUEST_OFFLINE) asked += DataMap.fromByteArray(bytes).getLong(Wire.KEY_DECK_ID)
                true
            })
            client.register()
            try {
                val wanted = outcome("prelims DPSP and mains DPSP and relations").download.map { n -> decks.single { it.name == n } }
                assertEquals(3, wanted.size)
                client.requestOfflinePacks(wanted + wanted[0]) // the same deck twice is one download
                // Only the first is asked for until it arrives.
                waitUntil("the first request") { asked.size == 1 }
                assertEquals(wanted[0].id, asked[0])
                assertEquals(Download.Waiting(wanted[0].name, (client.download.value as Download.Waiting).since, 2), client.download.value)
                delay(300)
                assertEquals(1, asked.size)
                for ((i, deck) in wanted.withIndex()) {
                    sendPack(deck, packId = 500L + i)
                    if (i < wanted.lastIndex) {
                        waitUntil("the request for ${wanted[i + 1].name}") { asked.size == i + 2 }
                        assertEquals(wanted[i + 1].id, asked[i + 1])
                    }
                }
                waitUntil("the last download") { client.download.value is Download.Ready }
                assertEquals(wanted[2].name, (client.download.value as Download.Ready).summary.deckName)
                assertEquals(wanted.map { it.id }, asked.toList())
                assertEquals(wanted.map { it.id }.toSet(), store.summaries().map { it.deckId }.toSet())
                store.summaries().forEach { assertEquals(3, it.total) }
            } finally {
                client.unregister()
            }
        }
    }

    @Test
    fun aFailedDownloadStopsTheRestAndSaysHowMany() {
        runBlocking {
            val asked = Collections.synchronizedList(mutableListOf<Long>())
            val client = DataLayerClient(context, OfflineStore(dir), sendToPhone = { _, bytes ->
                asked += DataMap.fromByteArray(bytes).getLong(Wire.KEY_DECK_ID)
                false // the phone isn't there
            })
            client.requestOfflinePacks(decks.take(3))
            val failed = client.download.value
            assertTrue("$failed", failed is Download.Failed && failed.message.endsWith("(2 more not downloaded)"))
            assertEquals(listOf(decks[0].id), asked.toList())
            // Stop waiting: the queue is gone, so nothing else is asked for.
            client.requestOfflinePacks(decks.take(2))
            client.clearDownload()
            assertEquals(Download.Idle, client.download.value)
            assertEquals(2, asked.size)
        }
    }
}
