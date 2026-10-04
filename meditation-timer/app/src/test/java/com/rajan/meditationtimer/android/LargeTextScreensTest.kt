package com.rajan.meditationtimer

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The worst case for layout: a small phone (360 dp wide) with the system font at 150%, as many
 * people set it. Every screen must still open, scroll and reach its controls; the screenshots
 * (prefixed "large-") show whether anything clips or overlaps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeTextScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/large-$name.png")

    @Before
    fun setUp() {
        SessionLog.resetForTests()
        File(app.filesDir, "sessions.csv").delete()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        SessionRepository.reset()
        SessionRepository.startCounting(false)
        RuntimeEnvironment.setFontScale(1.5f)
        val today = LocalDate.now(zone)
        SessionLog.get(app).merge(
            (0L..70L).filter { it % 5 != 3L }.map { d ->
                SessionRecord(
                    today.minusDays(d).atTime(6, 50).atZone(zone).toInstant().toEpochMilli(), 1200, 1200,
                    rating = 4, note = if (d == 7L) "A long note to see how the card copes when the words run on past a couple of lines of large text" else "",
                    before = 2, after = 4, noticed = 6,
                )
            },
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario?.close()
        SessionRepository.reset()
        RuntimeEnvironment.setFontScale(1f)
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `sit screen, its settings and Begin all reachable`() {
        shot("01-sit")
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("Weekly goal").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/large-02-settings.png")
        compose.onNodeWithText("Daily reminder").performScrollTo()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Sound & stillness").performScrollTo().performClick()
        compose.onNodeWithText("Background sound").performScrollTo()
        captureScreenRoboImage("build/outputs/roborazzi/large-02b-sound.png")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `welcome and guide at large text`() {
        compose.onNodeWithText("Guide").performScrollTo().performClick()
        compose.onNodeWithText("How to sit").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/large-09-guide.png")
        compose.onNodeWithText("Show the welcome again").performScrollTo().performClick()
        compose.waitForIdle()
        shot("10-welcome")
        compose.onNodeWithText("I sit regularly").performScrollTo().performClick()
        compose.onNodeWithText("I'll decide later").performScrollTo().performClick()
        compose.waitForIdle()
        shot("11-welcome-how-to-sit")
        // The first-sit button is pinned below the text, so it's never pushed off screen.
        compose.onNodeWithText("Begin my first sit  ·  20 min").assertExists()
        compose.onNodeWithText("Look around first").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·  20 min").assertExists()
    }

    @Test
    fun `running and finished screens`() {
        compose.mainClock.autoAdvance = false
        SessionRepository.startCounting(true)
        val config = SessionConfig(1200, 5, 10, true, 0)
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 420_000), config))
        compose.mainClock.advanceTimeBy(2_000)
        shot("03-running")
        compose.mainClock.autoAdvance = true
        SessionRepository.finished(config, System.currentTimeMillis(), 1200, before = 2, noticed = 6)
        compose.waitForIdle()
        shot("04-finished")
        compose.onNodeWithText("Done").performScrollTo()
        shot("05-finished-bottom")
    }

    @Test
    fun `history cards`() {
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        shot("06-history")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("in review", substring = true))
        shot("07-review")
        compose.onNodeWithText("Trends").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("How your sits felt"))
        shot("08-mood")
        compose.onNodeWithText("Sessions").performClick()
        shot("09-log")
    }
}
