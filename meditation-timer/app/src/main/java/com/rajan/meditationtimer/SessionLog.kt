package com.rajan.meditationtimer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Append-only log of finished sessions, one CSV line each, in app-private storage
 * (included in Android's automatic backup, so history follows the user to a new phone).
 */
class SessionLog private constructor(private val file: File) {
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

    @Synchronized
    fun add(record: SessionRecord) {
        file.appendText(record.encode() + "\n")
        _records.value = _records.value + record
    }

    /** Attaches the post-sit reflection to an already logged session. */
    @Synchronized
    fun annotate(startedAtMs: Long, rating: Int, note: String) {
        val updated = _records.value.map {
            if (it.startedAtMs == startedAtMs) it.copy(rating = rating, note = note.trim()) else it
        }
        rewrite(updated)
    }

    /**
     * Restores sessions from a backup. Sessions already here (same start minute) are kept as
     * they are, so restoring the same file twice changes nothing. Returns how many were added.
     */
    @Synchronized
    fun merge(imported: List<SessionRecord>): Int {
        val existing = _records.value.map { it.startedAtMs / 60_000 }.toMutableSet()
        val added = imported.filter { existing.add(it.startedAtMs / 60_000) }
        if (added.isNotEmpty()) rewrite((_records.value + added).sortedBy { it.startedAtMs })
        return added.size
    }

    private fun rewrite(records: List<SessionRecord>) {
        // Write-then-rename so a crash mid-write can't truncate the history.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(records.joinToString("") { it.encode() + "\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _records.value = records
    }

    private fun load(): List<SessionRecord> =
        if (file.exists()) file.readLines().mapNotNull(SessionRecord::decode) else emptyList()

    companion object {
        @Volatile
        private var instance: SessionLog? = null

        fun get(context: Context): SessionLog = instance ?: synchronized(this) {
            instance ?: SessionLog(File(context.applicationContext.filesDir, "sessions.csv")).also { instance = it }
        }
    }
}
