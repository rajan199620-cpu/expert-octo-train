package com.rajan.mindfield

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.FieldDay
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Schedule
import com.rajan.mindfield.core.Settings
import com.rajan.mindfield.core.Spacing
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** What has to happen after any change to your data, whichever screen or receiver made it. */
object Effects {
    fun onChanged(context: Context, before: AppState, after: AppState) {
        val today = Store.today()
        // The widget shows today's concept and whether it's reported: redraw it only when that changes.
        if (TodayWidget.key(before, today) != TodayWidget.key(after, today)) TodayWidget.refresh(context)
        val b = before.settings
        val a = after.settings
        if (b != a) Scheduler.scheduleAll(context)
        if (b.morningOn != a.morningOn || b.morningMinute != a.morningMinute) Health.resetBaseline(context, today)
        // Reporting the evening's concept from the app clears its nudges if they're still showing.
        val day = FieldDay.current(Store.now())
        if (!before.reportedOn(day) && after.reportedOn(day)) Notifier.clearNudges(context)
        GoogleSync.backupSoon(context)
    }
}

/**
 * Three daily alarms: the morning concept, the evening field report and (optional) a surprise
 * mid-day spot check, plus one just after midnight while there is a home-screen widget to turn to
 * the new day. Exact only where Android grants that by default (Android 12 and older); elsewhere
 * inexact alarms, which need no special permission and are kind to the battery, but may arrive up
 * to about an hour late. Each one re-arms itself, and boot, app updates and clock or time-zone
 * changes re-arm them all. Nothing is armed until the welcome steps are done.
 */
object Scheduler {
    const val ACTION_MORNING = "com.rajan.mindfield.MORNING"
    const val ACTION_EVENING = "com.rajan.mindfield.EVENING"
    const val ACTION_SPOT = "com.rajan.mindfield.SPOT"
    const val ACTION_MIDNIGHT = "com.rajan.mindfield.MIDNIGHT"
    private val OWN = setOf(ACTION_MORNING, ACTION_EVENING, ACTION_SPOT, ACTION_MIDNIGHT)

    /**
     * A reminder that is due but hasn't arrived (an inexact alarm running late, or the phone was
     * off at the time) still comes if the alarms are re-armed within this long after its time,
     * instead of being moved to tomorrow.
     */
    private const val GRACE_MS = 2 * 60 * 60 * 1000L

    /** One alarm: [after] gives its first time strictly after a moment. */
    private class Alarm(val action: String, val code: Int, val on: Boolean, val wakeup: Boolean, val after: (ZonedDateTime) -> ZonedDateTime)

    private fun alarms(context: Context): List<Alarm> {
        val s = Store.init(context).state.value.settings
        // Until the welcome steps are done: no reminders, and no concept picked early.
        val ready = s.onboarded
        return listOf(
            Alarm(ACTION_MORNING, 1, ready && s.morningOn, true) { Schedule.next(s.morningMinute, it) },
            Alarm(ACTION_EVENING, 2, ready && s.eveningOn, true) { Schedule.next(s.eveningMinute, it) },
            Alarm(ACTION_SPOT, 3, ready && s.spotCheckOn, true) { Schedule.nextSpot(it) },
            // Not worth waking the phone for: it runs when the screen next comes on.
            Alarm(ACTION_MIDNIGHT, 4, ready && TodayWidget.installed(context), false) { Schedule.next(1, it) },
        )
    }

    /** Re-arms every alarm for the current settings, clock and time zone. */
    fun scheduleAll(context: Context) = alarms(context).forEach { arm(context, it, keepArmed = false) }

    /**
     * Opening the app: arms what isn't armed, and leaves alone what is, so opening the app between
     * a reminder's time and its late arrival doesn't move it to tomorrow.
     */
    fun ensureAll(context: Context) = alarms(context).forEach { arm(context, it, keepArmed = true) }

    /** An alarm went off: that one moves on to its next time, and a system broadcast re-arms them all. */
    fun handled(context: Context, action: String?) {
        if (action !in OWN) return scheduleAll(context)
        prefs(context).edit { remove(action) }
        alarms(context).firstOrNull { it.action == action }?.let { arm(context, it, keepArmed = false) }
    }

    /** True when reminders come on the minute; otherwise Android may deliver them a little late. */
    fun exact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun intent(context: Context, action: String) = Intent(context, AlarmReceiver::class.java).setAction(action)

