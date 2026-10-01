package com.rajan.meditationtimer

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * The real service, storage, reminder and widget on Robolectric. Time is simulated, so a
 * 20-minute sit takes milliseconds and every pause, end window and bell runs exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStressTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var startId = 0

    @Before
    fun clean() {
        SessionLog.resetForTests()
        File(app.filesDir, "sessions.csv").delete()
        SessionRepository.reset()
        SessionRepository.startCounting(false)
        SessionRepository.pendingBefore = 0
    }

    @After
    fun tidy() = SessionRepository.reset()

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun begin(minutes: Int = 10, interval: Int = 0): ServiceController<MeditationService> {
        // Vibrate-only: no audio decoding needed on the JVM; bell timing is the same code path.
        MeditationService.start(app, SessionConfig(minutes * 60, 5, 10, true, interval), 0.5f, AlertMode.VIBRATE, false)
        val intent = shadowOf(app).nextStartedService
        assertNotNull("Begin must start the service", intent)
        return Robolectric.buildService(MeditationService::class.java, intent).create().startCommand(0, ++startId)
    }

    /** Delivers whatever the UI or notification just sent to the service. */
    private fun ServiceController<MeditationService>.deliver(send: (Context) -> Unit) {
        send(app)
        val intent: Intent = shadowOf(app).nextStartedService ?: return
        withIntent(intent).startCommand(0, ++startId)
    }

    private val records get() = SessionLog.get(app).records.value

    @Test
    fun `a full sit finishes on time and logs exactly once`() {
        begin(minutes = 10)
        idle(599)
        assertTrue(SessionRepository.state.value is SessionState.Running)
        idle(2)
        val done = SessionRepository.state.value as SessionState.Finished
        assertEquals(600, done.satSec)
        assertEquals(1, records.size)
        assertEquals(600, records.single().actualSec)
    }

    @Test
    fun `pause holds the clock and the finish, resume carries on where it stopped`() {
        val s = begin(minutes = 10)
        idle(120)
        s.deliver(MeditationService::pause)
        val paused = SessionRepository.state.value as SessionState.Running
        assertTrue(paused.isPaused)
        idle(3_600) // an hour paused: must not finish, must not count
        assertTrue(SessionRepository.state.value is SessionState.Running)
        s.deliver(MeditationService::resume)
        idle(479)
        assertTrue("finished early", SessionRepository.state.value is SessionState.Running)
        idle(2)
        assertEquals(600, (SessionRepository.state.value as SessionState.Finished).satSec)
        assertEquals(1, records.size)
    }

    @Test
    fun `End opens a 5-second window, keep sitting cancels it, doing nothing ends and logs`() {
        val s = begin(minutes = 20)
        idle(300)
        s.deliver(MeditationService::end)
        assertTrue((SessionRepository.state.value as SessionState.Running).isEnding)
        idle(3)
        s.deliver(MeditationService::resume) // Keep sitting
        val resumed = SessionRepository.state.value as SessionState.Running
        assertTrue(!resumed.isEnding && !resumed.isPaused)
        idle(10)
        assertTrue("window must be gone", SessionRepository.state.value is SessionState.Running)
        s.deliver(MeditationService::end)
        idle(6)
        val done = SessionRepository.state.value as SessionState.Finished
        assertTrue(done.endedEarly)
        assertTrue("sat ${done.satSec}", done.satSec in 309..311)
        assertEquals(1, records.size)
    }

    @Test
    fun `ending under a minute logs nothing`() {
        val s = begin()
        idle(20)
        s.deliver(MeditationService::endNow)
        assertTrue(SessionRepository.state.value is SessionState.Idle)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `check-in and distraction count land in the log`() {
        SessionRepository.pendingBefore = 2
        SessionRepository.startCounting(true)
        begin(minutes = 5)
        repeat(7) { SessionRepository.noticedOnce() }
        idle(301)
        val done = SessionRepository.state.value as SessionState.Finished
        assertEquals(2, done.before)
        assertEquals(7, done.noticed)
        assertEquals(listOf(2 to 7), records.map { it.before to it.noticed })
        SessionLog.get(app).annotate(done.startedAtMs, 4, "steady", after = 5)
        assertEquals(5, records.single().after)
        assertEquals(4, records.single().rating)
        // Not counting: stored as -1, not 0.
        SessionRepository.startCounting(false)
        begin(minutes = 5)
        idle(301)
        assertEquals(-1, records.last().noticed)
    }

    @Test
    fun `a thousand random presses of pause, resume, end and keep sitting never break a sit`() {
        val rnd = Random(42)
        repeat(25) { sit ->
            SessionRepository.reset()
            val before = records.size
            val s = begin(minutes = rnd.nextInt(1, 30), interval = listOf(0, 5).random(rnd))
            var guard = 0
            while (SessionRepository.state.value is SessionState.Running && guard++ < 40) {
                when (rnd.nextInt(5)) {
                    0 -> s.deliver(MeditationService::pause)
                    1 -> s.deliver(MeditationService::resume)
                    2 -> s.deliver(MeditationService::end)
                    3 -> s.deliver(MeditationService::resume)
                    else -> {}
                }
                idle(rnd.nextLong(0, 240))
                (SessionRepository.state.value as? SessionState.Running)?.let { r ->
                    val e = r.clock.elapsedAt(android.os.SystemClock.elapsedRealtime())
                    assertTrue("sit $sit: elapsed $e beyond ${r.config.durationMs}", e <= r.config.durationMs + 1_000)
                }
            }
            // Wrap up whatever state it was left in.
            if (SessionRepository.state.value is SessionState.Running) { s.deliver(MeditationService::endNow); idle(1) }
            val added = records.size - before
            assertTrue("sit $sit logged $added times", added <= 1)
            when (val st = SessionRepository.state.value) {
                is SessionState.Finished -> {
                    assertEquals(1, added)
                    assertTrue(st.satSec in MIN_LOGGED_SEC..st.config.durationSec)
                }
                SessionState.Idle -> assertEquals(0, added)
                else -> throw AssertionError("sit $sit stuck in $st")
            }
        }
    }

    @Test
    fun `the log survives concurrent writes and corrupt lines`() {
        val file = File(app.filesDir, "sessions.csv")
        file.writeText("garbage\n1,2\n" + SessionRecord(1_000, 600, 600).encode() + "\n,,,,\n")
        SessionLog.resetForTests()
        assertEquals(1, records.size)
        val threads = (0 until 8).map { t ->
            Thread { repeat(100) { i -> SessionLog.get(app).add(SessionRecord(10_000_000L * (t * 100 + i + 1), 600, 600)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(801, records.size)
        SessionLog.resetForTests()
        assertEquals("every line written must read back", 801, records.size)
        // Merging the same backup twice adds nothing the second time.
        val incoming = (1..50).map { SessionRecord(99_000_000_000L + it * 3_600_000L, 600, 600, note = "n$it") }
        assertEquals(50, SessionLog.get(app).merge(incoming))
        assertEquals(0, SessionLog.get(app).merge(incoming))
    }

    @Test
    fun `settings with awkward text survive export and import`() {
        val prefs = Prefs(app)
        prefs.reminder = Reminder(true, 6 * 60 + 45, "After tea; brush=teeth & 🧘 50% done")
        prefs.countDistractions = false
        prefs.checkIns = false
        val exported = prefs.exportSettings()
        val line = History.settingsLine(exported)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.importSettings(History.settingsFrom(line)!!)
        assertEquals(Reminder(true, 405, "After tea; brush=teeth & 🧘 50% done"), prefs.reminder)
        assertEquals(false, prefs.countDistractions)
        assertEquals(false, prefs.checkIns)
    }

    @Test
    fun `reminder is scheduled, fires a notification, and stays quiet on days you sat`() {
        val prefs = Prefs(app)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        prefs.reminder = Reminder(true, 7 * 60, "after morning tea")
        ReminderScheduler.schedule(app)
        val next = alarms.peekNextScheduledAlarm()
        assertNotNull(next)
        val expected = Reminder(true, 7 * 60, "").nextAt(ZonedDateTime.now(ZoneId.systemDefault())).toInstant().toEpochMilli()
        assertTrue(kotlin.math.abs(next!!.triggerAtMs - expected) < 60_000)

        ReminderScheduler.fire(app)
        assertEquals(1, notifications.allNotifications.size)
        assertEquals("After morning tea", notifications.allNotifications.single().extras.getCharSequence("android.title").toString())

        // A sit today: the nudge is withdrawn, and no new one is posted.
        SessionLog.get(app).add(SessionRecord(java.time.Instant.now().toEpochMilli(), 600, 600))
        assertEquals(0, notifications.allNotifications.size)
        ReminderScheduler.fire(app)
        assertEquals(0, notifications.allNotifications.size)

        prefs.reminder = prefs.reminder.copy(enabled = false)
        ReminderScheduler.schedule(app)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `widget shows this week and updates after a sit`() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(SitWidget::class.java, R.layout.widget_sit)
        SitWidget.refresh(app)
        fun week() = manager.getViewFor(id).findViewById<TextView>(R.id.widget_week).text.toString()
        fun button() = manager.getViewFor(id).findViewById<TextView>(R.id.widget_start).text.toString()
        assertEquals("0 days this week", week())
        assertTrue(button().startsWith("Sit · "))
        SessionLog.get(app).add(SessionRecord(java.time.Instant.now().toEpochMilli(), 600, 600))
        assertEquals("1 day this week", week())
    }
}
