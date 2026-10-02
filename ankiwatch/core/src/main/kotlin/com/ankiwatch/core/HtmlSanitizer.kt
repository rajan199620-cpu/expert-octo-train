package com.ankiwatch.core

/**
 * Shrinks card HTML before it crosses the Bluetooth link, without changing what the watch
 * renders: scripts, styles and comments are dropped (the watch never shows them) and images
 * keep only their alt text (an inline base64 image can be hundreds of kilobytes).
 *
 * Every removed region is replaced by an empty `<b></b>`, so text on either side cannot fuse
 * into a cloze marker that was not there before (e.g. `}<!-- -->}` must not become `}}`).
 */
object HtmlSanitizer {

    private const val SEPARATOR = "<b></b>"

    fun forWatch(html: String): String {
        if (html.indexOf('<') < 0) return html
        val out = StringBuilder(html.length)
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }
            when (val scan = TagScanner.scan(html, i)) {
                null -> {
                    out.append(c)
                    i++
                }
                is TagScanner.Scan.Skip, is TagScanner.Scan.RawElement -> {
                    out.append(SEPARATOR)
                    i = scan.end
                }
                is TagScanner.Scan.TagScan -> {
                    val tag = scan.tag
                    if (tag.name == "img" && !tag.isEnd) {
                        val alt = tag.attrs["alt"]
                        if (alt.isNullOrEmpty()) out.append("<img>")
                        else out.append("<img alt=\"").append(escapeAttribute(alt)).append("\">")
                    } else {
                        out.append(html, i, scan.end)
                    }
                    i = scan.end
                }
            }
        }
        return out.toString()
    }

    fun escapeAttribute(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    fun escapeText(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    /** True when [html] has no visible text (empty, whitespace, `<br>`, `&nbsp;` …). */
    fun isBlank(html: String): Boolean {
        if (html.isBlank()) return true
        val rendered = CardRenderer.render(html, RenderOptions(plainClozes = true))
        return rendered.blocks.none { b -> b.runs.any { it.text.isNotBlank() } }
    }
}
