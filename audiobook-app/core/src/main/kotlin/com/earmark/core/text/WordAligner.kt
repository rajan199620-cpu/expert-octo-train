package com.earmark.core.text

/**
 * Maps positions in the spoken text back to the displayed sentence.
 *
 * The voice reads the normalised text ("500 rupees", "section 302", "Doctor Rao"), while the
 * screen shows the original ("₹500", "§ 302", "Dr. Rao"). To highlight the word being spoken,
 * both are split into words and aligned with an edit-distance alignment; spoken words with no
 * counterpart (the "rupees" in "500 rupees") attach to their neighbour.
 */
class WordAligner private constructor(
    private val spokenWords: List<IntRange>,
    private val displayWords: List<IntRange>,
    /** For each spoken word, the display word it belongs to (never -1 once built). */
    private val spokenToDisplay: IntArray,
) {
    /** Display character range of the word being spoken at [spokenOffset], or null if none. */
    fun displayRangeFor(spokenOffset: Int): IntRange? {
        if (spokenWords.isEmpty() || displayWords.isEmpty()) return null
        // The word containing the offset, or the next one if the offset sits on a space.
        var lo = 0
        var hi = spokenWords.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (spokenWords[mid].last < spokenOffset) lo = mid + 1 else hi = mid
        }
        val d = spokenToDisplay[lo]
        return if (d in displayWords.indices) displayWords[d] else null
    }

    companion object {
        private val TOKEN = Regex("""\S+""")
        private val EDGE_PUNCT = "\"'“”‘’()[]{}.,;:!?…«»–—-*"

        fun align(display: String, spoken: String): WordAligner {
            val d = tokens(display)
            val s = tokens(spoken)
            val map = IntArray(s.size) { -1 }
            if (d.isEmpty() || s.isEmpty()) return WordAligner(s.map { it.second }, d.map { it.second }, map)
            val dk = d.map { key(it.first) }
            val sk = s.map { key(it.first) }

            // Levenshtein over words; ties prefer matches, then substitutions.
            val n = s.size
            val m = d.size
            val cost = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) cost[i][0] = i
            for (j in 0..m) cost[0][j] = j
            for (i in 1..n) for (j in 1..m) {
                val sub = cost[i - 1][j - 1] + if (sk[i - 1] == dk[j - 1]) 0 else 1
                cost[i][j] = minOf(sub, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
            }
            var i = n
            var j = m
            while (i > 0 && j > 0) {
                val same = sk[i - 1] == dk[j - 1]
                when {
                    cost[i][j] == cost[i - 1][j - 1] + (if (same) 0 else 1) -> { map[i - 1] = j - 1; i--; j-- }
                    cost[i][j] == cost[i - 1][j] + 1 -> { i-- } // spoken word with no display word
                    else -> { j-- } // display word that is not spoken (e.g. a dropped citation)
                }
            }
            // Unmatched spoken words belong to a neighbouring display word: the preceding one
            // ("500 rupees" -> "₹500"), unless that was an exact match and the following one was
            // rewritten ("paid 1 lakh rupees" -> "paid ₹1,00,000": "1 lakh" belongs to the amount).
            val exact = BooleanArray(n) { k -> map[k] >= 0 && sk[k] == dk[map[k]] }
            var k = 0
            while (k < n) {
                if (map[k] >= 0) { k++; continue }
                var end = k
                while (end < n && map[end] < 0) end++
                val prev = if (k > 0) k - 1 else -1
                val next = if (end < n) end else -1
                val target = when {
                    prev >= 0 && next >= 0 && exact[prev] && !exact[next] -> map[next]
                    prev >= 0 -> map[prev]
                    next >= 0 -> map[next]
                    else -> 0
                }
                for (x in k until end) map[x] = target
                k = end
            }
            return WordAligner(s.map { it.second }, d.map { it.second }, map)
        }

        /** Words with their ranges, trimmed of surrounding punctuation for a tidy highlight. */
        private fun tokens(text: String): List<Pair<String, IntRange>> = TOKEN.findAll(text).mapNotNull { m ->
            var a = m.range.first
            var b = m.range.last
            while (a < b && text[a] in EDGE_PUNCT && !opensInside(text, a, m.range.last)) a++
            // Keep a ")" that closes a bracket inside the word: "179(1)(a)" stays whole.
            while (b > a && text[b] in EDGE_PUNCT && !(text[b] == ')' && closesInside(text, a, b))) b--
            val word = text.substring(a, b + 1)
            if (word.isEmpty()) null else word to (a..b)
        }.toList()

        private fun closesInside(text: String, from: Int, at: Int): Boolean {
            val inner = text.substring(from, at)
            return inner.count { it == '(' } > inner.count { it == ')' }
        }

        /** "(a)" alone is trimmed to "a", but "(2019)SCC"-style glued brackets keep their "(". */
        private fun opensInside(text: String, at: Int, last: Int): Boolean =
            text[at] == '(' && text.indexOf(')', at) in (at + 1) until last

        private fun key(word: String): String {
            val k = word.lowercase().filter { it.isLetterOrDigit() }
            return k.ifEmpty { word }
        }
    }
}
