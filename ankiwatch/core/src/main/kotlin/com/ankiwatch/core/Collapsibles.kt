package com.ankiwatch.core

/**
 * Turns the collapsed sections of a note ("why", "note", "source" …) into covered spans the
 * watch shows as `[why]` until tapped, the way the deck hides them on the phone:
 *  - `<details><summary>why</summary> … </details>` (unless it has `open`);
 *  - a clickable label followed by a hidden element, the template's own panel pattern:
 *    `<div onclick="showNextElement(this)">why</div><div style="display:none"> … </div>`;
 *  - a sidenote span, `<span class="sn" data-sn="why" data-sn-c="3"> … </span>`, labelled by
 *    `data-sn`. `data-sn-c` names the cloze it explains: often it restates that answer, so
 *    the renderer leaves it out on the question side of that cloze's own card.
 * Other hidden elements stay hidden, as the renderer always did: on template cards they hold
 * the template's own machinery, not something to read.
 *
 * Each becomes a [Node.Cloze] with [SIDENOTE_ORD], which no card tests, so it renders like a
 * sibling cloze: covered by its label, opened by a tap, on both sides of the card. A tested
 * cloze inside one keeps it open, as for any cloze that holds the tested one.
 */
internal object Collapsibles {

    /** The ord of a collapsed section: below every real cloze number. */
    const val SIDENOTE_ORD = -1

    private const val MAX_LABEL = 40
    private const val DEFAULT_LABEL = "more"
    private const val MAX_DEPTH = 64

    fun fold(field: ParsedField): ParsedField {
        if (!mayHaveAny(field.nodes)) return field
        var nextId = field.clozeCount
        val folded = foldList(field.nodes, 0) { nextId++ }
        return ParsedField(folded, nextId)
    }

    /** Cheap check: nothing to do for the usual field without such markup. */
    private fun mayHaveAny(nodes: List<Node>): Boolean {
        val stack = ArrayList<List<Node>>()
        stack.add(nodes)
        while (stack.isNotEmpty()) {
            for (node in stack.removeAt(stack.size - 1)) {
                when (node) {
                    is Node.Tag -> if (!node.isEnd && (node.name == "details" || node.attrs.containsKey("onclick") || isSidenote(node))) return true
                    is Node.Cloze -> stack.add(node.content)
                    is Node.Text -> Unit
                }
            }
        }
        return false
    }

    private fun foldList(nodes: List<Node>, depth: Int, newId: () -> Int): List<Node> {
        // Clozes nested absurdly deep keep their markup as it is rather than overflow the stack.
        if (depth > MAX_DEPTH) return nodes
        val out = ArrayList<Node>(nodes.size)
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            if (node is Node.Cloze) {
                out.add(Node.Cloze(node.id, node.ord, foldList(node.content, depth + 1, newId), node.hint, node.isAnchor, node.sidenoteFor))
                i++
                continue
            }
            if (node !is Node.Tag || node.isEnd || node.isSelfClosing) {
                out.add(node)
                i++
                continue
            }
            // <details>: the summary is the label, the rest the hidden body.
            if (node.name == "details" && !node.attrs.containsKey("open")) {
                val end = matchingEnd(nodes, i)
                val inner = nodes.subList(i + 1, minOf(end, nodes.size))
                val sumStart = inner.indexOfFirst { it is Node.Tag && it.name == "summary" && !it.isEnd }
                val label: String
                val body: List<Node>
                if (sumStart >= 0) {
                    val sumEnd = matchingEnd(inner, sumStart)
                    label = labelOf(inner.subList(sumStart + 1, minOf(sumEnd, inner.size)))
                    body = inner.subList(0, sumStart) + inner.subList(minOf(sumEnd + 1, inner.size), inner.size)
                } else {
                    label = ""
                    body = inner
                }
                out.addAll(collapsed(label, foldList(body, depth + 1, newId), newId()))
                i = end + 1
                continue
            }
            // A sidenote span: the whole element is the hidden body.
            if (isSidenote(node)) {
                val end = matchingEnd(nodes, i)
                val body = nodes.subList(i + 1, minOf(end, nodes.size))
                val label = labelOf(listOf(Node.Text(node.attrs["data-sn"].orEmpty())))
                val target = node.attrs["data-sn-c"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                out.addAll(collapsed(label, foldList(body, depth + 1, newId), newId(), target))
                i = end + 1
                continue
            }
            // A clickable label (no cloze in it) directly followed by a hidden element.
            if (node.attrs.containsKey("onclick")) {
                val end = matchingEnd(nodes, i)
                val labelNodes = nodes.subList(i + 1, minOf(end, nodes.size))
                val next = nextElement(nodes, end + 1)
                if (end < nodes.size && next >= 0 && labelNodes.none { it is Node.Cloze }) {
                    val hiddenEnd = matchingEnd(nodes, next)
                    val body = nodes.subList(next + 1, minOf(hiddenEnd, nodes.size))
                    out.addAll(collapsed(labelOf(labelNodes), foldList(body, depth + 1, newId), newId()))
                    i = hiddenEnd + 1
                    continue
                }
            }
            out.add(node)
            i++
        }
        return out
    }

