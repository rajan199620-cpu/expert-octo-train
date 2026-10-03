package com.rajan.meditationtimer

enum class BreathPhase(val label: String) { INHALE("Breathe in"), HOLD_IN("Hold"), EXHALE("Breathe out"), HOLD_OUT("Hold") }

/** How a rhythm is breathed: plainly, humming on the out-breath, or nostril by nostril. */
enum class BreathStyle { PLAIN, HUM, ALTERNATE }

/** A breathing rhythm; phases with 0 seconds are skipped. */
data class BreathPattern(
    val name: String,
    val description: String,
    val inhaleSec: Double,
    val holdInSec: Double,
    val exhaleSec: Double,
    val holdOutSec: Double,
    val style: BreathStyle = BreathStyle.PLAIN,
    /** What the research does and doesn't show, said plainly; null for the long-established ones. */
    val evidence: String? = null,
    /** How to do it, for rhythms that need more than "breathe in, breathe out". */
    val howTo: String? = null,
) {
    /**
     * Breaths that make one full round: alternate-nostril breathing goes left in, right out, then
     * right in, left out, so a round is two breaths and a session ends on a whole round.
     */
    val breathsPerRound: Int get() = if (style == BreathStyle.ALTERNATE) 2 else 1

    /** Total breaths for about [minutes]: rounded up to finish on a whole round. */
    fun breathsFor(minutes: Int): Long {
        val rounds = (minutes * 60_000L + cycleMs * breathsPerRound - 1) / (cycleMs * breathsPerRound)
        return rounds.coerceAtLeast(1) * breathsPerRound
    }

    /** What the screen says during [state]: the phase, plus the hum or the nostril where it matters. */
    fun cue(state: BreathState): String = when (style) {
        BreathStyle.PLAIN -> state.phase.label
        BreathStyle.HUM -> if (state.phase == BreathPhase.EXHALE) "Hum softly" else state.phase.label
        BreathStyle.ALTERNATE -> {
            // Even breaths: in left, out right. Odd breaths: in right, out left.
            val even = state.completedCycles % 2 == 0L
            when (state.phase) {
                BreathPhase.INHALE -> if (even) "In · left nostril" else "In · right nostril"
                BreathPhase.EXHALE -> if (even) "Out · right nostril" else "Out · left nostril"
                else -> state.phase.label
            }
        }
    }

    val cycleMs: Long get() = ((inhaleSec + holdInSec + exhaleSec + holdOutSec) * 1000).toLong()

    private val phases: List<Pair<BreathPhase, Long>>
        get() = listOf(
            BreathPhase.INHALE to inhaleSec,
            BreathPhase.HOLD_IN to holdInSec,
            BreathPhase.EXHALE to exhaleSec,
            BreathPhase.HOLD_OUT to holdOutSec,
        ).filter { it.second > 0 }.map { it.first to (it.second * 1000).toLong() }

    fun at(elapsedMs: Long): BreathState {
        var t = elapsedMs.coerceAtLeast(0) % cycleMs
        for ((phase, length) in phases) {
            if (t < length) return BreathState(phase, t.toFloat() / length, length - t, elapsedMs / cycleMs)
            t -= length
        }
        error("unreachable: t is always < cycleMs")
    }

    companion object {
        val ALL = listOf(
            BreathPattern("Coherent", "5.5 s in, 5.5 s out: about 5.5 breaths a minute, calming", 5.5, 0.0, 5.5, 0.0),
            BreathPattern("Box", "4 in, 4 hold, 4 out, 4 hold: steadying", 4.0, 4.0, 4.0, 4.0),
            BreathPattern("4-7-8", "4 in, 7 hold, 8 out: winding down for sleep", 4.0, 7.0, 8.0, 0.0),
            BreathPattern(
                "Bhramari", "Humming bee: 4 s in, then a soft hum for 8 s out", 4.0, 0.0, 8.0, 0.0,
                style = BreathStyle.HUM,
                howTo = "Lips closed, teeth slightly apart. Breathe in through the nose; on the out-breath " +
                    "hum a low, even \u201cmmm\u201d you can feel in your face. You may close your ears " +
                    "gently with your thumbs. Keep it soft, never forced.",
                evidence = "Small trials: heart rate and blood pressure fell after practice, and anxiety eased " +
                    "(e.g. a 2023 randomised trial in people with high blood pressure). Evidence is low " +
                    "to moderate: small groups, short follow-up.",
            ),
            BreathPattern(
                "Nadi Shodhana", "Alternate nostril: 4 s in, 6 s out, switching sides", 4.0, 0.0, 6.0, 0.0,
                style = BreathStyle.ALTERNATE,
                howTo = "Right thumb closes the right nostril, ring finger the left. In through the left, " +
                    "out through the right; in through the right, out through the left: that's one round. " +
                    "No breath-holding here. Skip it if your nose is blocked.",
                evidence = "Many small trials: with regular practice, resting blood pressure and heart rate " +
                    "came down, most in people with raised blood pressure. Effects on heart-rate " +
                    "variability are mixed.",
            ),
        )
    }
}

