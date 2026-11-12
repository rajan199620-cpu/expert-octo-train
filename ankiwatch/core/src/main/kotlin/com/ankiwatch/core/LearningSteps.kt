package com.ankiwatch.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A deck's learning settings from its AnkiDroid options: what Anki does with a card in a
 * learning or relearning step. The phone reads them when it builds an offline download, so
 * the watch can follow the deck's own steps instead of guessing them.
 */
data class DeckSteps(
    /** New cards' learning steps, in order. */
    val learnMs: List<Long>,
    /** Days a card gets when Good takes it past the last learning step. */
    val graduatingDays: Int,
    /** Days a card gets when Easy takes it out of learning. */
    val easyDays: Int,
    /** Relearning steps for a review card answered Again. */
    val relearnMs: List<Long>,
    /** Relearned cards' minimum interval, in days. */
    val lapseMinDays: Int,
    /** What a relearned card's interval is multiplied by (0: it starts again at the minimum). */
    val lapseMult: Double
) {
    companion object {
        const val MAX_STEPS = 32

        /**
         * Settings from the raw numbers in AnkiDroid's options (steps in minutes), or null
         * when they don't make sense, so the watch falls back to what the labels show.
         */
        fun of(
            learnMinutes: List<Double>,
            graduatingDays: Int,
            easyDays: Int,
            relearnMinutes: List<Double>,
            lapseMinDays: Int,
            lapseMult: Double
        ): DeckSteps? {
            fun ms(minutes: List<Double>): List<Long>? {
                if (minutes.size > MAX_STEPS) return null
                return minutes.map { m ->
                    if (m.isNaN() || m <= 0 || m > MAX_STEP_MINUTES) return null
                    (m * IntervalLabel.MINUTE_MS).roundToLong()
                }
            }
            val learn = ms(learnMinutes) ?: return null
            val relearn = ms(relearnMinutes) ?: return null
            if (graduatingDays !in 1..MAX_DAYS || easyDays !in 1..MAX_DAYS || lapseMinDays !in 1..MAX_DAYS) return null
            if (lapseMult.isNaN() || lapseMult < 0 || lapseMult > 10) return null
            return DeckSteps(learn, graduatingDays, easyDays, relearn, lapseMinDays, lapseMult)
        }

        private const val MAX_STEP_MINUTES = 366.0 * 24 * 60
        private const val MAX_DAYS = 36_500
    }
}

/**
 * How one card moves through learning steps on the watch, worked out from AnkiDroid's labels
 * for its first answer and, when the download carries them, its deck's [DeckSteps]:
 *
 * - Again goes back to the first step.
 * - Hard on the first step waits halfway to the second (or 1.5× a single step); on a later
 *   step it repeats that step.
 * - Good moves to the next step; past the last one the card graduates.
 * - Easy graduates at once.
 *
 * These are Anki's rules. [graduateMs] and [easyMs] are only known when they don't depend on
 * the card's history (the SM-2 scheduler's fixed intervals); otherwise they are null and the
 * button shows no time.
 */
