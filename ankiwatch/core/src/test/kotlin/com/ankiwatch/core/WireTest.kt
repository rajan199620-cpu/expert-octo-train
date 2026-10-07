package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WireTest {

    private fun constants(prefix: String): Map<String, String> =
        Wire::class.java.declaredFields
            .filter { it.name.startsWith(prefix) }
            .associate { it.name to it.get(null) as String }

    @Test
    fun keysAndPathsAreDistinct() {
        for (prefix in listOf("KEY_", "PATH_", "CAPABILITY_")) {
            val values = constants(prefix)
            assertTrue("no $prefix constants found", values.isNotEmpty())
            val dupes = values.entries.groupBy { it.value }.filter { it.value.size > 1 }
            assertTrue("duplicate $prefix values: $dupes", dupes.isEmpty())
        }
    }

    @Test
    fun capabilityNamesMatchTheAppResources() {
        // Gradle runs these tests from the core/ directory.
        val phone = File("../mobile/src/main/res/values/wear.xml").readText()
        val watch = File("../wear/src/main/res/values/wear.xml").readText()
        assertTrue(phone, phone.contains("<item>${Wire.CAPABILITY_PHONE}</item>"))
        assertTrue(watch, watch.contains("<item>${Wire.CAPABILITY_WATCH}</item>"))
    }

    @Test
    fun buryIsNotAGrade() {
        assertTrue(Wire.EASE_BURY !in 1..4)
    }

    @Test
    fun onlyGradesAndBuryAreAnswers() {
        assertTrue(Wire.isAnswerEase(Wire.EASE_BURY))
        for (ease in 1..4) assertTrue(Wire.isAnswerEase(ease))
        // -1 is what the phone reads when an answer carries no ease at all.
        for (ease in listOf(-1, 5, 6, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse("ease $ease", Wire.isAnswerEase(ease))
        }
    }

    @Test
    fun answerPathsNestUnderTheirPrefix() {
        assertEquals('/', Wire.PATH_ANSWER_PREFIX.last())
        assertEquals('/', Wire.PATH_ANSWER_ACK_PREFIX.last())
        for ((name, path) in constants("PATH_")) {
            assertTrue("$name must be absolute", path.startsWith("/"))
        }
        // The phone lists answers by prefix and the watch counts them that way: nothing
        // else may live under it, acks included.
        for ((name, path) in constants("PATH_") - "PATH_ANSWER_PREFIX") {
            assertFalse("$name is under the answers' prefix", path.startsWith(Wire.PATH_ANSWER_PREFIX))
        }
    }

    @Test
    fun anAnswersNameIsTheUuidInItsPath() {
        val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(uuid, Wire.answerName("${Wire.PATH_ANSWER_PREFIX}$uuid"))
        for (path in listOf(
            null, "", Wire.PATH_ANSWER_PREFIX, "/answer", "/answers/$uuid", "${Wire.PATH_ANSWER_ACK_PREFIX}$uuid",
            "${Wire.PATH_ANSWER_PREFIX}$uuid/more", "/x${Wire.PATH_ANSWER_PREFIX}$uuid", Wire.PATH_RESPONSE_CARDS
        )) {
            assertEquals("$path", null, Wire.answerName(path))
        }
    }

    @Test
    fun acksAreSplitSmallEnoughAndNameEachAnswerOnce() {
        assertEquals(emptyList<List<String>>(), Wire.ackChunks(emptyList()))
        assertEquals(listOf(listOf("a", "b")), Wire.ackChunks(listOf("a", "b", "a")))
        for (n in listOf(1, 999, 1_000, 1_001, 2_500, 10_000)) {
            val names = (1..n).map { "%08x-d9cb-469f-a165-70867728950e".format(it) }
            val chunks = Wire.ackChunks(names + names.take(7))
            assertEquals("$n", names, chunks.flatten())
            assertEquals("$n", (n + Wire.ACK_CHUNK - 1) / Wire.ACK_CHUNK, chunks.size)
            for (chunk in chunks) {
                assertTrue("$n", chunk.size <= Wire.ACK_CHUNK)
                // What one ack's names take up as a DataMap string array: under the 100 KB cap.
                assertTrue("$n", chunk.sumOf { it.toByteArray().size + 4 } < 60_000)
            }
        }
    }
}
