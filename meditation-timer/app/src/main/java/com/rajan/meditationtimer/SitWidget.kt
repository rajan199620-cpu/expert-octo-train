package com.rajan.meditationtimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home-screen widget: this week as seven dots and one button that starts your usual sit,
 * so starting never depends on opening the app (and getting distracted in it) first.
 */
class SitWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        for (id in ids) manager.updateAppWidget(id, views)
        scheduleMidnight(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIDNIGHT) refresh(context) else super.onReceive(context, intent)
    }

    /** The last widget was removed: nothing to redraw at midnight. */
    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(midnightIntent(context))
    }

    companion object {
        private val DOTS = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5, R.id.dot6)
        private const val ACTION_MIDNIGHT = "com.rajan.meditationtimer.WIDGET_MIDNIGHT"

        /** Redraws every placed widget; called after each sit and settings change, and just after midnight. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SitWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            for (id in ids) manager.updateAppWidget(id, views)
            scheduleMidnight(context)
        }

        /**
         * Today's dot moves on at midnight, and Monday starts a fresh row, without waiting hours for
         * the launcher's next update. A plain alarm: it never wakes the phone, and is delivered the
         * next time the screen comes on, which is when the widget can be seen.
         */
        private fun scheduleMidnight(context: Context) {
            val zone = ZoneId.systemDefault()
            val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
            runCatching { context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, midnightIntent(context)) }
        }

        private fun midnightIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 21,
            Intent(context, SitWidget::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun views(context: Context): RemoteViews {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val sitDays = SessionLog.get(context).records.value.map { it.day(zone) }.toSet()
            val week = History.week(sitDays, today)
            val daysSat = week.count { it.second == true }
            val minutes = Prefs(context).timerConfig.durationSec / 60

            return RemoteViews(context.packageName, R.layout.widget_sit).apply {
                setTextViewText(R.id.widget_week, weekLine(daysSat, Prefs(context).weeklyGoal))
                week.forEachIndexed { i, (_, sat) ->
                    setImageViewResource(
                        DOTS[i],
                        when (sat) {
                            true -> R.drawable.widget_dot_on
                            false -> R.drawable.widget_dot_off
                            null -> R.drawable.widget_dot_future
                        },
                    )
                }
                setTextViewText(R.id.widget_start, "Sit · $minutes min")
                setOnClickPendingIntent(
                    R.id.widget_start,
                    PendingIntent.getActivity(
                        context, 20,
                        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK_SIT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 21,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
    }
}
