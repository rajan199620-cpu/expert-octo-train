package com.rajan.meditationtimer

import android.app.Application
import android.graphics.Color
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import android.appwidget.AppWidgetManager
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        SessionRepository.reset()
        SessionRepository.startCounting(false)
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
        // Set-once settings start folded into one line; "Change" opens them.
        compose.onNodeWithText("Practice tools").assertDoesNotExist()
        compose.onNodeWithText("Change").performScrollTo().performClick()
        compose.onNodeWithText("Practice tools").performScrollTo()
        compose.onNodeWithText("Count distractions").assertExists()
        shot("02-practice-tools")

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
        assertTrue(SessionRepository.counting)
        val started = shadowOf(app).nextStartedService
        assertEquals(MeditationService::class.java.name, started.component?.className)
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
        compose.onNodeWithText("Change").performScrollTo().performClick()
        compose.onNodeWithText("Background sound").performScrollTo()
        compose.onNodeWithText("Listen").assertDoesNotExist() // off: no volume or preview
        compose.onNodeWithText("Rain").performScrollTo().performClick()
        assertEquals(Ambience.RAIN, Prefs(app).ambience)
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Stop").assertExists()
        shot("15-background-sound")
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Birds").performScrollTo().performClick()
        assertEquals(Ambience.BIRDS, Prefs(app).ambience)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("birds", substring = true).assertExists()
        compose.onNodeWithText("Background sound").assertDoesNotExist()
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
        shot("07-history-top")
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("What a sit changes"))
        shot("08-history-check-ins")
        list.performScrollToNode(hasText("Catching the wandering mind"))
        shot("09-history-noticing")
        list.performScrollToNode(hasText("Sessions"))
        shot("10-history-log")
        list.performScrollToNode(hasText("Google account"))
        shot("11-history-google")
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
    fun `history with five years of sits still opens and scrolls to the end`() {
        seed(days = 5 * 365, perDay = 2)
        val started = System.nanoTime()
        launch()
        compose.onAllNodesWithText("History").onLast().performClick()
        compose.waitForIdle()
        val list = compose.onNode(hasScrollToNodeAction())
        // The log shows recent days first; older ones load on request instead of all at once.
        list.performScrollToNode(hasText("Show earlier days", substring = true))
        compose.onNodeWithText("Show earlier days", substring = true).performClick()
        list.performScrollToNode(hasText("version", substring = true))
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
        widget.captureRoboImage("build/outputs/roborazzi/12-widget.png")
    }
}
