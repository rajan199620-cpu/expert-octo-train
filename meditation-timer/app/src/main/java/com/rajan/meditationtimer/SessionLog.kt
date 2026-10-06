package com.rajan.meditationtimer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Log of finished sessions, one CSV line each, in app-private storage (included in Android's
 * automatic backup, so history follows the user to a new phone). Sits you delete leave a
 * one-line marker ("x,<start minute>") behind, so no backup can bring them back.
 */
class SessionLog private constructor(private val context: Context, private val file: File) {
    private val _records = MutableStateFlow(emptyList<SessionRecord>())
    val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

    /** Start minutes (epoch minutes) of the sits deleted on purpose. */
    @Volatile var deleted: Set<Long> = emptySet()
        private set

    init {
        load()
    }

    @Synchronized
    fun add(record: SessionRecord) {
        // A new sit in the very minute of a deleted one (the clock was changed) is still a sit.
        if (key(record) in deleted) {
            deleted = deleted - key(record)
            rewrite(_records.value + record)
        } else {
            file.appendText(record.encode() + "\n")
            _records.value = _records.value + record
            AutoBackup.write(context, _records.value, deleted)
            GoogleBackup.backupSoon(context)
            SitWidget.refresh(context)
        }
        ReminderScheduler.dismiss(context)
    }

    /** Attaches the post-sit reflection to an already logged session. */
    @Synchronized
    fun annotate(startedAtMs: Long, rating: Int, note: String, after: Int = 0) {
        val updated = _records.value.map {
            if (it.startedAtMs == startedAtMs) it.copy(rating = rating, note = note.trim(), after = after) else it
        }
        rewrite(updated)
    }

    /** Removes a sit for good: from here, and (through the marker it leaves) from every backup. */
    @Synchronized
    fun delete(startedAtMs: Long) {
        val gone = startedAtMs / 60_000
        deleted = deleted + gone
        rewrite(_records.value.filter { key(it) != gone })
    }

    /**
     * Restores sessions from a backup. Sessions already here (same start minute) are kept as
     * they are, so restoring the same file twice changes nothing, and restoring the note-less
     * automatic copy never erases notes already on the phone. Sits deleted here or in the
     * backup ([deletedThere]) stay deleted. Returns how many were added.
     */
    @Synchronized
    fun merge(imported: List<SessionRecord>, deletedThere: Set<Long> = emptySet()): Int {
        val allDeleted = deleted + deletedThere
        val kept = _records.value.filter { key(it) !in allDeleted }
        val existing = (kept.map(::key) + allDeleted).toMutableSet()
        val added = imported.filter { existing.add(key(it)) }
        if (added.isNotEmpty() || kept.size != _records.value.size || allDeleted != deleted) {
            deleted = allDeleted
            rewrite((kept + added).sortedBy { it.startedAtMs })
        }
        return added.size
    }

    /** Writes both backups again, for history that lives outside this log (attention-check scores). */
    fun backUp() {
        AutoBackup.write(context, _records.value, deleted)
        GoogleBackup.backupSoon(context)
    }

    private fun key(record: SessionRecord) = record.startedAtMs / 60_000

    private fun rewrite(records: List<SessionRecord>) {
        // Write-then-rename so a crash mid-write can't truncate the history.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(records.joinToString("") { it.encode() + "\n" } + deleted.sorted().joinToString("") { "$DELETED_MARK$it\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _records.value = records
        AutoBackup.write(context, records, deleted)
        GoogleBackup.backupSoon(context)
        SitWidget.refresh(context)
    }

    private fun load() {
        if (!file.exists()) return
        val lines = file.readLines()
        deleted = lines.filter { it.startsWith(DELETED_MARK) }.mapNotNull { it.removePrefix(DELETED_MARK).trim().toLongOrNull() }.toSet()
        _records.value = lines.mapNotNull(SessionRecord::decode).filter { key(it) !in deleted }
    }

    companion object {
        /** Two fields, so the record decoder (which needs 3, 5 or 8) skips the line. */
        private const val DELETED_MARK = "x,"

        @Volatile
        private var instance: SessionLog? = null

        /** Tests only: forget the instance so the next [get] reads a fresh file. */
        internal fun resetForTests() {
            instance = null
        }

        fun get(context: Context): SessionLog = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                SessionLog(app, File(app.filesDir, "sessions.csv")).also { instance = it }
            }
        }
    }
}
