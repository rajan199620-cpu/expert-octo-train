package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BellScheduleTest {
    private fun config(min: Int = 20, opening: Int = 5, closing: Int = 10, end: Boolean = false) =
        SessionConfig(min * 60, opening, closing, end)

    @Test
    fun defaultSessionRingsFiveSecondsInAndTenSecondsBeforeEnd() {
        assertEquals(
            listOf(TimedCue(5_000, Cue.OPENING), TimedCue(1_190_000, Cue.CLOSING)),
            BellSchedule.cues(config()),
        )
    }

    @Test
    fun endBellIsAddedWhenEnabled() {
        assertEquals(
            listOf(TimedCue(5_000, Cue.OPENING), TimedCue(595_000, Cue.CLOSING), TimedCue(600_000, Cue.END)),
            BellSchedule.cues(config(min = 10, closing = 5, end = true)),
        )
    }

    @Test
    fun closingBellIsDroppedWhenItWouldCollideWithOpeningBell() {
        // 1 min session, opening at 30s, closing 60s before end = 0s -> before the opening bell.
        assertEquals(listOf(TimedCue(30_000, Cue.OPENING)), BellSchedule.cues(config(min = 1, opening = 30, closing = 60)))
        // Closing at 31s is only 1s after the opening bell -> dropped.
        assertEquals(
            listOf(TimedCue(30_000, Cue.OPENING)),
            BellSchedule.cues(SessionConfig(durationSec = 61, openingBellSec = 30, closingBellSec = 30, bellAtEnd = false)),
        )
        // Exactly MIN_GAP_MS apart is kept.
        assertEquals(
            listOf(TimedCue(30_000, Cue.OPENING), TimedCue(33_000, Cue.CLOSING)),
            BellSchedule.cues(SessionConfig(durationSec = 63, openingBellSec = 30, closingBellSec = 30, bellAtEnd = false)),
        )
    }

    @Test
    fun openingBellPastTheEndIsSkipped() {
        assertEquals(
            listOf(TimedCue(0, Cue.CLOSING)),
            BellSchedule.cues(SessionConfig(durationSec = 10, openingBellSec = 30, closingBellSec = 10, bellAtEnd = false)),
        )
    }

    @Test
    fun nextCueTracksElapsedTime() {
        val c = config()
        assertEquals(Cue.OPENING, BellSchedule.next(c, 0)?.cue)
        assertEquals(Cue.CLOSING, BellSchedule.next(c, 5_000)?.cue)
        assertNull(BellSchedule.next(c, 1_190_000))
    }

    @Test
    fun clockRoundsUpAndShowsHours() {
        assertEquals("20:00", formatClock(1_200_000))
        assertEquals("0:01", formatClock(1))
        assertEquals("0:00", formatClock(0))
        assertEquals("1:05", formatClock(64_001))
        assertEquals("1:00:00", formatClock(3_600_000))
    }
}
