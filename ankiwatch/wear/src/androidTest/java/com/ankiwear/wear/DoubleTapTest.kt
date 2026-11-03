package com.ankiwear.wear

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.screens.ReviewScreen
import com.ankiwear.wear.screens.ReviewTags
import com.ankiwear.wear.theme.AnkiWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A double tap anywhere on a card buries it, once, on either side; single taps, slow taps,
 * taps far apart, drags, long presses and two fingers never do, and a tap on one card and a
 * tap on the next are not a double tap.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapTest {

    // A stuck test fails with where it was stuck, instead of hanging the whole run.
    @get:Rule(order = 0)
    val timeout: Timeout = Timeout.builder().withTimeout(180, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val answers = mutableListOf<Int>()
    private lateinit var cardState: MutableState<CardData>

    private val lawC3 get() = DemoContent.cards(1L)[0] // covered clozes: a tap peeks, it doesn't reveal
    private val basic get() = DemoContent.cards(3L)[0] // no clozes: a tap anywhere reveals

    private fun show(card: CardData, focus: Boolean = true, allowBury: Boolean = true) {
        rule.setContent {
            val state = remember { mutableStateOf(card) }
            cardState = state
            AnkiWearTheme {
                ReviewScreen(
                    card = state.value,
                    revisionKey = "${state.value.noteId}:${state.value.cardOrd}",
                    focusMode = focus,
                    allowBury = allowBury,
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

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        rule.onRoot().performTouchInput(block)
        rule.waitForIdle()
    }

    @Test
    fun aDoubleTapBuriesOnTheQuestionSide() {
        show(lawC3)
        touch { doubleClick(center) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
        // More double taps on the same card send nothing more.
        touch { doubleClick(center) }
        touch { doubleClick(center) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
    }

    @Test
    fun aDoubleTapBuriesOnTheAnswerSide() {
        show(lawC3)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        touch { doubleClick(center) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
    }

    @Test
    fun onACardWhereATapRevealsTheDoubleTapStillBuries() {
        show(basic)
        touch { doubleClick(center) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
    }

    @Test
    fun aDoubleTapOnShowAnswerBuriesAndDoesNotGrade() {
        show(lawC3)
        val at = rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).fetchSemanticsNode().boundsInRoot.center
        // The first tap reveals; the second is the double tap, and only a bury goes out.
        touch { doubleClick(at) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
    }

    @Test
    fun aQuickTapAfterAGradeDoesNotBury() {
        show(lawC3)
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).performClick()
        rule.waitForIdle()
        val good = rule.onNodeWithTag(ReviewTags.ease(3)).fetchSemanticsNode().boundsInRoot.center
        touch { doubleClick(good) }
        assertEquals(listOf(3), answers)
    }

    @Test
    fun singleSlowFarDraggedLongAndTwoFingerTapsNeverBury() {
        show(lawC3, focus = false)
        val far = with(rule.density) { 60.dp.toPx() }
        touch { click(center) }
        touch {
            click(center)
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 200)
            click(center)
        }
        touch {
            click(center)
            advanceEventTime(viewConfiguration.doubleTapMinTimeMillis + 60)
            click(center + Offset(far, 0f))
        }
        touch {
            down(center)
            advanceEventTime(30)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 3))
            up()
            advanceEventTime(100)
            click(center)
        }
        touch {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
            advanceEventTime(100)
            click(center)
        }
        touch {
            down(0, center)
            down(1, center + Offset(far, 0f))
            advanceEventTime(40)
            up(1)
            up(0)
            advanceEventTime(100)
            click(center)
        }
        assertEquals(emptyList<Int>(), answers)
    }

    @Test
    fun aTapOnOneCardAndATapOnTheNextAreNotADoubleTap() {
        show(lawC3)
        touch { click(center) }
        switchTo(lawC3.copy(noteId = 777))
        touch { click(center) }
        assertEquals(emptyList<Int>(), answers)
        // The next card can still be buried by a double tap of its own.
        touch { doubleClick(center) }
        assertEquals(listOf(Wire.EASE_BURY), answers)
    }

    @Test
    fun withoutBuryADoubleTapDoesNothing() {
        show(lawC3, allowBury = false)
        touch { doubleClick(center) }
        assertEquals(emptyList<Int>(), answers)
    }

    /**
     * Random runs of taps, slow taps, far taps, long presses and drags. A model of what a
     * double tap is says for each run whether the card is buried; the screen must agree.
     */
    @Test
    fun randomTouchesBuryExactlyWhenThereIsADoubleTap() {
        val rnd = Random(2611)
        show(lawC3, focus = false)
        val nearby = with(rule.density) { 40.dp.toPx() }
        var buried = 0
        val runs = 150
        for (run in 1..runs) {
            switchTo(lawC3.copy(noteId = 1000L + run))
            val before = answers.size
            val log = StringBuilder()
            var expectBury = false
            touch {
                val cfg = viewConfiguration
                val slop = cfg.touchSlop
                val origin = center
                var last: Pair<Offset, Long>? = null
                var now = 0L // event time since the run started; only differences matter
                repeat(1 + rnd.nextInt(4)) {
                    // The pause before this touch, clear of the edges of what counts.
                    val gap = when (rnd.nextInt(3)) {
                        0 -> rnd.nextLong(1, cfg.doubleTapMinTimeMillis - 15)
                        1 -> rnd.nextLong(cfg.doubleTapMinTimeMillis + 15, cfg.doubleTapTimeoutMillis - 15)
                        else -> rnd.nextLong(cfg.doubleTapTimeoutMillis + 30, cfg.doubleTapTimeoutMillis + 600)
                    }
                    advanceEventTime(gap)
                    now += gap
                    var at = origin + Offset(rnd.nextInt(-50, 51) * nearby / 50f, rnd.nextInt(-30, 31) * nearby / 50f)
                    // Never right on the edge of "close together", where rounding would decide.
                    while (last?.let { (p, _) -> kotlin.math.abs((at - p).getDistance() - nearby) < 3f } == true) at += Offset(4f, 4f)
                    when (rnd.nextInt(6)) {
                        0 -> { // A drag: never a tap.
                            down(at); advanceEventTime(20); moveBy(Offset(0f, -slop * 2.5f)); advanceEventTime(20); up()
                            now += 40
                            last = null
                            log.append("drag ")
                        }
                        1 -> { // A long press: never a tap.
                            val hold = cfg.longPressTimeoutMillis + 30 + rnd.nextLong(200)
                            down(at); advanceEventTime(hold); up()
                            now += hold
                            last = null
                            log.append("hold ")
                        }
                        else -> {
                            val hold = rnd.nextLong(10, minOf(150L, cfg.longPressTimeoutMillis - 50))
                            val prev = last
                            val double = prev != null &&
                                (now - prev.second) in cfg.doubleTapMinTimeMillis..cfg.doubleTapTimeoutMillis &&
                                (at - prev.first).getDistance() <= nearby
                            down(at); advanceEventTime(hold); up()
                            now += hold
                            if (double) expectBury = true
                            last = if (double) null else at to now
                            log.append("tap(+$gap ms${if (double) ", double" else ""}) ")
                        }
                    }
                }
            }
            val got = answers.drop(before)
            assertEquals("run $run: $log", if (expectBury) listOf(Wire.EASE_BURY) else emptyList<Int>(), got)
            if (expectBury) buried++
        }
        Log.i("AnkiWatchTest", "double tap: $runs random runs, $buried buried")
        assertTrue("only $buried buried", buried >= 15)
        assertTrue("$buried of $runs buried", buried <= runs - 15)
    }
}
