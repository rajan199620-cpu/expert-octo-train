package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The generated sounds, checked the way an ear can't be: never clipping, never silent, never
 * blowing up over a long sit, stereo rather than mono, the right kind of spectrum, and cheap
 * enough to run all sit long on a phone.
 */
class AmbienceTest {
    private val rate = 32_000

    private fun render(kind: Ambience, seconds: Int, seed: Long = 1): FloatArray {
        val s = Soundscape.create(kind, rate, seed)!!
        val out = FloatArray(seconds * rate * 2)
        val block = FloatArray(2048)
        var pos = 0
        while (pos < out.size) {
            val frames = minOf(1024, (out.size - pos) / 2)
            s.render(block, frames)
            System.arraycopy(block, 0, out, pos, frames * 2)
            pos += frames * 2
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    /** Share of energy in [lo, hi) Hz, from a coarse DFT over one channel. */
    private fun bandShare(x: FloatArray, lo: Double, hi: Double): Double {
        val n = 4096
        var inBand = 0.0; var total = 0.0
        var start = 0
        var windows = 0
        while (start + 2 * n <= x.size && windows < 12) {
            for (k in 1 until n / 2 step 4) {
                var re = 0.0; var im = 0.0
                for (t in 0 until n) {
                    val v = x[start + 2 * t] * (0.5 - 0.5 * cos(2 * PI * t / n))
                    re += v * cos(2 * PI * k * t / n); im -= v * sin(2 * PI * k * t / n)
                }
                val p = re * re + im * im
                val f = k.toDouble() * rate / n
                total += p
                if (f >= lo && f < hi) inBand += p
            }
            start += rate * 2 * 5 // a window every 5 s
            windows++
        }
        return inBand / total
    }

    @Test
    fun bothSoundsStayInRangeAndAtAComfortableLevel() {
        for (kind in listOf(Ambience.RAIN, Ambience.BIRDS)) {
            val x = render(kind, 60)
            assertTrue("$kind has NaN", x.none { it.isNaN() })
            val peak = x.maxOf { abs(it) }
            assertTrue("$kind peak $peak", peak <= 1f)
            val level = rms(x)
            // Around -30 to -16 dBFS: present, with headroom, before the user's own volume.
            assertTrue("$kind rms $level", level in 0.03..0.16)
        }
    }

    @Test
    fun aThirtyMinuteSitNeitherFadesAwayNorBuildsUp() {
        for (kind in listOf(Ambience.RAIN, Ambience.BIRDS)) {
            val x = render(kind, 30 * 60, seed = 9)
            val minute = rate * 2 * 60
            val levels = (0 until 30).map { rms(x, it * minute, (it + 1) * minute) }
            val spread = levels.max() / levels.min()
            assertTrue("$kind levels drift: $levels", spread < 1.6)
            assertTrue("$kind peak", x.maxOf { abs(it) } <= 1f)
        }
    }

    @Test
    fun leftAndRightAreDifferentSoItSurroundsYou() {
        for (kind in listOf(Ambience.RAIN, Ambience.BIRDS)) {
            val x = render(kind, 20)
            var lr = 0.0; var ll = 0.0; var rr = 0.0
            for (i in 0 until x.size / 2) { val l = x[2 * i].toDouble(); val r = x[2 * i + 1].toDouble(); lr += l * r; ll += l * l; rr += r * r }
            val correlation = lr / sqrt(ll * rr)
            assertTrue("$kind correlation $correlation", correlation < 0.9)
        }
    }

    @Test
    fun rainIsBroadbandAndBirdsSingInTheirRange() {
        val rain = render(Ambience.RAIN, 60)
        // Rain: energy spread across the spectrum, not a tone; little left above 10 kHz.
        bandShare(rain, 300.0, 8000.0).let { assertTrue("rain mid $it", it > 0.5) }
        bandShare(rain, 10_000.0, 16_000.0).let { assertTrue("rain top $it", it < 0.05) }
        val birds = render(Ambience.BIRDS, 60)
        // Birdsong lives roughly 2–7 kHz; the breeze sits low; together most energy is in those bands.
        bandShare(birds, 2000.0, 7500.0).let { assertTrue("birds song band $it", it > 0.15) }
        bandShare(birds, 7500.0, 16_000.0).let { assertTrue("birds top $it", it < 0.05) }
    }

    @Test
    fun birdsActuallySingEveryFewSecondsAndRestBetween() {
        val x = render(Ambience.BIRDS, 120, seed = 4)
        // High band envelope per 100 ms: song windows stand clearly above the breeze floor.
        val hop = rate / 10
        // Second difference: +12 dB/octave, so the breeze drops away and song stands out.
        var p1 = 0f; var p2 = 0f
        val env = (0 until x.size / 2 / hop).map { w ->
            var e = 0.0
            for (t in w * hop until (w + 1) * hop) { val v = x[2 * t]; val d = v - 2 * p1 + p2; p2 = p1; p1 = v; e += d * d }
            e / hop
        }
        val floor = env.sorted()[env.size / 5]
        val singing = env.count { it > floor * 4 }
        assertTrue("singing windows $singing of ${env.size}", singing in env.size / 20..env.size * 3 / 5)
    }

    @Test
    fun differentSeedsGiveDifferentSoundSameSeedTheSame() {
        val a = render(Ambience.RAIN, 2, seed = 1)
        assertTrue(a.contentEquals(render(Ambience.RAIN, 2, seed = 1)))
        assertTrue(!a.contentEquals(render(Ambience.RAIN, 2, seed = 2)))
    }

    @Test
    fun cheapEnoughToRunAllSitLong() {
        for (kind in listOf(Ambience.RAIN, Ambience.BIRDS)) {
            render(kind, 5) // warm up the JIT
            val t = System.nanoTime()
            render(kind, 60)
            val ms = (System.nanoTime() - t) / 1_000_000
            // A minute of sound in well under a second: a small fraction of one core.
            assertTrue("$kind took $ms ms for 60 s", ms < 1_500)
        }
    }

    @Test
    fun gainRampsGlideWithoutJumpsAndLandExactly() {
        val g = GainRamp(rate)
        g.to(1f, 2000)
        var last = 0f
        repeat(rate * 2) {
            val v = g.next()
            assertTrue(v >= last && v - last <= 1f / (rate * 2) + 1e-6f)
            last = v
        }
        assertEquals(1f, g.value, 0f)
        assertTrue(g.settled)
        // Changing direction mid-ramp continues from where it is, no jump.
        g.to(0f, 1000)
        repeat(rate / 4) { g.next() }
        val mid = g.value
        g.to(1f, 1000)
        assertTrue(abs(g.next() - mid) < 0.001f)
    }

    @Test
    fun volumeSliderIsZeroAtTheBottomAndFullAtTheTop() {
        assertEquals(0f, sliderGain(0f), 0f)
        assertEquals(1f, sliderGain(1f), 1e-6f)
        assertTrue(sliderGain(0.5f) in 0.05f..0.2f)
        var last = 0f
        for (i in 1..100) { val v = sliderGain(i / 100f); assertTrue(v > last); last = v }
    }
}
