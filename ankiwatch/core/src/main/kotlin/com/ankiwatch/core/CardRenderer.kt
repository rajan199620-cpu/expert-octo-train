package com.ankiwatch.core

/**
 * @param activeOrd the cloze number this card tests (card ord + 1), or 0 for none.
 * @param showAnswer answer side: the tested cloze starts uncovered.
 * @param toggled cloze ids the user tapped; each tap flips that span from its default.
 * @param plainClozes render cloze markup as ordinary text (Note/Extra fields).
 */
data class RenderOptions(
    val activeOrd: Int = 0,
    val showAnswer: Boolean = false,
    val toggled: Set<Int> = emptySet(),
    val plainClozes: Boolean = false
)

/**
 * Turns a [ParsedField] into blocks of styled text for a small screen, applying the
 * Enhanced Cloze rules:
 *  - the tested cloze is covered by `[hint]` on the question side and uncovered on the answer side;
 *  - sibling clozes stay covered (tap to open) unless their answer starts with `#`;
 *  - the content of a covered cloze is never emitted, tags included, exactly as the template
 *    replaces the whole span with its hint.
 *
 * Works iteratively, so deeply nested input cannot overflow the stack.
 */
object CardRenderer {
    fun render(field: ParsedField, options: RenderOptions): RenderedField =
        Renderer(field, options).run()

    fun render(html: String, options: RenderOptions): RenderedField =
        render(ClozeParser.parse(html), options)
}

private val BLOCK_TAGS = setOf(
    "address", "article", "aside", "blockquote", "caption", "center", "dd", "details", "dialog",
    "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2",
    "h3", "h4", "h5", "h6", "header", "hgroup", "li", "main", "menu", "nav", "ol", "p", "pre",
    "section", "summary", "table", "tbody", "tfoot", "thead", "tr", "ul"
)

private val VOID_TAGS = setOf(
    "area", "base", "col", "embed", "input", "link", "meta", "param", "source", "track", "wbr"
)

private val HIDDEN_TAGS = setOf("head", "rp", "select", "option", "button", "audio", "video", "svg", "math", "canvas", "iframe", "object")

private const val MAX_CONTEXT_ANCESTORS = 6
private const val MAX_ALT_LENGTH = 40
private const val ELLIPSIS = "…"

private class ListCtx(val ordered: Boolean, var counter: Int, val type: Char, val bulletLevel: Int)

private class Elem(
    val name: String,
    val isBlock: Boolean,
    val styleBits: Int,
    val hidden: Boolean,
    val heading: Int, // 0 none, 1 heading, 2 subheading
    val accent: Accent,
    val boxed: Boolean,
    val pre: Boolean,
    val list: ListCtx?,
    val isLi: Boolean
) {
    var liHead = -1
}

private class Cursor(val nodes: List<Node>, val popsMark: Boolean) {
    var i = 0
}

private class Renderer(private val field: ParsedField, private val opt: RenderOptions) {

    private val blocks = ArrayList<Block>()

    // Current block, open until the next block boundary.
    private var open = false
    private val runs = ArrayList<Run>()
    private val runText = StringBuilder()
    private var runStyle = 0
    private var runMark: ClozeMark? = null
    private var curKind = BlockKind.TEXT
    private var curDepth = 0
    private var curLabel: String? = null
    private var curContinuation = false
    private var curAccent = Accent.NONE
    private var curBoxed = false
    private var curGenuine = false
    private var curContext: List<Int> = emptyList()

    private var pendingLabel: String? = null
    private var pendingLi: Elem? = null

    // Element stack and the counters derived from it.
    private val elems = ArrayList<Elem>()
    private val styleCount = IntArray(8)
    private var hiddenDepth = 0
    private var preDepth = 0
    private var boxedDepth = 0
    private var headingDepth = 0
    private var subheadingDepth = 0
    private val accents = ArrayList<Accent>()
    private val lists = ArrayList<ListCtx>()
    private val openLis = ArrayList<Elem>()
    private var lastHeading = -1
    private var lastSubheading = -1
    /** Set once the field uses real heading markup; bold-only lines then stop acting as titles. */
    private var sawExplicitHeading = false