    /** Index of the first hidden element at or after [from], past blank text and `<br>`; else -1. */
    private fun nextElement(nodes: List<Node>, from: Int): Int {
        var k = from
        while (k < nodes.size) {
            val n = nodes[k]
            when {
                n is Node.Text && n.text.isBlank() -> k++
                n is Node.Tag && n.name == "br" -> k++
                n is Node.Tag && !n.isEnd && !n.isSelfClosing && isHidden(n) -> return k
                else -> return -1
            }
        }
        return -1
    }

    /**
     * Index of the end tag closing the element opened at [start] in this list, counting
     * nested elements of the same name; nodes.size if it is never closed.
     */
    private fun matchingEnd(nodes: List<Node>, start: Int): Int {
        val name = (nodes[start] as Node.Tag).name
        var depth = 0
        for (k in start until nodes.size) {
            val n = nodes[k] as? Node.Tag ?: continue
            if (n.name != name || n.isSelfClosing) continue
            if (n.isEnd) {
                depth--
                if (depth == 0) return k
            } else {
                depth++
            }
        }
        return nodes.size
    }

    private fun collapsed(label: String, body: List<Node>, id: Int, sidenoteFor: Int = 0): List<Node> {
        val hint = label.ifBlank { DEFAULT_LABEL }
        val content = ArrayList<Node>(body.size + 2)
        content.add(DIV)
        content.addAll(body)
        content.add(DIV_END)
        // Its own block, so the label doesn't run on from the text before it.
        return listOf(DIV, Node.Cloze(id, SIDENOTE_ORD, content, hint, isAnchor = false, sidenoteFor = sidenoteFor), DIV_END)
    }

    private fun labelOf(nodes: List<Node>): String {
        val sb = StringBuilder()
        for (n in nodes) {
            when (n) {
                is Node.Text -> sb.append(n.text)
                is Node.Tag -> if (n.name == "br") sb.append(' ')
                is Node.Cloze -> Unit
            }
        }
        // Arrows and the like that only point at the panel.
        val text = sb.toString().collapseWhitespace().trim().trim('▸', '▾', '▶', '▼', '►', '+', '-', ':', ' ').trim()
        return if (text.length <= MAX_LABEL) text else text.take(MAX_LABEL - 1) + "…"
    }

    /** `data-sn="…"`, or a `sn` class. */
    private fun isSidenote(tag: Node.Tag): Boolean =
        tag.attrs.containsKey("data-sn") || tag.attrs["class"]?.split(' ', '\t', '\n')?.contains("sn") == true

    private fun isHidden(tag: Node.Tag): Boolean {
        val style = tag.attrs["style"] ?: return false
        return style.lowercase().filterNot { it.isHtmlSpace() }.contains("display:none")
    }

    private val DIV = Node.Tag("div", isEnd = false, isSelfClosing = false, attrs = emptyMap())
    private val DIV_END = Node.Tag("div", isEnd = true, isSelfClosing = false, attrs = emptyMap())
}
