package com.rajan.meditationtimer

/** What the user picked on the setup screen. */
data class SessionConfig(
    val durationSec: Int,
    /** Opening bell rings this many seconds after Begin. */
    val openingBellSec: Int,
    /** Closing bell rings this many seconds before the end. */
    val closingBellSec: Int,
    /** Also ring once more at the exact end. */
    val bellAtEnd: Boolean,
    /** Soft reminder bell every N minutes to come back to the breath; 0 = off. */
    val intervalMin: Int = 0,
    /** Slow settle-in breaths before the opening bell, in seconds; 0 = off. See [Settle]. */
    val settleSec: Int = 0,
) {
    val durationMs: Long get() = durationSec * 1000L
}

enum class Cue { OPENING, INTERVAL, CLOSING, END }

data class TimedCue(val atMs: Long, val cue: Cue)

object BellSchedule {
    /** Bells closer together than this would blur into one, so the later one is dropped. */
    const val MIN_GAP_MS = 3_000L

    fun cues(config: SessionConfig): List<TimedCue> {
        val total = config.durationMs
        val cues = mutableListOf<TimedCue>()

        // After settle-in breaths, the opening bell marks their end and the start of the sit proper.
        val opening = maxOf(config.openingBellSec * 1000L, Settle.lengthMs(config))
        if (opening < total) cues += TimedCue(opening, Cue.OPENING)

        if (config.closingBellSec > 0) {
            val closing = total - config.closingBellSec * 1000L
            val earliest = cues.lastOrNull()?.let { it.atMs + MIN_GAP_MS } ?: 0L
            if (closing >= earliest) cues += TimedCue(closing, Cue.CLOSING)
        }

        if (config.bellAtEnd) cues += TimedCue(total, Cue.END)

        // Interval bells fill the gaps, but never crowd the bells the user explicitly asked for.
        if (config.intervalMin > 0) {
            val step = config.intervalMin * 60_000L
            val fixed = cues.map { it.atMs }
            var t = step
            while (t < total) {
                if (fixed.none { kotlin.math.abs(it - t) < MIN_GAP_MS }) cues += TimedCue(t, Cue.INTERVAL)
                t += step
            }
        }
        return cues.sortedBy { it.atMs }
    }

    /** The next bell still to ring, or null once they have all rung. */
    fun next(config: SessionConfig, elapsedMs: Long): TimedCue? =
        cues(config).firstOrNull { it.atMs > elapsedMs }
}

/** 1:05, 20:00, 1:00:00 — rounds up so the display never shows 0:00 while time remains. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
