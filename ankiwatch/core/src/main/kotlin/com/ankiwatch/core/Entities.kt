package com.ankiwatch.core

/**
 * Decodes HTML character references the way a browser would for the text Anki stores in
 * note fields. Unknown or malformed references are left untouched rather than dropped, so
 * nothing the user typed silently disappears.
 */
object Entities {

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "zwnj" to "‌", "zwj" to "‍", "shy" to "­",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "minus" to "−",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
        "sect" to "§", "para" to "¶", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "plusmn" to "±", "times" to "×",
        "divide" to "÷", "middot" to "·", "bull" to "•", "dagger" to "†",
        "Dagger" to "‡", "prime" to "′", "Prime" to "″", "permil" to "‰",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "curren" to "¤", "larr" to "←", "rarr" to "→", "uarr" to "↑",
        "darr" to "↓", "harr" to "↔", "lArr" to "⇐", "rArr" to "⇒",
        "uArr" to "⇑", "dArr" to "⇓", "hArr" to "⇔", "ne" to "≠",
        "le" to "≤", "ge" to "≥", "asymp" to "≈", "equiv" to "≡",
        "infin" to "∞", "there4" to "∴", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³",
        "ordf" to "ª", "ordm" to "º", "iexcl" to "¡", "iquest" to "¿",
        "check" to "✓", "cross" to "✗", "star" to "☆", "starf" to "★",
        "loz" to "◊", "spades" to "♠", "clubs" to "♣", "hearts" to "♥",
        "diams" to "♦", "alpha" to "α", "beta" to "β", "gamma" to "γ",
        "delta" to "δ", "epsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ",
        "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ",
        "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω", "agrave" to "à", "aacute" to "á",
        "acirc" to "â", "auml" to "ä", "ccedil" to "ç", "egrave" to "è",
        "eacute" to "é", "ecirc" to "ê", "euml" to "ë", "iacute" to "í",
        "iuml" to "ï", "ntilde" to "ñ", "oacute" to "ó", "ocirc" to "ô",
        "ouml" to "ö", "uacute" to "ú", "uuml" to "ü", "szlig" to "ß"
    )

    /**
     * Browsers remap numeric references in the C1 range to Windows-1252, which is what text
     * pasted from Word ends up as (`&#150;` for an en dash, `&#147;`/`&#148;` for quotes).
     */
    private val WINDOWS_1252 = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026,
        0x86 to 0x2020, 0x87 to 0x2021, 0x88 to 0x02C6, 0x89 to 0x2030, 0x8A to 0x0160,
        0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D, 0x91 to 0x2018, 0x92 to 0x2019,
        0x93 to 0x201C, 0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014,
        0x98 to 0x02DC, 0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A, 0x9C to 0x0153,
        0x9E to 0x017E, 0x9F to 0x0178
    )

    /** Longest reference we try to resolve; anything longer is plain text. */
    private const val MAX_REFERENCE_LENGTH = 12

    fun decode(text: String): String {
        val first = text.indexOf('&')
        if (first < 0) return text
        val out = StringBuilder(text.length)
        out.append(text, 0, first)
        var i = first
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i - 1 > MAX_REFERENCE_LENGTH || semi == i + 1) {
                out.append(c)
                i++
                continue
            }
            val replacement = resolve(text.substring(i + 1, semi))
            if (replacement == null) {
                out.append(c)
                i++
            } else {
                out.append(replacement)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun resolve(body: String): String? {
        if (body[0] != '#') return NAMED[body]
        if (body.length < 2) return null
        val isHex = body[1] == 'x' || body[1] == 'X'
        val digits = if (isHex) body.substring(2) else body.substring(1)
        if (digits.isEmpty() || digits.length > 8) return null
        val valid = if (isHex) digits.all { it.isHexDigitChar() } else digits.all { it in '0'..'9' }
        if (!valid) return null
        val parsed = digits.toLongOrNull(if (isHex) 16 else 10) ?: return null
        var cp = if (parsed > Int.MAX_VALUE) -1 else parsed.toInt()
        cp = WINDOWS_1252[cp] ?: cp
        if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) cp = 0xFFFD
        return String(Character.toChars(cp))
    }

    private fun Char.isHexDigitChar(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
