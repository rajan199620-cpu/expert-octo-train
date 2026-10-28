package com.rajan.mindfield.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Decides which concept each day shows. */
object Curriculum {
    const val FIRST = "frequency-illusion"

    /** Before the app existed: a clock reading earlier than this is wrong, so no day is fixed for it. */
    val EARLIEST: LocalDate = LocalDate.of(2025, 1, 1)

    /** Once everything has been seen, a concept shown this recently isn't picked again (while others are left). */
    const val REVISIT_GAP_DAYS = 14

    /**
     * The concept for [day]. A day that already has one keeps it. A new day takes the next concept
     * you haven't seen, in library order. With focus areas chosen, two days in three come from them
     * and the third keeps some breadth. Once everything has been seen it revisits the concepts you
     * have spotted least, longest ago first, skipping any shown in the last [REVISIT_GAP_DAYS] days
     * (so one concept you never spot can't come back every day).
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
        return revisit(day, library, state)
    }

    private fun revisit(day: LocalDate, library: Library, state: AppState): String {
        val sightings = HashMap<String, Int>()
        for (e in state.liveEntries) if (e.mode.isSighting) sightings.merge(e.conceptId, 1, Int::plus)
        val lastShown = HashMap<String, LocalDate>()
        for ((d, a) in state.assignments) {
            val last = lastShown[a.conceptId]
            if (last == null || d > last) lastShown[a.conceptId] = d
        }
        val gap = minOf(REVISIT_GAP_DAYS, library.size - 1).toLong()
        val rested = library.all.filter { c -> lastShown[c.id]?.let { it >= day.minusDays(gap) } != true }
        return rested.ifEmpty { library.all }.minWithOrNull(
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

/**
 * One card's place in the review schedule: a concept you've met, or one of your lessons
 * ([Lessons.cardId]).
 *
 * [stability] and [difficulty] are its FSRS memory state (see [Fsrs]). They're null on a card that
 * hasn't been reviewed yet, and on one last reviewed under the old fixed schedule (1, 3, 7, 16, 35
 * and 90 days), which is taken up at its next review. [box] is now just a summary of the gap to the
 * next review, as a place on that old ladder, for the "how well they're sticking" chart.
 */
data class Card(
    val conceptId: String,
    val box: Int,
    val due: LocalDate,
    val lastReviewed: LocalDate?,
    val right: Int,
    val wrong: Int,
    val stability: Double? = null,
    val difficulty: Double? = null,
)

/** How a review went. Questions with one right answer use [AGAIN] and [GOOD]; your lessons, all three. */
enum class Rating(val grade: Int, val label: String) {
    AGAIN(1, "Forgot"),
    HARD(2, "Hard to recall"),
    GOOD(3, "Remembered"),
}

/**
 * FSRS-5, the scheduler Anki uses (Ye, Su & Cao, KDD 2022; the open-spaced-repetition project).
 * It models each card's memory with a stability (the days until recall falls to 90%) and a
 * difficulty, and schedules the next review for when recall is predicted to fall to [RETENTION].
 * Its default parameters were fitted to millions of Anki reviews; on the open srs-benchmark it
 * predicts recall better than fixed-interval schedules such as SM-2. The formulas and the default
 * [W] are from the project's "The Algorithm" page (FSRS-5).
 */
object Fsrs {
    val W = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
        1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    )
    const val DECAY = -0.5
    const val FACTOR = 19.0 / 81.0
    const val RETENTION = 0.9
    const val MIN_STABILITY = 0.1
    const val MAX_INTERVAL = 3650

    /** The chance of recalling a card [days] after its last review. */
    fun retrievability(days: Double, stability: Double): Double = (1 + FACTOR * days / stability).pow(DECAY)

    /** Days until recall is predicted to fall to [retention]: equal to the stability at 90%. */
    fun interval(stability: Double, retention: Double = RETENTION): Int =
        (stability / FACTOR * (retention.pow(1 / DECAY) - 1)).roundToInt().coerceIn(1, MAX_INTERVAL)

    fun initialStability(r: Rating): Double = maxOf(W[r.grade - 1], MIN_STABILITY)

    fun initialDifficulty(grade: Int): Double = W[4] - exp(W[5] * (grade - 1)) + 1

    fun nextDifficulty(d: Double, r: Rating): Double {
        val delta = -W[6] * (r.grade - 3)
        val damped = d + delta * (10 - d) / 9
        return (W[7] * initialDifficulty(4) + (1 - W[7]) * damped).coerceIn(1.0, 10.0)
    }

