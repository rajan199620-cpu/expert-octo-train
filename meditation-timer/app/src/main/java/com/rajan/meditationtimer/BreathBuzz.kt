package com.rajan.meditationtimer

/**
 * The vibrations that pace a breath with your eyes closed: one tap to breathe in, two taps to
 * breathe out, one long buzz to hold.
 *
 * - **Coded by rhythm, not strength.** People recognised vibration patterns by their rhythm 93%
 *   of the time, far better than by intensity (Brown, Brewster & Purchase 2005–06), and phones
 *   without amplitude control can't vary strength at all. Apple Watch's Breathe app does the same
 *   with its "Minimal" haptics: one tap to breathe in, two to breathe out.
 * - **Long enough to be felt.** The cheap vibration motors in many phones take 50–100 ms to spin
 *   up and as long again to stop (Texas Instruments haptics notes), so every pulse here
 *   is at least 100 ms, and the gap between two taps is long enough for them to feel like two.
 * - **Not tied to touch feedback.** The pattern is played through the vibration motor directly, so it
 *   doesn't depend on the "touch feedback" setting, which many people switch off.
 */
object BreathBuzz {
    enum class Kind(val label: String) { IN("one tap"), OUT("two taps"), HOLD("one long buzz") }

    /** A vibration as a waveform: [timings] alternate off and on (ms), [amplitudes] 0–255 for each. */
    class Pattern(val timings: LongArray, val amplitudes: IntArray) {
        /** The on-times, in order. */
        val pulses: List<Long> get() = timings.indices.filter { amplitudes[it] > 0 }.map { timings[it] }
        /** The off-times between pulses (not the lead-in). */
        val gaps: List<Long> get() = timings.indices.filter { it > 0 && amplitudes[it] == 0 }.map { timings[it] }
        val totalMs: Long get() = timings.sum()
    }

    const val TAP_MS = 120L
    const val DOUBLE_TAP_MS = 100L
    const val DOUBLE_GAP_MS = 160L
    const val HOLD_MS = 420L
    /** Firm enough through a cushion or a pocket, short of a ringtone's full strength. */
    const val STRENGTH = 170
    const val HOLD_STRENGTH = 110

    fun pattern(kind: Kind): Pattern = when (kind) {
        Kind.IN -> Pattern(longArrayOf(0, TAP_MS), intArrayOf(0, STRENGTH))
        Kind.OUT -> Pattern(
            longArrayOf(0, DOUBLE_TAP_MS, DOUBLE_GAP_MS, DOUBLE_TAP_MS),
            intArrayOf(0, STRENGTH, 0, STRENGTH),
        )
        Kind.HOLD -> Pattern(longArrayOf(0, HOLD_MS), intArrayOf(0, HOLD_STRENGTH))
    }

    /** What a pattern means, read back from its rhythm alone, as someone with closed eyes would. */
    fun read(pattern: Pattern): Kind? {
        val pulses = pattern.pulses
        return when {
            pulses.size == 2 -> Kind.OUT
            pulses.size == 1 && pulses[0] >= 3 * TAP_MS -> Kind.HOLD
            pulses.size == 1 -> Kind.IN
            else -> null
        }
    }

    fun of(phase: Settle.Phase): Kind = if (phase == Settle.Phase.IN) Kind.IN else Kind.OUT

    fun of(phase: BreathPhase): Kind = when (phase) {
        BreathPhase.INHALE -> Kind.IN
        BreathPhase.EXHALE -> Kind.OUT
        BreathPhase.HOLD_IN, BreathPhase.HOLD_OUT -> Kind.HOLD
    }

    /** Breath cues played so far, and the last one: tests check the pacing with them. */
    val played = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var last: Kind? = null
}
