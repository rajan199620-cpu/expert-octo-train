package com.ankiwatch.core

/** What a block of rendered text is, so the watch can style it. */
enum class BlockKind { TEXT, HEADING, SUBHEADING, RULE }

/** Colour role of a heading, taken from the Enhanced Cloze layout classes. */
enum class Accent { NONE, BLUE, RED, GREEN, YELLOW }

/** Inline style flags carried by a [Run]. */
object Style {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDERLINE = 4
    const val STRIKE = 8
    const val SUPERSCRIPT = 16
    const val SUBSCRIPT = 32
    const val CODE = 64
    /** De-emphasised text the watch adds itself: list markers, image placeholders. */
    const val DIM = 128
}

enum class ClozeState {
    /** The cloze being tested, covered by its hint box. */
    GENUINE_HIDDEN,
    /** The cloze being tested, uncovered (answer side, or tapped on the question side). */
    GENUINE_SHOWN,
    /** A sibling cloze, covered by its hint box. */
    PSEUDO_HIDDEN,
    /** A sibling cloze the user tapped open. */
    PSEUDO_SHOWN,
    /** A sibling cloze that is always open: a `#` anchor, or one that wraps the tested cloze. */
    CONTEXT;

    val isHidden: Boolean get() = this == GENUINE_HIDDEN || this == PSEUDO_HIDDEN
    val isGenuine: Boolean get() = this == GENUINE_HIDDEN || this == GENUINE_SHOWN
}

/** Which cloze span a run belongs to. [id] is what the UI toggles. */
data class ClozeMark(val id: Int, val state: ClozeState, val toggleable: Boolean)

/** A stretch of text with one style. Hidden clozes appear as their `[hint]` placeholder. */
data class Run(val text: String, val style: Int, val cloze: ClozeMark?)

data class Block(
    val index: Int,
    val runs: List<Run>,
    val kind: BlockKind,
    /** List nesting depth, 0 outside lists. */
    val depth: Int,
    /** List marker such as "1." or "•" for the first block of a list item. */
    val label: String?,
    /** A later line of a list item (after a `<br>` or a nested list), aligned with its text. */
    val continuation: Boolean,
    val accent: Accent,
    /** Inside an Enhanced Cloze `core-box` panel. */
    val boxed: Boolean,
    /** Holds the tested cloze, hidden or shown. */
    val containsGenuine: Boolean,
    /** The `<hr id=answer>` divider Anki puts between question and answer. */
    val isAnswerStart: Boolean,
    /** Earlier blocks that give this one its context: section heading, column title, parent list items. */
    val context: List<Int>
) {
    val text: String get() = runs.joinToString("") { it.text }
}

class RenderedField(val blocks: List<Block>, val clozeCount: Int) {

    val hasGenuine: Boolean = blocks.any { it.containsGenuine }

    fun firstGenuineBlock(): Int = blocks.indexOfFirst { it.containsGenuine }

    /** Index of the first block after Anki's answer divider, or -1. */
    fun answerStartBlock(): Int {
        val rule = blocks.indexOfFirst { it.isAnswerStart }
        return if (rule < 0 || rule + 1 >= blocks.size) -1 else rule + 1
    }

    /**
     * Blocks to show in focus mode: every block holding the tested cloze plus the blocks that
     * give it context, in document order. Null when there is nothing to focus on, in which
     * case the whole field should be shown.
     */
    fun focusIndices(): List<Int>? {
        val picked = java.util.TreeSet<Int>()
        for (block in blocks) {
            if (!block.containsGenuine) continue
            picked.add(block.index)
            for (c in block.context) if (c in blocks.indices) picked.add(c)
        }
        return if (picked.isEmpty()) null else picked.toList()
    }

    /** Plain text, one line per block. Used by tests and accessibility. */
    fun plainText(): String = blocks.joinToString("\n") { b ->
        when {
            b.kind == BlockKind.RULE -> "---"
            b.label != null -> "${b.label} ${b.text}"
            else -> b.text
        }
    }
}
