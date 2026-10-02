package com.ankiwatch.core

/**
 * Keeps a cards response under the Wearable Data Layer's 100 KiB DataItem limit. Over the
 * limit, `putDataItem` fails and the watch waits on a spinner forever, so oversized
 * responses are cut down here instead: first by sending fewer cards, then by trimming the
 * fields of the one card that is left.
 */
object PayloadBudget {

    /** Below the 100 KiB hard limit, leaving room for keys and DataMap framing. */
    const val MAX_BYTES = 90_000

    /** Rough per-card cost of keys, numbers and framing. */
    const val CARD_OVERHEAD_BYTES = 400

    const val TRUNCATION_NOTE = " … [shortened: too long for the watch]"

    data class CardText(
        val question: String,
        val answer: String,
        val content: String,
        val extras: List<Pair<String, String>>
    ) {
        fun byteSize(): Int = CARD_OVERHEAD_BYTES + utf8Length(question) + utf8Length(answer) +
            utf8Length(content) + extras.sumOf { utf8Length(it.first) + utf8Length(it.second) }
    }

    /** How many of [cards], in order, fit together. Always at least one if there are any. */
    fun cardsThatFit(cards: List<CardText>, budget: Int = MAX_BYTES): Int {
        var total = 0
        for ((k, card) in cards.withIndex()) {
            total += card.byteSize()
            if (total > budget) return maxOf(1, k)
        }
        return cards.size
    }

    /**
     * Trims one card until it fits [budget]: extras first (answer-side extras matter least),
     * then the template-rendered question/answer, then the cloze content.
     */
    fun shrink(card: CardText, budget: Int = MAX_BYTES): CardText {
        if (card.byteSize() <= budget) return card
        var current = card

        // 1. Extras, last ones first.
        val extras = current.extras.toMutableList()
        while (extras.isNotEmpty() && current.copy(extras = extras).byteSize() > budget) {
            val last = extras.removeAt(extras.size - 1)
            val withoutLast = current.copy(extras = extras).byteSize()
            val room = budget - withoutLast - utf8Length(last.first)
            if (room > 200) {
                extras.add(last.first to SafeTruncate.truncate(last.second, room))
                break
            }
        }
        current = current.copy(extras = extras)
        if (current.byteSize() <= budget) return current

        // 2. Question and answer share what is left after the content.
        val fixed = current.byteSize() - utf8Length(current.question) - utf8Length(current.answer)
        if (current.question.isNotEmpty() || current.answer.isNotEmpty()) {
            val room = (budget - fixed).coerceAtLeast(0)
            val half = room / 2
            current = current.copy(
                question = SafeTruncate.truncate(current.question, half),
                answer = SafeTruncate.truncate(current.answer, room - half)
            )
            if (current.byteSize() <= budget) return current
        }

        // 3. The content itself.
        val withoutContent = current.byteSize() - utf8Length(current.content)
        current = current.copy(content = SafeTruncate.truncate(current.content, (budget - withoutContent).coerceAtLeast(0)))
        return current
    }

    fun utf8Length(s: String): Int {
        var bytes = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1]) -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }
}

/**
 * Cuts HTML down to a byte budget at a point that is safe to render: never inside a tag, an
 * entity, a cloze marker or a surrogate pair. An unclosed cloze left by the cut is closed by
 * [ClozeParser] at the end of the field, so its answer stays hidden.
 */
object SafeTruncate {

    fun truncate(html: String, maxBytes: Int): String {
        if (PayloadBudget.utf8Length(html) <= maxBytes) return html
        val note = PayloadBudget.TRUNCATION_NOTE
        val limit = maxBytes - PayloadBudget.utf8Length(note)
        if (limit <= 0) return ""

        var bestCut = 0
        var bytes = 0
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val scan = TagScanner.scan(html, i)
                if (scan != null) {
                    if (bytes <= limit) bestCut = i
                    bytes += PayloadBudget.utf8Length(html.substring(i, scan.end))
                    i = scan.end
                    if (bytes <= limit) bestCut = i else break
                    continue
                }
            }
            if (c.isHtmlSpace() && bytes <= limit) bestCut = i
            val width = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(html[i + 1]) -> 4
                else -> 3
            }
            bytes += width
            if (bytes > limit) break
            i += if (width == 4) 2 else 1
        }
        return html.substring(0, bestCut) + note
    }
}
