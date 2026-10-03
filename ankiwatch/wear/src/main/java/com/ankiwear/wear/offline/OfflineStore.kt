package com.ankiwear.wear.offline

import android.util.Log
import com.ankiwatch.core.CardKey
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.OfflinePackCodec
import com.ankiwatch.core.OfflineQueue
import java.io.File
import java.io.IOException

/**
 * The decks downloaded for reviewing without the phone, kept as files in the app's storage:
 * each deck's pack as the phone sent it, and how far its review has got. Every write goes to
 * a temporary file that then replaces the old one, so the watch dying mid-write never leaves
 * half a file; a pack that can't be read any more is dropped rather than crashing the app.
 *
 * One download per deck: a new one for the same deck replaces it. Grades already given are
 * not stored here; they wait in the Data Layer until the phone has them.
 */
class OfflineStore(private val dir: File) {

    data class Summary(
        val deckId: Long,
        val deckName: String,
        val packId: Long,
        /** When the phone built the download (epoch millis). */
        val createdAt: Long,
        val total: Int,
        val remaining: Int,
        val answers: Int
    )

    private val packs = HashMap<Long, OfflinePack>()

    init {
        dir.mkdirs()
        // Leftovers of writes the watch didn't finish.
        dir.listFiles { f -> f.name.endsWith(TMP) }?.forEach { it.delete() }
    }

    /** Every downloaded deck, newest download first. */
    @Synchronized
    fun summaries(): List<Summary> = deckIds()
        .mapNotNull { open(it)?.let(::summary) }
        .sortedByDescending { it.createdAt }

    /** The deck's review where it was left, or null if it isn't downloaded (or is damaged). */
    @Synchronized
    fun open(deckId: Long): OfflineQueue? {
        val pack = pack(deckId) ?: return null
        val state = read(stateFile(deckId))?.let { OfflineQueue.decodeState(pack.id, it) } ?: OfflineQueue.State()
        return OfflineQueue(pack, state)
    }

    /** Remembers how far [queue]'s review has got. */
    @Synchronized
    fun save(queue: OfflineQueue) {
        if (!packFile(queue.pack.deckId).exists()) return // removed meanwhile
        write(stateFile(queue.pack.deckId), OfflineQueue.encodeState(queue.pack.id, queue.state))
    }

    /**
     * Stores a download from the phone, replacing the deck's previous one; the same download
     * twice changes nothing (its review isn't reset). Throws [IllegalArgumentException] for
     * bytes that aren't an intact pack, leaving what was stored before untouched.
     */
    @Synchronized
    fun import(bytes: ByteArray): Summary {
        val pack = OfflinePackCodec.decode(bytes)
        val existing = pack(pack.deckId)
        if (existing?.id != pack.id) {
            stateFile(pack.deckId).delete()
            write(packFile(pack.deckId), bytes)
            packs[pack.deckId] = pack
        }
        return summary(open(pack.deckId)!!)
    }

    @Synchronized
    fun remove(deckId: Long) {
        packs.remove(deckId)
        packFile(deckId).delete()
        stateFile(deckId).delete()
    }

    /** [key] was answered somewhere (online, or in another download holding the same card). */
    @Synchronized
    fun markDone(key: CardKey, exceptDeckId: Long? = null) {
        for (deckId in deckIds()) {
            if (deckId == exceptDeckId) continue
            val queue = open(deckId) ?: continue
            if (key !in queue) continue
            queue.markDone(key)
            save(queue)
        }
    }

    private fun summary(queue: OfflineQueue) = Summary(
        deckId = queue.pack.deckId,
        deckName = queue.pack.deckName,
        packId = queue.pack.id,
        createdAt = queue.pack.createdAt,
        total = queue.cards.size,
        remaining = queue.remaining,
        answers = queue.state.answers
    )

    private fun pack(deckId: Long): OfflinePack? {
        packs[deckId]?.let { return it }
        val bytes = read(packFile(deckId)) ?: return null
        return try {
            OfflinePackCodec.decode(bytes).also { packs[deckId] = it }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping damaged download for deck $deckId: ${e.message}")
            remove(deckId)
            null
        }
    }

    private fun deckIds(): List<Long> = dir.listFiles()
        ?.mapNotNull { f -> f.name.removePrefix(PACK).removeSuffix(BIN).takeIf { f.name.startsWith(PACK) && f.name.endsWith(BIN) }?.toLongOrNull() }
        .orEmpty()

    private fun packFile(deckId: Long) = File(dir, "$PACK$deckId$BIN")
    private fun stateFile(deckId: Long) = File(dir, "$STATE$deckId$BIN")

    private fun read(file: File): ByteArray? = try {
        if (file.exists()) file.readBytes() else null
    } catch (e: IOException) {
        Log.w(TAG, "Couldn't read ${file.name}: ${e.message}")
        null
    }

    private fun write(file: File, bytes: ByteArray) {
        val tmp = File(dir, file.name + TMP)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("couldn't save ${file.name}")
        }
    }

    private companion object {
        const val TAG = "OfflineStore"
        const val PACK = "pack-"
        const val STATE = "state-"
        const val BIN = ".bin"
        const val TMP = ".tmp"
    }
}
