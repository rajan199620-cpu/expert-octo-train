package com.ankiwatch.core

import java.text.Normalizer

/**
 * Finds the deck or decks a spoken phrase names: "download GS four", "prelims DPSP",
 * "both DPSP from prelims and mains". Speech comes back as a few guesses, best first, with
 * the words spelled any old way, so names and phrases are both reduced to plain tokens:
 * number words and roman numerals become digits, spelled-out letters ("g s") join up,
 * "general studies" is "gs", and words like "download" or "deck" are dropped.
 *
 * A deck may be named with its parents ("prelims polity" is Prelims' Polity, never Mains').
 * A phrase joined with "and" asks for several decks, and what one part names is carried to
 * the parts that leave it out: in "DPSP from prelims and mains", "mains" means Mains' DPSP.
 * When a name fits several decks equally well, nothing is guessed: the user picks.
 */
object DeckMatcher {

    sealed class Result {
        /** One deck is clearly meant. */
        data class One(val name: String, val heard: String) : Result()

        /** Each of these decks is clearly meant. */
        data class Many(val names: List<String>, val heard: String) : Result()

        /** Not sure: the user picks from [names]; those in [chosen] were clearly meant. */
        data class Choose(val names: List<String>, val chosen: Set<String>, val heard: String) : Result()

        /** Nothing fits [heard] (empty when nothing was heard). */
        data class None(val heard: String) : Result()
    }

    /** At most this many decks are offered to pick from, or downloaded at once. */
    const val MAX_CHOICES = 8

    /** Speech guesses looked at; recognisers send about five. */
    private const val MAX_GUESSES = 10

    /** Longer phrases are cut; nobody says a deck name of more words. */
    private const val MAX_WORDS = 24

    /** Parts of one phrase looked at ("a and b and c ..."). */
    private const val MAX_PARTS = 8

    private const val EXACT = 3
    private const val IN_NAME = 2
    private const val IN_PATH = 1

    /** Which decks [alternatives] (speech guesses, best first) name, among [deckNames]. */
    fun match(alternatives: List<String>, deckNames: List<String>): Result = Index(deckNames).match(alternatives)

    /** [deckNames] prepared once, for matching phrases against the same decks. */
    class Index(deckNames: List<String>) {
        private val decks = deckNames.distinct().map { Entry(it) }.filter { it.leaf.isNotEmpty() }

        fun match(alternatives: List<String>): Result {
            val heard = alternatives.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_GUESSES).toList()
            var best: Reading? = null
            for (phrase in heard) {
                val reading = read(phrase) ?: continue
                // A later guess wins only by fitting better; the recogniser's order breaks ties.
                if (best == null || reading.quality > best.quality) best = reading
            }
            return best?.result ?: Result.None(heard.firstOrNull() ?: "")
        }

        /** What [phrase] asks for, and how well it fits; null when nothing does. */
        private fun read(phrase: String): Reading? {
            val whole = readWhole(spoken(phrase), plural(phrase), phrase)
            val parts = splitParts(phrase).map { spoken(it) }.filter { it.isNotEmpty() }.take(MAX_PARTS)
            if (parts.size < 2) return whole
            // A deck called "ethics & human interface" is one deck, not two.
            if (whole != null && whole.tiers == EXACT) return whole
            val split = readParts(parts, phrase)
            return when {
                split.certainty == 2 -> split
                whole != null -> whole
                split.certainty > 0 -> split
                else -> null
            }
        }

        /** [query] read as one name; with [plural], every deck it fits ("both polity"). */
        private fun readWhole(query: List<String>, plural: Boolean, phrase: String): Reading? {
            val hits = best(query)
            if (hits.isEmpty()) return null
            val names = hits.map { it.entry.name }
            val result = when {
                names.size == 1 -> Result.One(names[0], phrase)
                plural && names.size <= MAX_CHOICES -> Result.Many(names, phrase)
                plural -> Result.Choose(names.take(MAX_CHOICES), names.take(MAX_CHOICES).toSet(), phrase)
                else -> Result.Choose(names.take(MAX_CHOICES), emptySet(), phrase)
            }
            return Reading(result, if (names.size == 1 || (plural && names.size <= MAX_CHOICES)) 2 else 1, hits[0].tier)
        }

