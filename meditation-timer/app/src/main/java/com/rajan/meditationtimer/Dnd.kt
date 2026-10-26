package com.rajan.meditationtimer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit

/**
 * Optional "silence my phone while I sit": switches to Priority-only Do Not Disturb for the
 * session and switches it back afterwards. Priority mode keeps the user's own exceptions
 * (starred contacts, repeat callers) and lets alarms through, so the bells still ring.
 */
class Dnd(context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    // Persisted, so DND is still restored if the app is killed mid-session.
    private val prefs = context.getSharedPreferences("dnd", Context.MODE_PRIVATE)

    val hasAccess: Boolean get() = manager.isNotificationPolicyAccessGranted

    fun engage() {
        if (!hasAccess) return
        // Never override a Do Not Disturb the user turned on themselves.
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        prefs.edit { putBoolean(KEY_ENGAGED, true) }
    }

    fun restore() {
        if (!prefs.getBoolean(KEY_ENGAGED, false)) return
        prefs.edit { putBoolean(KEY_ENGAGED, false) }
        // If the user changed DND themselves during the sit, leave their choice alone.
        if (hasAccess && manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    companion object {
        private const val KEY_ENGAGED = "engaged"

        val accessSettings: Intent get() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }
}
