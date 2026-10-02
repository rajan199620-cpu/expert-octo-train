package com.rajan.mindfield

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.captureRoboImage
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Drives the real app on the JVM and saves screenshots (CI commits them to
 * mindfield/docs/screenshots) so the design can be checked without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Before
    fun clean() = Seed.clean(app)

    @After
    fun close() {
        scenario?.close()
        Seed.backToNow()
    }

    private fun launch() {
        // A phone is in touch mode; without it the first control takes focus and the screen opens scrolled to it.
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
    fun `onboarding - five steps, then day one is the frequency illusion`() {
        launch()
        shot("01-welcome")
        compose.onNodeWithText("Begin").performClick()
        compose.onNodeWithText("How a day works").assertExists()
        shot("02-how-it-works")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("🧩 Thinking traps").performClick()
        shot("03-focus")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("When should it reach you?").assertExists()
        shot("04-times")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Keep your journal safe").assertExists()
        shot("05-google")
        compose.onNodeWithText("Not now, start exploring").performClick()
        compose.waitForIdle()
        assertTrue(Store.state.value.settings.onboarded)
        assertEquals(setOf(com.rajan.mindfield.core.Category.THINKING), Store.state.value.settings.focus)
        assertEquals(Curriculum.FIRST, Store.state.value.assignments[Store.today()]?.conceptId)
        compose.onNodeWithText("The frequency illusion").assertExists()
        shot("06-day-one")
    }

    @Test
    fun `today - predict unlocks the study, the plan saves, and the field report logs`() {
        Seed.weeks(35)
        launch()
        val c = Store.todayConcept()
        shot("10-today")
        compose.onNodeWithText("🔒  Make your prediction above to unlock the study.").assertExists()
        // Guess right.
        compose.onNodeWithText(c.predict.options[c.predict.answer]).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(c.predict.answer, Store.state.value.guesses[c.id]?.choice)
        compose.onNodeWithText("✓ You called it. The study is unlocked below.").assertExists()
        shot("11-today-predicted")
        scrollTo("The study".uppercase())
        shot("12-today-study")

        // An if-then plan for the mission.
        scrollTo("Save my plan")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("At the 3 pm meeting, I'll make the first offer")
        compose.onNodeWithText("Save my plan").performClick()
        compose.waitForIdle()
        assertEquals("At the 3 pm meeting, I'll make the first offer", Store.state.value.plans[Store.today()]?.text)
        scrollTo("How did it show up in your day?")
        shot("13-today-mission-and-report")

        // Field report: "Used it", a note and an outcome.
        compose.onAllNodesWithText(Mode.USED.label).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Field report").assertExists()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Opened the salary talk with my number first. It anchored the whole conversation.")
        compose.onNodeWithText("Worked").performScrollTo().performClick()
        shot("14-log-sheet")
        compose.onNodeWithText("Save to journal").performScrollTo().performClick()
        compose.waitForIdle()
        val e = Store.state.value.entriesOn(Store.today()).single()
        assertEquals(Mode.USED, e.mode)
        assertEquals(com.rajan.mindfield.core.Outcome.WORKED, e.outcome)
        scrollTo("How did it show up in your day?")
        shot("15-today-logged")
    }

    @Test
    fun `guide, a concept page, journal, review and you`() {
        Seed.weeks(40)
        launch()
        tab("Guide")
        compose.onNodeWithText("Field guide").assertExists()
        shot("20-guide")
        compose.onNodeWithText("💥 Myths").performScrollTo().performClick()
        compose.waitForIdle()
        shot("21-guide-myths")
        compose.onNodeWithText("All").performClick()

        // Open a discovered concept from the guide.
        val c = Store.library[Store.state.value.assignments.toSortedMap().values.first().conceptId]!!
        compose.onAllNodesWithText(c.title).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("←  Back").assertExists()
        shot("22-concept-page")
        compose.onNodeWithText("←  Back").performClick()

        tab("Journal")
        compose.onNodeWithText("Field journal").assertExists()
        shot("23-journal")

        tab("Review")
        shot("24-review")
        val answer = Seed.firstReviewAnswer()
        compose.onNodeWithText("Start review").performClick()
        compose.waitForIdle()
        shot("25-review-question")
        compose.onNodeWithText(answer).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("✓ Right").assertExists()
        shot("26-review-answered")

        tab("You")
        shot("27-you")
        scrollTo("Where you notice psychology".uppercase())
        shot("28-you-insights")
        scrollTo("Settings")
        shot("29-settings")
        scrollTo("Google account".uppercase())
        shot("30-settings-google")
    }

    @Test
    fun `google setup help shows the exact values to register`() {
        Seed.weeks(5)
        GoogleSync.setStateForTests(
            CloudState(message = "Google doesn't know this app yet. Add an Android OAuth client for it (steps below), then tap Connect again.", needsSetup = true),
        )
        launch()
        tab("You")
        scrollTo("One-time setup (2 minutes)")
        compose.onNodeWithText(GoogleSync.SHA1).assertExists()
        compose.onNodeWithText(GoogleSync.PACKAGE).assertExists()
        shot("31-google-setup-help")
        GoogleSync.setStateForTests(CloudState(email = "rajan@example.com", lastSyncMs = System.currentTimeMillis()))
        compose.waitForIdle()
        scrollTo("Linked to rajan@example.com")
        shot("32-google-linked")
    }

    @Test
    fun `dark mode`() {
        Seed.weeks(30)
        Store.settings { it.copy(theme = ThemeMode.DARK) }
        launch()
        shot("40-dark-today")
        tab("Guide")
        shot("41-dark-guide")
        tab("You")
        scrollTo("Where you notice psychology".uppercase())
        shot("42-dark-insights")
    }

    @Test
    fun `a myth day says so`() {
        Store.settings { it.copy(onboarded = true) }
        val today = Store.today()
        Store.update { it.copy(assignments = mapOf(today to com.rajan.mindfield.core.Assignment("ego-depletion", 1))) }
        launch()
        compose.onNodeWithText("PLOT TWIST: A MYTH").assertExists()
        shot("43-myth-day")
    }

    @Test
    fun `stress - a journal of three thousand notes opens, filters and pages`() {
        Store.settings { it.copy(onboarded = true) }
        val today = LocalDate.now(Seed.zone)
        Store.update { s ->
            var x = s
            for (i in 0 until 3000) {
                val day = today.minusDays((i / 3).toLong())
                val id = Store.library.all[i % Store.library.size].id
                x = x.copy(assignments = x.assignments + (day to com.rajan.mindfield.core.Assignment(Store.library.all[(i / 3) % Store.library.size].id, 0)))
                x = x.upsert(
                    com.rajan.mindfield.core.Entry("e$i", id, day, Mode.entries[i % 4], "Note number $i", null, i.toLong(), i.toLong()),
                )
            }
            x
        }
        launch()
        tab("Journal")
        compose.onNodeWithText("Field journal").assertExists()
        compose.onNodeWithText("🎯 Used").performClick()
        compose.waitForIdle()
        shot("44-journal-3000")
        scrollTo("Show older notes", substring = true)
        compose.onNodeWithText("Show older notes", substring = true).performClick()
        compose.waitForIdle()
        scrollTo("Show older notes (630 more)")
        compose.onNodeWithText("Show older notes (630 more)").assertExists()
        tab("You")
        shot("45-you-3000")
    }
}
