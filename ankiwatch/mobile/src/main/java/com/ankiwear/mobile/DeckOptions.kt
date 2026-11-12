package com.ankiwear.mobile

import com.ankiwatch.core.DeckSteps
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deck's learning settings from AnkiDroid's deck options JSON (Anki's deck config):
 * `new.delays` and `lapse.delays` (steps in minutes), `new.ints` (graduating and easy
 * intervals in days), `lapse.minInt` and `lapse.mult`. Null when anything is missing or
 * odd, as for a filtered deck's options, so the watch falls back to what the labels show.
 */
internal fun deckStepsFromOptions(json: String?): DeckSteps? {
    if (json.isNullOrBlank()) return null
    return try {
        val options = JSONObject(json)
        val new = options.getJSONObject("new")
        val lapse = options.getJSONObject("lapse")
        val ints = new.getJSONArray("ints")
        DeckSteps.of(
            learnMinutes = minutes(new.getJSONArray("delays")) ?: return null,
            graduatingDays = whole(ints.get(0)) ?: return null,
            easyDays = whole(ints.get(1)) ?: return null,
            relearnMinutes = minutes(lapse.getJSONArray("delays")) ?: return null,
            lapseMinDays = whole(lapse.get("minInt")) ?: return null,
            lapseMult = (lapse.get("mult") as? Number)?.toDouble() ?: return null
        )
    } catch (e: Exception) {
        null
    }
}

private fun minutes(array: JSONArray): List<Double>? =
    List(array.length()) { (array.get(it) as? Number)?.toDouble() ?: return null }

private fun whole(value: Any?): Int? {
    val d = (value as? Number)?.toDouble() ?: return null
    if (d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
    return d.toInt()
}
