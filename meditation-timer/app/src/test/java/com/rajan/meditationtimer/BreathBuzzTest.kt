package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathBuzzTest {
    private val kinds = BreathBuzz.Kind.entries

    @Test
    fun `each cue is a rhythm you can count - one tap in, two taps out, one long buzz to hold`() {
        assertEquals(1, BreathBuzz.pattern(BreathBuzz.Kind.IN).pulses.size)
        assertEquals(2, BreathBuzz.pattern(BreathBuzz.Kind.OUT).pulses.size)
        assertEquals(1, BreathBuzz.pattern(BreathBuzz.Kind.HOLD).pulses.size)
        // Read back from the rhythm alone, every cue means what it should, and no two are alike.
        for (k in kinds) assertEquals(k, BreathBuzz.read(BreathBuzz.pattern(k)))
        // Strength carries no meaning: at one fixed strength (no amplitude control) they still read the same.
        for (k in kinds) {
            val p = BreathBuzz.pattern(k)
            val flat = BreathBuzz.Pattern(p.timings, p.amplitudes.map { if (it > 0) 255 else 0 }.toIntArray())
            assertEquals(k, BreathBuzz.read(flat))
        }
    }

    @Test
    fun `every pulse is long enough to feel and taps stay apart, on slow motors too`() {
        for (k in kinds) {
            val p = BreathBuzz.pattern(k)
            assertEquals("$k: off/on pairs", p.timings.size, p.amplitudes.size)
            assertEquals("$k starts at once", 0L, p.timings[0])
            assertEquals("$k: the first entry is the off lead-in", 0, p.amplitudes[0])
            // Cheap vibration motors take 50–100 ms to spin up and as long to stop.
            assertTrue("$k: ${p.pulses}", p.pulses.all { it >= 100 })
            assertTrue("$k: ${p.gaps}", p.gaps.all { it >= 150 })
            assertTrue("$k: ${p.amplitudes.toList()}", p.amplitudes.all { it in 0..255 })
            assertTrue("$k: strong enough through a cushion", p.amplitudes.filter { it > 0 }.all { it >= 100 })
            // Off and on alternate, so the waveform plays as written.
            p.amplitudes.toList().zipWithNext().forEach { (a, b) -> assertTrue("$k alternates", (a == 0) != (b == 0)) }
            // Short, so a cue is over long before the phase it marks.
            assertTrue("$k lasts ${p.totalMs} ms", p.totalMs <= 500)
        }
        assertTrue(BreathBuzz.pattern(BreathBuzz.Kind.HOLD).pulses[0] >= 3 * BreathBuzz.pattern(BreathBuzz.Kind.IN).pulses[0])
    }

    @Test
    fun `phases map to cues, and every cue ends well inside its phase`() {
        assertEquals(BreathBuzz.Kind.IN, BreathBuzz.of(Settle.Phase.IN))
        assertEquals(BreathBuzz.Kind.OUT, BreathBuzz.of(Settle.Phase.OUT))
        assertEquals(BreathBuzz.Kind.IN, BreathBuzz.of(BreathPhase.INHALE))
        assertEquals(BreathBuzz.Kind.OUT, BreathBuzz.of(BreathPhase.EXHALE))
        assertEquals(BreathBuzz.Kind.HOLD, BreathBuzz.of(BreathPhase.HOLD_IN))
        assertEquals(BreathBuzz.Kind.HOLD, BreathBuzz.of(BreathPhase.HOLD_OUT))
        // Every rhythm on the Breathe tab, breath by breath: the cue at each phase change is the
        // phase's own, and finishes within an eighth of the phase.
        for (pattern in BreathPattern.ALL) {
            var last: BreathPhase? = null
            var lastCycle = -1L
            var t = 0L
            while (t < pattern.cycleMs * 4) {
                val s = pattern.at(t)
                if (s.phase != last || s.completedCycles != lastCycle) {
                    val cue = BreathBuzz.pattern(BreathBuzz.of(s.phase))
                    assertEquals("${pattern.name} at $t", BreathBuzz.of(s.phase), BreathBuzz.read(cue))
                    assertTrue("${pattern.name} at $t", cue.totalMs * 8 <= s.msLeftInPhase)
                    last = s.phase
                    lastCycle = s.completedCycles
                }
                t += 50
            }
        }
        for (phase in Settle.Phase.entries) {
            val len = if (phase == Settle.Phase.IN) Settle.INHALE_MS else Settle.EXHALE_MS
            assertTrue(BreathBuzz.pattern(BreathBuzz.of(phase)).totalMs * 8 <= len)
        }
    }

    @Test
    fun `resuming part-way through a breath cues it at once, but never doubles or crowds a cue`() {
        val len = 60_000L
        val grace = 250L
        assertNull("at Begin the breath's own cue is coming", Settle.rejoin(0, len, grace))
        assertNull(Settle.rejoin(grace, len, grace))
        assertEquals(Settle.Phase.IN, Settle.rejoin(grace + 1, len, grace))
        assertEquals(Settle.Phase.IN, Settle.rejoin(2_000, len, grace))
        assertNull("too near the out-breath's cue", Settle.rejoin(2_001, len, grace))
        assertEquals(Settle.Phase.OUT, Settle.rejoin(5_000, len, grace))
        assertEquals(Settle.Phase.OUT, Settle.rejoin(8_000, len, grace))
        assertNull(Settle.rejoin(8_001, len, grace))
        assertNull("after the settle-in, nothing", Settle.rejoin(60_000, len, grace))
        assertNull(Settle.rejoin(5_000, 0, grace))
        // Brute force: resume at every 10 ms of a 5-minute settle-in. The cues that follow (the
        // rejoin, if any, then each phase start the service would still post) are never closer
        // than 2 s, and the first one always names the phase you're actually in.
        val long = 300_000L
        val ticks = Settle.ticks(long)
        var elapsed = 0L
        while (elapsed < long) {
            val cues = buildList {
                Settle.rejoin(elapsed, long, grace)?.let { add(elapsed to it) }
                for ((at, phase) in ticks) if (at + grace >= elapsed) add(maxOf(at, elapsed) to phase)
            }
            cues.zipWithNext().forEach { (a, b) -> assertTrue("resume at $elapsed: $a then $b", b.first - a.first >= Settle.REJOIN_MIN_MS) }
            // No cue at all only in the last moments, when the opening bell is about to ring.
            val first = cues.firstOrNull()
            if (first == null) assertTrue("resume at $elapsed", long - elapsed < Settle.REJOIN_MIN_MS)
            else if (first.first - elapsed < 1) assertEquals("resume at $elapsed", Settle.at(elapsed, long)!!.phase, first.second)
            elapsed += 10
        }
    }

    @Test
    fun `an exercise is cued at every phase start and nowhere else`() {
        val box = BreathPattern.ALL.first { it.name == "Box" }
        val (i, h, o) = Triple(BreathBuzz.Kind.IN, BreathBuzz.Kind.HOLD, BreathBuzz.Kind.OUT)
        assertEquals(
            listOf(0L to i, 4_000L to h, 8_000L to o, 12_000L to h, 16_000L to i, 20_000L to h, 24_000L to o, 28_000L to h),
            BreathBuzz.cues(box, 2 * box.cycleMs),
        )
        // Every rhythm and length: one cue per phase, each matching what the screen shows then.
        for (pattern in BreathPattern.ALL) for (minutes in listOf(1, 3, 5, 10)) {
            val total = pattern.breathsFor(minutes) * pattern.cycleMs
            val cues = BreathBuzz.cues(pattern, total)
            assertEquals(pattern.name, pattern.phases.size * pattern.breathsFor(minutes), cues.size.toLong())
            for ((at, kind) in cues) assertEquals("${pattern.name} at $at", BreathBuzz.of(pattern.at(at).phase), kind)
            assertTrue(cues.all { it.first < total })
        }
    }
}
