package com.rajan.mindfield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import com.rajan.mindfield.core.Delivery
import java.time.LocalDate

/**
 * Watches for the two ways a daily app dies quietly: the phone's battery manager stopping the
 * reminders, and the Google backup pausing. Either one shows a banner instead of failing silently.
 */
object Health {
    private const val PREFS = "health"
    private const val KEY_LAST_MORNING = "last_morning"
    private const val KEY_BASELINE = "baseline"
    private const val KEY_SNOOZE = "snooze_until"
    private const val KEY_BACKUP_NOTICE = "backup_notice_day"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun morningFired(context: Context, day: LocalDate) = prefs(context).edit { putLong(KEY_LAST_MORNING, day.toEpochDay()) }

    /** The morning time or switch changed: start counting from today. */
    fun resetBaseline(context: Context, day: LocalDate) = prefs(context).edit { putLong(KEY_BASELINE, day.toEpochDay()) }

    /**
     * The morning reminder is armed for the first time on this install (a new phone, a reinstall):
     * count from today, never from another phone's history or the day you first used the app.
     */
    fun startCounting(context: Context, day: LocalDate) {
        if (!prefs(context).contains(KEY_BASELINE)) resetBaseline(context, day)
    }

    private fun day(context: Context, key: String): LocalDate? =
        prefs(context).getLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)

    /** True when two mornings in a row should have brought a notification and didn't. */
    fun remindersBlocked(context: Context): Boolean {
        val store = Store.init(context)
        val s = store.state.value
        val today = store.today()
        val snooze = day(context, KEY_SNOOZE)
        if (snooze != null && today <= snooze) return false
        val reference = listOfNotNull(day(context, KEY_LAST_MORNING), day(context, KEY_BASELINE), s.startDay).maxOrNull()
        val now = store.now()
        return Delivery.looksBlocked(s.settings.morningOn, reference, today, now.hour * 60 + now.minute, s.settings.morningMinute)
    }

    fun snoozeBanner(context: Context) {
        prefs(context).edit { putLong(KEY_SNOOZE, Store.today().plusDays(7).toEpochDay()) }
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Opens the phone's battery-optimisation list, where Mindfield can be set to "Don't optimise".
     * Falls back to the app's own settings page. (Google Play allows the one-tap exemption prompt
     * only for apps such as navigation and calls, so the list it is.)
     */
    fun openBatterySettings(context: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (intent in tries) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** At most one "backup paused" notification a day. */
    fun shouldNotifyBackup(context: Context): Boolean {
        val today = Store.today().toEpochDay()
        if (prefs(context).getLong(KEY_BACKUP_NOTICE, Long.MIN_VALUE) == today) return false
        prefs(context).edit { putLong(KEY_BACKUP_NOTICE, today) }
        return true
    }

    /** Backup is linked but hasn't succeeded for three days, or Google needs a fresh sign-in. */
    fun backupStale(cloud: CloudState, nowMs: Long): Boolean =
        cloud.email != null && !cloud.busy && (cloud.problem || nowMs - cloud.lastSyncMs > 3L * 24 * 3600 * 1000)

    fun resetForTests(context: Context) = prefs(context).edit { clear() }
}