    private val marks = ArrayList<ClozeMark>()
    private val containsActive = BooleanArray(field.clozeCount)

    fun run(): RenderedField {
        if (opt.activeOrd > 0 && !opt.plainClozes) computeContainsActive()
        val stack = ArrayList<Cursor>()
        stack.add(Cursor(field.nodes, popsMark = false))
        while (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            if (top.i >= top.nodes.size) {
                stack.removeAt(stack.size - 1)
                if (top.popsMark) marks.removeAt(marks.size - 1)
                continue
            }
            when (val node = top.nodes[top.i++]) {
                is Node.Text -> emitText(node.text)
                is Node.Tag -> handleTag(node)
                is Node.Cloze -> {
                    if (opt.plainClozes) {
                        stack.add(Cursor(node.content, popsMark = false))
                    } else {
                        val mark = markFor(node)
                        if (mark.state.isHidden) {
                            emitPlaceholder(node, mark)
                        } else {
                            marks.add(mark)
                            stack.add(Cursor(node.content, popsMark = true))
                        }
                    }
                }
            }
        }
        flush()
        return RenderedField(blocks, field.clozeCount)
    }

    // ── Clozes ───────────────────────────────────────────────────────────────────────────

    private fun markFor(node: Node.Cloze): ClozeMark {
        val genuine = opt.activeOrd > 0 && node.ord == opt.activeOrd
        val tapped = node.id in opt.toggled
        if (genuine) {
            val shown = opt.showAnswer != tapped
            return ClozeMark(node.id, if (shown) ClozeState.GENUINE_SHOWN else ClozeState.GENUINE_HIDDEN, toggleable = true)
        }
        if (node.isAnchor || containsActive.getOrElse(node.id) { false }) {
            return ClozeMark(node.id, ClozeState.CONTEXT, toggleable = false)
        }
        return ClozeMark(node.id, if (tapped) ClozeState.PSEUDO_SHOWN else ClozeState.PSEUDO_HIDDEN, toggleable = true)
    }

    /** Marks every cloze that has the tested cloze somewhere inside it. */
    private fun computeContainsActive() {
        class Frame(val nodes: List<Node>, val owner: Node.Cloze?) {
            var i = 0
        }
        val stack = ArrayList<Frame>()
        val ancestors = ArrayList<Node.Cloze>()
        stack.add(Frame(field.nodes, null))
        while (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            if (top.i >= top.nodes.size) {
                stack.removeAt(stack.size - 1)
                if (top.owner != null) ancestors.removeAt(ancestors.size - 1)
                continue
            }
            val node = top.nodes[top.i++]
            if (node is Node.Cloze) {
                if (node.ord == opt.activeOrd) {
                    for (k in ancestors.indices.reversed()) {
                        val id = ancestors[k].id
                        if (containsActive[id]) break // everything further out is already marked
                        containsActive[id] = true
                    }
                }
                ancestors.add(node)
                stack.add(Frame(node.content, node))
            }
        }
    }

    private fun emitPlaceholder(node: Node.Cloze, mark: ClozeMark) {
        val hint = node.hint?.takeIf { it.isNotBlank() } ?: ELLIPSIS
        appendInline("[$hint]", currentStyle(), mark, preserve = true)
    }

    // ── Text ─────────────────────────────────────────────────────────────────────────────

    private fun emitText(text: String) {
        if (hiddenDepth > 0 || text.isEmpty()) return
        val mark = marks.lastOrNull()
        if (preDepth > 0) {
            val lines = text.replace('\t', ' ').replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for ((k, line) in lines.withIndex()) {
                if (k > 0) flush()
                if (line.isNotEmpty()) appendInline(line, currentStyle(), mark, preserve = true)
            }
            return
        }
        appendInline(text.collapseWhitespace(), currentStyle(), mark, preserve = false)
    }

