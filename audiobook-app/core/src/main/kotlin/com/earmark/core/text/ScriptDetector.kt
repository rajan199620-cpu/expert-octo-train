package com.earmark.core.text

import com.earmark.core.model.Book

/**
 * Guesses the book's language from its script so the app can pick a matching voice. An
 * English voice reading Devanagari either skips it or spells it out, which is the fastest
 * way to make a "human" narrator sound broken.
 */
object ScriptDetector {
    private val scripts = mapOf(
        Character.UnicodeScript.DEVANAGARI to "hi",
        Character.UnicodeScript.BENGALI to "bn",
        Character.UnicodeScript.TAMIL to "ta",
        Character.UnicodeScript.TELUGU to "te",
        Character.UnicodeScript.GUJARATI to "gu",
        Character.UnicodeScript.GURMUKHI to "pa",
        Character.UnicodeScript.KANNADA to "kn",
        Character.UnicodeScript.MALAYALAM to "ml",
        Character.UnicodeScript.ORIYA to "or",
        Character.UnicodeScript.ARABIC to "ar",
        Character.UnicodeScript.HEBREW to "he",
        Character.UnicodeScript.CYRILLIC to "ru",
        Character.UnicodeScript.GREEK to "el",
        Character.UnicodeScript.THAI to "th",
        Character.UnicodeScript.HANGUL to "ko",
        Character.UnicodeScript.HIRAGANA to "ja",
        Character.UnicodeScript.KATAKANA to "ja",
        Character.UnicodeScript.HAN to "zh",
    )

    /** ISO 639-1 code for the dominant non-Latin script, or null for Latin/unknown text. */
    fun guessLanguage(text: CharSequence, sampleChars: Int = 20_000): String? {
        val counts = HashMap<String, Int>()
        var latin = 0
        var seen = 0
        var i = 0
        while (i < text.length && seen < sampleChars) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            seen++
            val script = Character.UnicodeScript.of(cp)
            if (script == Character.UnicodeScript.LATIN) latin++ else scripts[script]?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        // Japanese mixes kana with Han characters; a real share of kana means Japanese.
        if (seen > 0 && (counts["ja"] ?: 0) * 10 >= seen) return "ja"
        val best = counts.maxByOrNull { it.value } ?: return null
        return if (best.value > latin) best.key else null
    }

    fun guessLanguage(book: Book): String? {
        val sb = StringBuilder()
        val step = maxOf(1, book.sentences.size / 200)
        for (i in book.sentences.indices step step) {
            sb.append(book.sentences[i].text).append(' ')
            if (sb.length > 20_000) break
        }
        return guessLanguage(sb)
    }
}
