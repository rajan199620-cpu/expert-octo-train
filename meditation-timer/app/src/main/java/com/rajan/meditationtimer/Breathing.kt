package com.rajan.meditationtimer

enum class BreathPhase(val label: String) { INHALE("Breathe in"), HOLD_IN("Hold"), EXHALE("Breathe out"), HOLD_OUT("Hold") }

/** A breathing rhythm; phases with 0 seconds are skipped. */
data class BreathPattern(
    val name: String,
    val description: String,
    val inhaleSec: Double,
    val holdInSec: Double,
    val exhaleSec: Double,
    val holdOutSec: Double,
) {
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
