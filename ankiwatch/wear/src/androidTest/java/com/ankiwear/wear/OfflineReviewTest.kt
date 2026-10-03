package com.ankiwear.wear

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.OfflineQueue
import com.ankiwatch.core.PackCard
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.offline.Download
import com.ankiwear.wear.offline.OfflineStore
import com.ankiwear.wear.review.Press
import com.ankiwear.wear.review.SideButtons
import com.ankiwear.wear.screens.OfflineReviewScreen
import com.ankiwear.wear.screens.OfflineScreen
import com.ankiwear.wear.screens.OfflineTags
import com.ankiwear.wear.screens.ReviewTags
import com.ankiwear.wear.theme.AnkiWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Reviewing a downloaded deck on the Wear OS emulator with no phone at all: the order cards
 * come in, learning cards coming back after their delay (on a fake clock), waiting, the side
 * button, Bury, picking up after a restart, and a long random session.
 */
@RunWith(AndroidJUnit4::class)
class OfflineReviewTest {

    @get:Rule
    val rule = createComposeRule()

    /** (note, ord, ease) of every answer the screen hands on, in order. */
    private val answers = mutableListOf<Triple<Long, Int, Int>>()
    private var exits = 0

    /** Fake wall clock (epoch millis), read by the screen. */
    @Volatile
    private var clock = 1_700_000_000_000L
    private lateinit var queueState: MutableState<OfflineQueue>

    private fun CardData.toPackCard() = PackCard(
        noteId, cardOrd, buttonCount, nextReviewTimes, question, answer,
        cloze?.content.orEmpty(), cloze?.clozeNumber ?: 0, cloze?.extras.orEmpty(), cloze?.modelName.orEmpty()
    )

    /** The demo law note's three cards (c3, c5, c1); labels: Again <1m, Hard 6m, Good 1d, Easy 4d. */
    private val lawCards = DemoContent.cards(1L).map { it.toPackCard() }
    private fun lawPack() = OfflinePack(1, 1, "law::example act", 0, lawCards)

    private fun show(pack: OfflinePack, state: OfflineQueue.State = OfflineQueue.State()) {
        rule.setContent {
            val q = remember { mutableStateOf(OfflineQueue(pack, state)) }
            queueState = q
            AnkiWearTheme {
                OfflineReviewScreen(
                    queue = q.value,
                    clock = { clock },
                    tickMs = 50,
                    onAnswered = { card, ease, _ -> answers += Triple(card.noteId, card.cardOrd, ease) },
                    onExit = { exits++ }
                )
            }
        }
        rule.waitForIdle()
    }

    private fun header() = rule.onNodeWithTag(ReviewTags.HEADER)

    private fun reveal() {
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
    }

    private fun grade(ease: Int) {
        rule.onNodeWithTag(ReviewTags.ease(ease)).performClick()
        rule.waitForIdle()
    }

    private fun key(card: PackCard) = Triple(card.noteId, card.cardOrd, 0)

    @Test
    fun aDeckIsReviewedWithoutThePhone() {
        show(lawPack())
        val (c3, c5, c1) = lawCards
        header().assertTextEquals("Offline · 3 left")
        rule.assertOnScreen("Offline · 3 left")
        reveal()
        rule.onNodeWithText("1d").assertExists() // AnkiDroid's label on Good
        grade(3) // Good: a day, done for now
        header().assertTextEquals("Offline · 2 left")
        reveal()
        grade(1) // Again: back in under a minute
        header().assertTextEquals("Offline · 2 left") // c1 fresh, c5 learning
        reveal()
        grade(3)
        // Only c5 is left, due within the learn-ahead limit: it comes back straight away,
        // without AnkiDroid's labels (they were for its first answer).
        header().assertTextEquals("Offline · 1 left")
        reveal()
        rule.onAllNodesWithText("<1m").assertCountEquals(0)
        rule.scrollContentTo(hasText("hold: Hard · Easy"))
        rule.assertOnScreen("hold: Hard · Easy")
        grade(3)
        rule.onNodeWithTag(OfflineTags.DONE).assertExists()
        rule.screenshot("offline-done")
        assertEquals(
            listOf(Triple(c3.noteId, c3.cardOrd, 3), Triple(c5.noteId, c5.cardOrd, 1), Triple(c1.noteId, c1.cardOrd, 3), Triple(c5.noteId, c5.cardOrd, 3)),
            answers
        )
    }

