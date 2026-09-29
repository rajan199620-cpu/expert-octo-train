package com.earmark.core.text

import kotlinx.serialization.Serializable

/**
 * User pronunciation dictionary ("BNSS" -> "B N S S", "Nietzsche" -> "Neecha").
 * Voice Dream Reader's most-loved feature: no TTS engine gets proper nouns right on its own.
 */
@Serializable
data class LexiconEntry(
    val from: String,
    val to: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = true,
)

class Lexicon(entries: List<LexiconEntry>) {
    private val rules: List<Pair<Regex, String>> = entries
        .filter { it.from.isNotBlank() }
        .sortedByDescending { it.from.length } // longest match first: "New York City" before "New York"
        .map { e ->
            val body = Regex.escape(e.from.trim())
            val pattern = if (e.wholeWord) """(?<![\p{L}\p{N}])$body(?![\p{L}\p{N}])""" else body
            val options = if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options) to e.to
        }

    fun apply(text: String): String {
        if (rules.isEmpty()) return text
        // Single pass per rule over the original positions so a replacement is never re-matched
        // by a later, shorter rule ("New York" -> "NY" must not then become "Nigh").
        val claimed = BooleanArray(text.length)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for ((regex, replacement) in rules) {
            for (m in regex.findAll(text)) {
                val r = m.range
                if (r.isEmpty()) continue
                if ((r.first..r.last).any { claimed[it] }) continue
                for (k in r) claimed[k] = true
                edits += Triple(r.first, r.last + 1, replacement)
            }
        }
        if (edits.isEmpty()) return text
        edits.sortBy { it.first }
        val sb = StringBuilder()
        var pos = 0
        for ((s, e, rep) in edits) {
            sb.append(text, pos, s).append(rep)
            pos = e
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    companion object {
        val EMPTY = Lexicon(emptyList())
    }
}