    /** Stability after a review [days] after the last one, given the stability and difficulty before it. */
    fun nextStability(d: Double, s: Double, days: Long, r: Rating): Double {
        val next = when {
            // A second review the same day: FSRS-5's short-term formula.
            days <= 0 -> s * exp(W[17] * (r.grade - 3 + W[18]))
            r == Rating.AGAIN -> {
                val rec = retrievability(days.toDouble(), s)
                minOf(W[11] * d.pow(-W[12]) * ((s + 1).pow(W[13]) - 1) * exp(W[14] * (1 - rec)), s)
            }
            else -> {
                val rec = retrievability(days.toDouble(), s)
                val hard = if (r == Rating.HARD) W[15] else 1.0
                s * (exp(W[8]) * (11 - d) * s.pow(-W[9]) * (exp(W[10] * (1 - rec)) - 1) * hard + 1)
            }
        }
        return next.coerceIn(MIN_STABILITY, MAX_INTERVAL * 10.0)
    }
}

/**
 * Spaced retrieval: each card comes back the day after you meet it, then whenever FSRS predicts
 * your recall has fallen to 90%. Remembering stretches the gap (3 days, then about 11, 35, 100...);
 * forgetting brings it back the next day.
 */
object Spacing {
    /** The old fixed ladder, kept to read cards scheduled by it and to summarise gaps for the chart. */
    val INTERVALS = intArrayOf(1, 3, 7, 16, 35, 90)
    val MAX_BOX = INTERVALS.lastIndex
    const val DAILY_LIMIT = 10

    fun newCard(conceptId: String, unlocked: LocalDate) =
        Card(conceptId, 0, unlocked.plusDays(INTERVALS[0].toLong()), null, 0, 0)

    fun grade(card: Card, correct: Boolean, today: LocalDate): Card = grade(card, if (correct) Rating.GOOD else Rating.AGAIN, today)

    fun grade(card: Card, rating: Rating, today: LocalDate): Card {
        val last = card.lastReviewed
        val (s, d) = when {
            last == null -> Fsrs.initialStability(rating) to Fsrs.initialDifficulty(rating.grade).coerceIn(1.0, 10.0)
            else -> {
                // A card from the old schedule starts from the gap it had reached, and a difficulty
                // nudged by its record; FSRS corrects both within a review or two.
                val s0 = card.stability ?: INTERVALS[card.box.coerceIn(0, MAX_BOX)].toDouble()
                val d0 = card.difficulty ?: (Fsrs.initialDifficulty(3) + 0.8 * minOf(card.wrong, 5) - 0.2 * minOf(card.right, 5)).coerceIn(1.0, 10.0)
                val days = ChronoUnit.DAYS.between(last, today).coerceAtLeast(0)
                Fsrs.nextStability(d0, s0, days, rating) to Fsrs.nextDifficulty(d0, rating)
            }
        }
        // A miss comes back tomorrow whatever the model says, as Anki's relearning step does within
        // minutes: FSRS's post-lapse stability can be weeks for a once-strong card, but a lapse
        // should be repaired before it's spaced out again. The memory state is FSRS's either way.
        val gap = if (rating == Rating.AGAIN) 1 else Fsrs.interval(s)
        return card.copy(
            box = INTERVALS.indexOfLast { it <= gap }.coerceAtLeast(0),
            due = today.plusDays(gap.toLong()),
            lastReviewed = today,
            right = card.right + if (rating == Rating.AGAIN) 0 else 1,
            wrong = card.wrong + if (rating == Rating.AGAIN) 1 else 0,
            stability = s,
            difficulty = d,
        )
    }

    /**
     * Every card: each concept you've met, and each of your lessons. Ones never reviewed get a
     * fresh card dated from when you met the concept or wrote the lesson.
     */
    fun cards(state: AppState, library: Library): List<Card> =
        state.unlocked.filterKeys { library.contains(it) }.map { (id, day) -> state.cards[id] ?: newCard(id, day) } +
            state.liveLessons.map { l -> Lessons.cardId(l.id).let { id -> state.cards[id] ?: newCard(id, l.day) } }

    /** What to review today: most overdue first, then the shakiest, at most [DAILY_LIMIT]. */
    fun due(state: AppState, library: Library, today: LocalDate): List<Card> =
        cards(state, library).filter { it.due <= today }
            .sortedWith(compareBy<Card>({ it.due }, { it.stability ?: 0.0 }, { it.box }, { library[it.conceptId]?.number ?: 0 }, { it.conceptId }))
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
     * alternating review by review with recalling the study's result, which uses the "Predict
     * first" question, so a concept you've mastered still gets both. Concepts marked related are
     * never offered as wrong answers, because they would fit too.
     */
    fun question(card: Card, library: Library, unlocked: Collection<String>, seed: Long): Question {
        val concept = library[card.conceptId] ?: error("unknown concept ${card.conceptId}")
        val rnd = kotlin.random.Random(seed xor card.conceptId.hashCode().toLong())
        val related = concept.related.toSet() + library.all.filter { concept.id in it.related }.map { it.id }
        val candidates = unlocked.filter { it != concept.id && it !in related && library.contains(it) }.sorted()
        val nameIt = candidates.size >= 3 && (card.right + card.wrong) % 2 == 0
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
