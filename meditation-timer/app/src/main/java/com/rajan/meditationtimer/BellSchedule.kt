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
) {
    val durationMs: Long get() = durationSec * 1000L
}

enum class Cue { OPENING, CLOSING, END }

data class TimedCue(val atMs: Long, val cue: Cue)

object BellSchedule {
    /** Bells closer together than this would blur into one, so the later one is dropped. */
    const val MIN_GAP_MS = 3_000L

    fun cues(config: SessionConfig): List<TimedCue> {
        val total = config.durationMs
        val cues = mutableListOf<TimedCue>()

        val opening = config.openingBellSec * 1000L
        if (opening < total) cues += TimedCue(opening, Cue.OPENING)

        if (config.closingBellSec > 0) {
            val closing = total - config.closingBellSec * 1000L
            val earliest = cues.lastOrNull()?.let { it.atMs + MIN_GAP_MS } ?: 0L
            if (closing >= earliest) cues += TimedCue(closing, Cue.CLOSING)
        }

        if (config.bellAtEnd) cues += TimedCue(total, Cue.END)
        return cues
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
