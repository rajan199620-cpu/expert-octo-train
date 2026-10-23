package com.ankiwear.wear

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiwear.wear.model.DeckInfo
import com.ankiwear.wear.model.DeckTreeBuilder
import com.ankiwear.wear.screens.DeckListScreen
import com.ankiwear.wear.screens.DeckListTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Opening and closing branches of the deck list. It opens with every branch closed, and
 * what the user opened stays open through refreshes, a moment of loading, and the screen
 * being recreated (a trip into a review and back).
 */
@RunWith(AndroidJUnit4::class)
class DeckTreeTest {

    @get:Rule
    val rule = createComposeRule()

    private val decks = mutableStateOf(emptyList<DeckInfo>())
    private val loading = mutableStateOf(false)
    private val selected = mutableListOf<String>()

    private fun deck(name: String, due: Int = 1) = DeckInfo(name.hashCode().toLong() and 0xffffff, name, due, 0, due * 3)

    private val upsc = listOf(
        "UPSC", "UPSC::Anthro", "UPSC::Anthro::Paper 1", "UPSC::Mains",
        "UPSC::Mains::GS1", "UPSC::Mains::GS1::History", "UPSC::Mains::GS2", "UPSC::Mains::GS2::Polity",
        "UPSC::Mains::GS3", "UPSC::Mains::GS3::Economy", "UPSC::Mains::GS4", "UPSC::Mains::GS4::Ethics",
        "Relations",
        // No "Law" deck: the tree makes one up, and tapping it only opens it.
        "Law::BNS", "Law::BNSS"
    ).map { deck(it) }

