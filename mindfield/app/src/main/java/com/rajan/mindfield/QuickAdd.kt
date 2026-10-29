package com.rajan.mindfield

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

/**
 * "Add lesson" in the quick-settings panel: one tap from anywhere opens a new lesson, ready to
 * type. (The other ways in: the app icon's long-press menu, and sharing text to Mindfield.)
 */
class LessonTile : TileService() {
    override fun onStartListening() {
        qsTile?.run {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onTileAdded() = QuickAdd.markTile(this, true)

    override fun onTileRemoved() = QuickAdd.markTile(this, false)

    override fun onClick() {
        // On a locked phone, unlock first: lessons are private.
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        val intent = QuickAdd.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Receives text shared from another app (Gemini, a chat, a note) and passes it on as a new lesson.
 * A separate, invisible step so Mindfield opens in its own task, not inside the app that shared.
 */
class ShareLessonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        startActivity(
            QuickAdd.intent(this, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

object QuickAdd {
    const val EXTRA_TEXT = "lesson_text"
    /** Shared text past this is cut before it's passed on; the editor cuts it again to fit. */
    private const val SHARE_LIMIT = 4_000
    private const val PREFS = "quick_add"
    private const val KEY_TILE = "tile_added"

    /** Opens a new lesson in the app, with [text] (shared from elsewhere) to start it from. */
    fun intent(context: Context, text: String = ""): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_ADD_LESSON)
            .putExtra(EXTRA_TEXT, com.rajan.mindfield.core.Texts.clip(text, SHARE_LIMIT))

    /** Asking Android to add the tile needs Android 13; before that it's added by hand from the panel's edit view. */
    fun canOfferTile(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 && !tileAdded(context)

    fun tileAdded(context: Context): Boolean = prefs(context).getBoolean(KEY_TILE, false)

    fun markTile(context: Context, added: Boolean) = prefs(context).edit { putBoolean(KEY_TILE, added) }

    /** Android's "Add this tile?" prompt; [onResult] hears whether the tile is there now. */
    fun requestTile(context: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, LessonTile::class.java),
                context.getString(R.string.tile_add_lesson),
                Icon.createWithResource(context, R.drawable.ic_stat_mindfield),
                context.mainExecutor,
            ) { result ->
                val there = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                if (there) markTile(context, true)
                onResult(there)
            }
        }.onFailure { onResult(false) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
