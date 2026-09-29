package com.earmark.core.qa

import com.earmark.core.model.Book
import kotlin.math.ln

/**
 * BM25 keyword index over paragraph-sized passages. Runs on the phone with no embeddings
 * model and no network, which keeps "where did they mention X" free and instant, and gives
 * the LLM grounded passages to cite.
 */
class PassageIndex(private val book: Book, maxPassageChars: Int = 700) {
    data class Passage(val id: Int, val first: Int, val last: Int, val chapter: Int)
    data class Hit(val passage: Passage, val score: Double)

    val passages: List<Passage>
    private val termFreqs: List<Map<String, Int>>
    private val lengths: IntArray
    private val docFreq = HashMap<String, Int>()
    private val avgLength: Double

    init {
        val list = ArrayList<Passage>()
        var start = 0
        var chars = 0
        for (i in book.sentences.indices) {
            val s = book.sentences[i]
            val breakHere = i > start && (s.paragraph != book.sentences[i - 1].paragraph && chars > maxPassageChars / 3 ||
                chars + s.text.length > maxPassageChars || s.chapter != book.sentences[i - 1].chapter)
            if (breakHere) {
                list += Passage(list.size, start, i - 1, book.sentences[start].chapter)
                start = i
                chars = 0
            }
            chars += s.text.length + 1
        }
        if (book.sentences.isNotEmpty()) list += Passage(list.size, start, book.lastIndex, book.sentences[start].chapter)
        passages = list
        termFreqs = passages.map { p ->
            val tf = HashMap<String, Int>()
            for (i in p.first..p.last) for (tok in tokenize(book.sentences[i].text)) tf[tok] = (tf[tok] ?: 0) + 1
            tf
        }
        lengths = IntArray(passages.size) { termFreqs[it].values.sum() }
        termFreqs.forEach { tf -> tf.keys.forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 } }
        avgLength = if (lengths.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
    }

    /**
     * Top [k] passages for [query]. With [maxSentence] set (spoiler-safe mode) nothing after
     * the listener's position is returned, and a passage straddling it is cut off.
     */
    fun search(query: String, k: Int = 5, maxSentence: Int? = null): List<Hit> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || passages.isEmpty()) return emptyList()
        val n = passages.size.toDouble()
        val hits = ArrayList<Hit>()
        for ((idx, p) in passages.withIndex()) {
            if (maxSentence != null && p.first > maxSentence) break
            val passage = if (maxSentence != null && p.last > maxSentence) p.copy(last = maxSentence) else p
            val tf = if (passage === p) termFreqs[idx] else countTerms(passage)
            val len = if (passage === p) lengths[idx] else tf.values.sum()
            var score = 0.0
            for (t in terms) {
                val f = tf[t] ?: continue
                val df = docFreq[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len / avgLength))
            }
            if (score > 0) hits += Hit(passage, score)
        }
        return hits.sortedByDescending { it.score }.take(k)
    }

    fun text(p: Passage): String = book.textOf(p.first..p.last)

    private fun countTerms(p: Passage): Map<String, Int> {
        val tf = HashMap<String, Int>()
        for (i in p.first..p.last) for (tok in tokenize(book.sentences[i].text)) tf[tok] = (tf[tok] ?: 0) + 1
        return tf
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private val SPLIT = Regex("""[^\p{L}\p{N}]+""")
        private val STOP = setOf(
            "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with", "about", "as",
            "is", "are", "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those", "he", "she",
            "they", "them", "his", "her", "their", "i", "you", "we", "me", "my", "your", "our", "what", "who", "whom",
            "which", "when", "where", "why", "how", "do", "does", "did", "so", "not", "no", "from", "into", "than",
            "then", "there", "here", "has", "have", "had", "will", "would", "can", "could", "should", "may", "might",
            "tell", "said", "say", "mean", "means", "book", "chapter", "author",
        )

        fun tokenize(text: String): List<String> = text.lowercase().split(SPLIT)
            .filter { it.isNotEmpty() && it !in STOP && (it.length > 1 || it[0].isDigit()) }
            .map { stem(it) }

        /** Deliberately light suffix stripping: "punishments" and "punished" both find "punish". */
        internal fun stem(w: String): String {
            if (w.length <= 4 || !w.all { it in 'a'..'z' }) return w
            return when {
                w.endsWith("ies") && w.length > 5 -> w.dropLast(3) + "y"
                w.endsWith("ments") -> w.dropLast(5)
                w.endsWith("ment") -> w.dropLast(4)
                w.endsWith("ing") && w.length > 6 -> w.dropLast(3)
                w.endsWith("ed") && w.length > 5 -> w.dropLast(2)
                w.endsWith("es") && w.length > 5 && (w[w.length - 3] in "sxz" || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
                w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
                w.endsWith("ly") && w.length > 5 -> w.dropLast(2)
                else -> w
            }
        }
    }
}
