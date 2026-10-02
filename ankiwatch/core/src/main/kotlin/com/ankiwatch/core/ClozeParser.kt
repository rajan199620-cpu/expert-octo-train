package com.ankiwatch.core

/**
 * A parsed note field: HTML text, tags and cloze deletions as a tree.
 *
 * Cloze markers are found in text only (never inside a tag), the way Anki and the Enhanced
 * Cloze template read `{{cN::answer::hint}}`. Unlike the template's regex, nesting is
 * supported, so a cloze inside another cloze still gets its own number.
 */
sealed class Node {
    /** Entity-decoded text. */
    class Text(val text: String) : Node()

    class Tag(
        val name: String,
        val isEnd: Boolean,
        val isSelfClosing: Boolean,
        val attrs: Map<String, String>
    ) : Node()

    /**
     * One cloze span. [id] is unique within the field (document order of the opening
     * marker) and is what the UI uses to toggle a single span.
     *
     * [isAnchor] mirrors the template's `#` rule: an answer that starts with `#` stays
     * visible as context on sibling cards. The `#` itself is already stripped from [content].
     */
    class Cloze(
        val id: Int,
        val ord: Int,
        val content: List<Node>,
        val hint: String?,
        val isAnchor: Boolean
    ) : Node()
}

class ParsedField(val nodes: List<Node>, val clozeCount: Int) {
    /** Cloze numbers present in the field, ascending. */
    fun clozeNumbers(): List<Int> {
        val found = java.util.TreeSet<Int>()
        val stack = ArrayList<List<Node>>()
        stack.add(nodes)
        while (stack.isNotEmpty()) {
            for (node in stack.removeAt(stack.size - 1)) {
                if (node is Node.Cloze) {
                    found.add(node.ord)
                    stack.add(node.content)
                }
            }
        }
        return found.toList()
    }
}

object ClozeParser {

    private val SOUND_TAG = Regex("""\[(?:sound:[^\]]*|anki:[^\]]*)]""")

    private const val MAX_ORD_DIGITS = 6

    fun parse(html: String): ParsedField = buildTree(tokenize(html))

    // ── Tokenizer ────────────────────────────────────────────────────────────────────────

    private sealed class Tok {
        class Text(val raw: String) : Tok()
        class Tag(val tag: Node.Tag) : Tok()
        class Open(val ord: Int) : Tok()
        object Sep : Tok()
        object Close : Tok()
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        val text = StringBuilder()
        var depth = 0
        var i = 0
        val n = s.length

        fun flushText() {
            if (text.isNotEmpty()) {
                out.add(Tok.Text(text.toString()))
                text.setLength(0)
            }
        }

        while (i < n) {
            val c = s[i]
            if (c == '<') {
                val scan = TagScanner.scan(s, i)
                if (scan != null) {
                    flushText()
                    if (scan is TagScanner.Scan.TagScan) out.add(Tok.Tag(scan.tag))
                    i = scan.end
                    continue
                }
            } else if (c == '{' && s.startsWith("{{c", i)) {
                var j = i + 3
                while (j < n && j - (i + 3) < MAX_ORD_DIGITS && s[j] in '0'..'9') j++
                if (j > i + 3 && s.startsWith("::", j)) {
                    flushText()
                    out.add(Tok.Open(s.substring(i + 3, j).toInt()))
                    depth++
                    i = j + 2
                    continue
                }
            } else if (depth > 0 && c == ':' && s.startsWith("::", i)) {
                flushText()
                out.add(Tok.Sep)
                i += 2
                continue
            } else if (depth > 0 && c == '}' && s.startsWith("}}", i)) {
                flushText()
                out.add(Tok.Close)
                depth--
                i += 2
                continue
            }
            text.append(c)
            i++
        }
        flushText()
        return out
    }

    // ── Tree builder ─────────────────────────────────────────────────────────────────────

    private object SepMarker

