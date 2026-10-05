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
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
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
    fun `settle-in breaths tap once in and twice out, hold while paused, rejoin on resume, and never change the sit`() {
        MeditationService.start(app, SessionConfig(10 * 60, 5, 10, true, 0, settleSec = 60), 0.5f, AlertMode.VIBRATE, false)
        Chime.breathTicks.set(0)
        val s = Robolectric.buildService(MeditationService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(1)
        assertEquals("a cue at Begin, to breathe in", 1, Chime.breathTicks.get())
        assertEquals(BreathBuzz.Kind.IN, BreathBuzz.last)
        idle(4)
        assertEquals("one tap in, then two taps out", BreathBuzz.Kind.OUT, BreathBuzz.last)
        idle(20) // 25 s: breaths at 0, 4, 10, 14, 20, 24
        assertEquals(6, Chime.breathTicks.get())
        s.deliver(MeditationService::pause)
        idle(300)
        assertEquals("no buzzing while paused", 6, Chime.breathTicks.get())
        BreathBuzz.last = null
        s.deliver(MeditationService::resume)
        idle(1)
        assertEquals("resumed 1 s into an out-breath: it's cued at once", 7, Chime.breathTicks.get())
        assertEquals(BreathBuzz.Kind.OUT, BreathBuzz.last)
        idle(59)
        assertEquals("the rest of the six breaths, then quiet", 13, Chime.breathTicks.get())
        idle(600)
        assertEquals(13, Chime.breathTicks.get())
        assertEquals(600, (SessionRepository.state.value as SessionState.Finished).satSec)
        assertEquals(600, records.single().actualSec)
        // Without settle-in, no buzzing at all.
        SessionRepository.reset()
        Chime.breathTicks.set(0)
        begin(minutes = 2)
        idle(130)
        assertEquals(0, Chime.breathTicks.get())
    }

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
    fun `background sound never changes how a sit runs or logs, and leaves nothing playing`() {
        for (kind in listOf(Ambience.RAIN, Ambience.BIRDS, Ambience.CUSTOM)) {
            SessionRepository.reset()
            Prefs(app).ambience = kind
            Prefs(app).ambienceUri = if (kind == Ambience.CUSTOM) "content://nowhere/missing.mp3" else null // a deleted file
            val before = records.size
            val s = begin(minutes = 3, interval = 1)
            idle(30)
            s.deliver(MeditationService::pause)
            idle(600)
            s.deliver(MeditationService::resume)
            s.deliver(MeditationService::end)
            s.deliver(MeditationService::resume) // keep sitting
            idle(151)
            assertEquals("$kind", 180, (SessionRepository.state.value as SessionState.Finished).satSec)
            assertEquals(before + 1, records.size)
        }
        // Every sound thread fades out and ends on its own after the sit (real time, a few seconds).
        val deadline = System.currentTimeMillis() + 20_000
        while (AmbientPlayer.live.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(0, AmbientPlayer.live.get())
    }

    @Test
    fun `ambient player survives any order of calls`() {
        val player = AmbientPlayer(app)
        val rnd = Random(3)
        repeat(300) {
            when (rnd.nextInt(7)) {
                0 -> player.start(listOf(Ambience.RAIN, Ambience.BIRDS, Ambience.OFF).random(rnd), rnd.nextFloat(), fadeInMs = 50)
                1 -> player.pause()
                2 -> player.resume()
                3 -> player.duck()
                4 -> player.stop(rnd.nextInt(0, 100))
                5 -> player.setVolume(rnd.nextFloat())
                else -> player.stopNow()
            }
            if (rnd.nextInt(10) == 0) Thread.sleep(5)
        }
        player.stopNow()
        assertEquals(AmbientPlayer.State.STOPPED, player.state)
        val deadline = System.currentTimeMillis() + 10_000
        while (AmbientPlayer.live.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("sound threads left running", 0, AmbientPlayer.live.get())
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
        // A restored reminder is armed at once, not only after the next reboot or app update.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertNotNull("restored reminder armed", alarms.peekNextScheduledAlarm())
        // A damaged file can't set a 0-minute sit, a 0-bead mala or settings the app doesn't have.
        prefs.importSettings(
            mapOf(
                "duration_min" to "0", "mala_target" to "0", "alert_mode" to "LOUD", "interval_min" to "-5",
                "breath_pattern" to "Nope", "opening_bell_sec" to "99999", "breath_minutes" to "0",
            ),
        )
        assertEquals(20 * 60, prefs.timerConfig.durationSec)
        assertEquals(108, prefs.mala.target)
        assertEquals(AlertMode.BELL, prefs.alertMode)
        assertEquals(0, prefs.timerConfig.intervalMin)
        assertEquals(5, prefs.timerConfig.openingBellSec)
        assertEquals(BreathPattern.ALL.first().name, prefs.breathPattern)
        assertEquals(3, prefs.breathMinutes)
        // Good values in the same file still come through.
        prefs.importSettings(mapOf("duration_min" to "45", "mala_target" to "54", "alert_mode" to "VIBRATE"))
        assertEquals(45 * 60, prefs.timerConfig.durationSec)
        assertEquals(54, prefs.mala.target)
        assertEquals(AlertMode.VIBRATE, prefs.alertMode)
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
        assertEquals("0 of 5 days this week", week()) // the default goal
        assertTrue(button().startsWith("Sit · "))
        SessionLog.get(app).add(SessionRecord(java.time.Instant.now().toEpochMilli(), 600, 600))
        assertEquals("1 of 5 days this week", week())
        Prefs(app).weeklyGoal = 1
        SitWidget.refresh(app)
        assertEquals("1 of 1 days this week ✓", week())
        Prefs(app).weeklyGoal = 0
        SitWidget.refresh(app)
        assertEquals("1 day this week", week())
    }
}
