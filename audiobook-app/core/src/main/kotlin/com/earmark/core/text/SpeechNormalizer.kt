package com.earmark.core.text

/**
 * Turns display text into text a TTS voice can read naturally.
 *
 * A large share of "this sounds like a robot" comes from the text, not the voice: citation
 * markers read aloud ("bracket twelve"), "§" skipped, "Dr." read as "drive", "1,00,000" read
 * digit by digit, SHOUTED HEADINGS spelled out letter by letter. The display text is never
 * modified; this only produces the string handed to the engine.
 */
class SpeechNormalizer(
    private val lexicon: Lexicon = Lexicon.EMPTY,
    private val options: Options = Options(),
) {
    data class Options(
        val dropCitations: Boolean = true,
        val expandAbbreviations: Boolean = true,
        val speakUrlsAsDomain: Boolean = true,
    )

    fun normalize(input: String): String {
        var s = SentenceSegmenter.normalizeWhitespace(input)
        if (s.isEmpty()) return s
        s = lexicon.apply(s)
        s = s.replace(LIGATURES) { LIGATURE_MAP.getValue(it.value) }
        if (options.dropCitations) {
            s = s.replace(BRACKET_CITATION, "")
            s = s.replace(SUPERSCRIPT_DIGITS, "")
            s = s.replace(GLUED_FOOTNOTE, "$1")
        }
        if (options.speakUrlsAsDomain) {
            s = s.replace(URL) { m -> m.groupValues[1].removePrefix("www.") }
        }
        s = ungroupNumbers(s, INDIAN_GROUPED)
        s = ungroupNumbers(s, WESTERN_GROUPED)
        s = expandCurrency(s)
        s = expandSymbols(s)
        if (options.expandAbbreviations) s = expandAbbreviations(s)
        s = expandRomanAfterKeywords(s)
        s = s.replace(NUMBER_RANGE) { "${it.groupValues[1]} to ${it.groupValues[2]}" }
        s = s.replace(SPACED_DASH, ", ")
        s = s.replace(EM_DASH, ", ")
        s = fixShouting(s)
        s = s.replace(SPACE_BEFORE_PUNCT, "$1")
        s = s.replace(DOUBLE_COMMA, ",")
        s = s.replace(MULTI_SPACE, " ").trim()
        s = s.trimStart(',', ';', ' ')
        return s
    }

    /**
     * "1,00,000" and "1,000,000" lose their separators so engines read them as one number,
     * but "sections 302,304" is a list, not three hundred two thousand.
     */
    private fun ungroupNumbers(input: String, pattern: Regex): String = pattern.replace(input) { m ->
        val before = input.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase().trimEnd('.')
        if (before in LIST_CONTEXT) m.value else m.value.replace(",", "")
    }

    private fun expandCurrency(input: String): String {
        var s = input
        s = s.replace(CURRENCY_PREFIX) { m ->
            val unit = CURRENCY_NAMES.getValue(m.groupValues[1].trim().uppercase().removeSuffix("."))
            val amount = m.groupValues[2]
            val scale = m.groupValues[3].trim()
            speakMoney(amount, scale, unit)
        }
        return s
    }

    private fun speakMoney(amount: String, scale: String, unit: Currency): String {
        if (scale.isNotEmpty()) return "$amount ${SCALE_WORDS[scale.lowercase()] ?: scale} ${unit.plural}"
        val parts = amount.split('.')
        val whole = parts[0]
        val frac = parts.getOrNull(1)
        val wholeWord = if (whole == "1") unit.singular else unit.plural
        if (frac == null || frac.all { it == '0' }) return "$whole $wholeWord"
        if (frac.length == 2 && unit.minorPlural != null) {
            val minor = frac.trimStart('0')
            val minorWord = if (minor == "1") unit.minorSingular else unit.minorPlural
            return if (whole == "0") "$minor $minorWord" else "$whole $wholeWord and $minor $minorWord"
        }
        return "$amount ${unit.plural}"
    }

    private fun expandSymbols(input: String): String {
        var s = input
        s = s.replace(SECTION_SIGNS) { if (it.value.count { c -> c == '\u00A7' } > 1) "sections " else "section " }
        s = s.replace(PERCENT, "$1 percent")
        s = s.replace(DEGREES) { m ->
            val unit = when (m.groupValues[2]) { "C" -> " degrees Celsius"; "F" -> " degrees Fahrenheit"; else -> " degrees" }
            m.groupValues[1] + unit
        }
        s = s.replace(TIMES_BETWEEN_NUMBERS, "$1 times $2")
        for ((symbol, word) in SYMBOL_WORDS) s = s.replace(symbol, word)
        s = s.replace(TILDE_NUMBER, "about $1")
        return s
    }

    private fun expandAbbreviations(input: String): String {
        var s = input
        for ((regex, replacement) in ABBREVIATIONS) s = s.replace(regex, replacement)
        return s
    }

    private fun expandRomanAfterKeywords(input: String): String = input.replace(ROMAN_AFTER_KEYWORD) { m ->
        val n = RomanNumerals.toInt(m.groupValues[2])
        if (n == null) m.value else "${m.groupValues[1]} $n"
    }

    /** ALL-CAPS runs of long words are read letter by letter by some engines; sentence-case them. */
    private fun fixShouting(input: String): String {
        val letters = input.count { it.isLetter() }
        if (letters < 8) return input
        val upper = input.count { it.isUpperCase() }
        if (upper.toDouble() / letters < 0.8) {
            return input.replace(SHOUTED_WORD) { m -> m.value.lowercase().replaceFirstChar { it.titlecase() } }
        }
        val lowered = input.lowercase()
        return lowered.replaceFirstChar { it.titlecase() }
    }

    private data class Currency(
        val singular: String,
        val plural: String,
        val minorSingular: String? = null,
        val minorPlural: String? = null,
    )

    companion object {
        private val LIGATURE_MAP = mapOf(
            "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl",
            "ﬅ" to "st", "ﬆ" to "st",
        )
        private val LIGATURES = Regex("[ﬀ-ﬆ]")

        private val BRACKET_CITATION = Regex("""\s?\[(?:\d+(?:\s*[,–\-]\s*\d+)*|citation needed|note \d+|\w{0,3}\d+)\]""")
        private val SUPERSCRIPT_DIGITS = Regex("[¹²³⁰-⁹]+")
        /** "end of the sentence.12 Next" -> footnote number glued after punctuation. */
        private val GLUED_FOOTNOTE = Regex("""(?<=\p{Ll})([.,;:!?"”’)])\d{1,3}(?=\s+[\p{Lu}"“(]|\s*$)""")
        private val URL = Regex("""(?:https?://)([A-Za-z0-9.-]*[A-Za-z0-9])(?:[/?#][^\s)]*[^\s).,;:!?])?""")

        private val INDIAN_GROUPED = Regex("""(?<![\d.,])\d{1,2}(?:,\d{2})+,\d{3}(?![\d,])""")
        private val WESTERN_GROUPED = Regex("""(?<![\d.,])\d{1,3}(?:,\d{3})+(?![\d,])""")
        private val LIST_CONTEXT = setOf(
            "sections", "section", "ss", "s", "pp", "pages", "page", "nos", "articles", "rules", "clauses",
            "\u00A7\u00A7", "\u00A7", "and", "or", "paras", "paragraphs", "items", "chapters",
        )

        private val CURRENCY_NAMES = mapOf(
            "$" to Currency("dollar", "dollars", "cent", "cents"),
            "US$" to Currency("dollar", "dollars", "cent", "cents"),
            "USD" to Currency("dollar", "dollars", "cent", "cents"),
            "₹" to Currency("rupee", "rupees", "paisa", "paise"),
            "RS" to Currency("rupee", "rupees", "paisa", "paise"),
            "INR" to Currency("rupee", "rupees", "paisa", "paise"),
            "€" to Currency("euro", "euros", "cent", "cents"),
            "EUR" to Currency("euro", "euros", "cent", "cents"),
            "£" to Currency("pound", "pounds", "penny", "pence"),
            "GBP" to Currency("pound", "pounds", "penny", "pence"),
            "¥" to Currency("yen", "yen"),
        )
        private val CURRENCY_PREFIX = Regex(
            """(US\$|\$|₹|€|£|¥|\b(?:Rs\.?|INR|USD|EUR|GBP)\s?)\s?(\d+(?:\.\d+)?)(\s?(?:million|billion|trillion|crore|crores|lakh|lakhs|thousand|mn|bn|m|k|cr)\b)?""",
            RegexOption.IGNORE_CASE,
        )
        private val SCALE_WORDS = mapOf(
            "mn" to "million", "m" to "million", "bn" to "billion", "k" to "thousand", "cr" to "crore",
            "crores" to "crore", "lakhs" to "lakh",
        )

        private val SECTION_SIGNS = Regex("""§§?\s*""")
        private val PERCENT = Regex("""(\d)\s?%""")
        private val DEGREES = Regex("""(\d)\s?°\s?([CF])?\b""")
        private val TIMES_BETWEEN_NUMBERS = Regex("""(\d)\s?[×x]\s?(\d)""")
        private val TILDE_NUMBER = Regex("""~\s?(\d)""")
        private val SYMBOL_WORDS = listOf(
            " & " to " and ", "&" to " and ", "¶" to "paragraph ", "±" to " plus or minus ",
            "→" to " to ", "←" to " from ", "≈" to " approximately ", "≤" to " less than or equal to ",
            "≥" to " greater than or equal to ", "≠" to " not equal to ", "•" to ", ", "·" to ", ",
        )

        private fun abbr(pattern: String, replacement: String, ignoreCase: Boolean = false) =
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) to replacement

        private val ABBREVIATIONS = listOf(
            abbr("""\be\.\s?g\.(?:,)?""", "for example,", ignoreCase = true),
            abbr("""\bi\.\s?e\.(?:,)?""", "that is,", ignoreCase = true),
            abbr("""\betc\.(?=\s+\p{Ll})""", "et cetera"),
            abbr("""\betc\.""", "et cetera."),
            abbr("""\bviz\.""", "namely"),
            abbr("""\bcf\.""", "compare"),
            abbr("""\bapprox\.""", "approximately"),
            abbr("""\bvs\.?(?=\s)""", "versus"),
            abbr("""(?<=\p{L}) v\. (?=\p{Lu})""", " versus "),
            abbr("""\bet al\.""", "and others"),
            abbr("""\bDr\.(?=\s+\p{Lu})""", "Doctor"),
            abbr("""\bMr\.(?=\s)""", "Mister"),
            abbr("""\bMrs\.(?=\s)""", "Missus"),
            abbr("""\bProf\.(?=\s)""", "Professor"),
            abbr("""\bSt\.(?=\s+\p{Lu})""", "Saint"),
            abbr("""\bNo\.\s?(?=\d)""", "number "),
            abbr("""\bNos\.\s?(?=\d)""", "numbers "),
            abbr("""\bFig\.\s?(?=\d)""", "figure "),
            abbr("""\bVol\.\s?(?=\d)""", "volume "),
            abbr("""\bpp\.\s?(?=\d)""", "pages "),
            abbr("""\bp\.\s?(?=\d)""", "page "),
            abbr("""\b[Cc]h\.\s?(?=\d)""", "chapter "),
            abbr("""\b[Ss]ec\.\s?(?=\d)""", "section "),
            abbr("""\b[Ss]s\.\s?(?=\d)""", "sections "),
            abbr("""(?<![\p{L}.])[Ss]\.\s?(?=\d)""", "section "),
            abbr("""\bArt\.\s?(?=\d)""", "article "),
            abbr("""\bu/s\b""", "under section", ignoreCase = true),
            abbr("""\br/w\b""", "read with", ignoreCase = true),
            abbr("""\bw\.e\.f\.""", "with effect from", ignoreCase = true),
            abbr("""\bHon'?ble\b""", "Honourable", ignoreCase = true),
            abbr("""\bGovt\.""", "Government"),
            abbr("""\bDept\.""", "Department"),
            abbr("""\bJan\.(?=\s?\d)""", "January"),
            abbr("""\bFeb\.(?=\s?\d)""", "February"),
            abbr("""\bAug\.(?=\s?\d)""", "August"),
            abbr("""\bSept?\.(?=\s?\d)""", "September"),
            abbr("""\bOct\.(?=\s?\d)""", "October"),
            abbr("""\bNov\.(?=\s?\d)""", "November"),
            abbr("""\bDec\.(?=\s?\d)""", "December"),
        )

        private val ROMAN_AFTER_KEYWORD = Regex(
            """\b(Chapter|CHAPTER|Part|PART|Book|BOOK|Section|SECTION|Volume|Vol|Act|Schedule|SCHEDULE|Article|Appendix|War|Phase|Level|Class|Title|TITLE)\s+([IVXLCDM]{1,7})\b""",
        )
        private val NUMBER_RANGE = Regex("""(\d)\s?[–—]\s?(\d)""")
        private val SPACED_DASH = Regex("""\s+[–—-]{1,2}\s+""")
        private val EM_DASH = Regex("""—""")
        private val SHOUTED_WORD = Regex("""\b\p{Lu}{5,}\b""")
        private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?])""")
        private val DOUBLE_COMMA = Regex(""",\s*,""")
        private val MULTI_SPACE = Regex("""\s{2,}""")
    }
}
