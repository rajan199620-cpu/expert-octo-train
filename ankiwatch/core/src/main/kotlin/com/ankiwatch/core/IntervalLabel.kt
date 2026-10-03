package com.ankiwatch.core

/**
 * AnkiDroid's answer-button labels ("1m", "<1m", "10m", "1.5h", "4d", "1.2mo", "2y") as a
 * length of time. Offline, the watch uses them to bring a card back after the same delay
 * AnkiDroid would: a label under a day means the answer lands the card in a learning step.
 */
object IntervalLabel {

    const val MINUTE_MS = 60_000L
    const val DAY_MS = 86_400_000L

    /** Milliseconds the label stands for, or null when it isn't a recognisable interval. */
    fun millis(label: String): Long? {
        // Translated labels may carry bidi isolation marks and non-breaking spaces.
        val text = label.filterNot { it in INVISIBLE }.replace(SPACES, " ").trim().trimStart('<', '~', '≈', '>').trim()
        val match = PATTERN.matchEntire(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = UNITS[match.groupValues[2].lowercase()] ?: return null
        val ms = amount * unit
        if (ms.isNaN() || ms < 0 || ms > MAX_MS) return null
        return Math.round(ms)
    }

    private val PATTERN = Regex("""(\d{1,9}(?:[.,]\d{1,6})?)\s*(\p{L}+)\.?""")
    private const val INVISIBLE = "\u2066\u2067\u2068\u2069\u200E\u200F\u200B\uFEFF"
    private val SPACES = Regex("[\u00A0\u2007\u2009\u202F]")

    private const val MAX_MS = 1_000.0 * 86_400 * 366 * 1_000 // a thousand years

    private val UNITS: Map<String, Double> = buildMap {
        fun put(ms: Double, vararg names: String) = names.forEach { put(it, ms) }
        put(1_000.0, "s", "sec", "secs", "second", "seconds")
        put(60_000.0, "m", "min", "mins", "minute", "minutes")
        put(3_600_000.0, "h", "hr", "hrs", "hour", "hours")
        put(86_400_000.0, "d", "day", "days")
        put(7 * 86_400_000.0, "w", "wk", "wks", "week", "weeks")
        put(30 * 86_400_000.0, "mo", "mos", "month", "months")
        put(365 * 86_400_000.0, "y", "yr", "yrs", "year", "years")
    }
}
