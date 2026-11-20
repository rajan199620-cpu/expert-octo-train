package com.rajan.meditationtimer

import android.content.Context
import androidx.core.content.edit

/**
 * The sit under way, kept on disk while it runs (see [SitProgress]), and the note about one that
 * was cut short until the Sit screen has shown it. Its own file, apart from the settings, so a
 * settings backup never carries a sit in progress to another phone.
 */
class SitCheckpoint(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sit_in_progress", Context.MODE_PRIVATE)

    fun save(progress: SitProgress) = sp.edit { putString(KEY_PROGRESS, progress.encode()) }

    fun read(): SitProgress? = SitProgress.decode(sp.getString(KEY_PROGRESS, null))

    /** The sit was logged, or ended too soon to log: nothing to recover. */
    fun clear() = sp.edit { remove(KEY_PROGRESS) }

    var cutShort: CutShort?
        get() = CutShort.decode(sp.getString(KEY_CUT_SHORT, null))
        set(value) = sp.edit { putString(KEY_CUT_SHORT, value?.encode()) }

    /**
     * Called with no sit running, when the app opens: a sit still written down here was cut short
     * when Android closed the app. Logs what was sat (once: it is forgotten here as it is logged)
     * and keeps a note so the Sit screen can say what happened.
     */
    fun recover(log: SessionLog) {
        val progress = read() ?: return
        val alreadyLogged = log.records.value.any { it.startedAtMs / 60_000 == progress.startedAtMs / 60_000 }
        if (!alreadyLogged) {
            progress.record(log.records.value)?.let(log::add)
            cutShort = CutShort.of(progress)
        }
        clear()
    }

    private companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_CUT_SHORT = "cut_short"
    }
}
