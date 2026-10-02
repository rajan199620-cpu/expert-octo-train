package com.ankiwear.wear

import android.content.Intent
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwear.wear.screens.ReviewTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real key events through MainActivity in demo mode: the side button (KEYCODE_BACK on a
 * Galaxy Watch) reveals, then grades — press = Good, hold = Again — and is an ordinary
 * Back everywhere else.
 */
@RunWith(AndroidJUnit4::class)
class SideButtonKeyTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun launchDemo(): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEMO, true)
        return ActivityScenario.launch(intent)
    }

    private fun press() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    /** Holds the side button for [ms], with the key repeats a real long press produces. */
    private fun hold(ms: Long = 700) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(
            KeyEvent(down, down + ms - 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS)
        )
        instrumentation.sendKeySync(KeyEvent(down, down + ms, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        rule.waitForIdle()
    }

    @Test
    fun pressRevealsThenPressGradesGood() {
        val scenario = launchDemo()
        rule.onNodeWithText("Basics").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).assertExists()
        press() // reveal
        rule.onNodeWithTag(ReviewTags.ease(3)).assertExists()
        press() // Good: the deck's only card is done
        rule.onNodeWithText("Done!").assertExists()
        press() // from "Done!" back to the deck list …
        rule.onNodeWithText("Basics").assertExists()
        press() // … where it is the ordinary Back again and leaves the app
        SystemClock.sleep(500)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun holdGradesAgainAndTheCardComesBack() {
        launchDemo().use {
            rule.onNodeWithText("Basics").performClick()
            rule.waitForIdle()
            press() // reveal
            hold() // Again: demo re-queues the card instead of finishing
            rule.onNodeWithTag(ReviewTags.SHOW_ANSWER).assertExists()
            press()
            press() // Good this time
            rule.onNodeWithText("Done!").assertExists()
        }
    }

    @Test
    fun backStillWorksOutsideAReview() {
        val scenario = launchDemo()
        rule.onNodeWithText("Basics").assertExists()
        press()
        SystemClock.sleep(500)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
