package com.rajan.meditationtimer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Background sound during a sit. Off by default: silence is the classic setting. */
enum class Ambience(val label: String) {
    OFF("Off"),
    RAIN("Rain"),
    BIRDS("Birds"),
    RAIN_AND_BIRDS("Rain & birds"),
    CUSTOM("My recording"),
}

/**
 * An endless soundscape generated sample by sample, so it never loops: a recording repeating
 * every minute becomes something the mind notices and waits for. Pure Kotlin, no Android, so it
 * can be tested and rendered on any JVM.
 */
abstract class Soundscape(val sampleRate: Int, seed: Long) {
    private var state = seed xor 0x5DEECE66DL

    /** Fast uniform random in [0, 1). */
    protected fun rand(): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }

    protected fun noise() = rand() * 2f - 1f
    protected fun between(a: Float, b: Float) = a + (b - a) * rand()

    /** One-pole low-pass coefficient for a cutoff in Hz. */
    protected fun lp(hz: Float) = (1 - exp(-2 * PI * hz / sampleRate)).toFloat()

    /** Fills [out] with [frames] interleaved stereo frames, each sample within -1..1. */
    open fun render(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            frame()
            out[2 * i] = soft(left)
            out[2 * i + 1] = soft(right)
        }
    }

    protected var left = 0f
    protected var right = 0f
    protected abstract fun frame()

    /** Transparent below 0.8, rounds anything louder so a burst can never clip. */
    protected fun soft(x: Float): Float = if (abs(x) < 0.8f) x else (0.8f + 0.2f * tanh((abs(x) - 0.8f) / 0.2f)).let { if (x < 0) -it else it }

    companion object {
        fun create(kind: Ambience, sampleRate: Int, seed: Long = System.nanoTime()): Soundscape? = when (kind) {
            Ambience.RAIN -> Rain(sampleRate, seed)
            Ambience.BIRDS -> Birds(sampleRate, seed)
            Ambience.RAIN_AND_BIRDS -> Mix(Rain(sampleRate, seed), 0.7f, Birds(sampleRate, seed + 1), 0.9f)
            else -> null
        }
    }
}

/** A slow random wander between [lo] and [hi], changing over several seconds: gusts, breeze. */
private class Wander(private val rate: Int, private val lo: Float, private val hi: Float, private val pick: () -> Float) {
    private var value = (lo + hi) / 2
    private var target = value
    private var left = 0
    private val a = (1 - exp(-2 * PI * 0.15 / rate)).toFloat()
    fun next(): Float {
        if (--left <= 0) {
            target = lo + (hi - lo) * pick()
            left = (rate * (3 + 6 * pick())).toInt()
        }
        value += a * (target - value)
        return value
    }
}