    private fun appendInline(text: String, style: Int, mark: ClozeMark?, preserve: Boolean) {
        var t = text
        if (!open) {
            if (!preserve) t = t.trimStart(' ', ' ')
            if (t.isEmpty()) return
            startBlock()
        } else if (!preserve && t.startsWith(' ') && endsWithSpace()) {
            t = t.substring(1)
            if (t.isEmpty()) return
        }
        if (mark != null && mark.state.isGenuine) curGenuine = true
        if (runText.isNotEmpty() && (style != runStyle || mark != runMark)) commitRun()
        runStyle = style
        runMark = mark
        runText.append(t)
    }

    private fun endsWithSpace(): Boolean {
        val last = if (runText.isNotEmpty()) runText[runText.length - 1]
        else runs.lastOrNull()?.text?.lastOrNull() ?: return true
        return last == ' ' || last == ' '
    }

    private fun commitRun() {
        if (runText.isEmpty()) return
        runs.add(Run(runText.toString(), runStyle, runMark))
        runText.setLength(0)
    }

    private fun currentStyle(): Int {
        var bits = 0
        for (b in 0 until 7) if (styleCount[b] > 0) bits = bits or (1 shl b)
        return bits
    }

    // ── Blocks ───────────────────────────────────────────────────────────────────────────

    private fun startBlock() {
        val index = blocks.size
        open = true
        curKind = when {
            headingDepth > 0 -> BlockKind.HEADING
            subheadingDepth > 0 -> BlockKind.SUBHEADING
            else -> BlockKind.TEXT
        }
        curDepth = (lists.size - 1).coerceAtLeast(0)
        curLabel = pendingLabel
        curContinuation = curLabel == null && openLis.isNotEmpty()
        curAccent = accents.lastOrNull() ?: Accent.NONE
        curBoxed = boxedDepth > 0
        curGenuine = false
        curContext = buildContext()
        pendingLi?.liHead = index
        pendingLi = null
        pendingLabel = null
        when (curKind) {
            BlockKind.HEADING -> {
                lastHeading = index
                lastSubheading = -1
                sawExplicitHeading = true
            }
            BlockKind.SUBHEADING -> {
                lastSubheading = index
                sawExplicitHeading = true
            }
            else -> Unit
        }
    }

    /**
     * A top-level line that is entirely bold and has no clozes (the topic line of an answer
     * skeleton, "<b>Theories of …</b>") works as a title for the blocks below it, unless
     * the field has proper heading markup.
     */
    private fun isBoldTitle(): Boolean {
        if (sawExplicitHeading || curKind != BlockKind.TEXT || curLabel != null || curContinuation || curDepth != 0) return false
        if (lists.isNotEmpty()) return false
        var sawText = false
        for (run in runs) {
            if (run.cloze != null) return false
            if (run.text.isBlank()) continue
            if (run.style and Style.BOLD == 0) return false
            sawText = true
        }
        return sawText
    }

    private fun buildContext(): List<Int> {
        val ctx = ArrayList<Int>(4)
        if (curKind == BlockKind.TEXT) {
            if (lastHeading >= 0) ctx.add(lastHeading)
            if (lastSubheading >= 0) ctx.add(lastSubheading)
        } else if (curKind == BlockKind.SUBHEADING && lastHeading >= 0) {
            ctx.add(lastHeading)
        }
        val from = (openLis.size - MAX_CONTEXT_ANCESTORS).coerceAtLeast(0)
        for (k in from until openLis.size) {
            val head = openLis[k].liHead
            if (head >= 0) ctx.add(head)
        }
        return if (ctx.size <= 1) ctx else ctx.distinct().sorted()
    }

