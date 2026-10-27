package com.rajan.mindfield

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
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
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Ten in the morning today: Today shows its morning layout whatever time CI runs. */
    private fun morning() = Seed.at(LocalDate.now(Seed.zone), 10)

    /** Opens review from the Guide (it's no longer a tab). */
    private fun openReview() {
        tab("Guide")
        compose.onNodeWithText("Review & practice").performClick()
        compose.waitForIdle()
    }

    /** Opens a settings topic: the gear on the You tab, then the topic's row. */
    private fun openSettings(row: String) {
        compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        compose.waitForIdle()
        openSheet(row)
    }

    private fun step(label: String) = compose.onNode(hasText(label) and hasStateDescriptionIn("done", "to do"))

    private fun hasStateDescriptionIn(vararg values: String) =
        SemanticsMatcher("state is one of ${values.toList()}") { it.config.getOrNull(SemanticsProperties.StateDescription) in values }

    private fun stateIs(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

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
        compose.onNodeWithText("Thinking traps").performClick()
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
        morning()
        launch()
        val c = Store.todayConcept()
        shot("10-today")
        // Today opens at the top, on the concept itself.
        compose.onAllNodesWithText(c.title).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Make your prediction above to unlock the study.").assertExists()
        // The floating "Log today" button is gone: the report's own buttons do that job.
        compose.onAllNodesWithText("Log today", substring = true).assertCountEquals(0)
        // The real-world case opens with the study, so it can't give the answer away.
        compose.onAllNodesWithText("In the real world".uppercase()).assertCountEquals(0)
        // Guess right.
        compose.onNodeWithText(c.predict.options[c.predict.answer]).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(c.predict.answer, Store.state.value.guesses[c.id]?.choice)
        compose.onNodeWithText("✓ You called it. The study is unlocked below.").assertExists()
        shot("11-today-predicted")
        scrollTo("The study".uppercase())
        shot("12-today-study")
        // The real-world case starts folded, with a teaser; tapping opens it, nuance and all.
        scrollTo("In the real world".uppercase())
        compose.onAllNodesWithText("The nuance").assertCountEquals(0)
        shot("12b-today-real-world")
        compose.onNodeWithText("Read the case and its nuance").performClick()
        compose.waitForIdle()
        if (c.caseNuance != null) {
            scrollTo("The nuance")
            shot("12c-today-nuance")
        }

        // An if-then plan for the mission.
        // Nothing on Today may take focus by itself: the screen must open at the top, with no keyboard.
        assertEquals(0, compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size)
        scrollTo("Make an if-then plan", substring = true)
        compose.onNodeWithText("Make an if-then plan", substring = true).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("At the 3 pm meeting, I'll make the first offer")
        compose.onNodeWithText("Save my plan").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("At the 3 pm meeting, I'll make the first offer", Store.state.value.plans[Store.today()]?.text)
        scrollTo("How did it show up in your day?")
        shot("13-today-mission-and-report")

        // Field report: "Used it", a note and an outcome.
        // A click lands at the node's centre, so the button itself must be on screen, not just its section.
        compose.onAllNodesWithText(Mode.USED.label).onFirst().performScrollTo().performClick()
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

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `guide, a concept page, journal, review and you`() {
        Seed.weeks(40)
        launch()
        tab("Guide")
        compose.onNodeWithText("Field guide").assertExists()
        shot("20-guide")
        compose.onNodeWithText("Myths").performScrollTo().performClick()
        compose.waitForIdle()
        shot("21-guide-myths")
        compose.onNodeWithText("All").performClick()

        // Open a discovered concept from the guide.
        val c = Store.library[Store.state.value.assignments.toSortedMap().values.first().conceptId]!!
        compose.onAllNodesWithText(c.title).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back").assertExists()
        shot("22-concept-page")
        compose.onNodeWithText("Back").performClick()

        tab("Journal")
        compose.onNodeWithText("Field journal").assertExists()
        shot("23-journal")

        openReview()
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
        // Settings sit behind the gear: a list of topics, each opening its own sheet.
        compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Backup file").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/29-settings.png")
        openSheet("Google account")
        compose.onNodeWithText("Connect Google account").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/30-settings-google.png")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Connect Google account").assertDoesNotExist()
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back goes to Today from another tab, and closes a concept page first`() {
        Seed.weeks(10)
        launch()
        tab("Journal")
        compose.onNodeWithText("Field journal").assertExists()
        back()
        compose.onNodeWithText("Field journal").assertDoesNotExist()
        compose.onNodeWithText("in the field", substring = true).assertExists()
        // A concept page opened from the Guide closes first; the next Back goes to Today.
        tab("Guide")
        val c = Store.library[Store.state.value.assignments.toSortedMap().values.first().conceptId]!!
        compose.onAllNodesWithText(c.title).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back").assertExists()
        back()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Field guide").assertExists()
        back()
        compose.onNodeWithText("in the field", substring = true).assertExists()
        scenario!!.onActivity { assertFalse("Back on Today is the only way out", it.isFinishing) }
    }

    private fun openSheet(row: String) {
        scrollTo(row)
        compose.onAllNodesWithText(row).onFirst().performClick()
        compose.waitForIdle()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `you - forgiving streak, weekly goal, month in review, how you compare`() {
        Seed.weeks(40)
        // A goal of 7 can't be met before the week is out (today isn't logged yet), whatever day the test runs.
        Store.settings { it.copy(weeklyGoal = 7) }
        launch()
        tab("You")
        compose.onNodeWithText("of 7 days this week", substring = true).assertExists()
        compose.onNodeWithText("One missed day a week is forgiven", substring = true).assertExists()
        scrollTo("How you compare".uppercase())
        compose.onNodeWithText("of study results", substring = true).assertExists()
        compose.onNodeWithText("Baumel et al.", substring = true).assertExists()
        shot("33-you-compare")
        scrollTo("field reports on", substring = true)
        shot("34-you-month")
        // Browse back a month and forward again.
        val title = compose.onAllNodesWithText(" in the field", substring = true).fetchSemanticsNodes().size +
            compose.onAllNodesWithText(" so far", substring = true).fetchSemanticsNodes().size
        assertTrue("a month title is shown", title > 0)
        compose.onNodeWithText("‹").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("›").onFirst().performClick() // the month arrow comes before the settings rows' chevrons
        compose.waitForIdle()
        // Weekly goal: change it in its sheet; the row and the week line follow.
        openSettings("Weekly goal")
        compose.onNodeWithText("3 days").performClick()
        compose.waitForIdle()
        assertEquals(3, Store.state.value.settings.weeklyGoal)
        captureScreenRoboImage("build/outputs/roborazzi/35-settings-goal.png")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("of 7 days this week", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 days a week with a field report").assertExists()
    }

    @Test
    fun `today's path is a stepper that ticks off as you go and jumps to each step`() {
        Seed.weeks(35)
        morning()
        launch()
        val c = Store.todayConcept()
        step("Predict").assert(stateIs("to do"))
        compose.onNodeWithText(c.predict.options[c.predict.answer]).performScrollTo().performClick()
        compose.waitForIdle()
        step("Predict").performScrollTo().assert(stateIs("done"))
        // Tapping a step scrolls the page to it.
        step("Report").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("How did it show up in your day?").assertIsDisplayed()
        shot("16-today-path-report")
        Store.log(c.id, Mode.SPOTTED, "On the bus")
        compose.waitForIdle()
        step("Report").assert(stateIs("done"))
    }

    @Test
    fun `the review step opens review over Today, and Back returns`() {
        Seed.weeks(35)
        morning()
        launch()
        val due = com.rajan.mindfield.core.Spacing.due(Store.state.value, Store.library, Store.today()).size
        assertTrue("the seed leaves reviews due", due > 0)
        step("Review").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("$due due")))
        step("Review").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start review").assertExists()
        compose.onNodeWithText("Coming up".uppercase()).performScrollTo().assertExists()
        back()
        compose.onNodeWithText("Start review").assertDoesNotExist()
        compose.onNodeWithText("in the field", substring = true).assertExists()
        // Four tabs: Review is no longer one of them.
        compose.onAllNodes(hasText("Review") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertCountEquals(0)
    }

    @Test
    fun `in the evening the field report comes first, with your plan`() {
        Seed.weeks(10)
        val today = LocalDate.now(Seed.zone)
        Seed.at(today, 21, 30)
        Store.plan(today, "At dinner, I'll watch who talks first")
        launch()
        val c = Store.todayConcept()
        compose.onNodeWithText("You planned:", substring = true).assertExists()
        val report = compose.onNodeWithText("How did it show up in your day?").fetchSemanticsNode().positionInRoot.y
        val predict = compose.onNodeWithText("Predict first".uppercase()).fetchSemanticsNode().positionInRoot.y
        assertTrue("report ($report) above predict ($predict)", report < predict)
        shot("17-today-evening")
        // One tap files it from the top; the report stays where it is afterwards.
        compose.onAllNodesWithText(Mode.SPOTTED.label).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save to journal").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(c.id, Store.state.value.entriesOn(today).single().conceptId)
        val after = compose.onNodeWithText("How did it show up in your day?").fetchSemanticsNode().positionInRoot.y
        val predictAfter = compose.onNodeWithText("Predict first".uppercase()).fetchSemanticsNode().positionInRoot.y
        assertTrue(after < predictAfter)
    }

    @Test
    fun `a minute before the evening report, Today keeps its morning order`() {
        Seed.weeks(10)
        Seed.at(LocalDate.now(Seed.zone), 20, 59)
        launch()
        compose.onNodeWithText("You planned:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Predict first".uppercase()).assertExists()
        scrollTo("How did it show up in your day?")
        val report = compose.onNodeWithText("How did it show up in your day?").fetchSemanticsNode().positionInRoot.y
        val predict = compose.onNodeWithText("Predict first".uppercase()).fetchSemanticsNode().positionInRoot.y
        assertTrue(report > predict)
    }

    @Test
    fun `the page is lighter - shared from the plate, sources folded`() {
        Seed.weeks(10)
        morning()
        launch()
        val c = Store.todayConcept()
        compose.onNodeWithContentDescription("Share this concept").assertExists()
        compose.onAllNodesWithText("Teach it to someone".uppercase()).assertCountEquals(0)
        compose.onNodeWithText(c.source).assertDoesNotExist()
        scrollTo("Sources ·", substring = true)
        compose.onNodeWithText("Sources ·", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(c.source).assertExists()
        // Watch out now sits at the foot of the mission card.
        compose.onNodeWithText(c.guard).assertExists()
    }

    @Test
    fun `stress - every concept in the library renders on Today, studied and opened`() {
        Seed.weeks(3)
        morning()
        launch()
        val today = Store.today()
        for (c in Store.library.all) {
            Store.update { st ->
                st.copy(
                    assignments = st.assignments + (today to com.rajan.mindfield.core.Assignment(c.id, 1)),
                    guesses = st.guesses + (c.id to com.rajan.mindfield.core.Guess(c.predict.answer, 1)),
                )
            }
            compose.waitForIdle()
            compose.onAllNodesWithText(c.title).onFirst().assertExists()
            compose.onNodeWithText("Read the case and its nuance").performScrollTo().performClick()
            compose.waitForIdle()
            c.caseNuance?.let { compose.onNodeWithText(it).assertExists() }
        }
    }

    @Test
    fun `the guide stamps what you've spotted and outlines what's still to find`() {
        Seed.weeks(20)
        launch()
        tab("Guide")
        val spotted = compose.onAllNodes(SemanticsMatcher("spotted") { (it.config.getOrNull(SemanticsProperties.StateDescription) ?: "").startsWith("spotted") })
        assertTrue(spotted.fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodesWithText("SPOTTED", substring = true).onFirst().assertExists()
        scrollTo("Undiscovered")
        compose.onAllNodesWithText("Undiscovered").onFirst().assertExists()
        shot("20b-guide-stamps")
    }

    @Test
    fun `the review shortcut opens review, and it survives turning the phone`() {
        Seed.weeks(20)
        morning()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        scenario = ActivityScenario.launch(
            android.content.Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_SHORTCUT_REVIEW),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Quick questions on concepts", substring = true).assertExists()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Quick questions on concepts", substring = true).assertExists()
    }

    @Test
    fun `a sign-in notification opens the Google account sheet`() {
        Seed.weeks(5)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        scenario = ActivityScenario.launch(
            android.content.Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_ACCOUNT),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Connect Google account").assertExists()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `google setup help shows the exact values to register`() {
        Seed.weeks(5)
        GoogleSync.setStateForTests(
            CloudState(message = "Google doesn't know this app yet. Add an Android OAuth client for it (steps below), then tap Connect again.", needsSetup = true),
        )
        launch()
        tab("You")
        openSettings("Google account")
        compose.onNodeWithText("One-time setup (2 minutes)").performScrollTo()
        compose.onNodeWithText(GoogleSync.SHA1).assertExists()
        compose.onNodeWithText(GoogleSync.PACKAGE).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/31-google-setup-help.png")
        GoogleSync.setStateForTests(CloudState(email = "rajan@example.com", lastSyncMs = System.currentTimeMillis()))
        compose.waitForIdle()
        compose.onNodeWithText("Unlink").performScrollTo().assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/32-google-linked.png")
    }

    @Test
    fun `unlink forgets the account and offers Connect again, even when Google can't be reached`() {
        Seed.weeks(5)
        GoogleSync.setStateForTests(CloudState(email = "rajan@example.com", lastSyncMs = System.currentTimeMillis()))
        launch()
        tab("You")
        openSettings("Google account")
        compose.onAllNodesWithText("Linked to rajan@example.com").onFirst().assertExists()
        compose.onNodeWithText("Unlink").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(null, GoogleSync.state.value.email)
        compose.onAllNodesWithText("Connect Google account").onFirst().performScrollTo().assertExists()
        compose.onAllNodesWithText("Linked to", substring = true).assertCountEquals(0)
    }

    @Test
    fun `concepts from the book review carry their evidence labels`() {
        Store.settings { it.copy(onboarded = true) }
        val today = Store.today()
        Store.update { it.copy(assignments = mapOf(today to com.rajan.mindfield.core.Assignment("forced-teaming", 1))) }
        launch()
        compose.onAllNodesWithText("Forced teaming and other warning signs").onFirst().assertExists()
        compose.onAllNodesWithText("Debated").onFirst().assertExists()
        shot("46-debated-day")
    }

    @Test
    fun `myths from the second book review show as busted, with the longest new title intact`() {
        Store.settings { it.copy(onboarded = true) }
        val today = Store.today()
        Store.update { it.copy(assignments = mapOf(today to com.rajan.mindfield.core.Assignment("left-right-brain-myth", 1))) }
        launch()
        compose.onAllNodesWithText("Left-brained and right-brained people").onFirst().assertExists()
        compose.onAllNodesWithText("Busted").onFirst().assertExists()
        shot("47-busted-day")
    }

    @Test
    fun `dark mode`() {
        Seed.weeks(30)
        Store.settings { it.copy(theme = ThemeMode.DARK) }
        morning()
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
        compose.onNodeWithText("Used").performClick()
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

    @Test
    fun `a field report being written survives turning the phone, and Back asks before throwing it away`() {
        Seed.weeks(5)
        morning()
        launch()
        val c = Store.todayConcept()
        val note = "Saw it twice on the bus."
        compose.onAllNodesWithText(Mode.SPOTTED.label).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(note)
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Field report").assertExists()
        compose.onNode(hasSetTextAction() and hasText(note)).assertExists()
        back()
        compose.onNodeWithText("Discard this note?").assertExists()
        compose.onNodeWithText("Keep writing").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText(note)).assertExists()
        compose.onNodeWithText("Save to journal").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(note, Store.state.value.entriesOn(Store.today()).single { it.conceptId == c.id }.note)
    }

    @Test
    fun `a review round in progress survives a trip to another tab`() {
        Seed.weeks(40)
        launch()
        openReview()
        val answer = Seed.firstReviewAnswer()
        compose.onNodeWithText("Start review").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(answer).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("✓ Right").assertExists()
        tab("Journal")
        compose.onNodeWithText("Field journal").assertExists()
        openReview()
        compose.onNodeWithText("✓ Right").assertExists()
        compose.onAllNodesWithText("1 of ", substring = true).onFirst().assertExists()
    }
}