    private fun existing(context: Context, alarm: Alarm): PendingIntent? =
        PendingIntent.getBroadcast(context, alarm.code, intent(context, alarm.action), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(context: Context, alarm: Alarm, keepArmed: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = prefs(context)
        if (!alarm.on) {
            existing(context, alarm)?.let { manager.cancel(it); it.cancel() }
            prefs.edit { remove(alarm.action) }
            return
        }
        val now = Store.now()
        val nowMs = now.toInstant().toEpochMilli()
        // When the armed alarm is due; delivering it clears this, so a time in the past means "not arrived yet".
        val armedFor = prefs.getLong(alarm.action, 0L)
        if (keepArmed && armedFor > nowMs - GRACE_MS && existing(context, alarm) != null) return
        // Still one of this alarm's times (not a time since changed, or another time zone's)?
        val stillDue = armedFor in (nowMs - GRACE_MS)..nowMs &&
            alarm.after(Instant.ofEpochMilli(armedFor - 1).atZone(now.zone)).toInstant().toEpochMilli() == armedFor
        val at = if (stillDue) armedFor else alarm.after(now).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context, alarm.code, intent(context, alarm.action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.cancel(pending)
        if (alarm.action == ACTION_MORNING) Health.startCounting(context, Store.today())
        if (!alarm.wakeup) {
            manager.set(AlarmManager.RTC, at, pending)
        } else {
            // On time where Android allows it without asking (Android 12 and older); otherwise a few
            // minutes late is still fine for a nudge.
            val set = runCatching {
                if (exact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            if (set.isFailure) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        prefs.edit { putLong(alarm.action, at) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store.init(context)
        when (intent.action) {
            Scheduler.ACTION_MORNING -> {
                Notifier.morning(context)
                Health.morningFired(context, store.today())
            }
            Scheduler.ACTION_EVENING -> Notifier.evening(context)
            Scheduler.ACTION_SPOT -> Notifier.spot(context)
        }
        // After firing, that alarm moves on; boot, app update, clock or time-zone change re-arm all.
        Scheduler.handled(context, intent.action)
        TodayWidget.refresh(context)
        store.flush()
    }
}

/** The notifications themselves, including logging straight from the evening one. */
object Notifier {
    private const val CH_DAILY = "daily"
    private const val CH_REPORT = "report"
    private const val CH_SPOT = "spot"
    private const val CH_BACKUP = "backup"
    private const val CH_CONFIRM = "confirm"
    const val ID_MORNING = 1
    const val ID_BACKUP = 4
    const val ID_EVENING = 2
    const val ID_SPOT = 3

    const val ACTION_LOG = "com.rajan.mindfield.LOG"
    const val ACTION_NOTE = "com.rajan.mindfield.NOTE"
    const val EXTRA_MODE = "mode"
    const val EXTRA_CONCEPT = "concept"
    const val EXTRA_ENTRY = "entry"
    const val KEY_NOTE = "note"
    private const val KEY_ASKED = "asked_permission"

    /** Set while a notification's own button logs, so that notification turns into "Logged" in place. */
    private val loggingFromNotification = ThreadLocal<Boolean>()

    fun channels(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CH_DAILY, "Today's concept", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "One idea about the mind, each morning."
        })
        m.createNotificationChannel(NotificationChannel(CH_REPORT, "Evening field report", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A one-tap check-in: how did today's concept show up?"
        })
        m.createNotificationChannel(NotificationChannel(CH_SPOT, "Spot checks", NotificationManager.IMPORTANCE_LOW).apply {
            description = "An optional surprise nudge during the day."
        })
        m.createNotificationChannel(NotificationChannel(CH_BACKUP, "Backup problems", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tells you if the Google backup stops, so it never fails silently."
        })
        m.createNotificationChannel(NotificationChannel(CH_CONFIRM, "Confirmations", NotificationManager.IMPORTANCE_LOW).apply {
            description = "\u201cLogged\u201d and \u201cSaved\u201d after you log from a notification, without a sound."
        })
    }

    fun backupProblem(context: Context, message: String) {
        channels(context)
        val n = Notification.Builder(context, CH_BACKUP)
            .setSmallIcon(R.drawable.ic_stat_mindfield)
            .setContentTitle("Google backup paused")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open(context, 50, MainActivity.ACTION_ACCOUNT))
            .setAutoCancel(true)
            .build()
        post(context, ID_BACKUP, n)
    }

    /** Backups work again: the "paused" notification no longer applies. */
    fun clearBackupProblem(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_BACKUP) }
    }

    /** Whether Android lets the app post at all: the permission (Android 13 and later) and the app's notification switch. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }

    /** False when that kind of notification has been switched off on its own in the phone's settings. */
    private fun channelOn(context: Context, channel: String?): Boolean =
        channel == null ||
            context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    /** Reminders are on, but they can't reach you: notifications are off for the app, or for that reminder. */
    fun blocked(context: Context, settings: Settings): Boolean {
        if (!settings.morningOn && !settings.eveningOn) return false
        return !allowed(context) ||
            (settings.morningOn && !channelOn(context, CH_DAILY)) ||
            (settings.eveningOn && !channelOn(context, CH_REPORT))
    }

    /** Android shows its permission prompt only until you've said no twice; after that, it's the settings page. */
    fun askedBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) = prefs(context).edit { putBoolean(KEY_ASKED, true) }

