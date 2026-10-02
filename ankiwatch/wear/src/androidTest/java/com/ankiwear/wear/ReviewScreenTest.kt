package com.ankiwear.wear

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwatch.core.CardRenderer
import com.ankiwatch.core.RenderOptions
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.ClozeCard
import com.ankiwear.wear.review.Press
import com.ankiwear.wear.review.SideButtons
import com.ankiwear.wear.screens.ReviewScreen
import com.ankiwear.wear.screens.ReviewTags
import com.ankiwear.wear.theme.AnkiWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The watch review screen on a Wear OS emulator: what is covered, what taps and the side
 * button do, and how it copes with nasty and huge cards.
 */
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val answers = mutableListOf<Int>()
    private lateinit var cardState: MutableState<CardData?>
    private lateinit var focusState: MutableState<Boolean>

    private val lawC3 get() = DemoContent.cards(1L)[0] // tests c3 ("second mode" + "condition")
    private val lawC5 get() = DemoContent.cards(1L)[1] // ord 4 → c5 (punishment)
    private val lawC1 get() = DemoContent.cards(1L)[2] // ord 0 → c1, the '#' anchor
    private val anthroC4 get() = DemoContent.cards(2L)[0]
    private val basic get() = DemoContent.cards(3L)[0]

    /** Every answer word in the demo law note; all are covered on any law question side. */
    private val lawSecrets = listOf("persuading", "agreeing", "pursuance", "intentionally", "seven years", "good faith")

    private fun show(card: CardData, focus: Boolean = true) {
        rule.setContent {
            val state = remember { mutableStateOf<CardData?>(card) }
            cardState = state
            val focusMode = remember { mutableStateOf(focus) }
            focusState = focusMode
            val current = state.value
            AnkiWearTheme {
                ReviewScreen(
                    card = current,
                    revisionKey = "${current?.noteId}:${current?.cardOrd}:${current?.hashCode()}",
                    focusMode = focusMode.value,
                    onFocusModeChange = { focusMode.value = it },
                    onAnswer = { _, _, ease, _ -> answers.add(ease) },
                    onFinished = {}
                )
            }
        }
        rule.waitForIdle()
    }

    private fun switchTo(card: CardData) {
        rule.runOnIdle { cardState.value = card }
        rule.waitForIdle()
    }

    /** Index of the first block holding the tested cloze on the given side, or -1. */
    private fun firstTestedBlock(card: CardData, answer: Boolean): Int {
        val cloze = card.cloze!!
        return CardRenderer.render(cloze.content, RenderOptions(activeOrd = cloze.clozeNumber, showAnswer = answer)).firstGenuineBlock()
    }

    private fun blockIndexContaining(card: CardData, text: String, answer: Boolean = false): Int {
        val cloze = card.cloze!!
        val rendered = CardRenderer.render(cloze.content, RenderOptions(activeOrd = cloze.clozeNumber, showAnswer = answer))
        return rendered.blocks.first { it.text.contains(text) }.index
    }

    // ── What is covered ──────────────────────────────────────────────────────────────────

    @Test
    fun questionSideNeverShowsCoveredAnswers() {
        show(lawC3, focus = false)
        for (card in listOf(lawC3, lawC5, lawC1)) {
            switchTo(card)
            // Walk the whole note: covered answers must be absent everywhere.
            val seen = StringBuilder(rule.allText())
            repeat(12) {
                rule.onNodeWithTag(ReviewTags.CONTENT).performTouchInput { swipeUp() }
                rule.waitForIdle()
                seen.append(rule.allText())
            }
            for (secret in lawSecrets) {
                assertFalse("card c${card.cloze!!.clozeNumber} showed '$secret':\n$seen", seen.contains(secret))
            }
        }
        // The '#' anchor is context on sibling cards …
        switchTo(lawC3)
        rule.scrollContentTo(hasText("A person commits the example offence", substring = true))
        rule.assertOnScreen("A person commits the example offence")
        // … but tested (covered) on its own card.
        switchTo(lawC1)
        assertFalse(rule.allText().contains("A person commits the example offence"))
        assertTrue(rule.allText().contains("[which act?]"))
    }

    @Test
    fun focusModeShowsTestedClozeWithItsContext() {
        show(lawC5, focus = true)
        val text = rule.allText()
        assertFalse("focus mode should hide the limbs:\n$text", text.contains("First limb"))
        // Heading, column title and the tested cloze all readable at once, without scrolling.
        rule.assertOnScreen("Act §999")
        rule.assertOnScreen("Punishment")
        rule.assertOnScreen("[punishment]")
        rule.screenshot("law-c5-focus-question")

        rule.scrollContentTo(hasTestTag(ReviewTags.FOCUS_TOGGLE))
        rule.onNodeWithTag(ReviewTags.FOCUS_TOGGLE).performClick()
        rule.waitForIdle()
        // The whole note opens on the tested cloze …
        rule.assertOnScreen("[punishment]")
        rule.screenshot("law-c5-whole-note")
        // … and the rest is a scroll away.
        rule.scrollContentTo(hasText("First limb", substring = true))
        rule.assertOnScreen("First limb")
    }

    /**
     * Whatever the note's length, each side opens with the tested cloze on screen: covered on
     * the question side, its answer on the answer side. Checked by geometry, not by text
     * merely existing in the composition.
     */
    @Test
    fun testedClozeIsInViewWhenEachSideOpens() {
        show(lawC3, focus = true)
        for (focus in listOf(true, false)) {
            rule.runOnIdle { focusState.value = focus }
            for (card in listOf(lawC3, lawC5, lawC1, anthroC4)) {
                val name = "c${card.cloze!!.clozeNumber} of note ${card.noteId}, focus=$focus"
                switchTo(card)
                rule.assertRowInView(ReviewTags.block(firstTestedBlock(card, answer = false)), "$name question")
                rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
                rule.waitForIdle()
                rule.assertRowInView(ReviewTags.block(firstTestedBlock(card, answer = true)), "$name answer")
            }
        }
    }

    @Test
    fun tappingASiblingClozePeeksAtIt() {
        show(lawC3, focus = false)
        val index = blockIndexContaining(lawC3, "[first mode]")
        rule.scrollContentTo(hasTestTag(ReviewTags.block(index)))
        rule.tapTextIn(ReviewTags.block(index), "[first mode]")
        assertTrue(rule.allText().contains("by persuading another person to do the act"))
        // Tap again: covered again.
        rule.tapTextIn(ReviewTags.block(index), "by persuading")
        assertFalse(rule.allText().contains("by persuading"))
    }

    @Test
    fun peekedTestedClozeStaysOpenOnTheAnswerSide() {
        show(lawC3, focus = true)
        val index = blockIndexContaining(lawC3, "[second mode]")
        rule.tapTextIn(ReviewTags.block(index), "[second mode]")
        assertTrue(rule.allText().contains("by agreeing with others"))
        assertTrue(rule.allText().contains("[condition]"))
        rule.screenshot("law-c3-peek")

        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        val text = rule.allText()
        assertFalse(text, text.contains("persuading"))
        rule.assertOnScreen("by agreeing with others")
        rule.assertOnScreen("an act in pursuance must follow")
    }

    @Test
    fun answerSideShowsNoteAndExtra() {
        show(lawC3, focus = true)
        rule.screenshot("law-c3-focus-question")
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        rule.screenshot("law-c3-focus-answer")
        rule.assertOnScreen("an act in pursuance must follow")
        rule.scrollContentTo(hasText("Definitional section", substring = true))
        rule.assertOnScreen("Definitional section")
        rule.scrollContentTo(hasText("Memory: P-A-H", substring = true))
        rule.assertOnScreen("Memory: P-A-H")
        // The hold grades' intervals close the answer side.
        rule.scrollContentTo(hasText("hold: Hard 6m · Easy 4d"))
        rule.assertOnScreen("hold: Hard 6m · Easy 4d")
        rule.screenshot("law-c3-extras")
    }

    @Test
    fun anthropologySkeletonKeepsParentItem() {
        show(anthroC4, focus = true)
        val text = rule.allText()
        assertTrue(text, text.contains("Theories of the origin of the institution"))
        assertFalse(text, text.contains("Racial theory"))
        assertFalse(text, text.contains("ignores ritual status"))
        rule.assertOnScreen("Occupational theory")
        rule.assertOnScreen("Critique: […]")
        rule.screenshot("anthro-c4-question")
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        rule.assertOnScreen("ignores ritual status")
        rule.screenshot("anthro-c4-answer")
    }

    // ── Grading ──────────────────────────────────────────────────────────────────────────

    @Test
    fun goodAndAgainButtonsAndHolds() {
        show(lawC3)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        // Each pill carries its own next interval.
        rule.onNode(hasText("<1m") and hasAnyAncestor(hasTestTag(ReviewTags.ease(1))), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1d") and hasAnyAncestor(hasTestTag(ReviewTags.ease(3))), useUnmergedTree = true).assertExists()
        rule.assertOnScreen("Again")
        rule.assertOnScreen("Good")
        rule.onNodeWithTag(ReviewTags.ease(3)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(3), answers)

        switchTo(lawC5)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        // Only the two big buttons exist; Hard and Easy are holds.
        rule.onAllNodesWithTag(ReviewTags.ease(2)).assertCountEquals(0)
        rule.onAllNodesWithTag(ReviewTags.ease(4)).assertCountEquals(0)
        rule.onNodeWithTag(ReviewTags.ease(1)).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf(3, 2), answers) // hold Again = Hard

        switchTo(anthroC4)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.ease(3)).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf(3, 2, 4), answers) // hold Good = Easy

        switchTo(lawC1)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.ease(1)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(3, 2, 4, 1), answers)
    }

    @Test
    fun aSecondTapCannotGradeTwice() {
        show(lawC3)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        val good = rule.onNodeWithTag(ReviewTags.ease(3))
        good.performClick()
        good.performClick()
        rule.waitForIdle()
        assertEquals(listOf(3), answers)
    }

    @Test
    fun sideButtonRevealsThenGradesGoodOrAgain() {
        show(lawC3)
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) } // reveal
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.ease(3)).assertExists()
        assertTrue(answers.isEmpty())
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) } // Good
        rule.waitForIdle()
        assertEquals(listOf(3), answers)

        switchTo(lawC5)
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.LONG) } // a hold on the question side only reveals
        rule.waitForIdle()
        assertTrue(answers == listOf(3))
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.LONG) } // Again
        rule.waitForIdle()
        assertEquals(listOf(3, 1), answers)
    }

    /**
     * On a round watch the screen's corners don't exist. Every control must sit inside the
     * display circle: checked at the 45° points of each pill's rounded corners (the parts
     * nearest the circle's edge) and at its side midpoints.
     */
    @Test
    fun controlsFitTheRoundScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("not a round display", context.resources.configuration.isScreenRound)
        show(lawC3)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val cx = root.center.x
        val cy = root.center.y
        val radius = minOf(root.width, root.height) / 2f

        fun assertInsideCircle(tag: String) {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val r = minOf(b.height, b.width) / 2f
            val inset = r * (1f - 1f / sqrt(2f))
            val points = listOf(
                b.left + inset to b.top + inset, b.right - inset to b.top + inset,
                b.left + inset to b.bottom - inset, b.right - inset to b.bottom - inset,
                b.left to b.center.y, b.right to b.center.y
            )
            for ((x, y) in points) {
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                assertTrue("$tag point ($x, $y) is ${d - radius}px outside the round screen (bounds $b, screen $root)", d <= radius)
            }
        }

        assertInsideCircle(ReviewTags.SHOW_ANSWER)
        rule.screenshot("round-question")
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        assertInsideCircle(ReviewTags.ease(1))
        assertInsideCircle(ReviewTags.ease(3))
        rule.screenshot("round-answer")
    }

    @Test
    fun sideButtonIsSwallowedWhileTheNextCardsLoad() {
        var finished = 0
        val fetching = mutableStateOf(true)
        rule.setContent {
            AnkiWearTheme {
                ReviewScreen(
                    card = null,
                    revisionKey = "none",
                    isFetching = fetching.value,
                    onAnswer = { _, _, ease, _ -> answers.add(ease) },
                    onFinished = { finished++ }
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) }
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.LONG) }
        assertEquals(0, finished) // still loading: nothing happens, and it is not a Back
        rule.runOnIdle { fetching.value = false } // nothing more due: "Done!"
        rule.waitForIdle()
        rule.runOnIdle { SideButtons.handler!!.invoke(Press.SHORT) }
        assertEquals(1, finished)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun templateCardRevealsOnTapAndJumpsToTheAnswer() {
        show(basic)
        rule.screenshot("basic-question")
        rule.onNodeWithTag(ReviewTags.block(0)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.ease(3)).assertExists()
        rule.assertOnScreen("When each card is due next")
        rule.screenshot("basic-answer")
    }

    // ── Stress ───────────────────────────────────────────────────────────────────────────

    /** Random cloze notes with a unique secret per cloze; no nesting, no anchors. */
    private fun randomCard(rnd: Random, id: Long): Pair<CardData, Map<Int, List<String>>> {
        val secrets = HashMap<Int, MutableList<String>>()
        val words = listOf("alpha", "beta", "section", "court", "theory", "§45", "दंड", "—", "(a)", "x²")
        val sb = StringBuilder()
        var clozeIndex = 0
        repeat(1 + rnd.nextInt(6)) { block ->
            val list = rnd.nextInt(3) == 0
            if (list) sb.append(if (rnd.nextBoolean()) "<ol>" else "<ul>") else sb.append(if (rnd.nextBoolean()) "<p>" else "<div class=\"header header-red\">")
            repeat(1 + rnd.nextInt(4)) {
                if (list) sb.append("<li>")
                repeat(1 + rnd.nextInt(8)) {
                    if (rnd.nextInt(3) == 0) {
                        val ord = 1 + rnd.nextInt(5)
                        val secret = "Q${id}x${clozeIndex++}Z"
                        secrets.getOrPut(ord) { mutableListOf() }.add(secret)
                        sb.append("{{c").append(ord).append("::")
                        if (rnd.nextBoolean()) sb.append("<b>").append(secret).append("</b>") else sb.append(secret)
                        if (rnd.nextBoolean()) sb.append("::").append(words[rnd.nextInt(words.size)])
                        sb.append("}} ")
                    } else {
                        sb.append(words[rnd.nextInt(words.size)]).append(if (rnd.nextInt(5) == 0) "<br>" else " ")
                    }
                }
                if (list) sb.append("</li>")
            }
            sb.append(if (list) "</ol>" else if (block % 2 == 0) "</p>" else "</div>")
        }
        val active = secrets.keys.randomOrNull(rnd) ?: 1
        val card = CardData(
            noteId = id, cardOrd = active - 1, question = "", answer = "", buttonCount = 4,
            nextReviewTimes = listOf("<1m", "6m", "1d", "4d"),
            cloze = ClozeCard(sb.toString(), active, listOf("Note" to "n$id"))
        )
        return card to secrets
    }

    @Test
    fun stressManyRandomCards() {
        val rnd = Random(2026)
        val (first, _) = randomCard(rnd, 0)
        show(first, focus = rnd.nextBoolean())
        var slowest = 0L
        val count = 120
        for (i in 1..count) {
            val (card, secrets) = randomCard(rnd, i.toLong())
            val start = SystemClock.uptimeMillis()
            switchTo(card)
            slowest = maxOf(slowest, SystemClock.uptimeMillis() - start)
            val text = rule.allText()
            for ((_, list) in secrets) for (s in list) {
                assertFalse("card $i leaked $s\n${card.cloze!!.content}\n$text", text.contains(s))
            }
            val question = firstTestedBlock(card, answer = false)
            if (question >= 0) rule.assertRowInView(ReviewTags.block(question), "card $i question\n${card.cloze!!.content}")
            rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
            rule.waitForIdle()
            val answer = firstTestedBlock(card, answer = true)
            if (answer >= 0) rule.assertRowInView(ReviewTags.block(answer), "card $i answer\n${card.cloze!!.content}")
            val before = answers.size
            rule.onNodeWithTag(if (i % 3 == 0) ReviewTags.ease(1) else ReviewTags.ease(3)).performClick()
            rule.waitForIdle()
            assertEquals("card $i grade", before + 1, answers.size)
        }
        Log.i("AnkiWatchTest", "stress: $count random cards, slowest card switch ${slowest}ms")
        assertTrue("slowest card switch took ${slowest}ms", slowest < 3_000)
    }

    @Test
    fun hugeNoteStaysResponsive() {
        val law = lawC3.cloze!!.content
        val huge = law.repeat(40) // ~40 statute sections in one note
        val card = lawC3.copy(noteId = 999, cloze = lawC3.cloze!!.copy(content = huge))
        val start = SystemClock.uptimeMillis()
        show(card, focus = false)
        val firstFrame = SystemClock.uptimeMillis() - start
        repeat(25) {
            rule.onNodeWithTag(ReviewTags.CONTENT).performTouchInput { swipeUp() }
        }
        rule.waitForIdle()
        for (secret in lawSecrets) assertFalse(rule.allText().contains(secret))
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.ease(3)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(3), answers)
        Log.i("AnkiWatchTest", "huge note (${huge.length} chars) first frame ${firstFrame}ms")
        assertTrue("first frame took ${firstFrame}ms", firstFrame < 10_000)
    }

    @Test
    fun malformedAndEmptyCardsDoNotCrash() {
        show(basic)
        val nasty = listOf(
            "", "   ", "<br><br>", "{{c1::", "{{c1::unclosed <b>bold", "}}::{{", "<ol><li><ol><li>",
            "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "&#xD800;&#0;&bogus;",
            "<div style=\"display:none\">{{c1::x}}</div>", "a".repeat(20_000), "{{c1::" + "{{c1::".repeat(500)
        )
        for ((k, html) in nasty.withIndex()) {
            switchTo(basic.copy(noteId = 5000L + k, cloze = ClozeCard(html, 1)))
            rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(ReviewTags.ease(3)).performClick()
            rule.waitForIdle()
        }
        assertEquals(nasty.size, answers.size)
    }
}
