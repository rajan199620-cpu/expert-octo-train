package com.ankiwear.wear.model

/**
 * A node in the deck hierarchy. AnkiDroid encodes parent/child relationships in the deck
 * NAME with "::" as the separator (e.g. "Languages::Spanish::Vocabulary"). The watch
 * builds this tree from the flat deck list the phone sends so it can render proper
 * indented, collapsible subdecks instead of a wall of prefixed strings.
 *
 * @property deck the original DeckInfo. For a REAL AnkiDroid deck these counts are
 *   already subtree-aggregated (AnkiDroid sources them from sched.deckDueTree()), so a
 *   parent's count already includes its subdecks — we must NOT sum children again.
 * @property displayName the leaf segment of the full name (e.g. "Vocabulary"), used
 *   to render the row without repeating the parent path.
 * @property depth nesting depth — 0 for top-level decks, 1 for direct children, etc.
 * @property children direct child sub-decks, in name-sorted order.
 * @property displayNew / displayLearn / displayReview the counts to render for this
 *   row. For real decks these equal AnkiDroid's already-aggregated counts; for a
 *   synthesized (virtual) intermediate that AnkiDroid never reported, they're rolled
 *   up from the children so the row isn't blank.
 */
data class DeckNode(
    val deck: DeckInfo,
    val displayName: String,
    val depth: Int,
    val children: List<DeckNode>,
    val displayNew: Int,
    val displayLearn: Int,
    val displayReview: Int
) {
    val displayTotal: Int get() = displayNew + displayLearn + displayReview
    val hasChildren: Boolean get() = children.isNotEmpty()

    /** True for synthesized intermediate nodes that don't correspond to a real AnkiDroid
     *  deck (id == -1). These shouldn't be tappable for review — only for expand/collapse. */
    val isVirtual: Boolean get() = deck.id == VIRTUAL_DECK_ID

    companion object {
        const val VIRTUAL_DECK_ID = -1L
    }
}