/** RBJ band-pass (constant peak gain), for raindrop resonances. */
private class BandPass(f: Float, q: Float, rate: Int) {
    private val b0: Float; private val b2: Float; private val a1: Float; private val a2: Float
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    init {
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        b0 = (alpha / a0).toFloat(); b2 = (-alpha / a0).toFloat()
        a1 = (-2 * kotlin.math.cos(w) / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }
    fun process(x: Float): Float {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/**
 * Steady rain: a wash of filtered noise in each ear (decorrelated, so it surrounds rather than
 * sits in the middle), a low body, hundreds of small drops a second each ringing at its own
 * pitch, and now and then a heavier drip close by. Gusts swell and ease every few seconds.
 */
class Rain(rate: Int, seed: Long) : Soundscape(rate, seed) {
    private val hissA = lp(6500f); private val hissHp = lp(350f)
    private val bodyA = lp(900f); private val bodyHp = lp(120f)
    private val rumbleA = lp(80f)
    private var hL = 0f; private var hR = 0f; private var hpL = 0f; private var hpR = 0f
    private var bL = 0f; private var bR = 0f; private var bpL = 0f; private var bpR = 0f
    private var r1 = 0f; private var r2 = 0f
    private val gust = Wander(rate, 0.8f, 1.15f, ::rand)

    private class Drop(val filter: BandPass, val decay: Float, var env: Float, val pan: Float)
    private val drops = ArrayList<Drop>()

    private class Drip(var phase: Float, var freq: Float, val fall: Float, var env: Float, val decay: Float, val pan: Float)
    private val drips = ArrayList<Drip>()

    override fun frame() {
        val g = gust.next()
        // Wash: two independent noises, so left and right are different rain.
        hL += hissA * (noise() - hL); hR += hissA * (noise() - hR)
        hpL += hissHp * (hL - hpL); hpR += hissHp * (hR - hpR)
        bL += bodyA * (noise() - bL); bR += bodyA * (noise() - bR)
        bpL += bodyHp * (bL - bpL); bpR += bodyHp * (bR - bpR)
        r1 += rumbleA * (noise() - r1); r2 += rumbleA * (r1 - r2)
        var l = 0.30f * g * (hL - hpL) + 0.34f * g * (bL - bpL) + 1.6f * r2
        var r = 0.30f * g * (hR - hpR) + 0.34f * g * (bR - bpR) + 1.6f * r2

        // Small drops: about 240 a second, more in a gust; sizes follow a power law, so most are
        // faint ticks and a few stand out.
        if (rand() < 240f * g * g / sampleRate && drops.size < 96) {
            val size = rand().pow(3)
            drops.add(Drop(BandPass(between(1400f, 6500f), between(1.5f, 5f), sampleRate), exp(-1f / (sampleRate * between(0.0015f, 0.006f))), 0.05f + 0.6f * size, rand()))
        }
        var i = 0
        while (i < drops.size) {
            val d = drops[i]
            val y = d.filter.process(noise() * d.env)
            l += y * (1 - d.pan); r += y * d.pan
            d.env *= d.decay
            if (d.env < 0.0005f) { drops[i] = drops[drops.size - 1]; drops.removeAt(drops.size - 1) } else i++
        }

        // Heavier drips off a roof edge: a short falling tone, a couple a second, never loud.
        if (rand() < 1.5f / sampleRate && drips.size < 4) {
            drips.add(Drip(0f, between(500f, 900f), between(0.99990f, 0.99997f), between(0.03f, 0.07f), exp(-1f / (sampleRate * between(0.02f, 0.04f))), between(0.15f, 0.85f)))
        }
        i = 0
        while (i < drips.size) {
            val d = drips[i]
            d.phase += (2 * PI * d.freq / sampleRate).toFloat()
            d.freq *= d.fall
            val y = sin(d.phase) * d.env
            l += y * (1 - d.pan); r += y * d.pan
            d.env *= d.decay
            if (d.env < 0.0003f) { drips[i] = drips[drips.size - 1]; drips.removeAt(drips.size - 1) } else i++
        }
        // Gentle two-pole tone filter: real rain heard indoors has little above ~8 kHz, and a
        // bright hiss tires the ear over a long sit.
        t1L += toneA * (l - t1L); t2L += toneA * (t1L - t2L)
        t1R += toneA * (r - t1R); t2R += toneA * (t1R - t2R)
        left = 0.8f * t2L; right = 0.8f * t2R
    }
    private val toneA = lp(7000f)
    private var t1L = 0f; private var t2L = 0f; private var t1R = 0f; private var t2R = 0f
}

/**
 * A morning garden: a soft breeze under a few birds at different distances, each with its own
 * kind of song (a slow whistle and a farther one answering, a dove's low coo, a gentle carol, and
 * a distant koel), each singing every several seconds. Far birds are quieter and duller; a light reverb places them.
 */
class Birds(rate: Int, seed: Long) : Soundscape(rate, seed) {
    private val breezeA = lp(420f); private val breezeB = lp(1800f)
    private var w1L = 0f; private var w1R = 0f; private var w2L = 0f; private var w2R = 0f
    private val breeze = Wander(rate, 0.5f, 1.2f, ::rand)

    /** One note of a song: a sine sweeping [f0]→[f1] with optional vibrato, under a smooth envelope. */
    private class Note(val start: Int, val length: Int, val f0: Float, val f1: Float, val vibHz: Float, val vibDepth: Float, val amp: Float, val curve: Float)

    private inner class Bird(val kind: Int, val pan: Float, distance: Float) {
        val gain = 1f / (1f + 3.5f * distance)
        val dull = lp(9000f - 5500f * distance)
        var lpState = 0f
        var nextSong = (sampleRate * between(0.5f, 8f)).toInt()
        var notes: List<Note> = emptyList()
        var clock = 0
        var phase = 0f
        var vibPhase = 0f

        fun song(): List<Note> {
            val out = ArrayList<Note>()
            var t = 0
            fun ms(x: Float) = (x * sampleRate / 1000).toInt()
            when (kind) {
                0 -> { // slow clear whistle, 3–5 notes, a little lower or higher each time
                    val base = between(2600f, 3400f)
                    repeat(3 + (rand() * 3).toInt()) { n ->
                        val len = ms(if (n == 0) between(380f, 520f) else between(220f, 360f))
                        val f = base * (if (n == 0) 1f else between(1.08f, 1.22f))
                        out.add(Note(t, len, f, f * between(0.97f, 1.03f), 0f, 0f, 0.9f, 1f))
                        t += len + ms(between(60f, 120f))
                    }
                }
                // No quick chirps or trills: sharp, fast notes startle rather than soothe, so every
                // note here is long and glides slowly (the limits are [MIN_NOTE_MS] and [MAX_GLIDE_HZ_PER_SEC]).
                1 -> { // spotted dove: a soft low "croo-cru-cru", the calmest sound in an Indian garden
                    val f = between(430f, 520f)
                    repeat(3 + (rand() * 2).toInt()) { n ->
                        val len = ms(if (n == 0) between(420f, 520f) else between(260f, 340f))
                        out.add(Note(t, len, f * (if (n == 0) 1.06f else 1f), f * 0.96f, 0f, 0f, 1f, 1f))
                        t += len + ms(between(140f, 220f))
                    }
                }
                2 -> { // a second, farther whistler answering the first, lower and slower
                    val base = between(2100f, 2600f)
                    repeat(2 + (rand() * 2).toInt()) { n ->
                        val len = ms(between(420f, 600f))
                        val f = base * (if (n % 2 == 0) 1f else between(0.88f, 0.94f))
                        out.add(Note(t, len, f, f * between(0.98f, 1.02f), 0f, 0f, 0.85f, 1f))
                        t += len + ms(between(150f, 260f))
                    }
                }
                3 -> repeat(4 + (rand() * 3).toInt()) { // gentle carol: slow slurs with a soft waver
                    val len = ms(between(200f, 340f))
                    val f = between(2300f, 3600f)
                    when ((rand() * 3).toInt()) {
                        0 -> out.add(Note(t, len, f, f * between(1.06f, 1.18f), 0f, 0f, 0.8f, 1f))
                        1 -> out.add(Note(t, len, f * between(1.06f, 1.18f), f, 0f, 0f, 0.8f, 1f))
                        else -> out.add(Note(t, len, f, f, between(4f, 6f), between(30f, 60f), 0.75f, 1f))
                    }
                    t += len + ms(between(120f, 220f))
                }
                else -> { // distant koel: a rising "ku-oo", repeated, each a little higher
                    var f = between(650f, 750f)
                    repeat(3 + (rand() * 3).toInt()) {
                        val len = ms(between(380f, 460f))
                        out.add(Note(t, len, f, f * 1.45f, 0f, 0f, 0.9f, 0.7f))
                        t += len + ms(between(350f, 500f))
                        f *= 1.05f
                    }
                }
            }
            return out
        }

        fun next(): Float {
            if (notes.isEmpty()) {
                if (--nextSong <= 0) { notes = song(); clock = 0 }
                return 0f
            }
            var y = 0f
            // At most one note sounds at a time within a bird's song.
            val note = notes.firstOrNull { clock >= it.start && clock < it.start + it.length }
            if (note != null) {
                val x = (clock - note.start).toFloat() / note.length
                val sweep = note.f0 + (note.f1 - note.f0) * x.pow(note.curve)
                vibPhase += (2 * PI * note.vibHz / sampleRate).toFloat()
                val f = sweep + note.vibDepth * sin(vibPhase)
                phase += (2 * PI * f / sampleRate).toFloat()
                if (phase > 2 * PI) phase -= (2 * PI).toFloat()
                val env = sin(PI * x).toFloat().pow(1.5f)
                y = (sin(phase) + 0.12f * sin(2 * phase)) * env * note.amp
            }
            clock++
            if (clock > notes.last().let { it.start + it.length }) {
                notes = emptyList()
                // Some birds sing more often than others; nobody sings on a timer.
                nextSong = (sampleRate * (if (kind == 4) between(18f, 40f) else between(4f, 13f))).toInt()
            }
            lpState += dull * (y - lpState)
            return lpState * gain
        }
    }

    /** Tests only: [count] songs from every bird, each note as [length ms, start Hz, end Hz, vibrato Hz]. */
    internal fun sampleNotes(count: Int): List<FloatArray> = birds.flatMap { bird ->
        List(count) { bird.song() }.flatten().map { n -> floatArrayOf(n.length * 1000f / sampleRate, n.f0, n.f1, n.vibHz) }
    }

    private val birds = listOf(
        Bird(0, 0.30f, 0.35f),
        Bird(1, 0.75f, 0.30f),
        Bird(2, 0.15f, 0.70f),
        Bird(3, 0.60f, 0.45f),
        Bird(4, 0.85f, 0.95f),
    )

    // Light Schroeder reverb: four damped combs and two all-passes per ear, a little apart.
    private inner class Reverb(scale: Float) {
        private val combs = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray((it * scale * sampleRate / 44100).toInt()) }
        private val idx = IntArray(4)
        private val damp = FloatArray(4)
        private val aps = intArrayOf(556, 441).map { FloatArray((it * scale * sampleRate / 44100).toInt()) }
        private val apIdx = IntArray(2)
        fun process(x: Float): Float {
            var sum = 0f
            for (c in 0 until 4) {
                val buf = combs[c]
                val out = buf[idx[c]]
                damp[c] = out * 0.6f + damp[c] * 0.4f
                buf[idx[c]] = x + damp[c] * 0.76f
                idx[c] = (idx[c] + 1) % buf.size
                sum += out
            }
            var y = sum * 0.25f
            for (a in 0 until 2) {
                val buf = aps[a]
                val b = buf[apIdx[a]]
                buf[apIdx[a]] = y + b * 0.5f
                y = b - y * 0.5f
                apIdx[a] = (apIdx[a] + 1) % buf.size
            }
            return y
        }
    }
    private val verbL = Reverb(1f)
    private val verbR = Reverb(1.07f)

    override fun frame() {
        val b = breeze.next()
        w1L += breezeA * (noise() - w1L); w1R += breezeA * (noise() - w1R)
        w2L += breezeB * (noise() - w2L); w2R += breezeB * (noise() - w2R)
        var l = b * (0.26f * w1L + 0.04f * w2L)
        var r = b * (0.26f * w1R + 0.04f * w2R)
        var dryL = 0f; var dryR = 0f
        for (bird in birds) {
            val y = bird.next() * 0.55f
            dryL += y * (1 - bird.pan); dryR += y * bird.pan
        }
        l += dryL + 0.35f * verbL.process(dryL + dryR * 0.3f)
        r += dryR + 0.35f * verbR.process(dryR + dryL * 0.3f)
        left = l; right = r
    }
}

/** Shortest note and slowest glide any bird may sing: long, slow notes calm; quick chirps startle. */
internal const val MIN_NOTE_MS = 150f
internal const val MAX_GLIDE_HZ_PER_SEC = 6000f
internal const val MAX_VIBRATO_HZ = 8f

/** Two soundscapes at once (rain with birds over it), each a little lower so the sum isn't louder. */
class Mix(private val a: Soundscape, private val gainA: Float, private val b: Soundscape, private val gainB: Float) :
    Soundscape(a.sampleRate, 0) {
    private var bufA = FloatArray(0)
    private var bufB = FloatArray(0)

    override fun render(out: FloatArray, frames: Int) {
        if (bufA.size < frames * 2) { bufA = FloatArray(frames * 2); bufB = FloatArray(frames * 2) }
        a.render(bufA, frames)
        b.render(bufB, frames)
        for (i in 0 until frames * 2) out[i] = soft(bufA[i] * gainA + bufB[i] * gainB)
    }

    override fun frame() {}
}

/**
 * A gain that glides to its target instead of jumping (a jump is a click). Used for the fade in
 * at the start of a sit, the fade out at the end and on pause, and for ducking under a bell.
 */
class GainRamp(private val sampleRate: Int, initial: Float = 0f) {
    var value = initial
        private set
    private var target = initial
    private var start = initial
    private var total = 0
    private var left = 0

    fun to(target: Float, rampMs: Int) {
        start = value
        this.target = target
        total = max(1, rampMs * sampleRate / 1000)
        left = total
    }

    /** Computed from the start each time rather than summed, so it lands exactly, with no last step. */
    fun next(): Float {
        if (left > 0) {
            left--
            value = start + (target - start) * (total - left).toFloat() / total
        }
        return value
    }

    val settled: Boolean get() = left == 0
}

/** Volume slider position (0..1) to a gain that sounds even across its travel. */
fun sliderGain(x: Float): Float = if (x <= 0f) 0f else exp(ln(100f) * (x.coerceAtMost(1f) - 1f))
