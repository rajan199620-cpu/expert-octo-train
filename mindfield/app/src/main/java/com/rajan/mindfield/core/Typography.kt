package com.rajan.mindfield.core

/**
 * The library is written with plain keyboard quotes; the app shows proper typography.
 * Double quotes open after a space or bracket and close otherwise; apostrophes become ’.
 */
object Typography {
    private const val OPENERS = "([{/–—-"

    fun smarten(text: String): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            val prev = if (i == 0) ' ' else text[i - 1]
            val next = if (i == text.lastIndex) ' ' else text[i + 1]
            when (c) {
                '"' -> out.append(if (prev.isWhitespace() || prev in OPENERS) '“' else '”')
                '\'' -> out.append(if ((prev.isWhitespace() || prev in OPENERS) && next.isLetter()) '‘' else '’')
                else -> out.append(c)
            }
        }
        return out.toString().replace("...", "…").replace(" -- ", " — ")
    }
}
