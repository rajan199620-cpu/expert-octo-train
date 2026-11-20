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

    private fun cycle(counts: Int) = List(counts) { false } + true

    @Test
    fun breathCountScoresExactNines() {
        val presses = cycle(8) + cycle(8) + cycle(7) + cycle(9) + List(3) { false } // last cycle unfinished
        val r = BreathCount.score(presses)
        assertEquals(BreathCountResult(2, 4), r)
        assertEquals(50, r.accuracyPercent)
    }

    @Test
    fun lostCountStartsTheRoundAgainAndIsNotAMiscount() {
        val (b, n, r) = Triple(BreathCount.Key.BREATH, BreathCount.Key.NINE, BreathCount.Key.RESET)
        // Off count after 5, said so, then a clean 1–9: that round is exact, the slip is a reset.
        val keys = List(8) { b } + n + List(5) { b } + r + List(8) { b } + n + r + r
        assertEquals(BreathCountResult(2, 2, 3), BreathCount.scoreKeys(keys))
        // Without saying so, the same restart reads as a 13-breath round: a miscount.
        assertEquals(BreathCountResult(1, 2), BreathCount.scoreKeys(keys.filter { it != r }))
        // Only resets: no round finished, so no accuracy, and nothing to save.
        assertEquals(null, BreathCount.scoreKeys(listOf(b, r, r)).accuracyPercent)
    }

    @Test
    fun breathCountWithNoFinishedCycleHasNoAccuracy() {
        assertEquals(null, BreathCount.score(List(5) { false }).accuracyPercent)
        assertEquals(BreathCountResult(0, 1), BreathCount.score(listOf(true)))
    }

    @Test
    fun breathChecksRoundTrip() {
        val c = BreathCheck(1_790_000_000_000, BreathCountResult(7, 9))
        assertEquals(c, BreathCheck.decode(c.encode()))
        assertEquals(null, BreathCheck.decode("x,1,2"))
    }

    @Test
    fun `a rhythm whose phases aren't whole milliseconds still has a phase at every moment`() {
        val odd = BreathPattern("Odd", "", 1.2345, 0.6789, 2.3456, 0.0)
        assertEquals(odd.phases.sumOf { it.second }, odd.cycleMs)
        for (t in 0L until 3 * odd.cycleMs) odd.at(t)
        assertEquals(BreathPhase.EXHALE, odd.at(odd.cycleMs - 1).phase)
    }
}
