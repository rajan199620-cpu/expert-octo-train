package com.rajan.meditationtimer

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.LocalDate
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
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @Test
    fun `sit screen, practice tools, and the check-in on Begin`() {
        seed()
        launch()
        shot("01-sit")
        compose.onNodeWithText("Practice tools").performScrollTo()
        compose.onNodeWithText("Count distractions").assertExists()
        shot("02-practice-tools")

        compose.onNodeWithText("Begin", substring = true).performClick()
        compose.onNodeWithText("How do you feel right now?").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/03-check-in.png")
        compose.onNodeWithText("Tense").performClick()
        compose.waitForIdle()
        assertEquals(1, SessionRepository.pendingBefore)
        assertTrue(SessionRepository.counting)
        val started = shadowOf(app).nextStartedService
        assertEquals(MeditationService::class.java.name, started.component?.className)
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
        compose.onNodeWithText("And how do you feel now?").assertExists()
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
        manager.getViewFor(id).captureRoboImage("build/outputs/roborazzi/12-widget.png")
    }
}