class LearningPlan(
    val stepsMs: List<Long>,
    val graduateMs: Long?,
    val easyMs: Long?
) {
    /** What [grade] does from step [step]: the delay and step it lands on, or null when the card graduates. */
    fun after(grade: Grade, step: Int): Pair<Long, Int>? {
        if (stepsMs.isEmpty()) return if (grade == Grade.AGAIN) DEFAULT_AGAIN_MS to 0 else null
        val i = step.coerceIn(0, stepsMs.lastIndex)
        return when (grade) {
            Grade.AGAIN -> stepsMs[0] to 0
            Grade.HARD -> hardDelay(i) to i
            Grade.GOOD -> if (i + 1 < stepsMs.size) stepsMs[i + 1] to i + 1 else null
            Grade.EASY -> null
        }
    }

    /** What [grade] from step [step] puts on its button: the delay or graduating interval, if known. */
    fun labelMs(grade: Grade, step: Int): Long? {
        after(grade, step)?.let { return it.first }
        return when (grade) {
            Grade.EASY -> easyMs
            else -> graduateMs
        }
    }

    private fun hardDelay(i: Int): Long {
        if (i > 0) return stepsMs[i]
        val first = stepsMs[0]
        return if (stepsMs.size > 1) (first + stepsMs[1]) / 2
        else minOf((first * 1.5).roundToLong(), first + IntervalLabel.DAY_MS)
    }

    /**
     * Where a card's first answer, [grade], whose label said [labelMs], leaves it: the delay
     * and the step whose delay matches the label (a card can be downloaded part way through
     * its steps). The step's exact delay stands in for the rounded label ("6m" for 5½).
     */
    fun first(grade: Grade, labelMs: Long): Pair<Long, Int> {
        if (stepsMs.isEmpty()) return labelMs to 0
        val step = when (grade) {
            Grade.AGAIN -> 0
            Grade.HARD -> if (close(labelMs, hardDelay(0))) return hardDelay(0) to 0
            else stepsMs.indexOfFirst { close(it, labelMs) }
            // Good reached the step it matches from the one before, so never the first.
            else -> (1 until stepsMs.size).firstOrNull { close(stepsMs[it], labelMs) } ?: -1
        }
        return if (step >= 0 && close(stepsMs[step], labelMs)) stepsMs[step] to step
        // A label the steps don't explain: trust it, and treat the card as on its last step.
        else labelMs to (if (grade == Grade.AGAIN) 0 else stepsMs.lastIndex)
    }

    companion object {
        /** Again on a card whose labels don't say: AnkiDroid's default first step. */
        const val DEFAULT_AGAIN_MS = IntervalLabel.MINUTE_MS

        /** Labels are rounded ("6m" for 5½ minutes), so a delay and a label match loosely. */
        fun close(a: Long, b: Long): Boolean = abs(a - b) <= maxOf(31_000L, maxOf(a, b) / 10)

        /**
         * The plan for [card], from its first-answer [labels] (milliseconds by grade) and its
         * deck's settings. [sm2] says whether the deck's fixed graduating intervals hold
         * (null: unknown, so they aren't shown).
         */
        fun of(labels: Map<Grade, Long?>, steps: DeckSteps?, sm2: Boolean?): LearningPlan {
            val again = labels[Grade.AGAIN]
            val good = labels[Grade.GOOD]
            if (steps != null) when (kind(labels, steps)) {
                Kind.RELEARN -> {
                    val graduate = if (sm2 == true && steps.lapseMult == 0.0) maxOf(1, steps.lapseMinDays) * IntervalLabel.DAY_MS else null
                    return LearningPlan(steps.relearnMs, graduate, null)
                }
                Kind.LEARN -> return LearningPlan(
                    steps.learnMs,
                    if (sm2 == true) steps.graduatingDays * IntervalLabel.DAY_MS else null,
                    if (sm2 == true) steps.easyDays * IntervalLabel.DAY_MS else null
                )
                null -> Unit
            }
            // Without settings that fit, the steps the labels reveal: Again's, then Good's.
            val first = again?.takeIf { it < IntervalLabel.DAY_MS } ?: DEFAULT_AGAIN_MS
            val second = good?.takeIf { it < IntervalLabel.DAY_MS && !close(it, first) }
            return LearningPlan(listOfNotNull(first, second), null, null)
        }

        private enum class Kind { LEARN, RELEARN }

        /**
         * Whether a card's first labels are a new (or learning) card's, on [steps]' learning
         * steps, or a review card's, whose Again starts relearning; null when they fit neither.
         */
        private fun kind(labels: Map<Grade, Long?>, steps: DeckSteps): Kind? {
            val again = labels[Grade.AGAIN] ?: return null
            val hard = labels[Grade.HARD]
            val good = labels[Grade.GOOD]
            val learn = steps.learnMs.isNotEmpty() && close(again, steps.learnMs[0])
            val relearn = steps.relearnMs.isNotEmpty() && close(again, steps.relearnMs[0])
            // A review card's Hard and Good are days away; a new card's Hard is a step.
            val reviewLike = (hard == null || hard >= IntervalLabel.DAY_MS) && (good == null || good >= IntervalLabel.DAY_MS)
            return when {
                relearn && (!learn || reviewLike) -> Kind.RELEARN
                learn -> Kind.LEARN
                else -> null
            }
        }

        /**
         * Whether a deck's cards follow its fixed graduating intervals (SM-2) rather than
         * FSRS, judged from new cards' first labels: Easy on a new card is the deck's easy
         * interval under SM-2. Null when no card says.
         */
        fun sm2(cards: List<Pair<Map<Grade, Long?>, DeckSteps>>): Boolean? {
            var yes = 0
            var no = 0
            for ((labels, steps) in cards) {
                if (kind(labels, steps) != Kind.LEARN) continue
                val easy = labels[Grade.EASY] ?: continue
                if (easy < IntervalLabel.DAY_MS) continue
                if (close(easy, steps.easyDays * IntervalLabel.DAY_MS)) yes++ else no++
            }
            return when {
                no > 0 -> false
                yes > 0 -> true
                else -> null
            }
        }

        /** [ms] the way AnkiDroid writes it on a button: "<1m", "10m", "1.5h", "4d", "1.2mo", "2y". */
        fun format(ms: Long): String {
            val minutes = ms / IntervalLabel.MINUTE_MS.toDouble()
            val hours = minutes / 60
            val days = hours / 24
            fun oneDecimal(x: Double): String {
                val r = Math.round(x * 10) / 10.0
                return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
            }
            return when {
                ms < IntervalLabel.MINUTE_MS -> "<1m"
                minutes < 59.5 -> "${Math.round(minutes)}m"
                hours < 23.95 -> "${oneDecimal(hours)}h"
                days < 29.5 -> "${Math.round(days)}d"
                days < 365 -> "${oneDecimal(days / 30)}mo"
                else -> "${oneDecimal(days / 365)}y"
            }
        }
    }

    /** The four grades, whichever button numbers a card uses for them. */
    enum class Grade {
        AGAIN, HARD, GOOD, EASY;

        fun ease(buttonCount: Int): Int? = when (buttonCount) {
            2 -> when (this) { AGAIN -> 1; GOOD -> 2; else -> null }
            3 -> when (this) { AGAIN -> 1; GOOD -> 2; EASY -> 3; else -> null }
            else -> ordinal + 1
        }

        companion object {
            fun of(ease: Int, buttonCount: Int): Grade =
                entries.firstOrNull { it.ease(buttonCount) == ease } ?: GOOD
        }
    }
}
