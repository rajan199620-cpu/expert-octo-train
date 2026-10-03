package com.ankiwatch.core

import org.junit.Assert.assertEquals
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
    fun answerPathsNestUnderTheirPrefix() {
        assertEquals('/', Wire.PATH_ANSWER_PREFIX.last())
        for ((name, path) in constants("PATH_")) {
            assertTrue("$name must be absolute", path.startsWith("/"))
        }
    }
}