data class BreathState(
    val phase: BreathPhase,
    /** 0..1 through the current phase. */
    val progress: Float,
    val msLeftInPhase: Long,
    val completedCycles: Long,
) {
    /** Circle size 0..1: grows on the in-breath, full while holding in, shrinks on the out-breath. */
    val expansion: Float
        get() = when (phase) {
            BreathPhase.INHALE -> easeInOut(progress)
            BreathPhase.HOLD_IN -> 1f
            BreathPhase.EXHALE -> 1f - easeInOut(progress)
            BreathPhase.HOLD_OUT -> 0f
        }

    private fun easeInOut(x: Float) = (0.5 - 0.5 * kotlin.math.cos(Math.PI * x)).toFloat()
}

/** Japa mala counting: [target] beads make a round; a bell marks each finished round. */
data class MalaCount(val beads: Int = 0, val rounds: Int = 0, val target: Int = 108) {
    /** Returns the new count and whether this bead completed a round. */
    fun tap(): Pair<MalaCount, Boolean> =
        if (beads + 1 >= target) copy(beads = 0, rounds = rounds + 1) to true else copy(beads = beads + 1) to false
}

/**
 * Breath-counting attention check, adapted from the task validated as a behavioural measure of
 * mindfulness (Levinson et al., 2014): count breaths 1–9, pressing one key on breaths 1–8 and the
 * other key on breath 9, then start again. A cycle is correct when exactly eight "count" presses
 * come before the "nine" press. Accuracy = correct cycles / all cycles. Shorter than the research
 * version, so it is for comparing your own results over time, not with the study's numbers.
 */
object BreathCount {
    /** [presses]: false = breaths 1–8 key, true = breath-9 key. A trailing unfinished cycle is ignored. */
    fun score(presses: List<Boolean>): BreathCountResult {
        var run = 0
        var correct = 0
        var total = 0
        for (nine in presses) {
            if (nine) {
                total++
                if (run == 8) correct++
                run = 0
            } else {
                run++
            }
        }
        return BreathCountResult(correct, total)
    }
}

data class BreathCountResult(val correct: Int, val total: Int) {
    /** 0..100, or null if no cycle was completed. */
    val accuracyPercent: Int? get() = if (total == 0) null else Math.round(100.0 * correct / total).toInt()
}

/** One completed check, as stored: when it happened and its score. */
data class BreathCheck(val atMs: Long, val result: BreathCountResult) {
    fun encode() = "$atMs,${result.correct},${result.total}"

    companion object {
        fun decode(line: String): BreathCheck? {
            val p = line.split(',')
            if (p.size != 3) return null
            return BreathCheck(
                p[0].toLongOrNull() ?: return null,
                BreathCountResult(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null),
            )
        }
    }
}
