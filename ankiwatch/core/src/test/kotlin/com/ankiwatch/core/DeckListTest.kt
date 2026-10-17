package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * The deck list's bytes. A collection with many nested decks used to be cut to what fitted
 * in one DataItem, so whole branches (Mains::GS2, GS3, GS4) never reached the watch.
 */
class DeckListTest {

    private val iterations = (System.getProperty("stress.iterations") ?: "2500").toInt()

    /** A collection shaped like a big UPSC one: deep, long, partly non-ASCII names. */
    private fun bigCollection(count: Int): List<WireDeck> = (0 until count).map { i ->
        val paper = "GS${1 + i % 4}"
        val name = "Mains::$paper::ethics & human interface::Values::Lessons leaders, reformers, administrators::topic $i — नीतिशास्त्र"
        WireDeck(1_700_000_000_000L + i, name, i % 50, i % 7, i * 3)
    }

    @Test
    fun aListRoundTripsExactly() {
        val rnd = Random(21)
        repeat(iterations / 10) {
            val decks = List(rnd.nextInt(0, 40)) {
                WireDeck(
                    rnd.nextLong(),
                    String(CharArray(rnd.nextInt(0, 80)) { rnd.nextInt(1, 0xD7FF).toChar() }),
                    rnd.nextInt(), rnd.nextInt(), rnd.nextInt()
                )
            }
            assertEquals(decks, DeckListCodec.decode(DeckListCodec.encode(decks)))
        }
        assertEquals(emptyList<WireDeck>(), DeckListCodec.decode(DeckListCodec.encode(emptyList())))
    }

    @Test
    fun aCollectionFarTooBigForADataItemArrivesWhole() {
        val decks = bigCollection(5_000)
        // What used to happen: only part of it fitted in the DataItem.
        val inline = PayloadBudget.decksThatFit(decks.map { it.name }, decks.map { true })
        assertTrue("${inline.size} fitted inline", inline.size < 1_000)

        val start = System.nanoTime()
        val bytes = DeckListCodec.encode(decks)
        val back = DeckListCodec.decode(bytes)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(decks, back)
        assertTrue(back.any { it.name.startsWith("Mains::GS4::") })
        assertTrue("5000 decks take ${bytes.size} bytes", bytes.size < 1_000_000)
        assertTrue("encode+decode took ${ms}ms", ms < 5_000)
    }

    @Test
    fun damagedListsAreRefusedCleanly() {
        val rnd = Random(23)
        repeat(iterations / 5) { i ->
            val decks = bigCollection(rnd.nextInt(0, 30)).shuffled(rnd)
            val good = DeckListCodec.encode(decks)
            val bad: ByteArray = when (rnd.nextInt(5)) {
                0 -> good.copyOf(rnd.nextInt(0, good.size))
                1 -> good.copyOf().also { b -> repeat(rnd.nextInt(1, 4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
                2 -> good + ByteArray(rnd.nextInt(1, 20)) { rnd.nextInt(256).toByte() }
                3 -> ByteArray(rnd.nextInt(0, 300)) { rnd.nextInt(256).toByte() }
                else -> gzip(ByteArray(rnd.nextInt(0, 200)) { rnd.nextInt(256).toByte() })
            }
            try {
                // Only a change that leaves the packed data intact may decode, to the same list.
                assertEquals("case $i decoded to something else", decks, DeckListCodec.decode(bad))
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.startsWith("Deck list is damaged"))
            } catch (e: Throwable) {
                fail("case $i threw $e instead of a clean refusal")
            }
        }
    }

    @Test
    fun anOfflinePackIsNotADeckListAndClaimsAreChecked() {
        val pack = OfflinePackCodec.encode(OfflinePack(1, 2, "d", 3, emptyList()))
        fun list(write: DataOutputStream.() -> Unit) = gzip(ByteArrayOutputStream().also { b -> DataOutputStream(b).use(write) }.toByteArray())
        val cases = listOf(
            pack,
            list { writeInt(0x41574431); writeByte(2); writeInt(0) },                     // unknown version
            list { writeInt(0x41574431); writeByte(1); writeInt(-1) },                    // negative count
            list { writeInt(0x41574431); writeByte(1); writeInt(DeckListCodec.MAX_DECKS + 1) },
            list { writeInt(0x41574431); writeByte(1); writeInt(1); writeLong(1); writeInt(Int.MAX_VALUE) }, // huge name
            list { writeInt(0x41574431); writeByte(1); writeInt(2_000); writeLong(1) },   // cut short
        )
        for ((i, bytes) in cases.withIndex()) {
            try {
                DeckListCodec.decode(bytes)
                fail("case $i accepted")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.startsWith("Deck list is damaged"))
            }
        }
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
