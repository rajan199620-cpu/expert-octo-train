package com.earmark.core.text

/**
 * Parses numbers the way speech recognisers hand them over: "3", "1.5", "1.5x",
 * "twenty five", "twenty-five", "a", "one and a half", "one point five", "half".
 */
object NumberWords {
    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "couple" to 2, "few" to 3,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "last" to -1,
    )
    private val digitPattern = Regex("""^(\d+(?:\.\d+)?)(?:x|st|nd|rd|th)?$""")

    private val connectors = listOf("and", "point", "half", "quarter", "quarters", "hundred", "thousand", "of")

    /** Regex fragment (one capture group) matching a spoken or written number of up to six words. */
    val PATTERN: String by lazy {
        val words = (units.keys + tens.keys + ordinals.keys.filter { it != "last" } + connectors)
            .sortedByDescending { it.length }
            .joinToString("|")
        """(\d+(?:\.\d+)?|(?:$words)(?:[ -](?:$words)){0,5})"""
    }

    fun parse(phrase: String): Double? {
        val words = phrase.lowercase().replace('-', ' ').replace(Regex("""\s+"""), " ").trim()
            .removeSuffix(" times").removeSuffix(" x").split(' ')
            .filter { it.isNotEmpty() && it != "and" && it != "of" }
        if (words.isEmpty()) return null
        if (words.size == 1) {
            digitPattern.matchEntire(words[0])?.let { return it.groupValues[1].toDouble() }
            if (words[0] == "half") return 0.5
            ordinals[words[0]]?.let { if (it > 0) return it.toDouble() }
        }
        val pointIdx = words.indexOf("point")
        if (pointIdx > 0) {
            val whole = parseInteger(words.subList(0, pointIdx)) ?: return null
            val frac = words.subList(pointIdx + 1, words.size)
            if (frac.isEmpty()) return null
            val digits = frac.map { w -> units[w]?.takeIf { it < 10 } ?: w.toIntOrNull()?.takeIf { it < 10 } ?: return null }
            return "$whole.${digits.joinToString("")}".toDouble()
        }
        if (words.last() == "half") {
            // "one and a half": the "a" belongs to "a half", not to the whole number.
            val head = words.dropLast(1).let { if (it.lastOrNull() in setOf("a", "an")) it.dropLast(1) else it }
            val whole = if (head.isEmpty()) 0 else parseInteger(head) ?: return null
            return whole + 0.5
        }
        if (words.size == 2 && words[1] in setOf("quarter", "quarters")) {
            val n = parseInteger(words.subList(0, 1)) ?: return null
            return n * 0.25
        }
        return parseInteger(words)?.toDouble()
    }

    fun parseInt(phrase: String): Int? = parse(phrase)?.takeIf { it == Math.floor(it) }?.toInt()

    private fun parseInteger(input: List<String>): Int? {
        // "a couple", "a hundred": the article is not a number once other words follow.
        val words = if (input.size > 1) input.filter { it != "a" && it != "an" } else input
        if (words.isEmpty()) return null
        if (words.size == 1) {
            words[0].toIntOrNull()?.let { return it }
            ordinals[words[0]]?.let { if (it > 0) return it }
        }
        var total = 0
        var current = 0
        var sawAny = false
        for (w in words) {
            when {
                w in units -> { current += units.getValue(w); sawAny = true }
                w in tens -> { current += tens.getValue(w); sawAny = true }
                w in ordinals && ordinals.getValue(w) > 0 -> { current += ordinals.getValue(w); sawAny = true }
                w == "hundred" -> { current = (if (current == 0) 1 else current) * 100; sawAny = true }
                w == "thousand" -> { total += (if (current == 0) 1 else current) * 1000; current = 0; sawAny = true }
                w.toIntOrNull() != null -> { current += w.toInt(); sawAny = true }
                else -> return null
            }
        }
        return if (sawAny) total + current else null
    }
}
