package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathingTest {
    private val box = BreathPattern.ALL.first { it.name == "Box" }
    private val coherent = BreathPattern.ALL.first { it.name == "Coherent" }

    @Test
    fun boxBreathingWalksThroughAllFourPhases() {
        assertEquals(16_000L, box.cycleMs)
        assertEquals(BreathPhase.INHALE, box.at(0).phase)
        assertEquals(BreathPhase.HOLD_IN, box.at(4_000).phase)
        assertEquals(BreathPhase.EXHALE, box.at(8_000).phase)
        assertEquals(BreathPhase.HOLD_OUT, box.at(12_000).phase)
        val wrapped = box.at(17_000)
        assertEquals(BreathPhase.INHALE, wrapped.phase)
        assertEquals(1L, wrapped.completedCycles)
        assertEquals(3_000L, wrapped.msLeftInPhase)
    }

    @Test
    fun zeroLengthHoldsAreSkipped() {
        assertEquals(11_000L, coherent.cycleMs)
        assertEquals(BreathPhase.EXHALE, coherent.at(5_500).phase)
        assertEquals(BreathPhase.INHALE, coherent.at(11_000).phase)
    }

    @Test
    fun circleGrowsInHoldsFullAndShrinksOut() {
        assertEquals(0f, box.at(0).expansion, 1e-4f)
        assertEquals(0.5f, box.at(2_000).expansion, 1e-4f)
        assertEquals(1f, box.at(5_000).expansion, 1e-4f)
        assertEquals(0.5f, box.at(10_000).expansion, 1e-4f)
        assertEquals(0f, box.at(13_000).expansion, 1e-4f)
    }

    @Test
    fun malaCompletesARoundAtTarget() {
        var count = MalaCount(target = 3)
        val results = (1..7).map { count.tap().also { count = it.first }.second }
        assertEquals(listOf(false, false, true, false, false, true, false), results)
        assertEquals(MalaCount(beads = 1, rounds = 2, target = 3), count)
        assertFalse(MalaCount(beads = 106).tap().second)
        assertTrue(MalaCount(beads = 107).tap().second)
    }
}
