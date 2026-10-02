package com.ankiwear.wear.model

/**
 * Builds a tree of [DeckNode]s from the flat list of decks AnkiDroid returns.
 *
 * AnkiDroid encodes hierarchy in the deck name using "::" as the path separator. Decks
 * arrive with their full path (e.g. "Languages::Spanish"). IMPORTANT: AnkiDroid's counts
 * are already subtree-aggregated (sourced from sched.deckDueTree()), so a parent deck's
 * count already includes its subdecks — this builder must NOT sum children again or every
 * parent double-counts. This builder:
 *   1. Synthesizes any missing intermediate decks (defensive; AnkiDroid normally creates
 *      them automatically, but a deck named "Foo::Bar::Baz" with no "Foo" or "Foo::Bar"
 *      shouldn't break the tree).
 *   2. Groups children under their parent path.
 *   3. Uses each real deck's count as-is; only synthesized (virtual) intermediates —
 *      which AnkiDroid never reported a count for — roll up their children.
 *
 * Children of each parent are sorted alphabetically by display name to match AnkiDroid.
 */
object DeckTreeBuilder {

    private const val SEPARATOR = "::"

    fun build(decks: List<DeckInfo>): List<DeckNode> {
        if (decks.isEmpty()) return emptyList()

        val knownByName = decks.associateBy { it.name }.toMutableMap()

        // Synthesize missing intermediate paths so every node has a parent. With the
        // standard "Foo::Bar" → "Foo" relationship, this is a no-op for AnkiDroid's
        // output — but it keeps the tree well-formed if AnkiDroid ever skips one.
        for (deck in decks) {
            val parts = deck.name.split(SEPARATOR)
            for (i in 1 until parts.size) {
                val intermediate = parts.take(i).joinToString(SEPARATOR)
                if (intermediate !in knownByName) {
                    knownByName[intermediate] = DeckInfo(
                        id = DeckNode.VIRTUAL_DECK_ID,
                        name = intermediate,
                        newCount = 0,
                        learnCount = 0,
                        reviewCount = 0
                    )
                }
            }
        }

        val sorted = knownByName.values.sortedBy { it.name.lowercase() }

        // Group decks by parent path. Top-level decks land in `roots`.
        val childrenByParent = mutableMapOf<String, MutableList<DeckInfo>>()
        val roots = mutableListOf<DeckInfo>()

        for (deck in sorted) {
            val parts = deck.name.split(SEPARATOR)
            if (parts.size == 1) {
                roots.add(deck)
            } else {
                val parentPath = parts.dropLast(1).joinToString(SEPARATOR)
                childrenByParent.getOrPut(parentPath) { mutableListOf() }.add(deck)
            }
        }

        fun build(deck: DeckInfo, depth: Int): DeckNode {
            val displayName = deck.name.substringAfterLast(SEPARATOR)
            val children = childrenByParent[deck.name]
                ?.map { build(it, depth + 1) }
                ?.sortedBy { it.displayName.lowercase() }
                ?: emptyList()
            // Real decks: AnkiDroid already aggregated subdecks into this count, so use
            // it directly. Virtual (synthesized) intermediates have no real count, so we
            // roll up their children's display values. Recursion stops at the first real
            // descendant — whose value is itself already aggregated — so nothing is
            // double-counted regardless of how virtual/real nodes interleave.
            val isVirtual = deck.id == DeckNode.VIRTUAL_DECK_ID
            val displayNew = if (isVirtual) children.sumOf { it.displayNew } else deck.newCount
            val displayLearn = if (isVirtual) children.sumOf { it.displayLearn } else deck.learnCount
            val displayReview = if (isVirtual) children.sumOf { it.displayReview } else deck.reviewCount
            return DeckNode(
                deck = deck,
                displayName = displayName,
                depth = depth,
                children = children,
                displayNew = displayNew,
                displayLearn = displayLearn,
                displayReview = displayReview
            )
        }

        return roots.map { build(it, 0) }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Flattens the tree into a render list, including only children of nodes present
     * in [expandedNames]. The full deck name (path) is used as the expansion key — it's
     * stable across deck-count changes and uniquely identifies a node even when ids
     * are synthesized (-1).
     */
    fun flattenVisible(tree: List<DeckNode>, expandedNames: Set<String>): List<DeckNode> {
        val out = mutableListOf<DeckNode>()
        fun walk(node: DeckNode) {
            out.add(node)
            if (node.deck.name in expandedNames) {
                node.children.forEach(::walk)
            }
        }
        tree.forEach(::walk)
        return out
    }

    /** Collects every node name in the tree — useful for "expand all" as the initial state. */
    fun allNodeNames(tree: List<DeckNode>): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(node: DeckNode) {
            out.add(node.deck.name)
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return out
    }
}
