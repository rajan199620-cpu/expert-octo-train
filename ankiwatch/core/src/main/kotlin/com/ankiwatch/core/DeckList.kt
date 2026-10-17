package com.ankiwatch.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One deck as AnkiDroid lists it: full "::" path and its (subtree) due counts. */
data class WireDeck(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int
)

/**
 * The bytes a whole deck list travels as. It goes in an Asset, which has no 100 KiB cap,
 * so a collection with thousands of (sub)decks reaches the watch in full; a DataMap list
 * of them would not fit in one DataItem. Reading is strict like [OfflinePackCodec]: anything
 * malformed, truncated or implausibly large is an [IllegalArgumentException].
 */
object DeckListCodec {

    private const val MAGIC = 0x41574431 // "AWD1"
    private const val VERSION = 1

    const val MAX_DECKS = 100_000
    const val MAX_NAME_BYTES = 64_000
    const val MAX_UNPACKED_BYTES = 64_000_000L

    fun encode(decks: List<WireDeck>): ByteArray {
        require(decks.size <= MAX_DECKS) { "too many decks (${decks.size})" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeInt(decks.size)
            for (deck in decks) {
                val utf8 = deck.name.toByteArray(Charsets.UTF_8)
                require(utf8.size <= MAX_NAME_BYTES) { "a deck name of ${utf8.size} bytes is too long" }
                out.writeLong(deck.id)
                out.writeInt(utf8.size)
                out.write(utf8)
                out.writeInt(deck.newCount)
                out.writeInt(deck.learnCount)
                out.writeInt(deck.reviewCount)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<WireDeck> = try {
        DataInputStream(Capped(GZIPInputStream(ByteArrayInputStream(bytes)), MAX_UNPACKED_BYTES)).use { input ->
            if (input.readInt() != MAGIC) throw corrupt("not a deck list")
            val version = input.readUnsignedByte()
            if (version != VERSION) throw corrupt("unknown version $version")
            val count = count(input.readInt(), MAX_DECKS, "decks")
            val decks = ArrayList<WireDeck>(minOf(count, 4_096))
            repeat(count) {
                val id = input.readLong()
                val utf8 = ByteArray(count(input.readInt(), MAX_NAME_BYTES, "name bytes"))
                input.readFully(utf8)
                decks.add(WireDeck(id, String(utf8, Charsets.UTF_8), input.readInt(), input.readInt(), input.readInt()))
            }
            if (input.read() != -1) throw corrupt("data after the last deck")
            decks
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

    private fun corrupt(why: String) = IllegalArgumentException("Deck list is damaged: $why")
}
