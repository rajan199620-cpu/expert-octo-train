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
import android.os.Build
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Schedule
import com.rajan.mindfield.core.Spacing
import androidx.compose.ui.graphics.toArgb

/** What has to happen after any change to your data, whichever screen or receiver made it. */
object Effects {
    fun onChanged(context: Context, before: AppState, after: AppState) {
        TodayWidget.refresh(context)
        if (before.settings != after.settings) Scheduler.scheduleAll(context)
        // Logging today's concept from the app clears the evening nudge if it's still showing.
        val today = Store.today()
        if (before.entriesOn(today).isEmpty() && after.entriesOn(today).isNotEmpty()) Notifier.clearNudges(context)
        val dataChanged = before.copy(settings = after.settings) != after
        if (dataChanged || before.settings != after.settings) GoogleSync.backupSoon(context)
    }
}

/**
 * Three daily alarms: the morning concept, the evening field report and (optional) a surprise
 * mid-day spot check. Inexact alarms that may run a few minutes late, which needs no special
 * permission and is kind to the battery. Each one re-arms itself, and boot, app updates and
 * clock or time-zone changes re-arm them all.
 */
object Scheduler {
    const val ACTION_MORNING = "com.rajan.mindfield.MORNING"
    const val ACTION_EVENING = "com.rajan.mindfield.EVENING"
    const val ACTION_SPOT = "com.rajan.mindfield.SPOT"

    fun scheduleAll(context: Context) {
        val settings = Store.init(context).state.value.settings
        val now = Store.now()
        arm(context, ACTION_MORNING, 1, settings.morningOn) { Schedule.next(settings.morningMinute, now) }
        arm(context, ACTION_EVENING, 2, settings.eveningOn) { Schedule.next(settings.eveningMinute, now) }
        arm(context, ACTION_SPOT, 3, settings.spotCheckOn) { Schedule.nextSpot(now) }
    }

    private fun arm(context: Context, action: String, code: Int, on: Boolean, at: () -> java.time.ZonedDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, code,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(pending)
        if (on) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at().toInstant().toEpochMilli(), pending)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store.init(context)
        when (intent.action) {
            Scheduler.ACTION_MORNING -> Notifier.morning(context)
            Scheduler.ACTION_EVENING -> Notifier.evening(context)
            Scheduler.ACTION_SPOT -> Notifier.spot(context)
        }
        // Every path re-arms: after firing, boot, app update, clock or time-zone change.
        Scheduler.scheduleAll(context)
        TodayWidget.refresh(context)
        store.flush()
    }
}

/** The notifications themselves, including logging straight from the evening one. */
object Notifier {
    private const val CH_DAILY = "daily"
    private const val CH_REPORT = "report"
    private const val CH_SPOT = "spot"
    const val ID_MORNING = 1
    const val ID_EVENING = 2
    const val ID_SPOT = 3

    const val ACTION_LOG = "com.rajan.mindfield.LOG"
    const val ACTION_NOTE = "com.rajan.mindfield.NOTE"
    const val EXTRA_MODE = "mode"
    const val EXTRA_CONCEPT = "concept"
    const val EXTRA_ENTRY = "entry"
    const val KEY_NOTE = "note"

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
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, id: Int, n: Notification) {
        if (!allowed(context)) return
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

    fun morning(context: Context) {
        channels(context)
        val store = Store.init(context)
        val c = store.todayConcept()
        val due = Spacing.due(store.state.value, store.library, store.today()).size
        val big = buildString {
            append(c.hook)
            append("\n\n🎯 Today's mission: ").append(c.missionLine)
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

    /** The field report. Skipped if you've already logged today. */
    fun evening(context: Context) {
        channels(context)
        val store = Store.init(context)
        val c = store.todayConcept()
        if (store.state.value.entriesOn(store.today()).isNotEmpty()) return
        val plan = store.state.value.plans[store.today()]?.text?.takeIf { it.isNotBlank() }
        val text = if (plan != null) "You planned: “$plan”. How did it go?" else "How did it show up today? One tap logs it."
        val b = builder(context, CH_REPORT, c)
            .setContentTitle("Field report · ${c.title}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n\nWatch for it: ${Concept.firstSentence(c.spot)}"))
            .setContentIntent(open(context, 20, MainActivity.ACTION_LOG, c.id))
        for (mode in listOf(Mode.SPOTTED, Mode.MYSELF, Mode.USED)) {
            b.addAction(Notification.Action.Builder(null as Icon?, "${mode.emoji} ${mode.short}", logIntent(context, c.id, mode)).build())
        }
        post(context, ID_EVENING, b.build())
    }

    fun spot(context: Context) {
        channels(context)
        val store = Store.init(context)
        val c = store.todayConcept()
        if (store.state.value.entriesOn(store.today()).isNotEmpty()) return
        val n = builder(context, CH_SPOT, c)
            .setContentTitle("Spot check 🔍 ${c.title}")
            .setContentText(Concept.firstSentence(c.spot))
            .setStyle(Notification.BigTextStyle().bigText(c.spot))
            .setContentIntent(open(context, 30, MainActivity.ACTION_LOG, c.id))
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

    /** After a one-tap log: "Logged ✓", with a reply box to add a line about it. */
    fun logged(context: Context, concept: Concept, mode: Mode, entryId: String) {
        val reply = PendingIntent.getBroadcast(
            context, 200,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_NOTE).putExtra(EXTRA_ENTRY, entryId),
            // A reply action's intent must be mutable so the system can add the typed text.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_NOTE).setLabel(mode.prompt).build()
        val n = builder(context, CH_REPORT, concept)
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
        val n = builder(context, CH_REPORT, concept)
            .setContentTitle("Saved to your field journal ✓")
            .setContentText(concept.title)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(4_000)
            .setContentIntent(open(context, 22, MainActivity.ACTION_JOURNAL))
            .build()
        post(context, ID_EVENING, n)
    }

    fun clearNudges(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java)
        // Keep a "Logged" confirmation that is waiting for a note; only clear the unanswered nudges.
        val evening = m.activeNotifications.firstOrNull { it.id == ID_EVENING }
        if (evening != null && evening.notification.actions?.any { it.remoteInputs != null } != true) m.cancel(ID_EVENING)
        m.cancel(ID_SPOT)
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
                // Tapped after midnight? Log it against the day the concept was shown.
                val today = store.today()
                val day = store.state.value.assignments.filterValues { it.conceptId == concept.id }.keys
                    .filter { it <= today }.maxOrNull()?.takeIf { it >= today.minusDays(1) } ?: today
                val entry = store.log(concept.id, mode, day = day)
                Notifier.logged(context, concept, mode, entry.id)
            }
            Notifier.ACTION_NOTE -> {
                val id = intent.getStringExtra(Notifier.EXTRA_ENTRY) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_NOTE)?.toString().orEmpty()
                store.addNote(id, text)
                val concept = store.state.value.entries.firstOrNull { it.id == id }?.conceptId?.let { store.library[it] } ?: return
                Notifier.noteSaved(context, concept)
            }
        }
        store.flush()
    }
}