        /**
         * Several parts. The part saying most, once it names one deck, tells which of its words
         * are the deck's own name ("dpsp") and which its parents' ("prelims"). Each other part
         * is then tried with that name ("mains" → "mains dpsp"), then with those parents
         * ("history" → "history mains"), then alone.
         */
        private fun readParts(parts: List<List<String>>, phrase: String): Reading {
            val order = parts.indices.sortedWith(compareBy({ -parts[it].size }, { -it }))
            var anchor: Pair<Int, Entry>? = null
            for (i in order) {
                val hits = best(parts[i])
                if (hits.size == 1) {
                    anchor = i to hits[0].entry
                    break
                }
            }
            val sure = LinkedHashSet<String>()
            val unsure = LinkedHashSet<String>()
            var tiers = 0
            var missed = 0
            for ((i, part) in parts.withIndex()) {
                val tries = ArrayList<List<String>>()
                if (anchor != null && i != anchor.first) {
                    val anchorPart = parts[anchor.first]
                    val own = anchorPart.filter { t -> found(t, anchor.second.leaf, anchor.second.leafCompact) }
                    val parents = anchorPart - own.toSet()
                    if (own.isNotEmpty()) tries += (part + own).distinct()
                    if (parents.isNotEmpty()) tries += (part + parents).distinct()
                }
                tries += part
                var done = false
                for (t in tries) {
                    val hits = best(t)
                    if (hits.size == 1) {
                        sure += hits[0].entry.name
                        tiers += hits[0].tier
                        done = true
                        break
                    }
                }
                if (!done) {
                    val hits = best(part)
                    if (hits.isEmpty()) missed++
                    hits.forEach { unsure += it.entry.name }
                }
            }
            unsure.removeAll(sure)
            val result = when {
                // Part of it understood, part not: shown, not guessed.
                missed == 0 && unsure.isEmpty() && sure.size == 1 -> Result.One(sure.first(), phrase)
                missed == 0 && unsure.isEmpty() && sure.size in 2..MAX_CHOICES -> Result.Many(sure.toList(), phrase)
                sure.isEmpty() && unsure.isEmpty() -> return Reading(Result.None(phrase), 0, 0)
                else -> {
                    val names = (sure + unsure).take(MAX_CHOICES)
                    Result.Choose(names, sure.intersect(names.toSet()), phrase)
                }
            }
            val quality = when (result) {
                is Result.One, is Result.Many -> 2
                is Result.Choose -> 1
                is Result.None -> 0
            }
            return Reading(result, quality, tiers)
        }

