package com.rajan.meditationtimer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/** How a paced breath reaches you with your eyes closed: a breath to hear, taps to feel, or both. */
enum class BreathCue(val label: String) {
    SOUND("Breath sound"),
    VIBRATION("Vibration"),
    BOTH("Both");

    val sound: Boolean get() = this != VIBRATION
    val vibration: Boolean get() = this != SOUND
}

/**
 * A breath you can hear, made for whatever rhythm is being paced, so each in- and out-breath lasts
 * exactly as long as its phase: a recording would have to be stretched or cut to fit a 5.5-second
 * coherent breath, a 7-second hold or an 8-second out-breath. It is drawn in noise, the way a breath
 * is made of moving air:
 *
 * - **In-breath**: air drawn in through the nose, a soft hiss that swells and, as the lungs fill,
 *   rises a little in pitch.
 * - **Out-breath**: lower and warmer, strongest at the start and fading away, falling in pitch.
 * - **Holds**: silence. The next breath beginning is the cue that the hold is over.
 * - **Bhramari**: the out-breath is a low hum to hum along with.
 * - **Nadi Shodhana**: with earphones, each breath comes from the side of the nostril in use.
 *
 * Why a breath and not a beep: in a guided-breathing study a synthetic, breath-like sound helped
 * people breathe at a target pace, and kept them nearer it than a musical cue at faster rates
 * (Marentakis et al., CHI 2021). Breathing and sniffing sounds do bother some people (a recognised
 * misophonia trigger), which is one reason vibration stays a choice.
 *
 * Pure Kotlin, no Android, so it can be tested and rendered on any JVM. [render] is told the
 * exercise time of its first frame, so the sound stays locked to the breath on screen.
 */
