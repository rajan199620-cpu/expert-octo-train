package com.rajan.mindfield

import android.app.Application
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.rajan.mindfield.core.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The worst case for layout: a small phone (360 dp wide) with the system font at 150%.
 * Every screen must still open, scroll and reach its controls; the screenshots ("large-")
 * show whether anything clips or overlaps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeTextScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/large-$name.png")

    @Before
    fun setUp() {
        Seed.clean(app)
        RuntimeEnvironment.setFontScale(1.5f)
    }

    @After
    fun close() {
        scenario?.close()
        Seed.backToNow()
    }

    private fun launch() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun tab(label: String) {
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.waitForIdle()
    }

    /** Plain scrolling columns compose every child, so scroll the node itself; lazy lists need the list to search. */
    private fun scrollTo(text: String, substring: Boolean = false) {
        val nodes = compose.onAllNodesWithText(text, substring = substring)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) {
            nodes.onFirst().performScrollTo()
        } else {
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
        }
        compose.waitForIdle()
    }

    @Test
    fun `onboarding fits`() {
        launch()
        shot("01-welcome")
        compose.onNodeWithText("Begin").performClick()
        shot("02-how")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        shot("03-times")
        compose.onNodeWithText("Next").performClick()
        shot("04-google")
        compose.onNodeWithText("Not now, start exploring").performClick()
        compose.waitForIdle()
        shot("05-today")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `every screen opens and its controls are reachable`() {
        Seed.weeks(30)
        launch()
        shot("10-today")
        val c = Store.todayConcept()
        compose.onNodeWithText(c.predict.options[c.predict.answer]).performScrollTo().performClick()
        scrollTo("Make an if-then plan", substring = true)
        shot("11-today-mission")
        scrollTo(Mode.NOT_TODAY.label)
        shot("12-today-report")
        compose.onNodeWithText(Mode.NOT_TODAY.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save to journal").performScrollTo()
        shot("13-log-sheet")
        compose.onNodeWithText("Save to journal").performClick()
        compose.waitForIdle()
        assertEquals(Mode.NOT_TODAY, Store.state.value.entriesOn(Store.today()).single().mode)

        tab("Guide")
        shot("20-guide")
        tab("Review")
        shot("21-review")
        compose.onNodeWithText("Start review").performScrollTo().performClick()
        compose.waitForIdle()
        shot("22-review-question")
        compose.onNodeWithText("End").performClick()
        tab("Journal")
        shot("23-journal")
        tab("You")
        shot("24-you")
        scrollTo("Settings")
        shot("25-settings")
        scrollTo("Backup file")
        compose.onAllNodesWithText("Backup file").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save backup").performScrollTo().assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/large-26-backup.png")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        scrollTo("How you compare".uppercase())
        shot("27-you-compare")
    }
}
