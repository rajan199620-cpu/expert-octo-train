package com.earmark.core.parse

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

/**
 * Flattens (X)HTML into paragraph blocks. Block elements flush a paragraph, inline elements
 * join into it, and things nobody wants read aloud (scripts, footnote call-outs, page-number
 * markers, image alt noise) are skipped.
 */
object HtmlTextExtractor {
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "blockquote", "li", "ul", "ol", "dl", "dt", "dd", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "footer", "table", "tr", "td", "th", "caption",
        "figure", "figcaption", "body", "main", "hr", "address", "center", "details", "summary", "aside",
    )
    private val SKIP_TAGS = setOf("script", "style", "noscript", "head", "title", "svg", "math", "template", "iframe", "object", "nav", "img", "audio", "video", "rt", "rp")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val WHITESPACE = Regex("""\s+""")
    private val NOTE_TYPES = setOf("noteref", "footnote", "endnote", "rearnote", "pagebreak", "footnotes", "endnotes")

    fun extract(root: Element): List<RawBlock> {
        val out = ArrayList<RawBlock>()
        val buf = StringBuilder()
        var heading = false

        fun flush() {
            val text = buf.toString().replace(WHITESPACE, " ").trim()
            if (text.isNotEmpty()) out += RawBlock(text, isHeading = heading)
            buf.setLength(0)
            heading = false
        }

        // Iterative traversal: a pathological EPUB with thousands of nested elements must not
        // overflow the (small) stack of an Android background thread.
        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when (node) {
                    is TextNode -> buf.append(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in SKIP_TAGS || isNote(node)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        if (tag == "br") buf.append(' ')
                        if (tag in BLOCK_TAGS) flush()
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in HEADING_TAGS) heading = true
                    if (tag in BLOCK_TAGS) flush()
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        }, root)
        flush()
        return out
    }

    private fun isNote(e: Element): Boolean {
        val types = (e.attr("epub:type") + " " + e.attr("role")).lowercase().split(' ', '-')
            .map { it.removePrefix("doc") }
        if (types.any { it in NOTE_TYPES }) return true
        // <sup><a href="#fn3">3</a></sup> style footnote call-outs.
        if (e.normalName() == "sup" && e.text().trim().matches(Regex("""[\d*†‡§]{1,3}"""))) return true
        if (e.normalName() == "a" && e.attr("href").contains("#fn") && e.text().trim().matches(Regex("""\[?\d{1,3}]?"""))) return true
        return false
    }
}
