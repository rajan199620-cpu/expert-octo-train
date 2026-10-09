package com.rajan.mindfield

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb

/**
 * Home-screen widget: today's concept with its emblem and one line, plus "Log it" for the
 * evening report, so the idea stays in sight all day (that's how you start spotting it).
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            val views = views(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        fun views(context: Context): RemoteViews {
            val store = Store.init(context)
            val c = store.todayConcept()
            val logged = store.state.value.entriesOn(store.today()).isNotEmpty()
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

        private fun open(context: Context, code: Int, action: String, concept: String? = null) = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action).putExtra(Notifier.EXTRA_CONCEPT, concept)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
