package com.rajan.mindfield

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Curriculum
import java.time.LocalDate

/**
 * Home-screen widget: today's concept with its emblem and one line, plus "Log it" for the
 * evening report, so the idea stays in sight all day (that's how you start spotting it).
 * It turns to the new day just after midnight, and redraws whenever what it shows changes.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        for (id in ids) manager.updateAppWidget(id, views)
        // A widget on the home screen needs the midnight alarm.
        Scheduler.ensureAll(context)
    }

    override fun onDisabled(context: Context) {
        // The last widget is gone, and with it the need for the midnight alarm.
        Scheduler.scheduleAll(context)
    }

    companion object {
        fun installed(context: Context): Boolean = ids(context).isNotEmpty()

        private fun ids(context: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)) }
                .getOrNull() ?: IntArray(0)

        /** What the widget shows: it is redrawn only when this changes. */
        fun key(state: AppState, today: LocalDate): Any =
            Triple(state.settings.onboarded, state.assignments[today]?.conceptId, state.reportedOn(today))

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = ids(context)
            if (ids.isEmpty()) return
            val views = views(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        fun views(context: Context): RemoteViews {
            val store = Store.init(context)
            if (!store.state.value.settings.onboarded) return welcome(context)
            val c = store.todayConcept()
            val logged = store.state.value.reportedOn(store.today())
            return RemoteViews(context.packageName, R.layout.widget_today).apply {
                setInt(R.id.widget_bg, "setColorFilter", Palettes.plate(c.category).toArgb())
                setImageViewBitmap(R.id.widget_emblem, Emblems.bitmap(context, c, 200))
                setTextViewText(R.id.widget_label, "TODAY · ${c.numberLabel.uppercase()} · ${c.category.short.uppercase()}")
                setTextViewText(R.id.widget_title, c.title)
                setTextViewText(R.id.widget_hook, c.hook)
                setTextViewText(R.id.widget_log, if (logged) "Logged ✓" else "Log it")
                setOnClickPendingIntent(R.id.widget_root, open(context, 40, MainActivity.ACTION_TODAY))
                setOnClickPendingIntent(R.id.widget_log, open(context, 41, MainActivity.ACTION_LOG, c.id))
            }
        }

        /** Before the welcome steps are done: an invitation to finish them, not a concept picked early. */
        private fun welcome(context: Context): RemoteViews {
            val store = Store.init(context)
            val first = store.library[Curriculum.FIRST] ?: store.library.all.first()
            return RemoteViews(context.packageName, R.layout.widget_today).apply {
                setInt(R.id.widget_bg, "setColorFilter", Palettes.plate(first.category).toArgb())
                setImageViewBitmap(R.id.widget_emblem, Emblems.bitmap(context, first, 200))
                setTextViewText(R.id.widget_label, "MINDFIELD")
                setTextViewText(R.id.widget_title, "A field guide to the human mind")
                setTextViewText(R.id.widget_hook, "Finish setting up to meet your first concept.")
                setTextViewText(R.id.widget_log, "Open")
                setOnClickPendingIntent(R.id.widget_root, open(context, 40, MainActivity.ACTION_TODAY))
                setOnClickPendingIntent(R.id.widget_log, open(context, 40, MainActivity.ACTION_TODAY))
            }
        }

        private fun open(context: Context, code: Int, action: String, concept: String? = null) = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action).putExtra(Notifier.EXTRA_CONCEPT, concept)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