    private class Frame(val ord: Int, val id: Int) {
        val children = ArrayList<Any>() // Node or SepMarker
    }

    private fun buildTree(tokens: List<Tok>): ParsedField {
        val root = ArrayList<Node>()
        val stack = ArrayList<Frame>()
        var nextId = 0

        fun add(item: Any) {
            if (stack.isEmpty()) {
                root.add(item as? Node ?: Node.Text("::"))
            } else {
                stack[stack.size - 1].children.add(item)
            }
        }

        for (tok in tokens) {
            when (tok) {
                is Tok.Text -> add(Node.Text(cleanText(tok.raw)))
                is Tok.Tag -> add(tok.tag)
                is Tok.Open -> stack.add(Frame(tok.ord, nextId++))
                is Tok.Sep -> add(SepMarker)
                is Tok.Close -> {
                    if (stack.isEmpty()) {
                        add(Node.Text("}}"))
                    } else {
                        add(finishCloze(stack.removeAt(stack.size - 1)))
                    }
                }
            }
        }
        // An unclosed cloze is closed at the end of the field. Anki would print it as
        // literal text, which on a review card would show the answer; hiding the rest of
        // the field is the safer reading of a typo.
        while (stack.isNotEmpty()) {
            add(finishCloze(stack.removeAt(stack.size - 1)))
        }
        return ParsedField(root, nextId)
    }

    private fun finishCloze(frame: Frame): Node.Cloze {
        val items = frame.children
        // Hint rule: the first `::` after the last nested cloze. For a cloze without nesting
        // this is the first `::`, which is exactly what the template's non-greedy regex does.
        var lastNested = -1
        for (k in items.indices) if (items[k] is Node.Cloze) lastNested = k
        var sepIndex = -1
        for (k in lastNested + 1 until items.size) {
            if (items[k] === SepMarker) {
                sepIndex = k
                break
            }
        }
        val contentItems = if (sepIndex >= 0) items.subList(0, sepIndex) else items
        val hintItems = if (sepIndex >= 0) items.subList(sepIndex + 1, items.size) else null

        val content = ArrayList<Node>(contentItems.size)
        for (item in contentItems) content.add(if (item === SepMarker) Node.Text("::") else item as Node)
        var isAnchor = false
        val firstNode = content.firstOrNull()
        if (firstNode is Node.Text && firstNode.text.startsWith("#")) {
            isAnchor = true
            content[0] = Node.Text(firstNode.text.substring(1))
        }
        val hint = hintItems?.let { flattenHint(it) }
        return Node.Cloze(frame.id, frame.ord, content, hint, isAnchor)
    }

    private fun flattenHint(items: List<Any>): String {
        val sb = StringBuilder()
        val pending = ArrayDeque<Any>()
        pending.addAll(items)
        while (pending.isNotEmpty()) {
            when (val item = pending.removeFirst()) {
                SepMarker -> sb.append("::")
                is Node.Text -> sb.append(item.text)
                is Node.Tag -> if (item.name == "br") sb.append(' ')
                is Node.Cloze -> {
                    for (k in item.content.indices.reversed()) pending.addFirst(item.content[k])
                }
            }
        }
        return sb.toString().collapseWhitespace().trim()
    }

    private fun cleanText(raw: String): String {
        val withoutSound = if (raw.indexOf('[') >= 0) SOUND_TAG.replace(raw, "") else raw
        return Entities.decode(withoutSound)
    }
}

internal fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

internal fun Char.isHtmlSpace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

internal fun String.collapseWhitespace(): String {
    if (none { it.isHtmlSpace() }) return this
    val sb = StringBuilder(length)
    var inSpace = false
    for (c in this) {
        if (c.isHtmlSpace()) {
            if (!inSpace) sb.append(' ')
            inSpace = true
        } else {
            sb.append(c)
            inSpace = false
        }
    }
    return sb.toString()
}
