package com.rajan.meditationtimer

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.util.DataSource
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
    fun `breathe-tab taps carry on with the phone locked, end on time and stop from the notification`() {
        val box = BreathPattern.ALL.first { it.name == "Box" }
        val played = BreathBuzz.played.get()
        val first = SystemClock.elapsedRealtime()
        BreathService.start(app, box, 1, first, BreathCue.VIBRATION)
        val s = Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        // No screen here at all: everything comes from the service.
        val seen = mutableListOf<BreathBuzz.Kind?>()
        repeat(8) { phase ->
            idle(if (phase == 0) 2 else 4)
            seen += BreathBuzz.last
        }
        val (i, h, o) = Triple(BreathBuzz.Kind.IN, BreathBuzz.Kind.HOLD, BreathBuzz.Kind.OUT)
        assertEquals(listOf(i, h, o, h, i, h, o, h), seen)
        assertEquals("one cue per phase, none doubled", 8, BreathBuzz.played.get() - played)
        assertTrue("holds the CPU so taps stay on time", ShadowPowerManager.getLatestWakeLock().isHeld)
        // The screen rebuilt (rotation) asks again with the same start: no restart, no extra tap.
        BreathService.start(app, box, 1, first, BreathCue.VIBRATION)
        assertNull(shadowOf(app).nextStartedService)
        // 1 minute of box rounds up to 4 whole breaths (64 s) = 16 phases.
        idle(60)
        assertEquals(16, BreathBuzz.played.get() - played)
        assertTrue("stops itself at the end", shadowOf(s.get()).isStoppedBySelf)
        assertFalse("and lets the CPU sleep", ShadowPowerManager.getLatestWakeLock().isHeld)
        // The closing bell rang from here, on time: the screen, unlocked later, mustn't ring it again.
        assertEquals(first, BreathService.ended.value)
        assertTrue(BreathService.ringsEnd(first))
        assertFalse(BreathService.busy)
        idle(60)
        assertEquals(16, BreathBuzz.played.get() - played)

        // Stop in the notification: quiet at once, and the screen is told.
        val started = SystemClock.elapsedRealtime()
        BreathService.start(app, box, 5, started, BreathCue.VIBRATION)
        val t = Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(10)
        val before = BreathBuzz.played.get()
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last { it.channelId == "breathe" }
        val stop = notification.actions.single()
        assertEquals("Stop", stop.title.toString())
        t.withIntent(shadowOf(stop.actionIntent).savedIntent).startCommand(0, ++startId)
        assertEquals(started, BreathService.cancelled.value)
        idle(120)
        assertEquals(before, BreathBuzz.played.get())
        assertTrue(shadowOf(t.get()).isStoppedBySelf)
    }

    @Test
    fun `the daily reminder and a breathing exercise never replace or clear each other`() {
        val box = BreathPattern.ALL.first { it.name == "Box" }
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        Prefs(app).reminder = Reminder(true, 7 * 60, "after morning tea")
        ReminderScheduler.fire(app)
        assertEquals(1, notifications.allNotifications.size)

        // Both showing at once: the exercise's notification has an id of its own.
        val started = SystemClock.elapsedRealtime()
        BreathService.start(app, box, 3, started, BreathCue.VIBRATION)
        val s = Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(5)
        assertTrue(BreathService.busy)
        assertEquals(setOf("reminders", "breathe"), notifications.allNotifications.map { it.channelId }.toSet())
        // Clearing the nudge (as a sit does) leaves the exercise's notification, and its Stop, alone.
        ReminderScheduler.dismiss(app)
        assertEquals("breathe", notifications.allNotifications.single().channelId)
        // No new nudge buzzing through the exercise.
        ReminderScheduler.fire(app)
        assertEquals("breathe", notifications.allNotifications.single().channelId)

        // Once it is over (3 minutes of box is 12 breaths, 192 s) and its bell rung out, the nudge comes as usual.
        idle(220)
        assertTrue(shadowOf(s.get()).isStoppedBySelf)
        assertFalse(BreathService.busy)
        assertEquals(started, BreathService.ended.value)
        ReminderScheduler.fire(app)
        assertEquals("reminders", notifications.allNotifications.single().channelId)
    }

    /** Waits (in real time: the sound runs on its own thread) for every breath-sound thread to end. */
    private fun breathSoundEnds() {
        val deadline = System.currentTimeMillis() + 5_000
        while (BreathSoundPlayer.live.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("breath sound left playing", 0, BreathSoundPlayer.live.get())
    }

    @Test
    fun `the breath sound paces a Breathe exercise instead of taps, and goes quiet at the end`() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        val box = BreathPattern.ALL.first { it.name == "Box" }
        val played = BreathBuzz.played.get()
        val starts = BreathSoundPlayer.starts.get()
        val first = SystemClock.elapsedRealtime()
        BreathService.start(app, box, 1, first, BreathCue.SOUND)
        val s = Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(10)
        assertEquals("heard, not felt", played, BreathBuzz.played.get())
        assertEquals(starts + 1, BreathSoundPlayer.starts.get())
        val deadline = System.currentTimeMillis() + 2_000
        while (BreathSoundPlayer.live.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("one sound thread, playing", 1, BreathSoundPlayer.live.get())
        // Music in other apps pauses for it, as for any guided audio.
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadowOf(audio).lastAudioFocusRequest.durationHint)
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last { it.channelId == "breathe" }
        assertEquals(app.getString(R.string.breath_notification_sound_text), notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        // Rotation: the same exercise asked for again starts nothing new.
        BreathService.start(app, box, 1, first, BreathCue.SOUND)
        assertNull(shadowOf(app).nextStartedService)
        idle(60)
        assertTrue("stops itself at the end", shadowOf(s.get()).isStoppedBySelf)
        assertEquals(first, BreathService.ended.value)
        breathSoundEnds()
        assertEquals(played, BreathBuzz.played.get())

        // Both: the sound and the taps together.
        val both = SystemClock.elapsedRealtime()
        BreathService.start(app, box, 1, both, BreathCue.BOTH)
        Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(70)
        assertEquals(16, BreathBuzz.played.get() - played)
        assertEquals(starts + 2, BreathSoundPlayer.starts.get())
        breathSoundEnds()
    }

    @Test
    fun `when the breath can't be heard, taps pace it instead - never no cue at all`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val box = BreathPattern.ALL.first { it.name == "Box" }
        // A call is on: no audio focus, so no sound, and taps instead.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        var played = BreathBuzz.played.get()
        val starts = BreathSoundPlayer.starts.get()
        BreathService.start(app, box, 1, SystemClock.elapsedRealtime(), BreathCue.SOUND)
        Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(70)
        assertEquals(starts, BreathSoundPlayer.starts.get())
        assertEquals(16, BreathBuzz.played.get() - played)
        // Media volume all the way down: the sound is still started (turn it up and it's there),
        // and the taps come too.
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        played = BreathBuzz.played.get()
        BreathService.start(app, box, 1, SystemClock.elapsedRealtime(), BreathCue.SOUND)
        Robolectric.buildService(BreathService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(70)
        assertEquals(starts + 1, BreathSoundPlayer.starts.get())
        assertEquals(16, BreathBuzz.played.get() - played)
        breathSoundEnds()
    }

    @Test
    fun `settle-in breaths are heard in a sit with bells, stop while paused, and are taps in a vibrate-only sit`() {
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        Prefs(app).breathCue = BreathCue.SOUND
        val starts = BreathSoundPlayer.starts.get()
        Chime.breathTicks.set(0)
        MeditationService.start(app, SessionConfig(10 * 60, 5, 10, true, 0, settleSec = 60), 0.5f, AlertMode.BELL, false)
        val s = Robolectric.buildService(MeditationService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(20)
        assertEquals("heard, not tapped", 0, Chime.breathTicks.get())
        assertEquals(starts + 1, BreathSoundPlayer.starts.get())
        s.deliver(MeditationService::pause)
        breathSoundEnds()
        s.deliver(MeditationService::resume)
        idle(1)
        assertEquals("picks up mid-breath on resume", starts + 2, BreathSoundPlayer.starts.get())
        idle(45)
        breathSoundEnds()
        idle(600)
        assertEquals(0, Chime.breathTicks.get())
        assertEquals(600, records.single().actualSec)

        // Vibrate-only means silent: the same settings give taps and no sound.
        SessionRepository.reset()
        MeditationService.start(app, SessionConfig(5 * 60, 5, 10, true, 0, settleSec = 60), 0.5f, AlertMode.VIBRATE, false)
        Robolectric.buildService(MeditationService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, ++startId)
        idle(70)
        assertEquals(12, Chime.breathTicks.get())
        assertEquals(starts + 2, BreathSoundPlayer.starts.get())
    }

    @Test
    fun `attention-check scores travel with the backup, and two phones keep each other's`() {
        val prefs = Prefs(app)
        prefs.addBreathCheck(BreathCheck(1_000, BreathCountResult(4, 5)))
        prefs.addBreathCheck(BreathCheck(2_000, BreathCountResult(6, 6)))
        val settings = History.settingsFrom(History.toCsv(emptyList(), ZoneId.of("UTC"), prefs.exportSettings()))!!

        // A new phone with one check of its own, then the backup restored twice: all three, once each, in order.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.addBreathCheck(BreathCheck(1_500, BreathCountResult(1, 2)))
        prefs.importSettings(settings)
        prefs.importSettings(settings)
        assertEquals(listOf(1_000L, 1_500L, 2_000L), prefs.breathChecks.map { it.atMs })
        assertEquals(BreathCountResult(6, 6), prefs.breathChecks.last().result)

        // Drive's merge on a phone already in use gathers scores only, never the settings.
        prefs.importSettings(mapOf("duration_min" to "45"))
        prefs.mergeBreathChecks(mapOf("duration_min" to "5", "breath_checks" to "3000%2C2%2C3"))
        assertEquals(45 * 60, prefs.timerConfig.durationSec)
        assertEquals(4, prefs.breathChecks.size)
        // Damaged scores (more right than counted, no rounds, a negative time, junk) are left out.
        prefs.mergeBreathChecks(mapOf("breath_checks" to "4000%2C5%2C3%0A5000%2C0%2C0%0A-1%2C1%2C1%0Ajunk%0A%25%25"))
        prefs.mergeBreathChecks(mapOf("breath_checks" to "%zz"))
        assertEquals(4, prefs.breathChecks.size)
    }

    @Test
    fun `a deleted sit stays deleted - after a restart, a restore, and another phone's backup`() {
        val log = SessionLog.get(app)
        val base = ZonedDateTime.of(2026, 9, 1, 7, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sits = (0 until 5).map { SessionRecord(base + it * 86_400_000L, 1200, 1200) }
        sits.forEach(log::add)
        val oldBackup = History.toCsv(sits, ZoneId.systemDefault())
        log.delete(sits[2].startedAtMs)
        assertEquals(4, records.size)
        assertTrue(sits[2] !in records)

        // Survives the app restarting (read back from the file).
        SessionLog.resetForTests()
        assertEquals(4, records.size)
        assertEquals(setOf(sits[2].startedAtMs / 60_000), SessionLog.get(app).deleted)

        // Restoring a backup made before the delete doesn't bring it back.
        assertEquals(0, SessionLog.get(app).merge(History.fromCsv(oldBackup, ZoneId.systemDefault()), History.deletedFrom(oldBackup)))
        assertEquals(4, records.size)

        // What this phone uploads carries the deletion, so the other phone drops the sit too.
        val upload = History.toCsv(records, ZoneId.systemDefault(), deleted = SessionLog.get(app).deleted)
        assertTrue(sits[2] !in History.fromCsv(upload, ZoneId.systemDefault()))
        assertEquals(SessionLog.get(app).deleted, History.deletedFrom(upload))

        // A sit deleted on the other phone goes here too, and new sits from there still arrive.
        val newThere = SessionRecord(base + 10 * 86_400_000L, 600, 600)
        val theirs = History.toCsv(sits + newThere, ZoneId.systemDefault(), deleted = setOf(sits[0].startedAtMs / 60_000))
        assertEquals(1, SessionLog.get(app).merge(History.fromCsv(theirs, ZoneId.systemDefault()), History.deletedFrom(theirs)))
        assertEquals(listOf(sits[1], sits[3], sits[4], newThere).map { it.startedAtMs }, records.map { it.startedAtMs })
        SessionLog.resetForTests()
        assertEquals(4, records.size)
        assertEquals(2, SessionLog.get(app).deleted.size)
    }

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
    fun `background sound gives way to calls and other apps, and comes back after a call`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val uri = Uri.parse("content://test/my-rain.mp3")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))
        val player = AmbientPlayer(app)
        player.start(Ambience.CUSTOM, 0.5f, uri, fadeInMs = 50)
        assertEquals(AmbientPlayer.State.PLAYING, player.state)
        val focus = shadowOf(audio).lastAudioFocusRequest
        assertEquals("asks for the speaker like any media app", AudioManager.AUDIOFOCUS_GAIN, focus.durationHint)
        val listener = focus.listener

        // A call rings: the sound pauses, and carries on when the call ends.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(AmbientPlayer.State.PAUSED, player.state)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AmbientPlayer.State.PLAYING, player.state)

        // You pause the sit during a call: the call ending doesn't start the sound again.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        player.pause()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AmbientPlayer.State.PAUSED, player.state)

        // You resume while a call is still on: the sound waits for the call to end.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        player.resume()
        assertEquals(AmbientPlayer.State.PAUSED, player.state)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AmbientPlayer.State.PLAYING, player.state)

        // A navigation prompt only dips it (Android does that itself): it keeps playing.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(AmbientPlayer.State.PLAYING, player.state)

        // Music or a video starts in another app: the sound stops for the rest of the sit.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle(3)
        assertEquals(AmbientPlayer.State.STOPPED, player.state)
        player.resume()
        assertEquals(AmbientPlayer.State.STOPPED, player.state)

        // Refused outright (a call already on when the sit starts): silence, not sound over the call.
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        player.start(Ambience.CUSTOM, 0.5f, uri, fadeInMs = 50)
        assertEquals(AmbientPlayer.State.STOPPED, player.state)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        player.stopNow()
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
        prefs.breathCue = BreathCue.BOTH
        val exported = prefs.exportSettings()
        val line = History.settingsLine(exported)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.importSettings(History.settingsFrom(line)!!)
        assertEquals(Reminder(true, 405, "After tea; brush=teeth & 🧘 50% done"), prefs.reminder)
        assertEquals(false, prefs.countDistractions)
        assertEquals(false, prefs.checkIns)
        assertEquals(BreathCue.BOTH, prefs.breathCue)
        // A restored reminder is armed at once, not only after the next reboot or app update.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertNotNull("restored reminder armed", alarms.peekNextScheduledAlarm())
        // A damaged file can't set a 0-minute sit, a 0-bead mala or settings the app doesn't have.
        prefs.importSettings(
            mapOf(
                "duration_min" to "0", "mala_target" to "0", "alert_mode" to "LOUD", "interval_min" to "-5",
                "breath_pattern" to "Nope", "opening_bell_sec" to "99999", "breath_minutes" to "0", "breath_cue" to "LOUD",
            ),
        )
        assertEquals(20 * 60, prefs.timerConfig.durationSec)
        assertEquals(108, prefs.mala.target)
        assertEquals(AlertMode.BELL, prefs.alertMode)
        assertEquals(0, prefs.timerConfig.intervalMin)
        assertEquals(5, prefs.timerConfig.openingBellSec)
        assertEquals(BreathPattern.ALL.first().name, prefs.breathPattern)
        assertEquals(3, prefs.breathMinutes)
        assertEquals(BreathCue.BOTH, prefs.breathCue)
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
