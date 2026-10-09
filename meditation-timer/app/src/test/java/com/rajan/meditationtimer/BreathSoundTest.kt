package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The breath sound, checked the way an ear can't be: silent on every hold, a breath on every
 * in and out, the in-breath brighter than the out, locked to the exercise clock wherever it
 * starts, from the right side for alternate-nostril breathing, a hum for Bhramari, never
 * clipping, and cheap enough for a phone.
 */
class BreathSoundTest {
    private val rate = 32_000
    private fun pattern(name: String) = BreathPattern.ALL.first { it.name == name }

    /** [seconds] of sound from exercise time [fromMs], rendered in phone-sized blocks. */
    private fun render(p: BreathPattern, seconds: Double, fromMs: Double = 0.0, block: Int = 512, seed: Long = 7, endMs: Double = Double.MAX_VALUE): FloatArray {
        val voice = BreathVoice(p, rate, seed, endMs)
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            voice.render(buf, n, fromMs + done * 1000.0 / rate)
            System.arraycopy(buf, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    /** RMS of one channel (0 left, 1 right) between two times in seconds. */
    private fun rms(x: FloatArray, fromSec: Double, toSec: Double, channel: Int = 0): Double {
        val a = (fromSec * rate).toInt()
        val b = (toSec * rate).toInt()
        return sqrt((a until b).sumOf { val v = x[2 * it + channel].toDouble(); v * v } / (b - a))
    }

    /** Zero crossings per second: higher for a brighter, hissier sound. */
    private fun brightness(x: FloatArray, fromSec: Double, toSec: Double): Double {
        val a = (fromSec * rate).toInt()
        val b = (toSec * rate).toInt()
        val crossings = (a + 1 until b).count { (x[2 * it] >= 0f) != (x[2 * (it - 1)] >= 0f) }
        return crossings / (toSec - fromSec)
    }

    @Test
    fun `holds are silent, every in- and out-breath is heard, and nothing clips`() {
        // Box: in 0–4 s, hold 4–8, out 8–12, hold 12–16.
        val x = render(pattern("Box"), 32.0)
        assertTrue(x.none { it.isNaN() })
        assertTrue("peak ${x.maxOf { abs(it) }}", x.maxOf { abs(it) } < 0.5f)
        for (cycle in 0 until 2) {
            val t = cycle * 16.0
            assertTrue("in-breath heard", rms(x, t + 1, t + 3) in 0.04..0.12)
            assertTrue("hold silent", rms(x, t + 4.05, t + 7.95) < 1e-4)
            assertTrue("out-breath heard", rms(x, t + 8.5, t + 10.5) in 0.04..0.12)
            assertTrue("hold silent", rms(x, t + 12.05, t + 15.95) < 1e-4)
        }
    }

    @Test
    fun `each breath swells and fades within its phase, so its end is the cue to change`() {
        val x = render(pattern("Coherent"), 11.0) // 5.5 s in, 5.5 s out
        val inStart = rms(x, 0.0, 0.25)
        val inMiddle = rms(x, 2.0, 3.5)
        val inEnd = rms(x, 5.4, 5.5)
        assertTrue("in-breath swells: $inStart < $inMiddle", inStart < inMiddle / 3)
        assertTrue("in-breath fades by its end: $inEnd", inEnd < inMiddle / 5)
        val outEarly = rms(x, 6.0, 7.5)
        val outLate = rms(x, 10.0, 10.9)
        assertTrue("out-breath strongest early, then fading: $outEarly > $outLate", outEarly > outLate * 3)
    }

    @Test
    fun `the in-breath is brighter than the out-breath`() {
        val x = render(pattern("Coherent"), 11.0)
        val inhale = brightness(x, 1.5, 4.5)
        val exhale = brightness(x, 6.0, 8.5)
        assertTrue("in $inhale vs out $exhale crossings a second", inhale > exhale * 1.3)
    }

    @Test
    fun `it follows the exercise clock wherever it starts`() {
        // Box, starting 30 s in: the last 2 s of a hold, then an in-breath at 32 s.
        val x = render(pattern("Box"), 6.0, fromMs = 30_000.0)
        assertTrue(rms(x, 0.0, 1.95) < 1e-4)
        assertTrue(rms(x, 3.0, 5.0) > 0.04)
        // Started mid-breath (a resumed sit), it is a breath at once.
        val mid = render(Settle.PATTERN, 1.0, fromMs = 2_000.0)
        assertTrue(rms(mid, 0.0, 1.0) > 0.04)
        // And nothing after the end of the exercise.
        val ended = render(pattern("Coherent"), 4.0, fromMs = 9_000.0, endMs = 11_000.0)
        assertTrue(rms(ended, 2.0, 4.0) == 0.0)
    }

    @Test
    fun `block size changes nothing`() {
        val small = render(pattern("4-7-8"), 20.0, block = 256)
        val large = render(pattern("4-7-8"), 20.0, block = 4096)
        var worst = 0f
        for (i in small.indices) worst = maxOf(worst, abs(small[i] - large[i]))
        assertTrue("differs by $worst", worst < 1e-3f)
    }

    @Test
    fun `alternate-nostril breaths come from the nostril's side`() {
        // 4 s in, 6 s out: breath 1 in left, out right; breath 2 in right, out left.
        val x = render(pattern("Nadi Shodhana"), 20.0)
        assertTrue(rms(x, 1.0, 3.0, 0) > 2 * rms(x, 1.0, 3.0, 1))
        assertTrue(rms(x, 4.5, 7.0, 1) > 2 * rms(x, 4.5, 7.0, 0))
        assertTrue(rms(x, 11.0, 13.0, 1) > 2 * rms(x, 11.0, 13.0, 0))
        assertTrue(rms(x, 14.5, 17.0, 0) > 2 * rms(x, 14.5, 17.0, 1))
        // Every other rhythm is centred.
        val centred = render(pattern("Coherent"), 5.0)
        assertEquals(rms(centred, 1.0, 4.0, 0), rms(centred, 1.0, 4.0, 1), 1e-9)
    }

    @Test
    fun `Bhramari's out-breath is a hum to hum along with`() {
        fun periodicity(x: FloatArray, fromSec: Double, toSec: Double): Double {
            val lag = (rate / 140.0).toInt()
            val a = (fromSec * rate).toInt()
            val b = (toSec * rate).toInt()
            var same = 0.0; var energy = 0.0
            for (i in a until b) {
                same += x[2 * i].toDouble() * x[2 * (i + lag)]
                energy += x[2 * i].toDouble() * x[2 * i]
            }
            return same / energy
        }
        val hum = render(pattern("Bhramari"), 12.0) // 4 s in, 8 s hummed out
        val plain = render(pattern("4-7-8"), 19.0) // its 8 s out-breath is plain
        assertTrue("hum repeats at 140 Hz", periodicity(hum, 5.0, 9.0) > 0.6)
        assertTrue("a breath doesn't", periodicity(plain, 12.0, 15.0) < 0.3)
        assertTrue("and is heard", rms(hum, 5.0, 9.0) in 0.04..0.12)
    }

    @Test
    fun `ten minutes of breath is made in a second or two, a fraction of a percent of a phone's time`() {
        render(pattern("Coherent"), 5.0)
        val began = System.nanoTime()
        render(pattern("Coherent"), 600.0)
        val ms = (System.nanoTime() - began) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
    }
}
