package com.rajan.mindfield

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.rajan.mindfield.core.Codec
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Schedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.util.TimeZone
import kotlin.random.Random

/**
 * The real alarms, notifications, notification buttons, widget and storage, on Robolectric.
 * Time is set by hand, so days pass in milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RemindersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val today: LocalDate = LocalDate.now(Seed.zone)

    @Before
    fun clean() {
        Seed.clean(app)
        Store.settings { it.copy(onboarded = true) }
    }

    @After
    fun tidy() = Seed.backToNow()

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun fire(action: String) = AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(action))

    /** When the alarm for [action] is set to go off, or null if it isn't armed. */
    private fun armedAt(action: String): Long? =
        alarms.scheduledAlarms.firstOrNull { shadowOf(it.operation).savedIntent.action == action }?.triggerAtMs

    @Test
    fun `morning and evening alarms are armed for the chosen times, and the spot check only when on`() {
        Seed.at(today, 6, 30)
        Scheduler.scheduleAll(app)
        val times = alarms.scheduledAlarms.map { it.triggerAtMs }.sorted()
        val now = Store.now()
        assertEquals(
            listOf(Schedule.next(8 * 60, now), Schedule.next(21 * 60, now)).map { it.toInstant().toEpochMilli() },
            times,
        )
        Store.settings { it.copy(spotCheckOn = true, morningMinute = 7 * 60 + 15) }
        assertEquals(3, alarms.scheduledAlarms.size)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == Schedule.next(7 * 60 + 15, now).toInstant().toEpochMilli() })
        Store.settings { it.copy(spotCheckOn = false, morningOn = false, eveningOn = false) }
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test
    fun `boot, app updates and clock changes re-arm everything`() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            alarms.scheduledAlarms.toList().forEach { app.getSystemService(AlarmManager::class.java).cancel(it.operation!!) }
            assertEquals(0, alarms.scheduledAlarms.size)
            fire(action)
            assertEquals(action, 2, alarms.scheduledAlarms.size)
        }
    }

    @Test
    fun `the morning notification brings today's concept and its mission, even with the app closed`() {
        Seed.at(today, 8)
        fire(Scheduler.ACTION_MORNING)
        val n = notifications.getNotification(Notifier.ID_MORNING)
        assertNotNull(n)
        val first = Store.library[Curriculum.FIRST]!!
        assertEquals(first.title, title(n!!))
        assertEquals(first.hook, text(n))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains(first.missionLine))
        assertEquals(Curriculum.FIRST, Store.state.value.assignments[today]?.conceptId)
        // Next morning: the next concept, never the same one twice.
        Seed.at(today.plusDays(1), 8)
        fire(Scheduler.ACTION_MORNING)
        val second = Store.state.value.assignments[today.plusDays(1)]!!.conceptId
        assertFalse(second == Curriculum.FIRST)
        assertEquals(Store.library[second]!!.title, title(notifications.getNotification(Notifier.ID_MORNING)))
    }

    @Test
    fun `one tap on the evening notification logs it, and the reply box adds a note`() {
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        val evening = notifications.getNotification(Notifier.ID_EVENING)!!
        val concept = Store.todayConcept()
        assertEquals("Field report · ${concept.title}", title(evening))
        assertEquals(listOf("👀 Spotted", "🙋 In me", "🎯 Used"), evening.actions.map { it.title.toString() })

        // Tap "🙋 In me".
        val tap = shadowOf(evening.actions[1].actionIntent).savedIntent
        ActionReceiver().onReceive(app, tap)
        val entry = Store.state.value.liveEntries.single()
        assertEquals(Mode.MYSELF, entry.mode)
        assertEquals(concept.id, entry.conceptId)
        assertEquals(today, entry.day)

        // The notification turns into "Logged", with a reply box.
        val logged = notifications.getNotification(Notifier.ID_EVENING)!!
        assertTrue(title(logged).contains("Logged"))
        val reply = logged.actions.single()
        val input = reply.remoteInputs.single()
        val replyIntent = Intent(shadowOf(reply.actionIntent).savedIntent)
        RemoteInput.addResultsToIntent(arrayOf(input), replyIntent, Bundle().apply { putCharSequence(input.resultKey, "  Caught myself anchoring on the first quote.  ") })
        ActionReceiver().onReceive(app, replyIntent)
        assertEquals("Caught myself anchoring on the first quote.", Store.state.value.liveEntries.single().note)
        assertTrue(title(notifications.getNotification(Notifier.ID_EVENING)!!).contains("Saved"))

        // A second reply adds a line instead of replacing the first.
        RemoteInput.addResultsToIntent(arrayOf(input), replyIntent, Bundle().apply { putCharSequence(input.resultKey, "And again at lunch.") })
        ActionReceiver().onReceive(app, replyIntent)
        assertEquals("Caught myself anchoring on the first quote.\nAnd again at lunch.", Store.state.value.liveEntries.single().note)
    }

    @Test
    fun `a tap just after midnight is logged for the day the concept was shown`() {
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        val tap = shadowOf(notifications.getNotification(Notifier.ID_EVENING)!!.actions[0].actionIntent).savedIntent
        Seed.at(today.plusDays(1), 0, 20)
        ActionReceiver().onReceive(app, tap)
        assertEquals(today, Store.state.value.liveEntries.single().day)
    }

    @Test
    fun `once you've logged, the evening report and spot checks stay quiet`() {
        Seed.at(today, 13)
        Store.log(Store.todayConcept().id, Mode.SPOTTED, "seen it")
        fire(Scheduler.ACTION_SPOT)
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        assertEquals(null, notifications.getNotification(Notifier.ID_EVENING))
        assertEquals(null, notifications.getNotification(Notifier.ID_SPOT))
    }

    @Test
    fun `logging in the app clears an unanswered nudge`() {
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        assertNotNull(notifications.getNotification(Notifier.ID_EVENING))
        Store.log(Store.todayConcept().id, Mode.USED, "from the app")
        assertEquals(null, notifications.getNotification(Notifier.ID_EVENING))
    }

    @Test
    fun `without notification permission nothing is posted and nothing crashes`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        fire(Scheduler.ACTION_MORNING)
        fire(Scheduler.ACTION_EVENING)
        assertEquals(0, notifications.allNotifications.size)
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test
    fun `widget shows today's concept and flips to Logged`() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(TodayWidget::class.java, R.layout.widget_today)
        TodayWidget.refresh(app)
        fun text(view: Int) = manager.getViewFor(id).findViewById<TextView>(view).text.toString()
        val c = Store.todayConcept()
        assertEquals(c.title, text(R.id.widget_title))
        assertEquals("Log it", text(R.id.widget_log))
        Store.log(c.id, Mode.SPOTTED)
        assertEquals("Logged ✓", text(R.id.widget_log))
    }

    @Test
    fun `the journal survives the process dying, and a damaged file is kept aside, not lost`() {
        Seed.weeks(20)
        val before = Store.state.value
        Store.reloadForTests(app)
        assertEquals(before, Store.state.value)

        File(app.filesDir, "mindfield.json").writeText("{ this is not json")
        Store.reloadForTests(app)
        assertTrue(Store.state.value.isEmpty)
        assertTrue(app.filesDir.listFiles()!!.any { it.name.startsWith("mindfield.json.unreadable") })
    }

    @Test
    fun `stress - a year of random use through the real store stays consistent`() {
        val rnd = Random(11)
        Store.settings { it.copy(onboarded = true) }
        var day = today.minusDays(365)
        var lastIds = emptySet<String>()
        repeat(365) { i ->
            day = day.plusDays(1)
            Seed.at(day, 8 + rnd.nextInt(3))
            if (rnd.nextInt(6) != 0) fire(Scheduler.ACTION_MORNING)
            val c = Store.todayConcept()
            if (rnd.nextBoolean()) Store.guess(c.id, rnd.nextInt(3))
            if (rnd.nextInt(5) == 0) Store.plan(day, "plan $i \"quoted\" ✨")
            Seed.at(day, 21)
            when (rnd.nextInt(5)) {
                0 -> fire(Scheduler.ACTION_EVENING).also {
                    notifications.getNotification(Notifier.ID_EVENING)?.actions?.getOrNull(rnd.nextInt(3))?.let { a ->
                        ActionReceiver().onReceive(app, shadowOf(a.actionIntent).savedIntent)
                    }
                }
                1, 2 -> Store.log(c.id, Mode.entries.random(rnd), "note $i, with a comma, and\nnew line")
                else -> Unit
            }
            val live = Store.state.value.liveEntries
            if (live.isNotEmpty() && rnd.nextInt(8) == 0) Store.edit(live.random(rnd).copy(note = "edited $i"))
            if (live.isNotEmpty() && rnd.nextInt(15) == 0) Store.delete(live.random(rnd).id)
            if (rnd.nextInt(30) == 0) Store.settings { it.copy(focus = com.rajan.mindfield.core.Category.entries.filter { rnd.nextBoolean() }.toSet()) }
            com.rajan.mindfield.core.Spacing.due(Store.state.value, Store.library, day).forEach { Store.grade(it.conceptId, rnd.nextBoolean()) }
            if (i % 60 == 59) {
                // The process dies and comes back: nothing is lost.
                val before = Store.state.value
                Store.reloadForTests(app)
                assertEquals("day $i", before, Store.state.value)
            }
            val ids = Store.state.value.unlocked.keys
            assertTrue(ids.containsAll(lastIds))
            lastIds = ids
        }
        val s = Store.state.value
        // One concept per day, all real; no repeats before the whole guide has been seen.
        assertTrue(s.assignments.values.all { Store.library.contains(it.conceptId) })
        val inOrder = s.assignments.toSortedMap().values.map { it.conceptId }
        assertEquals(Store.library.size, inOrder.take(Store.library.size).toSet().size)
        assertTrue(s.entries.all { Store.library.contains(it.conceptId) })
        // A backup of a year of use reads back exactly.
        val back = Codec.decode(Codec.encode(s, 0))
        assertEquals(s.entries.sortedBy { it.id }, back.entries.sortedBy { it.id })
        assertEquals(s.assignments, back.assignments)
        assertEquals(s.cards, back.cards)
    }

    @Test
    fun `nothing is armed or picked before the welcome steps are done`() {
        Store.settings { it.copy(onboarded = false) }
        assertEquals(0, alarms.scheduledAlarms.size)
        Seed.at(today, 8)
        fire(Scheduler.ACTION_MORNING)
        fire(Scheduler.ACTION_EVENING)
        assertEquals(0, notifications.allNotifications.size)
        assertTrue(Store.state.value.assignments.isEmpty())
        assertEquals(0, alarms.scheduledAlarms.size)
        // Finishing them arms the reminders.
        Store.settings { it.copy(onboarded = true) }
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test
    fun `after travel, today and every reminder follow the new time zone`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            Seed.backToNow()
            assertEquals(ZoneId.of("Asia/Kolkata"), Store.now().zone)
            // Android changes the default zone in the running app, then sends TIMEZONE_CHANGED.
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val la = ZoneId.of("America/Los_Angeles")
            assertEquals(la, Store.now().zone)
            assertEquals(LocalDate.now(la), Store.today())
            fire(Intent.ACTION_TIMEZONE_CHANGED)
            assertEquals(com.rajan.mindfield.core.Schedule.next(8 * 60, Store.now()).toInstant().toEpochMilli(), armedAt(Scheduler.ACTION_MORNING))
            assertEquals(com.rajan.mindfield.core.Schedule.next(21 * 60, Store.now()).toInstant().toEpochMilli(), armedAt(Scheduler.ACTION_EVENING))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `a reminder that's due but hasn't arrived yet still comes today`() {
        Seed.at(today, 7)
        Scheduler.scheduleAll(app)
        val eight = Schedule.next(8 * 60, Store.now()).toInstant().toEpochMilli()
        assertEquals(eight, armedAt(Scheduler.ACTION_MORNING))
        // 8:20 and the inexact alarm hasn't arrived: opening the app leaves it alone...
        Seed.at(today, 8, 20)
        Scheduler.ensureAll(app)
        assertEquals(eight, armedAt(Scheduler.ACTION_MORNING))
        // ...and a full re-arm (a settings change, a clock change) still delivers it, not tomorrow.
        Store.settings { it.copy(spotCheckOn = true) }
        assertEquals(eight, armedAt(Scheduler.ACTION_MORNING))
        // Once it has gone off, it moves on to tomorrow.
        fire(Scheduler.ACTION_MORNING)
        assertEquals(Schedule.next(8 * 60, Store.now()).toInstant().toEpochMilli(), armedAt(Scheduler.ACTION_MORNING))
        assertTrue(armedAt(Scheduler.ACTION_MORNING)!! > Store.now().toInstant().toEpochMilli())
        // A reminder missed by hours (the phone was off all morning) waits for tomorrow instead.
        Seed.at(today.plusDays(1), 13)
        Scheduler.scheduleAll(app)
        assertEquals(Schedule.next(8 * 60, Store.now()).toInstant().toEpochMilli(), armedAt(Scheduler.ACTION_MORNING))
    }

    @Test
    fun `notifications switched off are noticed, and no concept is picked for no one`() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(Notifier.allowed(app))
        assertTrue(Notifier.blocked(app, Store.state.value.settings))
        Seed.at(today, 8)
        fire(Scheduler.ACTION_MORNING)
        assertEquals(0, notifications.allNotifications.size)
        assertEquals(null, Store.state.value.assignments[today])
        // The alarm itself still fired, so the battery check doesn't blame the battery saver.
        assertFalse(Health.remindersBlocked(app))
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        assertFalse(Notifier.blocked(app, Store.state.value.settings))
    }

    @Test
    fun `a one-tap log turns the report into a quiet Logged in place, and a double tap logs once`() {
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        val evening = notifications.getNotification(Notifier.ID_EVENING)!!
        // Gone by 4 am: yesterday's report can't be tapped tomorrow afternoon.
        assertEquals(Duration.between(Store.now(), today.plusDays(1).atTime(4, 0).atZone(Store.now().zone)).toMillis(), evening.timeoutAfter)
        val tap = shadowOf(evening.actions[0].actionIntent).savedIntent
        ActionReceiver().onReceive(app, tap)
        ActionReceiver().onReceive(app, tap)
        assertEquals(1, Store.state.value.liveEntries.size)
        val logged = notifications.getNotification(Notifier.ID_EVENING)!!
        assertTrue(title(logged).contains("Logged"))
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(logged.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `a reply to a note deleted meanwhile is kept as a new note`() {
        Seed.at(today, 21)
        fire(Scheduler.ACTION_EVENING)
        ActionReceiver().onReceive(app, shadowOf(notifications.getNotification(Notifier.ID_EVENING)!!.actions[1].actionIntent).savedIntent)
        val logged = notifications.getNotification(Notifier.ID_EVENING)!!
        Store.delete(Store.state.value.liveEntries.single().id)
        val reply = logged.actions.single()
        val input = reply.remoteInputs.single()
        val replyIntent = Intent(shadowOf(reply.actionIntent).savedIntent)
        RemoteInput.addResultsToIntent(arrayOf(input), replyIntent, Bundle().apply { putCharSequence(input.resultKey, "Still worth keeping.") })
        ActionReceiver().onReceive(app, replyIntent)
        val kept = Store.state.value.liveEntries.single()
        assertEquals("Still worth keeping.", kept.note)
        assertEquals(Mode.MYSELF, kept.mode)
        assertTrue(title(notifications.getNotification(Notifier.ID_EVENING)!!).contains("Saved"))
    }

    @Test
    fun `an evening report time after midnight asks about the day just ending`() {
        Store.settings { it.copy(eveningMinute = 30) }
        Seed.at(today, 9)
        val shown = Store.todayConcept()
        Seed.at(today.plusDays(1), 0, 30)
        fire(Scheduler.ACTION_EVENING)
        assertEquals("Field report · ${shown.title}", title(notifications.getNotification(Notifier.ID_EVENING)!!))
        // Tomorrow's concept isn't picked in the night.
        assertEquals(null, Store.state.value.assignments[today.plusDays(1)])
    }

    @Test
    fun `a widget turns to the new day at midnight`() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        Seed.at(today, 20)
        assertEquals(null, armedAt(Scheduler.ACTION_MIDNIGHT))
        val id = manager.createWidget(TodayWidget::class.java, R.layout.widget_today)
        assertNotNull(armedAt(Scheduler.ACTION_MIDNIGHT))
        fun text(view: Int) = manager.getViewFor(id).findViewById<TextView>(view).text.toString()
        val c = Store.todayConcept()
        TodayWidget.refresh(app)
        assertEquals(c.title, text(R.id.widget_title))
        Seed.at(today.plusDays(1), 0, 1)
        fire(Scheduler.ACTION_MIDNIGHT)
        val next = Store.state.value.assignments.getValue(today.plusDays(1)).conceptId
        assertEquals(Store.library[next]!!.title, text(R.id.widget_title))
        assertEquals("Log it", text(R.id.widget_log))
    }

    @Test
    fun `a widget before the welcome steps invites you in, without picking a concept`() {
        Store.settings { it.copy(onboarded = false) }
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(TodayWidget::class.java, R.layout.widget_today)
        assertEquals("Open", manager.getViewFor(id).findViewById<TextView>(R.id.widget_log).text.toString())
        assertTrue(Store.state.value.assignments.isEmpty())
    }

    @Test
    fun `a save that fails says so instead of crashing, and the next one catches up`() {
        // A directory where the save writes first makes the write fail, as a full phone would.
        val blocker = File(app.filesDir, "mindfield.json.tmp").apply { mkdirs() }
        File(blocker, "in the way").writeText("x") // so clearing up after a failed save can't remove it
        Store.log(Store.todayConcept().id, Mode.SPOTTED, "kept in memory")
        Store.flush()
        assertTrue(Store.saveFailed.value)
        blocker.deleteRecursively()
        Store.retrySave()
        Store.flush()
        assertFalse(Store.saveFailed.value)
        Store.reloadForTests(app)
        assertEquals("kept in memory", Store.state.value.liveEntries.single().note)
    }

    @Test
    fun `a journal file that can't be read falls back to a finished save, and says so if there's none`() {
        Store.log(Store.todayConcept().id, Mode.SPOTTED, "the newest note")
        Store.flush()
        val good = File(app.filesDir, "mindfield.json").readText()
        // Power cut after the new copy was written but before it replaced the old one, which is damaged.
        File(app.filesDir, "mindfield.json.tmp").writeText(good)
        File(app.filesDir, "mindfield.json").writeText("{ damaged")
        Store.reloadForTests(app)
        assertEquals("the newest note", Store.state.value.liveEntries.single().note)
        assertFalse(Store.journalReset.value)
        File(app.filesDir, "mindfield.json.tmp").delete()
        File(app.filesDir, "mindfield.json").writeText("{ damaged")
        Store.reloadForTests(app)
        assertTrue(Store.journalReset.value)
    }
}
