package com.ankiwatch.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A card's identity in AnkiDroid: its note and which of the note's cards it is. */
data class CardKey(val noteId: Long, val cardOrd: Int)

/** One card as the watch shows it, exactly what a cards response carries. */
data class PackCard(
    val noteId: Long,
    val cardOrd: Int,
    val buttonCount: Int,
    /** AnkiDroid's labels for the answer buttons, in ease order ("1m", "10m", "4d"…). */
    val nextReviewTimes: List<String>,
    /** Sanitized template HTML; empty for cloze cards. */
    val question: String,
    val answer: String,
    /** Raw cloze field; empty for template cards. */
    val clozeContent: String = "",
    /** The tested cloze; 0 for template cards. */
    val clozeNumber: Int = 0,
    val extras: List<Pair<String, String>> = emptyList(),
    val modelName: String = ""
) {
    val key: CardKey get() = CardKey(noteId, cardOrd)
}

/**
 * A deck's due cards, in AnkiDroid's order, for reviewing on the watch without the phone.
 * [id] tells one download from the next, so the same pack is never imported twice.
 */
data class OfflinePack(
    val id: Long,
    val deckId: Long,
    val deckName: String,
    /** When the phone built it (epoch millis). */
    val createdAt: Long,
    val cards: List<PackCard>
)

/**
 * The bytes an [OfflinePack] travels and is stored as. Every distinct string is written
 * once (a note's cloze cards share its text), then the whole thing is gzipped. Reading is
 * strict: anything malformed, truncated or implausibly large is an
 * [IllegalArgumentException], never a crash or a huge allocation.
 */
object OfflinePackCodec {

    private const val MAGIC = 0x41575031 // "AWP1"
    private const val VERSION = 1

    /** Limits on what a pack may claim, checked before anything is allocated. */
    const val MAX_CARDS = 20_000
    const val MAX_STRINGS = 200_000
    const val MAX_STRING_BYTES = 4_000_000
    const val MAX_UNPACKED_BYTES = 32_000_000L
    private const val MAX_LABELS = 16
    private const val MAX_EXTRAS = 64

    fun encode(pack: OfflinePack): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        fun ref(s: String): Int = strings.getOrPut(s) { strings.size }
        // First pass: the string table, in first-use order.
        ref(pack.deckName)
        for (card in pack.cards) {
            card.nextReviewTimes.forEach { ref(it) }
            ref(card.question); ref(card.answer); ref(card.clozeContent); ref(card.modelName)
            card.extras.forEach { (label, value) -> ref(label); ref(value) }
        }
        require(pack.cards.size <= MAX_CARDS) { "too many cards (${pack.cards.size})" }
        require(strings.size <= MAX_STRINGS) { "too many distinct strings (${strings.size})" }

        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeLong(pack.id)
            out.writeLong(pack.deckId)
            out.writeLong(pack.createdAt)
            out.writeInt(strings.size)
            for (s in strings.keys) {
                val utf8 = s.toByteArray(Charsets.UTF_8)
                require(utf8.size <= MAX_STRING_BYTES) { "a string of ${utf8.size} bytes is too long" }
                out.writeInt(utf8.size)
                out.write(utf8)
            }
            out.writeInt(ref(pack.deckName))
            out.writeInt(pack.cards.size)
            for (card in pack.cards) {
                require(card.nextReviewTimes.size <= MAX_LABELS) { "too many labels" }
                require(card.extras.size <= MAX_EXTRAS) { "too many extras" }
                out.writeLong(card.noteId)
                out.writeInt(card.cardOrd)
                out.writeInt(card.buttonCount)
                out.writeByte(card.nextReviewTimes.size)
                card.nextReviewTimes.forEach { out.writeInt(ref(it)) }
                out.writeInt(ref(card.question))
                out.writeInt(ref(card.answer))
                out.writeInt(ref(card.clozeContent))
                out.writeInt(card.clozeNumber)
                out.writeByte(card.extras.size)
                card.extras.forEach { (label, value) -> out.writeInt(ref(label)); out.writeInt(ref(value)) }
                out.writeInt(ref(card.modelName))
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): OfflinePack = try {
        DataInputStream(Capped(GZIPInputStream(ByteArrayInputStream(bytes)), MAX_UNPACKED_BYTES)).use { input ->
            if (input.readInt() != MAGIC) throw corrupt("not an offline pack")
            val version = input.readUnsignedByte()
            if (version != VERSION) throw corrupt("unknown version $version")
            val id = input.readLong()
            val deckId = input.readLong()
            val createdAt = input.readLong()
            val stringCount = count(input.readInt(), MAX_STRINGS, "strings")
            val strings = ArrayList<String>(minOf(stringCount, 4_096))
            repeat(stringCount) {
                val length = count(input.readInt(), MAX_STRING_BYTES, "string bytes")
                val utf8 = ByteArray(length)
                input.readFully(utf8)
                strings.add(String(utf8, Charsets.UTF_8))
            }
            fun str(): String {
                val index = input.readInt()
                if (index !in strings.indices) throw corrupt("string $index of ${strings.size}")
                return strings[index]
            }
            val deckName = str()
            val cardCount = count(input.readInt(), MAX_CARDS, "cards")
            val cards = ArrayList<PackCard>(minOf(cardCount, 4_096))
            repeat(cardCount) {
                val noteId = input.readLong()
                val cardOrd = input.readInt()
                val buttonCount = input.readInt()
                val labels = List(count(input.readUnsignedByte(), MAX_LABELS, "labels")) { str() }
                val question = str()
                val answer = str()
                val clozeContent = str()
                val clozeNumber = input.readInt()
                val extras = List(count(input.readUnsignedByte(), MAX_EXTRAS, "extras")) { str() to str() }
                val modelName = str()
                cards.add(
                    PackCard(noteId, cardOrd, buttonCount, labels, question, answer, clozeContent, clozeNumber, extras, modelName)
                )
            }
            if (input.read() != -1) throw corrupt("data after the last card")
            OfflinePack(id, deckId, deckName, createdAt, cards)
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: EOFException) {
        throw corrupt("cut short")
    } catch (e: IOException) {
        throw corrupt(e.message ?: e.javaClass.simpleName)
    }

    private fun count(value: Int, max: Int, what: String): Int {
        if (value < 0 || value > max) throw corrupt("$value $what")
        return value
    }

    private fun corrupt(why: String) = IllegalArgumentException("Offline pack is damaged: $why")

    /** Fails once more than [limit] bytes come out, so a crafted gzip can't fill memory. */
    private class Capped(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            total += n
            if (total > limit) throw IOException("more than $limit bytes unpacked")
        }
    }
}