        /** The decks [query] fits best, most likely first. */
        private fun best(query: List<String>): List<Hit> {
            if (query.isEmpty()) return emptyList()
            val hits = decks.mapNotNull { score(query, it) }
            val top = hits.maxOfOrNull { it.tier } ?: return emptyList()
            var ranked = hits.filter { it.tier == top }
                .sortedWith(compareBy<Hit>({ it.fuzzy }, { it.extra }, { it.entry.depth }, { it.entry.name.length }, { it.entry.name }))
            // Words said exactly beat ones only near ("bns": BNS 2023, not BNSS).
            val exact = ranked.filter { it.fuzzy == 0 }
            if (exact.isNotEmpty()) ranked = exact
            // Only one deck had its whole name said ("ethics gs4": Ethics, not Ethics & Human Interface).
            val whole = ranked.filter { it.extra == 0 }
            return if (whole.size == 1) whole else ranked
        }
    }

    private class Reading(val result: Result, val certainty: Int, val tiers: Int) {
        val quality get() = certainty * 1_000 + tiers
    }

    /**
     * How well a deck fits: its [tier], then how many words were only near ones ([fuzzy]),
     * then [extra] words of its own name the phrase didn't use.
     */
    private class Hit(val entry: Entry, val tier: Int, val extra: Int, val fuzzy: Int = 0)

    private class Entry(val name: String) {
        private val segments: List<List<String>> = name.split("::").map { tokens(it) }
        val depth = segments.size - 1
        val leaf: List<String> = segments.last()
        val leafCompact = leaf.joinToString("")
        val path: List<String> = segments.flatten()
        val pathCompact = path.joinToString("")
        /** Each run of trailing segments run together: "gs4", "mainsgs4", "upscmainsgs4". */
        val suffixes: Set<String> = segments.indices.map { i -> segments.drop(i).flatten().joinToString("") }.toSet()
    }

    private fun score(query: List<String>, e: Entry): Hit? {
        if (query.joinToString("") in e.suffixes) return Hit(e, EXACT, 0)
        val inLeaf = query.count { found(it, e.leaf, e.leafCompact) }
        val fuzzy = query.count { t -> e.path.none { it == t } && !(t.any { c -> c.isDigit() } && e.pathCompact.contains(t)) }
        if (inLeaf == query.size) return Hit(e, IN_NAME, (e.leaf.size - inLeaf).coerceAtLeast(0), fuzzy)
        // Some words name the deck, the rest its parents ("prelims polity").
        if (inLeaf > 0 && query.all { found(it, e.path, e.pathCompact) }) {
            return Hit(e, IN_PATH, e.leaf.size - inLeaf.coerceAtMost(e.leaf.size), fuzzy)
        }
        return null
    }

    /** [t] is one of [words], or near enough for speech, or part of [compact] (a split "gs 4"). */
    private fun found(t: String, words: List<String>, compact: String): Boolean = words.any { w -> same(t, w) } ||
        (t.length >= 2 && t.any { it.isDigit() } && t.any { it.isLetter() } && compact.contains(t))

    /** Two words that are the same once misheard or inflected; numbers only ever exactly. */
    private fun same(t: String, w: String): Boolean {
        if (t == w) return true
        if (!t.all { it.isLetter() } || !w.all { it.isLetter() }) return false
        if (t.length >= 3 && w.length >= 3 && (w.startsWith(t) || t.startsWith(w))) return true
        if (t.length < 5 || w.length < 5) return false
        // "economics" / "economy", "historical" / "history".
        val shared = t.zip(w).takeWhile { (a, b) -> a == b }.size
        if (shared >= 5 && shared >= minOf(t.length, w.length) - 2) return true
        return close(t, w)
    }

    /** Within one edit for words of 5 or more letters, two for 9 or more. */
    private fun close(a: String, b: String): Boolean {
        val allowed = if (minOf(a.length, b.length) >= 9) 2 else 1
        if (kotlin.math.abs(a.length - b.length) > allowed) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > allowed) return false
            prev = cur
        }
        return prev[b.length] <= allowed
    }

    /** Words said around a deck name that aren't part of it. */
    private val FILLER = setOf(
        "download", "downloads", "downloading", "get", "fetch", "save", "offline", "please", "mode",
        "deck", "decks", "subdeck", "subdecks", "can", "could", "you", "i", "want", "would", "like",
        "me", "my", "start", "open", "review", "study", "ankiwatch", "anki", "watch", "now", "for",
        "to", "in", "on", "hey", "ok", "okay", "from", "under", "inside", "within", "both", "all",
        "each", "every", "them", "those", "these", "too", "as", "well"
    )

    /** Words asking for every deck a name fits ("both polity"). */
    private val PLURAL = setOf("both", "all", "each", "every")

    /** Where one phrase asks for more than one deck. */
    private val JOINS = Regex("(?i)\\s*(?:,|;|&|\\+|\\b(?:and|plus|also|as well as)\\b)\\s*")

    /** Dropped from names and phrases alike. */
    private val STOP = setOf("the", "a", "an", "and", "of")

    private val NUMBER_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12", "first" to "1", "second" to "2", "third" to "3",
        "fourth" to "4", "fifth" to "5", "sixth" to "6"
    )

    /** Said like a number after a word that takes one: "GS for", "paper won". */
    private val SOUNDS_LIKE = mapOf("for" to "4", "fore" to "4", "to" to "2", "too" to "2", "won" to "1", "free" to "3", "tree" to "3")

    /** Words a number or roman numeral follows ("paper IV"). */
    private val NUMBERED = setOf("paper", "part", "unit", "gs", "chapter", "module", "section", "volume", "book", "level", "class", "lesson")

    private val ROMAN = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPLIT = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

    /** Whether [phrase] asks for every deck that fits ("both", "all"). */
    private fun plural(phrase: String) = plain(phrase).split(NOT_WORD).any { it in PLURAL }

    /** [phrase] cut where it asks for another deck. */
    internal fun splitParts(phrase: String): List<String> = phrase.take(2_000).split(JOINS).filter { it.isNotBlank() }

    /** A spoken phrase as tokens, without the words around the deck name. */
    internal fun spoken(phrase: String): List<String> = tokens(phrase, spoken = true).filter { it !in FILLER }

    private fun plain(text: String) = Normalizer.normalize(text.take(2_000), Normalizer.Form.NFKD).replace(MARKS, "").lowercase()

    /** "G.S. Paper-IV & Ethics" → [gs, paper, 4, ethics]. */
    internal fun tokens(text: String, spoken: Boolean = false): List<String> {
        val words = plain(text).replace("&", " and ").replace("general studies", "gs")
            .split(NOT_WORD).asSequence().filter { it.isNotEmpty() }
            .flatMap { it.split(SPLIT).asSequence() }.take(MAX_WORDS * 2).toList()
        val out = ArrayList<String>(words.size)
        var letters = StringBuilder()
        fun flush() {
            if (letters.isNotEmpty()) out += letters.toString()
            letters = StringBuilder()
        }
        for (raw in words) {
            val previous = if (letters.isNotEmpty()) letters.toString() else out.lastOrNull()
            val afterNumbered = previous != null && previous in NUMBERED
            val w = when {
                raw in NUMBER_WORDS -> NUMBER_WORDS.getValue(raw)
                raw.all { it.isDigit() } -> raw.trimStart('0').ifEmpty { "0" }
                afterNumbered && raw in ROMAN -> ROMAN.getValue(raw)
                spoken && afterNumbered && raw in SOUNDS_LIKE -> SOUNDS_LIKE.getValue(raw)
                raw.length > 1 && raw in ROMAN && raw != "vi" -> ROMAN.getValue(raw)
                else -> raw
            }
            if (w in STOP) continue
            // Spelled-out letters join up: "u p s c" → "upsc", "g s 4" → "gs", "4".
            if (w.length == 1 && w[0].isLetter()) {
                letters.append(w)
                continue
            }
            flush()
            out += w
        }
        flush()
        return out.take(MAX_WORDS)
    }
}
