package com.ankiwatch.core

/**
 * Reads one HTML construct starting at a `<`. Shared by [ClozeParser] and [HtmlSanitizer] so
 * both agree on exactly where every tag, comment and script ends.
 */
internal object TagScanner {

    /** Elements whose contents are raw text that is never displayed. */
    val RAW_TEXT_TAGS = setOf("script", "style", "textarea", "title", "template", "noscript")

    sealed class Scan(val end: Int) {
        /** A comment, doctype or processing instruction: skip it. */
        class Skip(end: Int) : Scan(end)

        /** A start or end tag. */
        class TagScan(end: Int, val tag: Node.Tag) : Scan(end)

        /** A script/style-like element including its contents and end tag: skip it. */
        class RawElement(end: Int, val name: String) : Scan(end)
    }

    /**
     * Returns what starts at [start] (which must be a `<`), or null when the `<` is plain
     * text, e.g. "a < b" or a tag that is never closed.
     */
    fun scan(s: String, start: Int): Scan? {
        val n = s.length
        if (start + 1 >= n) return null
        val next = s[start + 1]

        if (s.startsWith("<!--", start)) {
            val end = s.indexOf("-->", start + 4)
            return Scan.Skip(if (end < 0) n else end + 3)
        }
        if (next == '!' || next == '?') {
            val end = s.indexOf('>', start + 2)
            return if (end < 0) null else Scan.Skip(end + 1)
        }

        val isEnd = next == '/'
        val nameStart = if (isEnd) start + 2 else start + 1
        if (nameStart >= n || !s[nameStart].isAsciiLetter()) return null
        var j = nameStart
        while (j < n && (s[j].isAsciiLetter() || s[j] in '0'..'9' || s[j] == '-' || s[j] == ':')) j++
        val name = s.substring(nameStart, j).lowercase()

        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (true) {
            while (j < n && s[j].isHtmlSpace()) j++
            if (j >= n) return null
            val c = s[j]
            if (c == '>') {
                j++
                break
            }
            if (c == '/') {
                if (j + 1 < n && s[j + 1] == '>') {
                    selfClosing = true
                    j += 2
                    break
                }
                j++
                continue
            }
            val attrStart = j
            while (j < n && !s[j].isHtmlSpace() && s[j] != '>' && s[j] != '=' && s[j] != '/') j++
            val attrName = s.substring(attrStart, j).lowercase()
            while (j < n && s[j].isHtmlSpace()) j++
            var value = ""
            if (j < n && s[j] == '=') {
                j++
                while (j < n && s[j].isHtmlSpace()) j++
                if (j >= n) return null
                val q = s[j]
                if (q == '"' || q == '\'') {
                    val close = s.indexOf(q, j + 1)
                    if (close < 0) return null
                    value = s.substring(j + 1, close)
                    j = close + 1
                } else {
                    val valueStart = j
                    while (j < n && !s[j].isHtmlSpace() && s[j] != '>') j++
                    value = s.substring(valueStart, j)
                }
            }
            if (attrName.isNotEmpty() && !isEnd) attrs.putIfAbsent(attrName, Entities.decode(value))
            if (j == attrStart) j++ // e.g. a lone '=': never loop without progress
        }

        if (name in RAW_TEXT_TAGS) {
            if (isEnd) return Scan.Skip(j)
            if (selfClosing) return Scan.Skip(j)
            val close = indexOfIgnoreCase(s, "</$name", j)
            if (close < 0) return Scan.RawElement(n, name)
            val gt = s.indexOf('>', close)
            return Scan.RawElement(if (gt < 0) n else gt + 1, name)
        }
        return Scan.TagScan(j, Node.Tag(name, isEnd, selfClosing, attrs))
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int {
        val last = s.length - needle.length
        var i = from
        while (i <= last) {
            if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }
}