    private fun flush() {
        if (!open) return
        commitRun()
        // Trim trailing whitespace across the final runs.
        while (runs.isNotEmpty()) {
            val last = runs[runs.size - 1]
            val trimmed = if (last.cloze?.state?.isHidden == true) last.text else last.text.trimEnd(' ', ' ')
            if (trimmed.isEmpty()) {
                runs.removeAt(runs.size - 1)
                continue
            }
            if (trimmed.length != last.text.length) runs[runs.size - 1] = last.copy(text = trimmed)
            break
        }
        open = false
        val index = blocks.size
        if (runs.isNotEmpty() && isBoldTitle()) {
            lastHeading = index
            lastSubheading = -1
        }
        if (runs.isEmpty()) {
            // Nothing visible after trimming: forget any pointers to this block.
            for (li in openLis) if (li.liHead == index) li.liHead = -1
            if (lastHeading == index) lastHeading = -1
            if (lastSubheading == index) lastSubheading = -1
            return
        }
        blocks.add(
            Block(
                index = index,
                runs = ArrayList(runs),
                kind = curKind,
                depth = curDepth,
                label = curLabel,
                continuation = curContinuation,
                accent = curAccent,
                boxed = curBoxed,
                containsGenuine = curGenuine,
                isAnswerStart = false,
                context = curContext
            )
        )
        runs.clear()
    }

