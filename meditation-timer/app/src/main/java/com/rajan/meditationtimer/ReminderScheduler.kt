package com.rajan.meditationtimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.time.ZonedDateTime

/**
 * Schedules the daily reminder with an inexact alarm (no special permission, kind to the battery;
 * it may arrive a few minutes late, which is fine for a nudge) and re-arms it after each firing,
 * a reboot, an app update or a time-zone change.
 */
object ReminderScheduler {
    private const val ACTION_REMIND = "com.rajan.meditationtimer.REMIND"
    private const val CHANNEL_ID = "reminders"
    private const val NOTIFICATION_ID = 2

    fun schedule(context: Context) {
        val reminder = Prefs(context).reminder
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        if (!reminder.enabled) return
        val at = reminder.nextAt(ZonedDateTime.now()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun fire(context: Context) {
        val prefs = Prefs(context)
        val reminder = prefs.reminder
        if (!reminder.enabled) return
        val zone = java.time.ZoneId.systemDefault()
        val sitDays = SessionLog.get(context).records.value.map { it.day(zone) }.toSet()
        if (!Reminder.shouldNotify(sitDays, ZonedDateTime.now(zone))) return
        if (SessionRepository.state.value !is SessionState.Idle) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val minutes = prefs.timerConfig.durationSec / 60
        val (title, text) = reminder.message(minutes)
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val start = PendingIntent.getActivity(
            context, 11,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_QUICK_SIT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meditation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_meditation),
                    context.getString(R.string.reminder_start, minutes),
                    start,
                ).build(),
            )
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** Clears today's nudge once you've sat. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.rajan.meditationtimer.REMIND") ReminderScheduler.fire(context)
        // Every path re-arms: after firing, boot, app update, clock or time-zone change.
        ReminderScheduler.schedule(context)
    }
}
