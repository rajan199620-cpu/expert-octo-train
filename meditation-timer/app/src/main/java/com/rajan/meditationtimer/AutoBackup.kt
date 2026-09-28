package com.rajan.meditationtimer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import java.time.ZoneId

/**
 * Mirrors the history to Downloads/Meditation Timer/meditation-history.csv after every change.
 * Files in Downloads survive uninstalling the app, so a reinstall (or a new phone, after copying
 * the file) never loses history: History -> Restore and pick that file.
 * Android 10+ only: older versions would need a storage permission for this.
 */
object AutoBackup {
    private const val FILE_NAME = "meditation-history.csv"
    private val FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Meditation Timer"
    const val LOCATION = "Downloads/Meditation Timer/$FILE_NAME"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun write(context: Context, records: List<SessionRecord>) {
        if (!supported || records.isEmpty()) return
        val csv = History.toCsv(records, ZoneId.systemDefault())
        val prefs = context.getSharedPreferences("autobackup", Context.MODE_PRIVATE)
        // Keep rewriting the same file; if the user deleted it, quietly create a fresh one.
        val saved = prefs.getString(KEY_URI, null)?.let(Uri::parse)
        if (saved != null && writeTo(context, saved, csv)) return
        val created = create(context) ?: return
        if (writeTo(context, created, csv)) prefs.edit { putString(KEY_URI, created.toString()) }
    }

    private fun create(context: Context): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun writeTo(context: Context, uri: Uri, csv: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(csv) }
    }.isSuccess

    private const val KEY_URI = "uri"
}
