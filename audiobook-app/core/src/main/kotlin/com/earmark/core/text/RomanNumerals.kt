package com.earmark.core.text

object RomanNumerals {
    private val values = linkedMapOf(
        "M" to 1000, "CM" to 900, "D" to 500, "CD" to 400, "C" to 100, "XC" to 90,
        "L" to 50, "XL" to 40, "X" to 10, "IX" to 9, "V" to 5, "IV" to 4, "I" to 1,
    )

    /** Returns the value of a canonical roman numeral (case-insensitive), or null. */
    fun toInt(s: String): Int? {
        if (s.isEmpty() || s.length > 15) return null
        val u = s.uppercase()
        if (u.any { it !in "MDCLXVI" }) return null
        var i = 0
        var total = 0
        while (i < u.length) {
            val two = if (i + 1 < u.length) u.substring(i, i + 2) else null
            if (two != null && two in values && values.getValue(two) > values.getValue(u[i].toString())) {
                total += values.getValue(two)
                i += 2
            } else {
                total += values.getValue(u[i].toString())
                i++
            }
        }
        return if (total in 1..3999 && fromInt(total) == u) total else null
    }

    fun fromInt(n: Int): String {
        require(n in 1..3999)
        var rest = n
        val sb = StringBuilder()
        for ((sym, v) in values) {
            while (rest >= v) {
                sb.append(sym)
                rest -= v
            }
        }
        return sb.toString()
    }
}
