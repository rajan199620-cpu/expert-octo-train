package com.rajan.meditationtimer

import android.Manifest
import android.content.pm.PackageManager
import android.app.Application
import android.graphics.Color
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import android.appwidget.AppWidgetManager
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.time.ZoneId
import kotlin.random.Random

/**
 * Drives the real app screens on the JVM and saves screenshots (CI commits them to
 * meditation-timer/docs/screenshots) so the design can be checked without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Before
    fun clean() {
        SessionLog.resetForTests()
        File(app.filesDir, "sessions.csv").delete()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        // Most tests start on the Sit screen; the welcome's and the reading's own tests undo these.
        Prefs(app).welcomeDone = true
        Prefs(app).readingSeenOn = LocalDate.now().toString()
        // Notifications allowed, as on most phones; the test of the question itself denies them.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SessionRepository.reset()
        SessionRepository.startCounting(false)
        app.getSharedPreferences("google_backup", 0).edit().clear().commit()
        GoogleBackup.resetForTests()
    }

    @After
    fun close() {
        scenario?.close()
        SessionRepository.reset()
    }

    /** About six weeks of practice: most mornings, check-ins, counts, a few notes. */
    private fun seed(days: Int = 40, perDay: Int = 1) {
        val rnd = Random(7)
        val today = LocalDate.now(zone)
        val all = ArrayList<SessionRecord>()
        for (d in days downTo 0) {
            if (d % 6 == 5) continue
            repeat(perDay) { k ->
                val before = rnd.nextInt(1, 4)
                all.add(
                    SessionRecord(
                        today.minusDays(d.toLong()).atTime(6 + k, 50).atZone(zone).toInstant().toEpochMilli(),
                        plannedSec = 1200,
                        actualSec = if (d % 9 == 4) 780 else 1200,
                        rating = rnd.nextInt(2, 6),
                        note = if (d % 7 == 0) "Noticed planning thoughts; came back each time." else "",
                        before = before,
                        after = (before + rnd.nextInt(0, 3)).coerceAtMost(5),
                        noticed = (12 - d / 5 + rnd.nextInt(-2, 3)).coerceAtLeast(1),
                    ),
                )
            }
        }
        SessionLog.get(app).merge(all)
    }

    private fun launch() {
        // A phone is in touch mode; without it the first control takes keyboard focus and the
        // screen opens scrolled to it, which no user would see.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `sit screen, practice tools, and the check-in on Begin`() {
        seed()
        launch()
        shot("01-sit")
        // Set-once settings are rows that say what's set; each opens only its own sheet.
        compose.onNodeWithText("Count distractions").assertDoesNotExist()
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("Count distractions").assertExists()
        compose.onNodeWithText("Weekly goal").assertExists()
        compose.onNodeWithText("Opening bell").assertDoesNotExist() // only this topic, not everything
        captureScreenRoboImage("build/outputs/roborazzi/02-practice-tools.png")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Count distractions").assertDoesNotExist()

        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.onNodeWithText("How do you feel right now?").assertExists()
        // Choosing a feeling only selects it: nothing starts until Begin is tapped.
        compose.onNodeWithText("Tense").performClick()
        compose.waitForIdle()
        assertNull("a feeling must not start the sit", shadowOf(app).nextStartedService)
        compose.onNodeWithText("How do you feel right now?").assertExists()
        compose.onNodeWithText("Tense").assertIsSelected()
        // Changing your mind: tap again to clear, then pick another.
        compose.onNodeWithText("Tense").performClick()
        compose.onNodeWithText("Tense").assertIsNotSelected()
        compose.onNodeWithText("Calm").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/03-check-in.png")
        assertNull(shadowOf(app).nextStartedService)
        compose.onNode(hasText("Begin  ·", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(5, SessionRepository.pendingBefore)
        assertFalse("counting is off unless switched on", SessionRepository.counting)
        val started = shadowOf(app).nextStartedService
        assertEquals(MeditationService::class.java.name, started.component?.className)
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back from another tab returns to Sit, and back during a breathing exercise stops it`() {
        launch()
        compose.onAllNodesWithText("History").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·", substring = true).assertDoesNotExist()
        back()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
        // Breathe: Back during an exercise stops it and stays on Breathe, like the Stop button.
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").performScrollTo().performClick()
        // The exercise draws every frame, so the clock is moved by hand while it runs.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Stop").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Start").assertExists()
        compose.mainClock.autoAdvance = true
        // And from Breathe itself, Back goes to Sit rather than out of the app.
        back()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
        scenario!!.onActivity { assertFalse("Back on Sit is the only way out", it.isFinishing) }
    }

    @Test
    fun `the notification question comes before the first sit, never over it, and only once`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).checkIns = false
        launch()
        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.waitForIdle()
        // Asked first: the sit waits for the answer instead of starting under the dialog.
        assertNull(shadowOf(app).nextStartedService)
        var asked: Any? = null
        scenario!!.onActivity { activity ->
            val request = shadowOf(activity).lastRequestedPermission
            asked = request
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
            // "Don't allow" still starts the sit: it runs either way, just without the lock-screen timer.
            @Suppress("DEPRECATION")
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        compose.waitForIdle()
        assertEquals(MeditationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertTrue(Prefs(app).sitNotificationsAsked)
        // The next sit starts at once: no asking again.
        SessionRepository.reset()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(MeditationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        scenario!!.onActivity { assertSame("no second request", asked, shadowOf(it).lastRequestedPermission) }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `first run welcome asks two questions, teaches how to sit, then begins the first sit`() {
        Prefs(app).welcomeDone = false
        Prefs(app).readingSeenOn = null // a first-ever open: the reading is due too, but the welcome comes first
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        launch()
        compose.onNodeWithText("Have you meditated before?").assertExists()
        compose.onNodeWithText("Breathe").assertDoesNotExist() // no tab bar until it's done
        shot("19-welcome-experience")
        compose.onNodeWithText("I'm new to this").performClick()
        compose.waitForIdle()
        assertEquals(5 * 60, Prefs(app).timerConfig.durationSec)
        assertEquals(3, Prefs(app).weeklyGoal)
        compose.onNodeWithText("When could you sit most days?").assertExists()
        shot("20-welcome-time")
        // Back keeps the answer; answering again moves on.
        compose.onNodeWithText("‹ Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("I'm new to this").assertIsSelected()
        compose.onNodeWithText("I'm new to this").performClick()
        compose.onNodeWithText("Before bed").performClick()
        compose.waitForIdle()
        assertEquals(Reminder(true, 21 * 60 + 30, "Before bed"), Prefs(app).reminder)
        compose.onNodeWithText("Come back, again and again").assertExists()
        shot("21-welcome-how-to-sit")
        assertFalse(Prefs(app).welcomeDone)
        compose.onNodeWithText("Begin my first sit  ·  5 min").performClick()
        compose.waitForIdle()
        assertTrue(Prefs(app).welcomeDone)
        // The welcome already taught how to sit, so no reading piles on top of it today.
        assertEquals(LocalDate.now().toString(), Prefs(app).readingSeenOn)
        compose.onNodeWithText("Today's research").assertDoesNotExist()
        // The same Begin as always: the check-in, and nothing starts until Begin is tapped.
        compose.onNodeWithText("How do you feel right now?").assertExists()
        assertNull(shadowOf(app).nextStartedService)
        compose.onNode(hasText("Begin  ·  5 min") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(MeditationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals(0, SessionRepository.pendingBefore)
    }

    @Test
    fun `skip keeps the usual settings, and someone who has sat before never sees the welcome`() {
        Prefs(app).welcomeDone = false
        launch()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·  20 min").assertExists()
        assertTrue(Prefs(app).welcomeDone)
        assertFalse(Prefs(app).reminder.enabled)
        scenario?.close()
        // Updating from a version without the welcome: history, but no welcome flag.
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        Prefs(app).readingSeenOn = LocalDate.now().toString()
        seed(days = 3)
        launch()
        compose.onNodeWithText("Have you meditated before?").assertDoesNotExist()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
        scenario?.close()
        // Or no history, but settings saved by a Begin.
        SessionLog.resetForTests()
        File(app.filesDir, "sessions.csv").delete()
        app.getSharedPreferences("settings", 0).edit().clear().putInt("duration_min", 15).commit()
        Prefs(app).readingSeenOn = LocalDate.now().toString()
        launch()
        compose.onNodeWithText("Have you meditated before?").assertDoesNotExist()
        compose.onNodeWithText("Begin  ·  15 min").assertExists()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `guide opens one topic at a time and can bring the welcome back`() {
        launch()
        compose.onNodeWithText("Guide").performScrollTo().performClick()
        compose.onNodeWithText("A sit, step by step").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/22-guide.png")
        compose.onNodeWithText("seconds to change your mind", substring = true).assertDoesNotExist()
        compose.onNodeWithText("A sit, step by step").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("seconds to change your mind", substring = true).assertExists()
        // Opening another closes the first, so the sheet never becomes a wall of text.
        compose.onNodeWithText("Bells, sound and silence").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("seconds to change your mind", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Vibrate only", substring = true).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/23-guide-topic.png")
        // The guide names the cue options exactly as the Sound sheet does.
        val text = Guide.topics(AutoBackup.LOCATION).flatMap { it.points }.joinToString("\n")
        for (mode in AlertMode.entries) assertTrue(mode.label, text.contains(mode.label))
        compose.onNodeWithText("Show the welcome again").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Have you meditated before?").assertExists()
        // Skipping a replay changes nothing.
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·  20 min").assertExists()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `the day's reading opens first - research, Next, a common problem, then the sit`() {
        Prefs(app).readingSeenOn = null
        seed()
        launch()
        val lesson = Principles.forLesson(Principles.lessonIndex(SessionLog.get(app).records.value.map { it.day(zone) }.toSet(), LocalDate.now()))
        compose.onNodeWithText("Today's research").assertExists()
        compose.onNodeWithText(lesson.title).assertExists()
        // The research itself is on the page, not folded away behind a button.
        compose.onNodeWithText(lesson.finding).performScrollTo().assertExists()
        compose.onNodeWithText(lesson.source).assertExists()
        compose.onNodeWithText("Breathe").assertDoesNotExist() // nothing else until it's read or skipped
        compose.onNodeWithText("Begin  ·", substring = true).assertDoesNotExist()
        shot("24-reading-research")
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        val problem = Problems.forIndex(0)
        compose.onNodeWithText("A common problem").assertExists()
        compose.onNodeWithText(problem.title).assertExists()
        compose.onNodeWithText(problem.answer).assertExists()
        shot("25-reading-problem")
        // Back goes to the research; Next again, then on to the sit.
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("Today's research").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
        compose.onNodeWithText("Breathe").assertExists() // the tab bar is back
        assertEquals(LocalDate.now().toString(), Prefs(app).readingSeenOn)
        assertNull("reading starts nothing", shadowOf(app).nextStartedService)
        // Opened again the same day: straight to the Sit screen.
        scenario?.close()
        launch()
        compose.onNodeWithText("Today's research").assertDoesNotExist()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
    }

    @Test
    fun `the reading returns the next day with the next problem, can be skipped, and switched off`() {
        val prefs = Prefs(app)
        prefs.readingSeenOn = LocalDate.now().minusDays(1).toString()
        prefs.readingProblem = 0
        prefs.readingProblemDay = LocalDate.now().minusDays(1).toString()
        launch()
        compose.onNodeWithText("Today's research").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText(Problems.forIndex(1).title).assertExists()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
        assertEquals(LocalDate.now().toString(), prefs.readingSeenOn)
        scenario?.close()
        // Switched off in Practice tools: never shown, even on a new day.
        prefs.readingSeenOn = LocalDate.now().minusDays(1).toString()
        launch()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("Today's reading when I open the app").performClick()
        assertFalse(prefs.dailyReading)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        scenario?.close()
        prefs.readingSeenOn = LocalDate.now().minusDays(2).toString()
        launch()
        compose.onNodeWithText("Today's research").assertDoesNotExist()
        compose.onNodeWithText("Begin  ·", substring = true).assertExists()
    }

    @Test
    fun `a one-tap sit from a shortcut isn't held up by the reading`() {
        Prefs(app).readingSeenOn = null
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        scenario = ActivityScenario.launch(
            android.content.Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK_SIT),
        )
        compose.waitForIdle()
        assertEquals(MeditationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        compose.onNodeWithText("Today's research").assertDoesNotExist()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `settle-in breaths pace the start, then the sit proper begins`() {
        launch()
        compose.mainClock.autoAdvance = false
        SessionRepository.startCounting(true)
        val config = SessionConfig(1200, 5, 10, true, 0, settleSec = 60)
        // 12 s in: the second breath, breathing in, 2 seconds of it left.
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 12_000), config))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Settle in  ·  breath 2 of 6").assertExists()
        compose.onNodeWithText("Breathe in", substring = true).assertExists()
        shot("26-settle-in")
        // Taps don't count as noticing while settling in.
        compose.onNodeWithText("Settle in  ·  breath 2 of 6").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, SessionRepository.noticed.value)
        // After the settle-in: the usual screen, and counting is on.
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 65_000), config))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Settle in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Today’s focus", substring = true).performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, SessionRepository.noticed.value)
    }

    @Test
    fun `bells and breaths sheet sets the settle-in, and off brings back the opening delay`() {
        launch()
        compose.onNodeWithText("Bells & breaths").performScrollTo().performClick()
        compose.onNodeWithText("Settle-in breaths").assertExists()
        // On by default (a minute), so the opening bell simply follows it.
        compose.onNodeWithText("Rings as the settle-in breaths end", substring = true).assertExists()
        compose.onAllNodesWithText("Off").onFirst().performClick() // the settle-in row comes first
        compose.onNodeWithText("Rings this long after you tap Begin").assertExists()
        compose.onNodeWithText("2 min").performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(120, Prefs(app).timerConfig.settleSec)
        compose.onNodeWithText("Settle-in 2 min", substring = true).assertExists()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `history says where you stand - your level, your run and your hours against the research, sources a tap away`() {
        seed()
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Where you stand").assertExists()
        // The seeded history: 459 minutes over the last 28 days, 113 of them this week.
        compose.onNodeWithText("16 min a day").assertExists()
        compose.onNodeWithText("→ Steady").assertExists()
        compose.onNodeWithText("this week 16 min a day", substring = true).assertExists()
        // 41 days unbroken by a full week off, 665 minutes in all.
        compose.onNodeWithText("5 weeks in, at 16 min a day: above the 13 a day", substring = true).assertExists()
        compose.onNodeWithText("3 weeks to go to match it", substring = true).assertExists()
        compose.onNodeWithText("11 hours in all. Next: 22.6 hours", substring = true).assertExists()
        compose.onNodeWithText("You sat on 24 of the last 28 days", substring = true).assertExists()
        compose.onNodeWithContentDescription("Your 4-week level over the last", substring = true).assertExists()
        compose.onNodeWithText("Basso et al.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("The research behind it", substring = true).performClick()
        for (source in listOf("Basso et al.", "Hölzel et al.", "Parsons et al.", "Bowles & Van Dam", "Baer et al.", "Lally et al.", "Vieten et al.", "Pew Research Center", "Adams et al.")) {
            compose.onNodeWithText(source, substring = true).performScrollTo().assertExists()
        }
        captureScreenRoboImage("build/outputs/roborazzi/27-history-standing.png")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun `guide lists the common problems, each answering whether to act`() {
        launch()
        compose.onNodeWithText("Guide").performScrollTo().performClick()
        compose.onNodeWithText("Common problems").performClick()
        compose.onNodeWithText("An itch").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Should you scratch it?").assertExists()
        compose.onNodeWithText("Bowen & Marlatt", substring = true).performScrollTo().assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/28-guide-problems.png")
        compose.onNodeWithText("An idea you want to write down").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Should you stop to write it?").assertExists()
        compose.onNodeWithText("Should you scratch it?").assertDoesNotExist() // one open at a time
    }

    @Test
    fun `not now closes the check-in without starting, and skipping the feeling still begins`() {
        launch()
        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("How do you feel right now?").assertDoesNotExist()
        assertNull(shadowOf(app).nextStartedService)
        // No feeling chosen is fine: Begin starts with the check-in recorded as skipped.
        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.onNode(hasText("Begin  ·", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(0, SessionRepository.pendingBefore)
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `background sound can be chosen, previewed and shows in the summary`() {
        launch()
        compose.onNodeWithText("Sound & stillness").performScrollTo().performClick()
        compose.onNodeWithText("Background sound").performScrollTo()
        compose.onNodeWithText("Listen").assertDoesNotExist() // off: no volume or preview
        compose.onNodeWithText("Rain").performScrollTo().performClick()
        assertEquals(Ambience.RAIN, Prefs(app).ambience)
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Stop").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/15-background-sound.png")
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Birds").performScrollTo().performClick()
        assertEquals(Ambience.BIRDS, Prefs(app).ambience)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("birds", substring = true).assertExists()
        compose.onNodeWithText("Background sound").assertDoesNotExist()
    }

    @Test
    fun `screen readers hear tabs as tabs and which is selected, and what minus and plus do`() {
        launch()
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(hasText("Sit") and tab).assertIsSelected()
        compose.onNode(hasText("Breathe") and tab).assertIsNotSelected()
        compose.onNode(hasText("Breathe") and tab).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Breathe") and tab).assertIsSelected()
        compose.onNode(hasText("Sit") and tab).assertIsNotSelected()
        compose.onNode(hasText("Sit") and tab).performClick()
        compose.waitForIdle()
        // The − and + around the dial say what they do, not "minus sign".
        compose.onNodeWithContentDescription("One minute longer").performClick()
        compose.onNodeWithText("Begin  ·  21 min").assertExists()
        compose.onNodeWithContentDescription("One minute shorter").performClick()
        compose.onNodeWithContentDescription("One minute shorter").performClick()
        compose.onNodeWithText("Begin  ·  19 min").assertExists()
        compose.onNodeWithText("−").assertDoesNotExist()
    }

    @Test
    fun `counting is optional - off unless chosen, then taps and volume keys do nothing and the screen may sleep`() {
        assertFalse("off by default", Prefs(app).countDistractions)
        Prefs(app).checkIns = false
        launch()
        compose.onNodeWithText("Begin  ·", substring = true).performClick()
        compose.waitForIdle()
        assertFalse(SessionRepository.counting)
        // The running screen: no counting hint, and a tap or a volume key counts nothing.
        compose.mainClock.autoAdvance = false
        val config = SessionConfig(1200, 5, 10, true, 0)
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 420_000), config))
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Mind wandered?", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Close your eyes. The bell will call you back.").assertExists()
        compose.onNodeWithText("Today’s focus", substring = true).performClick()
        compose.mainClock.advanceTimeBy(100)
        scenario!!.onActivity { it.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) }
        assertEquals(0, SessionRepository.noticed.value)
        assertNull("volume keys stay volume keys", VolumeKeys.handler)
        // Nothing holds the screen on: it sleeps like any other while you sit.
        scenario!!.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        // Turned on in Practice tools, the next sit counts.
        SessionRepository.reset()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("Count distractions").performClick()
        compose.waitForIdle()
        assertTrue(Prefs(app).countDistractions)
    }

    @Test
    fun `running screen counts taps and volume keys, not faster than twice a second`() {
        launch()
        compose.mainClock.autoAdvance = false
        SessionRepository.startCounting(true)
        val config = SessionConfig(1200, 5, 10, true, 0)
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 420_000), config))
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Mind wandered?", substring = true).assertExists()
        shot("04-running-counting")

        compose.onNodeWithText("Today’s focus", substring = true).performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, SessionRepository.noticed.value)
        // A double-tap within half a second counts once.
        compose.onNodeWithText("Today’s focus", substring = true).performClick()
        assertEquals(1, SessionRepository.noticed.value)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        scenario!!.onActivity { it.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) }
        assertEquals(2, SessionRepository.noticed.value)

        // Paused: taps don't count.
        SessionRepository.update(SessionState.Running(SessionClock(SystemClock.elapsedRealtime() - 420_000).pause(SystemClock.elapsedRealtime()), config))
        compose.mainClock.advanceTimeBy(1_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        scenario!!.onActivity { it.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) }
        assertEquals(2, SessionRepository.noticed.value)
        shot("05-running-paused")
    }

    @Test
    fun `finish screen shows the count and asks the after check-in, which is saved`() {
        launch()
        val start = System.currentTimeMillis()
        SessionLog.get(app).add(SessionRecord(start, 1200, 1200, before = 1, noticed = 7))
        SessionRepository.finished(SessionConfig(1200, 5, 10, true, 0), start, 1200, before = 1, noticed = 7)
        compose.waitForIdle()
        compose.onNodeWithText("You caught the mind wandering 7 times", substring = true).assertExists()
        compose.onNodeWithText("Before you stand", substring = true).assertExists()
        compose.onNodeWithText("How do you feel now?").assertExists()
        compose.onNodeWithText("How was the sit itself?").assertExists()
        shot("06-finished")
        // The after check-in comes first on this screen; its "Calm" is the first one.
        compose.onAllNodesWithText("Calm").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(5, SessionLog.get(app).records.value.single().after)
    }

    @Test
    fun `history shows the week, what a sit changes, noticing and the log`() {
        seed()
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        // Overview: the week against the goal, the month, and two headline numbers.
        compose.onNodeWithText("of 5 days this week", substring = true).assertExists()
        compose.onNodeWithText("in review", substring = true).assertExists()
        compose.onNodeWithText("What a sit changes").assertDoesNotExist() // that's in Trends
        shot("07-history-overview")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("calmer after a sit", substring = true))
        shot("08-history-overview-bottom")
        // A highlight opens Trends, at the top even though Overview was scrolled down to reach it.
        compose.onNodeWithText("calmer after a sit", substring = true).performClick()
        compose.onNodeWithText("What a sit changes").assertExists()
        shot("09-history-trends")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Catching the wandering mind"))
        shot("10-history-trends-bottom")
        compose.onNodeWithText("Sessions").performClick()
        shot("11-history-sessions")
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("Google account").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/12-history-backup.png")
    }

    @Test
    fun `disconnecting google backup asks first and deletes nothing`() {
        seed(days = 3)
        val sits = SessionLog.get(app).records.value.size
        app.getSharedPreferences("google_backup", 0).edit().putString("email", "me@example.com").commit()
        // Seeding saved the log, which already loaded the (then empty) account: load it afresh.
        GoogleBackup.resetForTests()
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("me@example.com").assertExists()
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
        compose.onNodeWithText("Nothing is deleted", substring = true).assertExists()
        // Changed their mind: still connected.
        compose.onNodeWithText("Keep backing up").performClick()
        compose.onNodeWithText("me@example.com").assertExists()
        assertEquals("me@example.com", app.getSharedPreferences("google_backup", 0).getString("email", null))
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
        compose.onNodeWithText("Yes, disconnect").performClick()
        compose.onNodeWithText("Connect Google account").assertExists()
        assertNull(app.getSharedPreferences("google_backup", 0).getString("email", null))
        assertEquals("the sits on this phone stay", sits, SessionLog.get(app).records.value.size)
    }

    @Test
    fun `weekly goal set in practice tools shows in history and the widget line`() {
        seed(days = 10)
        launch()
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("3 days").performClick()
        assertEquals(3, Prefs(app).weeklyGoal)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("3 days a week", substring = true).assertExists() // the row's summary
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("of 3 days this week", substring = true).assertExists()
        // Off: back to a plain count, no goal line.
        compose.onAllNodesWithText("Sit").onLast().performClick()
        compose.onNodeWithText("Practice tools").performScrollTo().performClick()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("of 3 days", substring = true).assertDoesNotExist()
        compose.onNodeWithText("to go", substring = true).assertDoesNotExist()
    }

    @Test
    fun `nadi shodhana guides each nostril in turn and bhramari explains itself`() {
        launch()
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bhramari").performScrollTo().performClick()
        compose.onNodeWithText("Evidence:", substring = true).assertExists()
        compose.onNodeWithText("Nadi Shodhana").performScrollTo().performClick()
        compose.onNodeWithText("No breath-holding", substring = true).assertExists()
        shot("16-breathe-nadi")
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("In · left nostril").assertExists()
        shot("17-breathe-session")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Out · right nostril").assertExists()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_500))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("In · right nostril").assertExists()
    }

    @Test
    fun `the breathe tab offers the breath sound, taps or both, and remembers the choice`() {
        launch()
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        // The breath sound unless chosen otherwise.
        compose.onNodeWithText("breathe in with the sound of the in-breath", substring = true).assertExists()
        compose.onNodeWithText("Breath sound").performScrollTo().assertIsSelected()
        shot("29-breathe-cue")
        compose.onNodeWithText("Vibration").performScrollTo().performClick()
        compose.onNodeWithText("one tap means breathe in", substring = true).assertExists()
        while (shadowOf(app).nextStartedService != null) Unit
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        assertEquals("VIBRATION", shadowOf(app).nextStartedService?.getStringExtra("cue"))
        assertEquals(BreathCue.VIBRATION, Prefs(app).breathCue)
        compose.onNodeWithText("Stop").performClick()
        compose.mainClock.advanceTimeBy(500)
        // The same choice paces the settle-in breaths of a sit.
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithText("Sit").onLast().performClick()
        compose.onNodeWithText("Bells & breaths").performScrollTo().performClick()
        compose.onNodeWithText("Breath cue").assertExists()
        compose.onNodeWithText("Vibration").assertIsSelected()
        compose.onNodeWithText("Both").performClick()
        assertEquals(BreathCue.BOTH, Prefs(app).breathCue)
    }

    @Test
    fun `the breathe screen hands the taps to the service, which outlives a locked screen but not Stop`() {
        launch()
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Box").performScrollTo().performClick()
        while (shadowOf(app).nextStartedService != null) Unit
        compose.onNodeWithText("Start").performScrollTo().performClick()
        // The orb redraws every frame, so time is stepped by hand from here.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        val started = shadowOf(app).nextStartedService
        assertEquals(BreathService::class.java.name, started?.component?.className)
        assertEquals("Box", started?.getStringExtra("pattern"))
        // Locking the phone stops the activity but must leave the taps running.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertNull(shadowOf(app).nextStoppedService)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Stop").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(BreathService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        compose.onNodeWithText("Start").assertExists()

        // Stopped from the notification: the screen closes the exercise too.
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        val intent = shadowOf(app).nextStartedService!!
        val service = Robolectric.buildService(BreathService::class.java, intent).create().startCommand(0, 1)
        val stop = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications.last { it.channelId == "breathe" }.actions.single()
        service.withIntent(shadowOf(stop.actionIntent).savedIntent).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Start").assertExists()
    }

    @Test
    fun `the attention check takes a lost count, by holding volume-down or on screen, without marking the next round wrong`() {
        // A three-second check: simulating five minutes of a live screen overwhelms Robolectric.
        checkLengthMs = 3_000
        try {
            attentionCheckWithRestarts()
        } finally {
            checkLengthMs = 5 * 60_000L
        }
    }

    private fun attentionCheckWithRestarts() {
        launch()
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start a 5-minute check").performScrollTo().performClick()
        // The check's clock ticks forever, so time is stepped by hand from here.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        val (down, up) = KeyEvent.KEYCODE_VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_UP
        fun key(code: Int, repeat: Int = 0) = scenario!!.onActivity { it.onKeyDown(code, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat)) }
        repeat(8) { key(down) }
        key(up)
        // Lost count on breath 4: volume-down held (its first press, then the repeats), then a clean round.
        repeat(4) { key(down) }
        for (r in 1..VolumeKeys.HOLD_REPEATS + 5) key(down, r)
        repeat(8) { key(down) }
        key(up)
        compose.onNodeWithText("Lost count — back to 1").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(BreathCountResult(2, 2), Prefs(app).breathChecks.single().result)
        compose.onNodeWithText("100% · 2 of 2 rounds exact · 2 restarts").assertExists()
    }

    @Test
    fun `an exercise that ends with the phone locked gets its bell from the service, and the screen leaves it ringing`() {
        launch()
        compose.onAllNodesWithText("Breathe").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Box").performScrollTo().performClick()
        compose.onNodeWithText("1 min").performScrollTo().performClick()
        while (shadowOf(app).nextStartedService != null) Unit
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        val intent = shadowOf(app).nextStartedService!!
        val startedAt = intent.getLongExtra("started_at", 0)
        Robolectric.buildService(BreathService::class.java, intent).create().startCommand(0, 1)
        // Locked through the end: 1 minute of box is 4 whole breaths, 64 s.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(70))
        assertEquals("the service rang the closing bell on time", startedAt, BreathService.ended.value)
        // Unlocked later: the exercise closes without a second, late bell, and nothing is stopped
        // under the bell still ringing out.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Nicely done", substring = true).assertExists()
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun `a note from a week ago comes back on the sit screen and can be put away until tomorrow`() {
        val weekAgo = LocalDate.now(zone).minusWeeks(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        SessionLog.get(app).merge(listOf(SessionRecord(weekAgo, 1200, 1200, rating = 4, note = "Breath felt wide today")))
        launch()
        compose.onNodeWithText("A week ago today").assertExists()
        compose.onNodeWithText("“Breath felt wide today”").performScrollTo()
        shot("13-on-this-day")
        compose.onAllNodesWithText("Hide").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("A week ago today").assertDoesNotExist()
        assertEquals(LocalDate.now(zone).toString(), Prefs(app).memoryHiddenOn)
        // Still hidden after the screen is rebuilt the same day.
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("A week ago today").assertDoesNotExist()
    }

    @Test
    fun `month in review opens on last month and steps back through months with sits`() {
        seed(days = 75)
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        val now = YearMonth.now(zone)
        fun title(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + (if (m.year == now.year) "" else " ${m.year}")
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("in review", substring = true))
        compose.onNodeWithText("${title(now.minusMonths(1))} in review").assertExists()
        compose.onNodeWithText("Days sat").assertExists()
        compose.onNodeWithText("Best week").assertExists()
        shot("14-month-review")
        compose.onNodeWithText("‹").performClick()
        compose.onNodeWithText("${title(now.minusMonths(2))} in review").assertExists()
        compose.onNodeWithText("›").performClick()
        compose.onNodeWithText("›").performClick()
        compose.onNodeWithText("${title(now)} so far").assertExists()
    }

    @Test
    fun `a sit can be deleted from the log after saying so`() {
        val zone = ZoneId.systemDefault()
        val keep = LocalDate.now(zone).minusDays(1).atTime(6, 10).atZone(zone).toInstant().toEpochMilli()
        val mistake = LocalDate.now(zone).minusDays(1).atTime(21, 40).atZone(zone).toInstant().toEpochMilli()
        SessionLog.get(app).merge(listOf(SessionRecord(keep, 1200, 1200), SessionRecord(mistake, 1200, 60)))
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.onNodeWithText("Sessions").performClick()
        compose.onNodeWithText("9:40", substring = true).performClick()
        compose.onNodeWithText("Delete this sit?", substring = true).assertExists()
        // Keep: nothing happens.
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Delete this sit?", substring = true).assertDoesNotExist()
        assertEquals(2, SessionLog.get(app).records.value.size)
        compose.onNodeWithText("9:40", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("9:40", substring = true).assertDoesNotExist()
        compose.onNodeWithText("6:10", substring = true).assertExists()
        assertEquals(listOf(keep), SessionLog.get(app).records.value.map { it.startedAtMs })
    }

    @Test
    fun `history with five years of sits still opens and scrolls to the end`() {
        seed(days = 5 * 365, perDay = 2)
        val started = System.nanoTime()
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("in review", substring = true))
        compose.onNodeWithText("Trends").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Catching the wandering mind"))
        compose.onNodeWithText("Sessions").performClick()
        val list = compose.onNode(hasScrollToNodeAction())
        // The log shows recent days first; older ones load on request instead of all at once.
        list.performScrollToNode(hasText("Show earlier days", substring = true))
        compose.onNodeWithText("Show earlier days", substring = true).performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("version", substring = true).performScrollTo()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 60_000)
    }

    @Test
    fun `home-screen widget`() {
        seed(days = 3)
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(SitWidget::class.java, R.layout.widget_sit)
        SitWidget.refresh(app)
        // Host it in a window at a typical 4x2 home-screen size so it lays out as on a launcher.
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = host.resources.displayMetrics.density
        val frame = FrameLayout(host).apply { setBackgroundColor(Color.rgb(28, 32, 40)) }
        val widget = manager.getViewFor(id)
        frame.addView(widget, FrameLayout.LayoutParams((340 * density).toInt(), (150 * density).toInt()))
        host.setContentView(frame)
        shadowOf(Looper.getMainLooper()).idle()
        widget.captureRoboImage("build/outputs/roborazzi/18-widget.png")
    }
}