    /** The app's notification settings, where notifications (or one kind of them) can be turned back on. */
    fun openSettings(context: Context) {
        val tries = listOf(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (intent in tries) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    private fun post(context: Context, id: Int, n: Notification) {
        if (!allowed(context) || !channelOn(context, n.channelId)) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun open(context: Context, code: Int, action: String? = null, concept: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java)
                .setAction(action ?: Intent.ACTION_MAIN)
                .putExtra(EXTRA_CONCEPT, concept)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun builder(context: Context, channel: String, concept: Concept) =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mindfield)
            .setColor(Palettes.plate(concept.category).toArgb())
            .setLargeIcon(Emblems.bitmap(context, concept, 160))
            .setAutoCancel(true)

    /** Only once the welcome steps are done, and only if it can be shown: a concept no one sees isn't picked. */
    private fun canShow(context: Context, channel: String): Boolean =
        Store.init(context).state.value.settings.onboarded && allowed(context) && channelOn(context, channel)

    fun morning(context: Context) {
        channels(context)
        // Last night's unanswered nudges are out of date by now.
        clearNudges(context)
        if (!canShow(context, CH_DAILY)) return
        val store = Store.init(context)
        val c = store.todayConcept()
        val due = Spacing.due(store.state.value, store.library, store.today()).size
        val big = buildString {
            append(c.hook)
            // The guess starts on the lock screen: just thinking of an answer primes the memory.
            append("\n\n🔮 Predict first: ").append(c.predict.question)
            append("\n🎯 Today's mission: ").append(c.missionLine)
            if (due > 0) append("\n🃏 ").append(if (due == 1) "1 quick review" else "$due quick reviews").append(" waiting")
        }
        val n = builder(context, CH_DAILY, c)
            .setContentTitle("${c.title}")
            .setSubText(if (c.isMyth) "Myth day" else c.category.short)
            .setContentText(c.hook)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(open(context, 10, MainActivity.ACTION_TODAY))
            .addAction(Notification.Action.Builder(null as Icon?, "Predict first", open(context, 11, MainActivity.ACTION_TODAY)).build())
            .build()
        post(context, ID_MORNING, n)
    }

    /**
     * The concept an evening nudge is about: today's, or yesterday's until 4 am (an evening time
     * after midnight, or a late alarm). Null when that day had no concept or it's been reported.
     */
    private fun nudgeConcept(store: Store, now: ZonedDateTime): Pair<LocalDate, Concept>? {
        val day = FieldDay.current(now)
        val concept = if (day == now.toLocalDate()) store.todayConcept() else store.conceptOn(day) ?: return null
        return if (store.state.value.reportedOn(day)) null else day to concept
    }

    /** The field report. Skipped once the day's concept is logged, and gone by 4 am. */
    fun evening(context: Context) {
        channels(context)
        if (!canShow(context, CH_REPORT)) return
        val store = Store.init(context)
        val now = store.now()
        val (day, c) = nudgeConcept(store, now) ?: return
        val plan = store.state.value.plans[day]?.text?.takeIf { it.isNotBlank() }
        val text = if (plan != null) "You planned: \u201c$plan\u201d. How did it go?" else "How did it show up today? One tap logs it."
        val b = builder(context, CH_REPORT, c)
            .setContentTitle("Field report · ${c.title}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n\nWatch for it: ${Concept.firstSentence(c.spot)}"))
            .setContentIntent(open(context, 20, MainActivity.ACTION_LOG, c.id))
            .setTimeoutAfter(Duration.between(now, FieldDay.expires(now)).toMillis())
        for (mode in listOf(Mode.SPOTTED, Mode.MYSELF, Mode.USED)) {
            b.addAction(Notification.Action.Builder(null as Icon?, "${mode.emoji} ${mode.short}", logIntent(context, c.id, mode)).build())
        }
        post(context, ID_EVENING, b.build())
    }

    fun spot(context: Context) {
        channels(context)
        if (!canShow(context, CH_SPOT)) return
        val store = Store.init(context)
        val now = store.now()
        val (_, c) = nudgeConcept(store, now) ?: return
        val n = builder(context, CH_SPOT, c)
            .setContentTitle("Spot check 🔍 ${c.title}")
            .setContentText(Concept.firstSentence(c.spot))
            .setStyle(Notification.BigTextStyle().bigText(c.spot))
            .setContentIntent(open(context, 30, MainActivity.ACTION_LOG, c.id))
            .setTimeoutAfter(Duration.between(now, FieldDay.expires(now)).toMillis())
            .build()
        post(context, ID_SPOT, n)
    }

    private fun logIntent(context: Context, conceptId: String, mode: Mode): PendingIntent =
        PendingIntent.getBroadcast(
            context, 100 + mode.ordinal,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_LOG)
                .putExtra(EXTRA_MODE, mode.key).putExtra(EXTRA_CONCEPT, conceptId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** After a one-tap log: "Logged ✓", with a reply box to add a line about it. Quiet: you just tapped. */
    fun logged(context: Context, concept: Concept, mode: Mode, entryId: String) {
        channels(context)
        val reply = PendingIntent.getBroadcast(
            context, 200,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_NOTE).putExtra(EXTRA_ENTRY, entryId),
            // A reply action's intent must be mutable so the system can add the typed text.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_NOTE).setLabel(mode.prompt).build()
        val n = builder(context, CH_CONFIRM, concept)
            .setContentTitle("${mode.emoji} Logged: ${mode.label}")
            .setContentText("${concept.title} · add a line about it?")
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, 21, MainActivity.ACTION_JOURNAL))
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_mindfield), "✍️ Add a note", reply)
                    .addRemoteInput(input)
                    .build(),
            )
            .build()
        post(context, ID_EVENING, n)
    }

    fun noteSaved(context: Context, concept: Concept) {
        channels(context)
        val n = builder(context, CH_CONFIRM, concept)
            .setContentTitle("Saved to your field journal ✓")
            .setContentText(concept.title)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(4_000)
            .setContentIntent(open(context, 22, MainActivity.ACTION_JOURNAL))
            .build()
        post(context, ID_EVENING, n)
    }

    /** Ends a reply that had nothing to save, so the notification doesn't keep waiting. */
    fun cancelReport(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_EVENING) }
    }

    fun clearNudges(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        if (loggingFromNotification.get() != true) {
            // Keep a "Logged" confirmation that is waiting for a note; only clear the unanswered nudges.
            val evening = runCatching { m.activeNotifications }.getOrNull()?.firstOrNull { it.id == ID_EVENING }
            if (evening != null && evening.notification.actions?.any { it.remoteInputs != null } != true) m.cancel(ID_EVENING)
        }
        m.cancel(ID_SPOT)
    }

    /** Runs a log made by a notification's own button (see [loggingFromNotification]). */
    fun <T> fromNotification(log: () -> T): T {
        loggingFromNotification.set(true)
        try {
            return log()
        } finally {
            loggingFromNotification.remove()
        }
    }
}