    private fun show(list: List<DeckInfo>, restore: StateRestorationTester? = null) {
        decks.value = list
        val content: @Composable () -> Unit = {
            DeckListScreen(
                decks = decks.value,
                isLoading = loading.value,
                onDeckSelected = { selected += it.name },
                showRefresh = false
            )
        }
        if (restore != null) restore.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private val rowTag = SemanticsProperties.TestTag
    private val isRow = SemanticsMatcher("is a deck row") { it.config.getOrNull(rowTag)?.startsWith("deck-row:") == true }

    /** Every deck the list shows, read row by row from top to bottom. */
    private fun shown(): Set<String> {
        val out = LinkedHashSet<String>()
        val list = rule.onNode(hasScrollToIndexAction(), useUnmergedTree = true)
        // Item 0 is the title and rows 1..n follow, so after scrolling to the last row read
        // so far, rows below it are placed too unless it is the last one.
        var i = 0
        while (true) {
            list.performScrollToIndex(i)
            rule.waitForIdle()
            rule.placedNodes(isRow).forEach { out += it.config[rowTag].removePrefix("deck-row:") }
            if (out.size <= i) return out
            i = out.size
        }
    }

    /** What the list should show with [open] opened. */
    private fun expected(list: List<DeckInfo>, open: Set<String>) =
        DeckTreeBuilder.flattenVisible(DeckTreeBuilder.build(list), open).map { it.deck.name }.toSet()

    private fun toggle(name: String) {
        val index = 1 + expected(decks.value, openNow).indexOf(name)
        assertTrue("$name is not on the list", index > 0)
        rule.onNode(hasScrollToIndexAction(), useUnmergedTree = true).performScrollToIndex(index)
        rule.waitForIdle()
        rule.onNodeWithTag(DeckListTags.toggle(name), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        openNow = if (name in openNow) openNow - name else openNow + name
    }

    private var openNow = emptySet<String>()

    private fun check(what: String) = assertEquals(what, expected(decks.value, openNow), shown())

    @Test
    fun everyBranchStartsClosedAndOpensOnlyWhereTapped() {
        show(upsc)
        assertEquals(setOf("UPSC", "Law", "Relations"), shown())

        toggle("UPSC")
        assertEquals(setOf("UPSC", "UPSC::Anthro", "UPSC::Mains", "Law", "Relations"), shown())
        toggle("UPSC::Mains")
        check("Mains open, its papers closed")
        assertTrue((1..4).all { "UPSC::Mains::GS$it" in shown() })
        assertTrue(shown().none { it.startsWith("UPSC::Mains::GS1::") })

        // A made-up parent: tapping its row opens it and starts no review.
        val law = 1 + expected(decks.value, openNow).indexOf("Law")
        rule.onNode(hasScrollToIndexAction(), useUnmergedTree = true).performScrollToIndex(law)
        rule.waitForIdle()
        rule.onNodeWithTag(DeckListTags.row("Law"), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        openNow = openNow + "Law"
        check("Law opened by its row")
        assertEquals(emptyList<String>(), selected)

        // Closing UPSC hides Mains; opening it again finds Mains as it was left.
        toggle("UPSC")
        check("UPSC closed")
        toggle("UPSC")
        check("UPSC reopened")
        assertTrue("UPSC::Mains::GS3" in shown())
    }

    @Test
    fun openBranchesSurviveRefreshesLoadingAndRecreation() {
        val restore = StateRestorationTester(rule)
        show(upsc, restore)
        toggle("UPSC")
        toggle("UPSC::Mains")

        // A refresh: new counts, and new decks, which arrive closed.
        rule.runOnIdle {
            decks.value = upsc.map { it.copy(reviewCount = it.reviewCount + 7) } +
                deck("UPSC::Mains::GS5") + deck("UPSC::Mains::GS1::New topic") + deck("Optional::Psychology")
        }
        rule.waitForIdle()
        check("after a refresh with new decks")
        assertTrue("UPSC::Mains::GS5" in shown())
        assertTrue("Optional" in shown() && "Optional::Psychology" !in shown())

        // A moment with nothing to show while it loads. Its spinner never stops moving, so
        // the test clock is stepped by hand while it's up; waiting for idle would never end.
        val before = decks.value
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread {
                loading.value = true
                decks.value = emptyList()
            }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithText("Loading decks...").assertExists()
            rule.runOnUiThread {
                decks.value = before
                loading.value = false
            }
            rule.mainClock.advanceTimeByFrame()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        check("after loading")

        // A deck that briefly vanishes keeps its place open when it comes back.
        rule.runOnIdle { decks.value = before.filterNot { it.name.startsWith("UPSC") } }
        rule.waitForIdle()
        rule.runOnIdle { decks.value = before }
        rule.waitForIdle()
        check("after Mains came back")

        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        check("after the screen was recreated")
        assertTrue("UPSC::Mains::GS4" in shown())
    }

    @Test
    fun randomTapsAlwaysShowExactlyTheOpenedBranches() {
        show(upsc + (1..4).flatMap { p -> (1..3).map { t -> deck("UPSC::Mains::GS$p::Topic $t::Part ${'A' + t}") } })
        val rnd = Random(26)
        repeat(30) { step ->
            val candidates = DeckTreeBuilder.flattenVisible(DeckTreeBuilder.build(decks.value), openNow).filter { it.hasChildren }
            toggle(candidates[rnd.nextInt(candidates.size)].deck.name)
            check("step $step, open: $openNow")
        }
    }

    @Test
    fun aHugeCollectionOpensClosedAndQuickly() {
        val names = LinkedHashSet<String>()
        var i = 0
        while (names.size < 4_000) {
            val paper = "Mains::GS${1 + i % 4}"
            names += paper
            names += "$paper::ethics & human interface"
            names += "$paper::ethics & human interface::Values — topic $i"
            i++
        }
        val big = names.map { deck(it) } + upsc
        val start = SystemClock.uptimeMillis()
        show(big)
        val ms = SystemClock.uptimeMillis() - start
        assertEquals(setOf("Mains", "UPSC", "Law", "Relations"), shown())
        toggle("Mains")
        check("Mains open")
        assertEquals(setOf("GS1", "GS2", "GS3", "GS4"), shown().filter { it.startsWith("Mains::") }.map { it.removePrefix("Mains::") }.toSet())
        Log.i("AnkiWatchTest", "deck tree: ${big.size} decks opened closed in $ms ms")
        assertTrue("took $ms ms", ms < 5_000)
    }
}
