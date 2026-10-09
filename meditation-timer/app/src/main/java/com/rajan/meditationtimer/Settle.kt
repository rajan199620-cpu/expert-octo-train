package com.rajan.meditationtimer

/**
 * Settle-in breaths: slow breathing with a long out-breath for the first minute of a sit, after
 * which the opening bell rings and the breath is left alone.
 *
 * Meditation doesn't require a breathing technique: mindfulness and Vipassana use the natural
 * breath. But slow breathing calms the body fast. A meta-analysis of 223 studies found it raises
 * vagal heart-rate variability during a session and straight after a single one (Laborde et al.,
 * 2022); one 5-minute session of 4 seconds in and 6 out lowered state anxiety (Magnon et al.,
 * 2021); and at 6 breaths a minute a longer out-breath relaxed people more than a longer in-breath
 * (Van Diest et al., 2014). No trial has yet tested whether it improves the sit that follows, so
 * it is a short, optional start rather than a required step.
 */
object Settle {
    const val INHALE_MS = 4_000L
    const val EXHALE_MS = 6_000L
    const val BREATH_MS = INHALE_MS + EXHALE_MS
    const val DEFAULT_SEC = 60
    val CHOICES_SEC = listOf(0, 30, 60, 120, 300)

    /** The settle-in rhythm as a breathing pattern, for its breath sound (see [BreathVoice]). */
    val PATTERN = BreathPattern("Settle in", "In for 4, out for 6", INHALE_MS / 1000.0, 0.0, EXHALE_MS / 1000.0, 0.0)

    enum class Phase(val label: String) { IN("Breathe in"), OUT("Breathe out") }

    data class State(val phase: Phase, val breath: Int, val breaths: Int, val msLeftInPhase: Long)

    /** How long the settle-in runs in this sit: whole breaths, and never more than half the sit. */
    fun lengthMs(config: SessionConfig): Long {
        if (config.settleSec <= 0) return 0
        val ms = minOf(config.settleSec * 1000L, config.durationMs / 2)
        return ms / BREATH_MS * BREATH_MS
    }

    /** Where in the settle-in [elapsedMs] falls, or null outside it. */
    fun at(elapsedMs: Long, lengthMs: Long): State? {
        if (elapsedMs < 0 || elapsedMs >= lengthMs) return null
        val breath = (elapsedMs / BREATH_MS).toInt() + 1
        val t = elapsedMs % BREATH_MS
        val breaths = (lengthMs / BREATH_MS).toInt()
        return if (t < INHALE_MS) {
            State(Phase.IN, breath, breaths, INHALE_MS - t)
        } else {
            State(Phase.OUT, breath, breaths, BREATH_MS - t)
        }
    }

    /** The start of every in- and out-breath, for a cue you can follow with eyes closed (see [BreathBuzz]). */
    fun ticks(lengthMs: Long): List<Pair<Long, Phase>> = buildList {
        var t = 0L
        while (t < lengthMs) {
            add(t to Phase.IN)
            add(t + INHALE_MS to Phase.OUT)
            t += BREATH_MS
        }
    }

    /** A rejoin cue needs at least this much of the phase left, or it would crowd the next one. */
    const val REJOIN_MIN_MS = 2_000L

    /**
     * After a resume part-way through a breath, the phase to cue straight away so you know where
     * you are, or null when the phase has only just begun (its own cue is still to come, within
     * [graceMs]) or is nearly over (the next cue is coming soon).
     */
    fun rejoin(elapsedMs: Long, lengthMs: Long, graceMs: Long): Phase? {
        val state = at(elapsedMs, lengthMs) ?: return null
        val phaseMs = if (state.phase == Phase.IN) INHALE_MS else EXHALE_MS
        val into = phaseMs - state.msLeftInPhase
        return state.phase.takeIf { into > graceMs && state.msLeftInPhase >= REJOIN_MIN_MS }
    }

    fun label(sec: Int): String = when {
        sec <= 0 -> "Off"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "${sec}s"
    }
}