/** Handles the buttons on the evening notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store.init(context)
        when (intent.action) {
            Notifier.ACTION_LOG -> {
                val mode = Mode.of(intent.getStringExtra(Notifier.EXTRA_MODE).orEmpty()) ?: return
                val concept = intent.getStringExtra(Notifier.EXTRA_CONCEPT)?.let { store.library[it] } ?: store.todayConcept()
                // Tapped after midnight? Until 4 am it counts for the day the concept was shown.
                val now = store.now()
                val day = FieldDay.of(store.state.value, concept.id, now)
                // A second tap before the notification changed: the same report, not a copy.
                val nowMs = now.toInstant().toEpochMilli()
                val again = store.state.value.liveEntries.lastOrNull {
                    it.conceptId == concept.id && it.day == day && it.mode == mode && nowMs - it.createdAt in 0..DOUBLE_TAP_MS
                }
                val entry = again ?: Notifier.fromNotification { store.log(concept.id, mode, day = day) }
                Notifier.logged(context, concept, mode, entry.id)
            }
            Notifier.ACTION_NOTE -> {
                val id = intent.getStringExtra(Notifier.EXTRA_ENTRY) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_NOTE)?.toString().orEmpty()
                val concept = store.addNote(id, text)?.conceptId?.let { store.library[it] }
                // Always answer a reply, or the notification keeps waiting.
                if (concept != null) Notifier.noteSaved(context, concept) else Notifier.cancelReport(context)
            }
        }
        store.flush()
    }

    private companion object {
        const val DOUBLE_TAP_MS = 30_000L
    }
}