class BreathVoice(
    private val pattern: BreathPattern,
    private val sampleRate: Int,
    seed: Long = 1L,
    /** Exercise time (ms) after which it is silent, whatever the pattern would do next. */
    private val endMs: Double = Double.MAX_VALUE,
) {
    private val phases = pattern.phases
    private val cycleMs = pattern.cycleMs.toDouble()
    private val msPerFrame = 1000.0 / sampleRate

    private var state = (seed * -0x61C8864680B583EBL xor 0x2545F4914F6CDD1DL).let { if (it == 0L) 1L else it }

    /** Uniform noise in -1..1 (xorshift: fast, and the same every run for a given seed). */
    private fun noise(): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 40).toInt() and 0xFFFFFF) / 8388608f - 1f
    }

    // Two resonances shape the air, a gentle low-pass softens the top.
    private val low = Resonance(sampleRate)
    private val high = Resonance(sampleRate)
    private var soft = 0f
    private val softIn = onePole(5500.0)
    private val softOut = onePole(3500.0)

    // A breath is never perfectly steady: a slow, slight flutter in its strength.
    private var flutter = 0f
    private var flutterTarget = 0f
    private var flutterLeft = 0
    private val flutterGlide = (1 - exp(-2 * PI * 12.0 / sampleRate)).toFloat()

    private var humPhase = 0.0

    // Where in the pattern the last frame fell, so finding the next one is O(1).
    private var phaseStart = 0.0
    private var phaseEnd = -1.0
    private var phaseIndex = 0
    private var cycle = 0L
    private var retuneIn = 0

    /** Fills [out] with [frames] interleaved stereo frames; the first is at [startMs] of exercise time. */
    fun render(out: FloatArray, frames: Int, startMs: Double) {
        for (i in 0 until frames) {
            val t = startMs + i * msPerFrame
            val air = noise()
            stepFlutter()
            if (t < 0 || t >= endMs) {
                // Keep the filters fed so a breath starting later begins smoothly.
                low.band(air); high.band(air)
                out[2 * i] = 0f
                out[2 * i + 1] = 0f
                continue
            }
            locate(t)
            val phase = phases[phaseIndex].first
            val p = ((t - phaseStart) / (phaseEnd - phaseStart)).toFloat().coerceIn(0f, 1f)
            val v = when (phase) {
                BreathPhase.INHALE -> inhale(air, p)
                BreathPhase.EXHALE -> if (pattern.style == BreathStyle.HUM) hum(air, p) else exhale(air, p)
                else -> { low.band(air); high.band(air); 0f }
            }
            val (gl, gr) = pan(phase)
            out[2 * i] = (v * gl).coerceIn(-1f, 1f)
            out[2 * i + 1] = (v * gr).coerceIn(-1f, 1f)
        }
    }

    private fun locate(t: Double) {
        if (t >= phaseStart && t < phaseEnd) return
        cycle = (t / cycleMs).toLong()
        var start = cycle * cycleMs
        phaseIndex = phases.lastIndex
        for ((i, phase) in phases.withIndex()) {
            val end = start + phase.second
            if (t < end || i == phases.lastIndex) {
                phaseIndex = i
                phaseStart = start
                phaseEnd = end
                break
            }
            start = end
        }
        retuneIn = 0
    }

    private fun stepFlutter() {
        if (--flutterLeft <= 0) {
            flutterTarget = noise()
            flutterLeft = (sampleRate * (0.08f + 0.12f * (noise() + 1f) / 2f)).toInt()
        }
        flutter += flutterGlide * (flutterTarget - flutter)
    }

    /** Air drawn in: a hiss centred near 1.2–1.7 kHz that climbs as the breath goes on. */
    private fun inhale(air: Float, p: Float): Float {
        if (--retuneIn <= 0) {
            low.tune(1150f + 500f * p, 1.1f)
            high.tune(2700f + 800f * p, 1.8f)
            retuneIn = RETUNE_EVERY
        }
        val shaped = 0.9f * low.band(air) + 0.3f * high.band(air)
        soft += softIn * (shaped - soft)
        val env = rise(p, 0.30f) * (1f - rise(p - 0.80f, 0.20f)) * (0.8f + 0.2f * sin(PI * p).toFloat())
        return INHALE_LEVEL * env * (1f + 0.12f * flutter) * soft
    }

    /** Air let go: lower and warmer, strongest early, falling in pitch as it fades. */
    private fun exhale(air: Float, p: Float): Float {
        if (--retuneIn <= 0) {
            low.tune(760f - 280f * p, 0.8f)
            high.tune(1500f - 450f * p, 1.4f)
            retuneIn = RETUNE_EVERY
        }
        val shaped = 0.9f * low.band(air) + 0.35f * high.band(air)
        soft += softOut * (shaped - soft)
        val env = rise(p, 0.10f) * (1f - rise(p - 0.55f, 0.45f)) * (1f - 0.25f * p)
        return EXHALE_LEVEL * env * (1f + 0.12f * flutter) * soft
    }

    /** Bhramari: a low, steady hum (lips closed, "mmm") with a little breath under it. */
    private fun hum(air: Float, p: Float): Float {
        val breath = exhale(air, p) * 0.3f
        humPhase += HUM_HZ * (1 + 0.004 * flutter) / sampleRate
        if (humPhase >= 1.0) humPhase -= 1.0
        val x = humPhase * HUM_TABLE.size
        val i = x.toInt()
        val frac = (x - i).toFloat()
        val wave = HUM_TABLE[i] + (HUM_TABLE[(i + 1) % HUM_TABLE.size] - HUM_TABLE[i]) * frac
        val env = rise(p, 0.08f) * (1f - rise(p - 0.85f, 0.15f))
        return HUM_LEVEL * env * wave + breath
    }

    /** Alternate-nostril breaths come from that side (even breaths: in left, out right); others centre. */
    private fun pan(phase: BreathPhase): Pair<Float, Float> {
        if (pattern.style != BreathStyle.ALTERNATE) return CENTRE
        val even = cycle % 2 == 0L
        val left = if (phase == BreathPhase.INHALE) even else !even
        return if (left) LEFT else RIGHT
    }

    /** One-pole low-pass coefficient: the in-breath keeps its air (5.5 kHz), the out-breath is darker (3.5 kHz). */
    private fun onePole(hz: Double) = (1 - exp(-2 * PI * hz / sampleRate)).toFloat()

    /** 0 → 1 smoothly over [width] (a smoothstep), 0 before, 1 after. */
    private fun rise(x: Float, width: Float): Float {
        val u = (x / width).coerceIn(0f, 1f)
        return u * u * (3 - 2 * u)
    }

    /** A band-pass with unity gain at its centre (a zero-delay state-variable filter: smooth when retuned). */
    private class Resonance(private val rate: Int) {
        private var ic1 = 0f
        private var ic2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var a3 = 0f
        private var k = 1f

        fun tune(hz: Float, q: Float) {
            val g = tan(PI * hz / rate).toFloat()
            k = 1f / q
            a1 = 1f / (1f + g * (g + k))
            a2 = g * a1
            a3 = g * a2
        }

        fun band(x: Float): Float {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2 * v1 - ic1
            ic2 = 2 * v2 - ic2
            return k * v1
        }
    }

    private companion object {
        const val RETUNE_EVERY = 16
        const val INHALE_LEVEL = 0.55f
        const val EXHALE_LEVEL = 0.56f
        const val HUM_LEVEL = 0.19f
        const val HUM_HZ = 140.0
        val CENTRE = 0.8f to 0.8f
        val LEFT = 1f to 0.25f
        val RIGHT = 0.25f to 1f

        /**
         * One cycle of a hummed "mmm". Its overtones run up past 1 kHz because a phone speaker
         * barely plays 140 Hz: the ear hears the hum's pitch from its overtones alone.
         */
        val HUM_TABLE: FloatArray = run {
            val amps = floatArrayOf(0.9f, 1f, 0.75f, 0.55f, 0.42f, 0.3f, 0.22f, 0.15f, 0.1f, 0.07f)
            val n = 1024
            val table = FloatArray(n) { i ->
                amps.indices.sumOf { h -> amps[h] * sin(2 * PI * (h + 1) * i / n) }.toFloat()
            }
            val peak = table.maxOf { abs(it) }
            FloatArray(n) { table[it] / peak }
        }
    }
}
