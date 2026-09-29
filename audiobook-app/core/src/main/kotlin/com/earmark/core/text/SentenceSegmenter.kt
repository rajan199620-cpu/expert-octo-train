package com.earmark.core.text

/**
 * Rule-based sentence splitter tuned for narration.
 *
 * Getting this wrong is audible: a false split puts a pause after "Dr." and a missed split
 * makes "bookmark this sentence" grab half a page. Beyond the usual abbreviation handling it
 * understands Devanagari danda (।), CJK full stops, closing quotes, list enumerators, and
 * caps sentence length so run-on legal/academic sentences stay addressable.
 */
class SentenceSegmenter(
    /** Sentences longer than this are split at the best clause boundary. */
    private val maxChars: Int = 400,
) {
    init {
        require(maxChars >= 40) { "maxChars too small: $maxChars" }
    }

    fun split(input: String): List<String> {
        val text = normalizeWhitespace(input)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in TERMINATORS) {
                var end = i + 1
                while (end < text.length && (text[end] in TERMINATORS || text[end] == '.')) end++
                while (end < text.length && text[end] in CLOSERS) end++
                val boundary = when {
                    c in CJK_TERMINATORS || text[end - 1] in CJK_TERMINATORS -> true
                    end >= text.length -> true
                    !text[end].isWhitespace() -> false
                    else -> isBoundary(text, start, i, end)
                }
                if (boundary) {
                    addSentence(out, text.substring(start, end))
                    start = end
                    while (start < text.length && text[start].isWhitespace()) start++
                    i = start
                    continue
                }
                i = end
                continue
            }
            i++
        }
        if (start < text.length) addSentence(out, text.substring(start))
        return out
    }

    private fun addSentence(out: MutableList<String>, raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        if (s.length <= maxChars) {
            out += s
        } else {
            out += splitLong(s)
        }
    }

    /** Splits an over-long sentence at clause punctuation, falling back to whitespace. */
    internal fun splitLong(s: String): List<String> {
        val parts = ArrayList<String>()
        var from = 0
        while (s.length - from > maxChars) {
            val cut = findCut(s, from)
            s.substring(from, cut).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            from = cut
            while (from < s.length && s[from] == ' ') from++
        }
        if (from < s.length) s.substring(from).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts
    }

    /**
     * Best cut position in s[from, from + maxChars]. Searches only that window: String.lastIndexOf
     * would otherwise scan back to the start of a multi-megabyte paragraph for every cut.
     */
    private fun findCut(s: String, from: Int): Int {
        val window = s.substring(from, minOf(s.length, from + maxChars))
        val minCut = maxChars / 3
        for (marks in CUT_PREFERENCE) {
            var best = -1
            for (mark in marks) {
                val idx = window.lastIndexOf(mark)
                if (idx >= minCut) best = maxOf(best, idx + mark.length)
            }
            if (best > 0) return from + best
        }
        val space = window.lastIndexOf(' ')
        if (space >= minCut) return from + space + 1
        return safeHardCut(s, from + window.length, from + 1)
    }

    /**
     * Text with no spaces (Chinese, Japanese, long URLs) has to be cut mid-run. Never cut inside
     * a surrogate pair, before a combining mark, or inside an emoji ZWJ sequence.
     */
    private fun safeHardCut(s: String, cut: Int, min: Int): Int {
        if (cut >= s.length) return s.length
        var c = cut
        while (c > min && !isClusterBoundary(s, c)) c--
        return if (c > min) c else cut
    }

    private fun isClusterBoundary(s: String, i: Int): Boolean {
        val next = s[i]
        val prev = s[i - 1]
        if (next.isLowSurrogate() && prev.isHighSurrogate()) return false
        if (prev == '\u200D' || next == '\u200D') return false
        if (next == '\uFE0F' || next == '\uFE0E' || next in '\u20D0'..'\u20FF') return false
        val type = Character.getType(s.codePointAt(i))
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) return false
        // Skin-tone modifiers and regional-indicator flag pairs.
        val cp = s.codePointAt(i)
        if (cp in 0x1F3FB..0x1F3FF) return false
        if (cp in 0x1F1E6..0x1F1FF && i >= 2 && s.codePointBefore(i) in 0x1F1E6..0x1F1FF) {
            var n = 0
            var k = i
            while (k >= 2 && s.codePointBefore(k) in 0x1F1E6..0x1F1FF) { n++; k -= 2 }
            if (n % 2 == 1) return false
        }
        return true
    }

    private fun isBoundary(text: String, sentenceStart: Int, termIndex: Int, afterClosers: Int): Boolean {
        var n = afterClosers
        while (n < text.length && text[n].isWhitespace()) n++
        if (n >= text.length) return true
        val next = text[n]
        if (!startsSentence(next)) return false
        val term = text[termIndex]
        if (term != '.') return true
        // Ellipsis followed by a capital is treated as a boundary.
        if (termIndex + 1 < text.length && text[termIndex + 1] == '.') return true

        val token = tokenBefore(text, sentenceStart, termIndex)
        if (token.isEmpty()) return true
        val lower = token.lowercase()
        if (lower in TITLES || lower in ALWAYS_CONTINUES) return false
        if (lower in NUMBERING && (next.isDigit() || nextTokenIsRoman(text, n))) return false
        if (token.length == 1 && token[0].isLetter() && token[0].isUpperCase()) return false // initials: J. K. Rowling
        if (DOTTED_ABBREV.matches(token)) return lower in DOTTED_CAN_END
        // "1. Introduction" / "IV. Results" / "(a). " at the very start of a sentence.
        if (ENUMERATOR.matches(text.substring(sentenceStart, termIndex).trim())) return false
        return true
    }

    private fun tokenBefore(text: String, sentenceStart: Int, termIndex: Int): String {
        var s = termIndex
        while (s > sentenceStart && !text[s - 1].isWhitespace()) s--
        return text.substring(s, termIndex).trimStart('(', '[', '"', '“', '‘', '\'')
    }

    private fun nextTokenIsRoman(text: String, from: Int): Boolean {
        var e = from
        while (e < text.length && text[e].isLetter()) e++
        val tok = text.substring(from, e)
        return tok.isNotEmpty() && RomanNumerals.toInt(tok) != null && tok.all { it.isUpperCase() }
    }

    private fun startsSentence(c: Char): Boolean = when {
        c.isUpperCase() || c.isDigit() -> true
        c in OPENERS -> true
        c.isLetter() && !c.isLowerCase() -> true // uncased scripts (Devanagari, CJK, Arabic...)
        else -> false
    }

    companion object {
        private val CJK_TERMINATORS = setOf('。', '！', '？')
        private val TERMINATORS = setOf('.', '!', '?', '…', '।', '॥') + CJK_TERMINATORS
        private val CLOSERS = setOf('"', '\'', '”', '’', ')', ']', '»', '」', '』', '*')
        private val OPENERS = setOf('"', '\'', '“', '‘', '(', '[', '«', '¿', '¡', '「', '—', '-')

        /** Never end a sentence: they are almost always followed by a name. */
        private val TITLES = setOf(
            "mr", "mrs", "ms", "mx", "dr", "prof", "rev", "hon", "gen", "col", "capt", "lt", "sgt", "maj",
            "gov", "sen", "rep", "supt", "insp", "messrs", "mt", "ft", "st", "smt", "shri", "sri", "adv",
        )

        /** Latin-style abbreviations that sit mid-sentence. */
        private val ALWAYS_CONTINUES = setOf("vs", "v", "viz", "cf", "approx", "ca", "al", "e.g", "i.e", "eg", "ie", "resp")

        /** Continue only when followed by a number: "Fig. 3", "No. 12", "Sec. 302", "Jan. 5". */
        private val NUMBERING = setOf(
            "no", "nos", "fig", "figs", "vol", "vols", "p", "pp", "ch", "chap", "sec", "secs", "art", "arts",
            "para", "paras", "cl", "r", "o", "ex", "eq", "eqn", "ed", "op", "jan", "feb", "mar", "apr", "jun",
            "jul", "aug", "sep", "sept", "oct", "nov", "dec", "s", "ss",
        )
        private val DOTTED_ABBREV = Regex("""^(?:\p{L}\.)+\p{L}$""")
        private val DOTTED_CAN_END = setOf("a.m", "p.m")
        private val ENUMERATOR = Regex("""^\(?(?:\d{1,3}|[ivxlcdmIVXLCDM]{1,6}|[a-zA-Z])\)?$""")

        private val CUT_PREFERENCE = listOf(
            listOf("; ", ": ", "\uFF1B", "\uFF1A"),
            listOf(" — ", "—", " – ", " - "),
            listOf(", ", "\uFF0C", "\u3001", "\u060C "),
        )

        private val WS = Regex("""[\s     ]+""")
        private val INVISIBLE = Regex("""[­​﻿⁠]""")

        /** Collapses whitespace and drops soft hyphens / zero-width spaces (they split words otherwise). */
        fun normalizeWhitespace(s: String): String = s.replace(INVISIBLE, "").replace(WS, " ").trim()
    }
}