    @Test
    fun learningCardsWaitAndCanBeShownEarly() {
        val learning = lawCards[0].copy(nextReviewTimes = listOf("1m", "30m", "1h", "4d"))
        show(OfflinePack(2, 1, "law", 0, listOf(learning)))
        reveal()
        grade(3) // Good: an hour
        rule.onNodeWithTag(OfflineTags.WAIT).assertExists()
        rule.onNodeWithText("Next card in 1 h").assertExists()
        rule.screenshot("offline-wait")
        rule.onNodeWithTag(OfflineTags.SHOW_NOW).performClick()
        rule.waitForIdle()
        header().assertTextEquals("Offline · 1 left")
        reveal()
        grade(3) // Good from the second step: graduated
        rule.onNodeWithTag(OfflineTags.DONE).assertExists()

        // Waiting ends on its own once the card is within the learn-ahead limit.
        rule.runOnIdle { queueState.value = OfflineQueue(OfflinePack(3, 1, "law", 0, listOf(learning))) }
        rule.waitForIdle()
        reveal()
        grade(3)
        rule.onNodeWithTag(OfflineTags.WAIT).assertExists()
        clock += 39 * 60_000L
        SystemClock.sleep(300)
        rule.onNodeWithTag(OfflineTags.WAIT).assertExists() // 60 min away, 39 gone: still 21
        clock += 2 * 60_000L
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ReviewTags.HEADER).fetchSemanticsNodes().isNotEmpty() }
        header().assertTextEquals("Offline · 1 left")
    }

    @Test
    fun theSideButtonWorksOffline() {
        show(lawPack())
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) } // reveal
        rule.waitForIdle()
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) } // Good
        rule.waitForIdle()
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) }
        rule.waitForIdle()
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.LONG) } // Again
        rule.waitForIdle()
        assertEquals(listOf(3, 1), answers.map { it.third })
        assertEquals(listOf(lawCards[0].cardOrd, lawCards[1].cardOrd), answers.map { it.second })
    }

    @Test
    fun buryWorksOffline() {
        show(lawPack())
        rule.scrollContentTo(hasTestTag(ReviewTags.BURY))
        rule.onNodeWithTag(ReviewTags.BURY).performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple(lawCards[0].noteId, lawCards[0].cardOrd, Wire.EASE_BURY)), answers)
        header().assertTextEquals("Offline · 2 left")
    }

    @Test
    fun aRestartPicksUpWhereTheReviewStopped() {
        val pack = lawPack()
        show(pack)
        reveal(); grade(3)
        reveal(); grade(1)
        val stored = OfflineQueue.encodeState(pack.id, queueState.value.state)
        // The app restarts: a new queue from what was stored.
        rule.runOnIdle { queueState.value = OfflineQueue(pack, OfflineQueue.decodeState(pack.id, stored)!!) }
        rule.waitForIdle()
        header().assertTextEquals("Offline · 2 left")
        reveal(); grade(3)
        reveal(); grade(3) // c5, back from learning
        rule.onNodeWithTag(OfflineTags.DONE).assertExists()
        assertEquals(4, answers.size)
    }

    /**
     * 100 random cloze cards with AnkiDroid-style labels, answered at random (buttons, holds,
     * the side button, Bury) while the clock jumps ahead; waiting screens are skipped with
     * "Show it now" or by waiting. Every card must get answered and the session must end.
     */
    @Test
    fun stressALongRandomOfflineSession() {
        val rnd = Random(77)
        val labels = listOf(listOf("1m", "6m", "10m", "4d"), listOf("10m", "2d", "5d", "9d"), listOf("1m", "10m", "1d", "4d"), listOf("<1m", "<10m", "1h", "2h"))
        val cards = (0 until 100).map { i ->
            val note = 10_000L + i / 3
            val ord = i % 3
            val content = "Note $note: " + (1..3).joinToString(" ") { c -> "part {{c$c::secret$note-$c}}" }
            PackCard(note, ord, 4, labels[rnd.nextInt(labels.size)], "", "", content, ord + 1, listOf("Note" to "n$note"), "Cloze")
        }
        val pack = OfflinePack(9, 1, "stress", 0, cards)
        show(pack)
        var slowest = 0L
        var steps = 0
        while (true) {
            assertTrue("session didn't end after $steps steps", steps++ < 1_500)
            when {
                rule.onAllNodesWithTag(OfflineTags.DONE).fetchSemanticsNodes().isNotEmpty() -> break
                rule.onAllNodesWithTag(OfflineTags.WAIT).fetchSemanticsNodes().isNotEmpty() -> {
                    if (rnd.nextBoolean()) {
                        rule.onNodeWithTag(OfflineTags.SHOW_NOW).performClick()
                    } else {
                        clock += 3 * 3_600_000L // past the longest learning step (2 h)
                        rule.waitUntil(5_000) { rule.onAllNodesWithTag(OfflineTags.WAIT).fetchSemanticsNodes().isEmpty() }
                    }
                    rule.waitForIdle()
                }
                else -> {
                    val before = answers.size
                    val start = SystemClock.uptimeMillis()
                    when (rnd.nextInt(10)) {
                        0 -> {
                            rule.scrollContentTo(hasTestTag(ReviewTags.BURY))
                            rule.onNodeWithTag(ReviewTags.BURY).performClick()
                        }
                        1 -> {
                            rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) }
                            rule.waitForIdle()
                            rule.runOnIdle { SideButtons.handler!!.invoke(if (rnd.nextBoolean()) Press.SHORT else Press.LONG) }
                        }
                        2 -> {
                            reveal()
                            rule.onNodeWithTag(ReviewTags.ease(if (rnd.nextBoolean()) 1 else 3)).performTouchInput { longClick() }
                        }
                        else -> {
                            reveal()
                            // Mostly Good, so learning cards graduate and the session ends.
                            rule.onNodeWithTag(ReviewTags.ease(if (rnd.nextInt(4) == 0) 1 else 3)).performClick()
                        }
                    }
                    rule.waitForIdle()
                    slowest = maxOf(slowest, SystemClock.uptimeMillis() - start)
                    assertEquals("step $steps: one answer per card shown", before + 1, answers.size)
                    if (rnd.nextInt(5) == 0) clock += rnd.nextLong(0, 15 * 60_000L)
                }
            }
        }
        val answered = answers.map { Triple(it.first, it.second, 0) }.toSet()
        assertEquals("every card answered", cards.map { key(it) }.toSet(), answered)
        assertTrue(answers.all { Wire.isAnswerEase(it.third) })
        Log.i("AnkiWatchTest", "offline stress: ${cards.size} cards, ${answers.size} answers, slowest step ${slowest}ms")
        assertTrue("slowest step took ${slowest}ms", slowest < 5_000)
    }

    // ── The Offline screen ───────────────────────────────────────────────────────────────

    private class ScreenState(
        packs: List<OfflineStore.Summary>,
        pending: Int,
        download: Download,
        phone: Boolean
    ) {
        val packs = mutableStateOf(packs)
        val pending = mutableStateOf(pending)
        val download = mutableStateOf(download)
        val phone = mutableStateOf(phone)
    }

    /** Scrolls the Offline screen's list until the node tagged [tag] is laid out. */
    private fun scrollOfflineTo(tag: String) {
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(OfflineTags.LIST)), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    @Test
    fun theOfflineScreenShowsDownloadsAndWhatTheyWaitFor() {
        val now = 1_700_000_000_000L
        val law = OfflineStore.Summary(1, "law::example act", 10, now - 2 * 3_600_000, 12, 7, 5)
        val state = ScreenState(listOf(law), pending = 3, download = Download.Idle, phone = true)
        val reviewed = mutableListOf<Long>()
        val removed = mutableListOf<Long>()
        var downloads = 0
        var dismissed = 0
        rule.setContent {
            AnkiWearTheme {
                OfflineScreen(
                    packs = state.packs.value,
                    pendingGrades = state.pending.value,
                    download = state.download.value,
                    phoneReachable = state.phone.value,
                    now = now,
                    onReview = { reviewed += it.deckId },
                    onDownload = { downloads++ },
                    onRemove = { removed += it.deckId },
                    onDismissDownload = { dismissed++ }
                )
            }
        }
        rule.onNodeWithTag(OfflineTags.SYNC).assertTextEquals("3 grades waiting for your phone")
        scrollOfflineTo(OfflineTags.pack(1))
        rule.onNodeWithText("example act").assertExists()
        rule.onNodeWithText("7 left · 2 h ago").assertExists()
        rule.screenshot("offline-screen")
        // A new download waits until the phone has the grades already given.
        scrollOfflineTo(OfflineTags.DOWNLOAD)
        rule.onNodeWithTag(OfflineTags.DOWNLOAD).assertIsNotEnabled()
        rule.onNodeWithText("Once your grades reach the phone").assertExists()

        rule.runOnIdle { state.pending.value = 0; state.phone.value = false }
        scrollOfflineTo(OfflineTags.SYNC)
        rule.onNodeWithTag(OfflineTags.SYNC).assertTextEquals("All grades are in AnkiDroid")
        scrollOfflineTo(OfflineTags.DOWNLOAD)
        rule.onNodeWithTag(OfflineTags.DOWNLOAD).assertIsNotEnabled()
        rule.onNodeWithText("Needs your phone nearby").assertExists()

        rule.runOnIdle { state.phone.value = true }
        rule.onNodeWithTag(OfflineTags.DOWNLOAD).assertIsEnabled().performClick()
        assertEquals(1, downloads)

        rule.runOnIdle { state.download.value = Download.Waiting("law::example act", now) }
        scrollOfflineTo(OfflineTags.DOWNLOAD_STATUS)
        rule.onNodeWithText("Getting example act…").assertExists()
        scrollOfflineTo(OfflineTags.DOWNLOAD)
        rule.onNodeWithTag(OfflineTags.DOWNLOAD).assertIsNotEnabled()
        rule.runOnIdle { state.download.value = Download.Waiting("law::example act", now - 61_000) }
        scrollOfflineTo(OfflineTags.DOWNLOAD_STATUS)
        rule.onNodeWithText("Stop waiting").performClick()
        assertEquals(1, dismissed)
        rule.runOnIdle { state.download.value = Download.Failed("The phone couldn't prepare the offline download: boom") }
        scrollOfflineTo(OfflineTags.DOWNLOAD_STATUS)
        rule.onNodeWithText("The phone couldn't prepare the offline download: boom").assertExists()
        rule.onNodeWithText("OK").performClick()
        assertEquals(2, dismissed)

        // Removing takes two taps.
        scrollOfflineTo(OfflineTags.remove(1))
        rule.onNodeWithTag(OfflineTags.remove(1)).performClick()
        assertTrue(removed.isEmpty())
        rule.onNodeWithText("Tap again to remove").assertExists()
        rule.onNodeWithTag(OfflineTags.remove(1)).performClick()
        assertEquals(listOf(1L), removed)

        rule.runOnIdle { state.packs.value = listOf(law) }
        scrollOfflineTo(OfflineTags.pack(1))
        rule.onNodeWithTag(OfflineTags.pack(1)).performClick()
        assertEquals(listOf(1L), reviewed)
    }
}
