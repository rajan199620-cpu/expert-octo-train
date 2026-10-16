package com.rajan.meditationtimer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
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
 * An endless soundscape generated sample by sample, so it never loops: birdsong repeating every
 * few minutes becomes something the mind notices and waits for. (Rain is a recording instead,
 * res/raw/rain.ogg: a wash of noise hides a long loop, and no synthesis came close to the real
 * thing.) Pure Kotlin, no Android, so it can be tested and rendered on any JVM.
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
            Ambience.BIRDS, Ambience.RAIN_AND_BIRDS -> Birds(sampleRate, seed)
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
