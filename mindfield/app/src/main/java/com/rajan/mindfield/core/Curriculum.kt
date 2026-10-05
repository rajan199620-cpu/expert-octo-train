package com.rajan.mindfield.core

import java.time.LocalDate

/** Decides which concept each day shows. */
object Curriculum {
    const val FIRST = "frequency-illusion"

    /**
     * The concept for [day]. A day that already has one keeps it. A new day takes the next concept
     * you haven't seen, in library order. With focus areas chosen, two days in three come from them
     * and the third keeps some breadth. Once everything has been seen it revisits the concepts you
     * have spotted least, longest ago first.
     */
    fun pick(day: LocalDate, library: Library, state: AppState): String {
        state.assignments[day]?.conceptId?.takeIf { library.contains(it) }?.let { return it }
        val seen = state.assignments.values.mapTo(HashSet()) { it.conceptId }
        val unseen = library.all.filter { it.id !in seen }
        if (seen.isEmpty() && library.contains(FIRST)) return FIRST
        if (unseen.isNotEmpty()) {
            val focus = state.settings.focus
            if (focus.isEmpty() || focus.size == Category.entries.size) return unseen.first().id
            val inFocus = unseen.filter { it.category in focus }
            val outside = unseen.filter { it.category !in focus }
            val breadthDay = state.assignments.size % 3 == 2
            val pool = when {
                inFocus.isEmpty() -> outside
                outside.isEmpty() -> inFocus
                breadthDay -> outside
                else -> inFocus
            }
            return pool.first().id
        }
        return revisit(library, state)
    }

    private fun revisit(library: Library, state: AppState): String {
        val sightings = HashMap<String, Int>()
        for (e in state.liveEntries) if (e.mode.isSighting) sightings.merge(e.conceptId, 1, Int::plus)
        val lastShown = HashMap<String, LocalDate>()
        for ((d, a) in state.assignments) {
            val last = lastShown[a.conceptId]
            if (last == null || d > last) lastShown[a.conceptId] = d
        }
        return library.all.minWithOrNull(
            compareBy<Concept>({ sightings[it.id] ?: 0 }, { lastShown[it.id] ?: LocalDate.MIN }, { it.number }),
        )!!.id
    }

    /** Records the pick for [day] if it is new. Returns the same state when nothing changed. */
    fun assign(day: LocalDate, library: Library, state: AppState, now: Long): AppState {
        val existing = state.assignments[day]
        if (existing != null && library.contains(existing.conceptId)) return state
        val id = pick(day, library, state)
        return state.copy(assignments = state.assignments + (day to Assignment(id, now)))
    }
}

/** One concept's place in the spaced-review schedule (a Leitner box). */
data class Card(
    val conceptId: String,
    val box: Int,
    val due: LocalDate,
    val lastReviewed: LocalDate?,
    val right: Int,
    val wrong: Int,
)

/**
 * Spaced retrieval: each concept comes back as a quick question 1 day after you meet it, then
 * after 3, 7, 16, 35 and 90 days while you keep getting it right. A miss sends it back to 1 day.
 */
object Spacing {
    val INTERVALS = intArrayOf(1, 3, 7, 16, 35, 90)
    val MAX_BOX = INTERVALS.lastIndex
    const val DAILY_LIMIT = 10

    fun newCard(conceptId: String, unlocked: LocalDate) =
        Card(conceptId, 0, unlocked.plusDays(INTERVALS[0].toLong()), null, 0, 0)

    fun grade(card: Card, correct: Boolean, today: LocalDate): Card {
        val box = if (correct) minOf(card.box + 1, MAX_BOX) else 0
        return card.copy(
            box = box,
            due = today.plusDays(INTERVALS[box].toLong()),
            lastReviewed = today,
            right = card.right + if (correct) 1 else 0,
            wrong = card.wrong + if (correct) 0 else 1,
        )
    }

    /** Every unlocked concept's card; ones never reviewed get a fresh card dated from their unlock. */
    fun cards(state: AppState, library: Library): List<Card> =
        state.unlocked.filterKeys { library.contains(it) }.map { (id, day) -> state.cards[id] ?: newCard(id, day) }

    /** What to review today: most overdue first, then the shakiest, at most [DAILY_LIMIT]. */
    fun due(state: AppState, library: Library, today: LocalDate): List<Card> =
        cards(state, library).filter { it.due <= today }
            .sortedWith(compareBy<Card>({ it.due }, { it.box }, { library[it.conceptId]?.number ?: 0 }))
            .take(DAILY_LIMIT)

    fun nextDue(state: AppState, library: Library, today: LocalDate): LocalDate? =
        cards(state, library).map { it.due }.filter { it > today }.minOrNull()
}

/** A review question. */
data class Question(
    val conceptId: String,
    val kind: Kind,
    val prompt: String,
    val options: List<String>,
    val answer: Int,
) {
    enum class Kind { NAME_IT, RECALL }
}

object Quiz {
    /**
     * "Name it" (an everyday story: which concept is this?) once you know four or more concepts,
     * alternating with recalling the study's result, which uses the "Predict first" question.
     * Concepts marked related are never offered as wrong answers, because they would fit too.
     */
    fun question(card: Card, library: Library, unlocked: Collection<String>, seed: Long): Question {
        val concept = library[card.conceptId] ?: error("unknown concept ${card.conceptId}")
        val rnd = kotlin.random.Random(seed xor card.conceptId.hashCode().toLong())
        val related = concept.related.toSet() + library.all.filter { concept.id in it.related }.map { it.id }
        val candidates = unlocked.filter { it != concept.id && it !in related && library.contains(it) }.sorted()
        val nameIt = candidates.size >= 3 && (card.box % 2 == 0)
        return if (nameIt) {
            val wrong = candidates.shuffled(rnd).take(3).map { library[it]!!.title }
            val options = (wrong + concept.title).shuffled(rnd)
            Question(concept.id, Question.Kind.NAME_IT, concept.scenario, options, options.indexOf(concept.title))
        } else {
            val order = concept.predict.options.indices.shuffled(rnd)
            Question(
                concept.id,
                Question.Kind.RECALL,
                concept.predict.question,
                order.map { concept.predict.options[it] },
                order.indexOf(concept.predict.answer),
            )
        }
    }
}
