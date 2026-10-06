package com.rajan.mindfield.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps Google backup work tied to the link it started under.
 *
 * Connecting, "Sync now" and the background upload run on another thread and can take seconds.
 * If you unlink meanwhile, work that started earlier must not finish by uploading your journal to
 * the account you just removed, or by quietly linking it again. Each piece of work takes a
 * [ticket] when it starts and checks it before every step that touches Drive or the link.
 */
class LinkGuard {
    private val generation = AtomicLong()

    fun ticket(): Long = generation.get()

    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket

    /** Makes every ticket handed out so far stale. */
    fun unlink() {
        generation.incrementAndGet()
    }
}

/** What the account card says after a successful link, in plain words. */
object LinkMessages {
    /** Shown when Google wouldn't tell us the address; backups still work. */
    const val UNKNOWN_ACCOUNT = "your Google account"

    /**
     * [previous] is the account this phone last backed up to (null if none), [now] the one just
     * linked, [restoredNotes] how many field notes came down from [now]'s backup.
     */
    fun afterConnect(previous: String?, now: String, restoredNotes: Int): String? {
        val notes = "$restoredNotes field ${if (restoredNotes == 1) "note" else "notes"}"
        val switched = previous != null && previous != now && previous != UNKNOWN_ACCOUNT && now != UNKNOWN_ACCOUNT
        return when {
            switched && restoredNotes > 0 ->
                "Now backing up to $now, and brought in $notes already saved there. The backup in $previous stays as it was."
            switched -> "Now backing up to $now. The backup in $previous stays as it was."
            restoredNotes > 0 -> "Restored $notes from Google Drive"
            else -> null
        }
    }

    /** Shown when unlinking worked here but Google couldn't be told (usually offline). */
    fun unlinkNotConfirmed(email: String): String =
        "Unlinked on this phone, but Google couldn't be reached to release $email, so Connect may pick it again. If it does, tap Unlink once you're online."
}
