package com.ankiwear.mobile

import com.ankiwatch.core.DeckSteps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Reading a deck's learning steps from AnkiDroid's deck options JSON. */
class DeckOptionsTest {

    private val minute = 60_000L

    /** What AnkiDroid's provider returns for a deck on the default preset (trimmed to what matters, plus noise). */
    private val stock = """
        {"id":1,"name":"Default","new":{"bury":false,"delays":[1.0,10.0],"initialFactor":2500,
         "ints":[1,4,0],"order":1,"perDay":20},"lapse":{"delays":[10.0],"leechAction":1,
         "leechFails":8,"minInt":1,"mult":0.0},"rev":{"perDay":200},"dyn":false,"fsrsWeights":[]}
    """.trimIndent()

    @Test
    fun theDefaultPreset() {
        assertEquals(
            DeckSteps(listOf(minute, 10 * minute), 1, 4, listOf(10 * minute), 1, 0.0),
            deckStepsFromOptions(stock)
        )
    }

    @Test
    fun customStepsAndWholeNumbersWrittenAsDecimals() {
        val json = JSONObject(stock).apply {
            getJSONObject("new").put("delays", org.json.JSONArray(listOf(0.5, 15, 60, 1440))).put("ints", org.json.JSONArray(listOf(2.0, 5, 7)))
            getJSONObject("lapse").put("delays", org.json.JSONArray()).put("minInt", 3).put("mult", 0.25)
        }.toString()
        assertEquals(
            DeckSteps(listOf(30_000L, 15 * minute, 60 * minute, 1_440 * minute), 2, 5, emptyList(), 3, 0.25),
            deckStepsFromOptions(json)
        )
    }

    @Test
    fun anythingMissingOrOddGivesNothing() {
        val broken = listOf<(JSONObject) -> Unit>(
            { it.remove("new") },
            { it.remove("lapse") },
            { it.getJSONObject("new").remove("delays") },
            { it.getJSONObject("new").put("delays", "1 10") },
            { it.getJSONObject("new").put("delays", org.json.JSONArray(listOf("1", 10))) },
            { it.getJSONObject("new").put("delays", org.json.JSONArray(listOf(-1, 10))) },
            { it.getJSONObject("new").put("ints", org.json.JSONArray(listOf(1))) },
            { it.getJSONObject("new").put("ints", org.json.JSONArray(listOf(1.5, 4))) },
            { it.getJSONObject("new").put("ints", org.json.JSONArray(listOf(0, 4))) },
            { it.getJSONObject("lapse").remove("minInt") },
            { it.getJSONObject("lapse").put("mult", "0") },
            { it.getJSONObject("lapse").put("mult", -1) }
        )
        broken.forEachIndexed { i, damage ->
            val json = JSONObject(stock).also(damage).toString()
            assertNull("case $i: $json", deckStepsFromOptions(json))
        }
        // A filtered deck's options have none of it.
        assertNull(deckStepsFromOptions("""{"dyn":true,"terms":[["",100,0]],"resched":true}"""))
        for (junk in listOf(null, "", " ", "null", "[]", "{", "1", "\"x\"", "{\"new\":null}")) assertNull(junk, deckStepsFromOptions(junk))
    }

    @Test
    fun randomJsonNeverThrows() {
        val rnd = Random(3)
        val pieces = listOf("{", "}", "[", "]", ",", ":", "\"new\"", "\"lapse\"", "\"delays\"", "\"ints\"", "\"minInt\"", "\"mult\"", "1", "-2.5", "1e309", "null", "true", "\"s\"")
        repeat(20_000) {
            val json = if (rnd.nextBoolean()) {
                // Real options with one value swapped for something random.
                val o = JSONObject(stock)
                val section = o.getJSONObject(if (rnd.nextBoolean()) "new" else "lapse")
                val key = section.keys().asSequence().toList().let { it[rnd.nextInt(it.size)] }
                section.put(key, listOf<Any>(JSONObject.NULL, "x", -1, 1e300, Double.MAX_VALUE, org.json.JSONArray(listOf(1, "a")), JSONObject())[rnd.nextInt(7)])
                o.toString()
            } else List(rnd.nextInt(0, 30)) { pieces[rnd.nextInt(pieces.size)] }.joinToString("")
            try {
                deckStepsFromOptions(json)
            } catch (e: Throwable) {
                fail("'$json' threw $e")
            }
        }
    }
}