    private fun addRule(isAnswer: Boolean) {
        flush()
        val last = blocks.lastOrNull()
        if (last != null && last.kind == BlockKind.RULE && !isAnswer) return
        blocks.add(
            Block(
                index = blocks.size,
                runs = emptyList(),
                kind = BlockKind.RULE,
                depth = 0,
                label = null,
                continuation = false,
                accent = Accent.NONE,
                boxed = false,
                containsGenuine = false,
                isAnswerStart = isAnswer,
                context = emptyList()
            )
        )
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────────────

    private fun handleTag(tag: Node.Tag) {
        val name = tag.name
        if (tag.isEnd) {
            closeElement(name)
            return
        }
        when (name) {
            "br" -> {
                if (hiddenDepth == 0) flush()
                return
            }
            "hr" -> {
                if (hiddenDepth == 0) addRule(isAnswer = tag.attrs["id"].equals("answer", ignoreCase = true))
                return
            }
            "img" -> {
                if (hiddenDepth == 0) {
                    val alt = tag.attrs["alt"]?.collapseWhitespace()?.trim().orEmpty()
                    val text = if (alt.isEmpty()) "[image]" else "[image: ${alt.take(MAX_ALT_LENGTH)}]"
                    appendInline(text, currentStyle() or Style.DIM, marks.lastOrNull(), preserve = true)
                }
                return
            }
        }
        if (name in VOID_TAGS) return

        if ((name == "td" || name == "th") && open && hiddenDepth == 0) {
            appendInline(" │ ", Style.DIM, null, preserve = true)
        }
        if (name == "li") closeOpenListItem()
        if (name == "p") closeOpenParagraph()

        val isBlock = name in BLOCK_TAGS
        if (isBlock) flush()

        val classes = tag.attrs["class"]?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val style = tag.attrs["style"]?.lowercase()?.replace(" ", "").orEmpty()

        var bits = when (name) {
            "b", "strong", "th" -> Style.BOLD
            "i", "em", "cite", "dfn", "var" -> Style.ITALIC
            "u", "ins" -> Style.UNDERLINE
            "s", "strike", "del" -> Style.STRIKE
            "sup" -> Style.SUPERSCRIPT
            "sub" -> Style.SUBSCRIPT
            "code", "kbd", "samp", "tt" -> Style.CODE
            "h1", "h2", "h3", "h4", "h5", "h6" -> Style.BOLD
            else -> 0
        }
        if (style.isNotEmpty()) bits = bits or styleBitsFromCss(style)
        if ("limb" in classes) bits = bits or Style.BOLD

        val hidden = name in HIDDEN_TAGS || tag.attrs.containsKey("hidden") ||
            "display:none" in style || "visibility:hidden" in style
        val heading = when {
            name == "h1" || name == "h2" || name == "h3" || "header" in classes -> 1
            name == "h4" || name == "h5" || name == "h6" || "col-title" in classes -> 2
            else -> 0
        }
        val accent = accentFor(classes, heading)
        val boxed = "core-box" in classes
        val list = when (name) {
            "ol" -> ListCtx(
                ordered = true,
                counter = (tag.attrs["start"]?.trim()?.toIntOrNull() ?: 1) - 1,
                type = listType(tag.attrs["type"], style),
                bulletLevel = 0
            )
            "ul", "menu", "dir" -> ListCtx(
                ordered = false,
                counter = 0,
                type = '1',
                bulletLevel = lists.count { !it.ordered }
            )
            else -> null
        }
        val elem = Elem(
            name = name,
            isBlock = isBlock,
            styleBits = bits,
            hidden = hidden,
            heading = heading,
            accent = accent,
            boxed = boxed,
            pre = name == "pre",
            list = list,
            isLi = name == "li"
        )
        push(elem)

        if ("column" in classes) lastSubheading = -1
        if (elem.isLi && hiddenDepth == 0) {
            pendingLabel = labelFor(lists.lastOrNull(), tag.attrs["value"])
            pendingLi = elem
        }
    }

    private fun push(e: Elem) {
        elems.add(e)
        for (b in 0 until 7) if (e.styleBits and (1 shl b) != 0) styleCount[b]++
        if (e.hidden) hiddenDepth++
        if (e.pre) preDepth++
        if (e.boxed) boxedDepth++
        if (e.heading == 1) headingDepth++
        if (e.heading == 2) subheadingDepth++
        if (e.accent != Accent.NONE) accents.add(e.accent)
        if (e.list != null) lists.add(e.list)
        if (e.isLi) openLis.add(e)
    }

    private fun popTo(index: Int) {
        var closedBlock = false
        while (elems.size > index) {
            val e = elems.removeAt(elems.size - 1)
            for (b in 0 until 7) if (e.styleBits and (1 shl b) != 0) styleCount[b]--
            if (e.hidden) hiddenDepth--
            if (e.pre) preDepth--
            if (e.boxed) boxedDepth--
            if (e.heading == 1) headingDepth--
            if (e.heading == 2) subheadingDepth--
            if (e.accent != Accent.NONE) accents.removeAt(accents.size - 1)
            if (e.list != null) lists.removeAt(lists.size - 1)
            if (e.isLi) {
                openLis.removeAt(openLis.size - 1)
                if (pendingLi === e) {
                    pendingLi = null
                    pendingLabel = null
                }
            }
            if (e.isBlock) closedBlock = true
        }
        if (closedBlock) flush()
    }

    private fun closeElement(name: String) {
        for (k in elems.indices.reversed()) {
            if (elems[k].name == name) {
                // Flush before popping so the block keeps the properties it started with.
                if (elems[k].isBlock) flush()
                popTo(k)
                return
            }
            // An end tag never closes past the list or table it sits in.
            if (name == "li" && (elems[k].name == "ol" || elems[k].name == "ul")) return
        }
        // Stray end tag: a stray </p> still ends the line, as in a browser.
        if (name == "p" || name == "br") flush()
    }

    /** `<li>` implicitly closes a still-open `<li>` of the same list. */
    private fun closeOpenListItem() {
        for (k in elems.indices.reversed()) {
            val n = elems[k].name
            if (n == "li") {
                flush()
                popTo(k)
                return
            }
            if (n == "ol" || n == "ul" || n == "menu" || n == "dir") return
        }
    }

    /** `<p>` implicitly closes an open `<p>`. */
    private fun closeOpenParagraph() {
        for (k in elems.indices.reversed()) {
            val e = elems[k]
            if (e.name == "p") {
                flush()
                popTo(k)
                return
            }
            if (e.isBlock) return
        }
    }

    private fun labelFor(list: ListCtx?, valueAttr: String?): String {
        if (list == null) return "•"
        if (!list.ordered) {
            return when (list.bulletLevel % 3) {
                0 -> "•"
                1 -> "◦"
                else -> "▪"
            }
        }
        val explicit = valueAttr?.trim()?.toIntOrNull()
        list.counter = explicit ?: (list.counter + 1)
        return formatCounter(list.counter, list.type) + "."
    }
}

private fun accentFor(classes: Set<String>, heading: Int): Accent = when {
    "header-red" in classes || "punishment" in classes -> Accent.RED
    "header-green" in classes || "classification" in classes -> Accent.GREEN
    "header-yellow" in classes || "proviso" in classes -> Accent.YELLOW
    "header-blue" in classes -> Accent.BLUE
    heading != 0 && ("header" in classes || "col-title" in classes) -> Accent.BLUE
    else -> Accent.NONE
}

private fun styleBitsFromCss(css: String): Int {
    var bits = 0
    val weight = cssValue(css, "font-weight")
    if (weight != null && (weight.startsWith("bold") || weight in setOf("600", "700", "800", "900"))) bits = bits or Style.BOLD
    val fontStyle = cssValue(css, "font-style")
    if (fontStyle != null && (fontStyle.startsWith("italic") || fontStyle.startsWith("oblique"))) bits = bits or Style.ITALIC
    val decoration = (cssValue(css, "text-decoration") ?: "") + (cssValue(css, "text-decoration-line") ?: "")
    if ("underline" in decoration) bits = bits or Style.UNDERLINE
    if ("line-through" in decoration) bits = bits or Style.STRIKE
    return bits
}

/** Value of [property] in a lowercased, space-free inline style, or null. */
private fun cssValue(css: String, property: String): String? {
    var from = 0
    while (true) {
        val at = css.indexOf("$property:", from)
        if (at < 0) return null
        // Must be a whole property name, not the tail of another (e.g. "-webkit-font-weight").
        if (at == 0 || css[at - 1] == ';' || css[at - 1] == '{') {
            val start = at + property.length + 1
            val end = css.indexOf(';', start).let { if (it < 0) css.length else it }
            return css.substring(start, end).removeSuffix("!important")
        }
        from = at + 1
    }
}

private fun listType(typeAttr: String?, css: String): Char {
    when (typeAttr?.trim()) {
        "a" -> return 'a'
        "A" -> return 'A'
        "i" -> return 'i'
        "I" -> return 'I'
    }
    return when (cssValue(css, "list-style-type") ?: cssValue(css, "list-style")) {
        "lower-alpha", "lower-latin" -> 'a'
        "upper-alpha", "upper-latin" -> 'A'
        "lower-roman" -> 'i'
        "upper-roman" -> 'I'
        else -> '1'
    }
}

internal fun formatCounter(n: Int, type: Char): String {
    if (n <= 0) return n.toString()
    return when (type) {
        'a' -> alpha(n)
        'A' -> alpha(n).uppercase()
        'i' -> if (n < 4000) roman(n) else n.toString()
        'I' -> if (n < 4000) roman(n).uppercase() else n.toString()
        else -> n.toString()
    }
}

private fun alpha(n: Int): String {
    val sb = StringBuilder()
    var v = n
    while (v > 0) {
        v--
        sb.append('a' + v % 26)
        v /= 26
    }
    return sb.reverse().toString()
}

private fun roman(n: Int): String {
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
    val sb = StringBuilder()
    var v = n
    for (k in values.indices) {
        while (v >= values[k]) {
            sb.append(symbols[k])
            v -= values[k]
        }
    }
    return sb.toString()
}
