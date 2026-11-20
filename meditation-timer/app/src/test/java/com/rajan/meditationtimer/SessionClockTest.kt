package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {
    @Test
    fun countsFromStart() {
        assertEquals(7_000L, SessionClock(startMs = 1_000).elapsedAt(8_000))
    }

    @Test
    fun standsStillWhilePaused() {
        val paused = SessionClock(startMs = 0).pause(60_000)
        assertTrue(paused.isPaused)
        assertEquals(60_000L, paused.elapsedAt(60_000))
        assertEquals(60_000L, paused.elapsedAt(500_000))
    }

    @Test
    fun resumeLeavesThePauseOut() {
        val clock = SessionClock(startMs = 0).pause(60_000).resume(90_000)
        assertFalse(clock.isPaused)
        assertEquals(70_000L, clock.elapsedAt(100_000))
    }

    @Test
    fun severalPausesAddUp() {
        val clock = SessionClock(startMs = 0)
            .pause(10_000).resume(15_000)
            .pause(40_000).resume(50_000)
        assertEquals(45_000L, clock.elapsedAt(60_000))
    }

    @Test
    fun repeatedPauseAndResumeAreHarmless() {
        val clock = SessionClock(startMs = 0).pause(10_000).pause(20_000)
        assertEquals(10_000L, clock.elapsedAt(30_000))
        val resumed = clock.resume(30_000).resume(40_000)
        assertEquals(10_000L, resumed.elapsedAt(30_000))
        assertEquals(20_000L, resumed.elapsedAt(40_000))
    }
}
