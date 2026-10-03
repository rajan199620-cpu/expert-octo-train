package com.ankiwatch.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * What the watch shows next from a downloaded deck while the phone is away, following
 * AnkiDroid's own session as closely as the downloaded data allows:
 *
 * - Cards come in AnkiDroid's order.
 * - An answer whose button label is under a day ("1m" for Again on a new card, "10m" for
 *   Good) puts the card in a learning step: it comes back after that delay, ahead of fresh
 *   cards once it is due.
 * - When only such cards are left, one due within the learn-ahead limit (20 minutes, as in
 *   AnkiDroid) is shown early; otherwise the queue says when the next one is due.
 *
 * Only a card's first answer has an exact label. After that the card follows the steps the
 * labels reveal: Again starts over, Good moves to the second step once (when the first Good
 * label was under a day), then graduates. This only decides what the watch shows next: the
 * phone replays the real grades through AnkiDroid's scheduler when it is back, so what
 * AnkiDroid records never depends on these guesses.
 */
class OfflineQueue(val pack: OfflinePack, state: State = State()) {

    /** A card that will come back: due at [dueAt], after a step of [delayMs]. */
    data class Waiting(val key: CardKey, val dueAt: Long, val delayMs: Long, val step: Int)

    data class State(
        /** Cards answered at least once. */
        val seen: Set<CardKey> = emptySet(),
        /** Cards finished for this download, here or elsewhere. */
        val done: Set<CardKey> = emptySet(),
        /** Cards coming back, in the order they were sent back. */
        val waiting: List<Waiting> = emptyList(),
        /** Answers given from this download. */
        val answers: Int = 0
    )

    sealed class Next {
        data class Show(val card: PackCard, val repeat: Boolean) : Next()
        data class Wait(val until: Long, val count: Int) : Next()
        object Finished : Next()
    }

    /** The pack's cards, each once, in order. */
    val cards: List<PackCard> = pack.cards.distinctBy { it.key }
    private val byKey: Map<CardKey, PackCard> = cards.associateBy { it.key }

    var state: State = sanitize(state)
        private set

    /** Fresh cards still to come, plus cards coming back. */
    val remaining: Int
        get() = cards.count { it.key !in state.seen && it.key !in state.done } + state.waiting.size

    operator fun contains(key: CardKey): Boolean = key in byKey

    /** The next thing to show at [now] (epoch millis). Changes nothing. */
    fun next(now: Long, learnAheadMs: Long = LEARN_AHEAD_MS): Next {
        val due = state.waiting.filter { it.dueAt <= now }.minByOrNull { it.dueAt }
        if (due != null) return Next.Show(byKey.getValue(due.key), repeat = true)
        val fresh = cards.firstOrNull { it.key !in state.seen && it.key !in state.done }
        if (fresh != null) return Next.Show(fresh, repeat = false)
        val soonest = state.waiting.minByOrNull { it.dueAt } ?: return Next.Finished
        if (soonest.dueAt <= now + learnAheadMs) return Next.Show(byKey.getValue(soonest.key), repeat = true)
        return Next.Wait(soonest.dueAt, state.waiting.size)
    }

    /** The card that comes back soonest, whenever that is; null if none is waiting. */
    fun showNow(): Next.Show? =
        state.waiting.minByOrNull { it.dueAt }?.let { Next.Show(byKey.getValue(it.key), repeat = true) }

    /**
     * Records [ease] (a grade 1–4 or [Wire.EASE_BURY]) for [key] at [now]. Unknown cards are
     * ignored: an answer for a card that isn't in this pack decides nothing here.
     */
    fun answer(key: CardKey, ease: Int, now: Long) {
        require(Wire.isAnswerEase(ease)) { "not an answer: $ease" }
        val card = byKey[key] ?: return
        val previous = state.waiting.firstOrNull { it.key == key }
        val rest = state.waiting.filterNot { it.key == key }
        val seen = state.seen + key
        val answers = state.answers + 1

        val comeBack: Waiting? = if (ease == Wire.EASE_BURY) {
            null
        } else {
            val grade = Grade.of(ease, card.buttonCount)
            val againMs = labelMs(card, Grade.AGAIN)?.takeIf { it < IntervalLabel.DAY_MS } ?: DEFAULT_AGAIN_MS
            val step = previous?.step ?: 0
            val firstAnswer = previous == null && key !in state.seen
            when {
                firstAnswer -> {
                    val delay = labelMs(card, grade) ?: if (grade == Grade.AGAIN) DEFAULT_AGAIN_MS else null
                    if (delay != null && delay < IntervalLabel.DAY_MS) {
                        Waiting(key, now + delay, delay, if (grade == Grade.GOOD || grade == Grade.EASY) 1 else 0)
                    } else null
                }
                grade == Grade.AGAIN -> Waiting(key, now + againMs, againMs, 0)
                grade == Grade.HARD -> {
                    val delay = previous?.delayMs ?: againMs
                    Waiting(key, now + delay, delay, step)
                }
                grade == Grade.GOOD && step == 0 -> {
                    val delay = labelMs(card, Grade.GOOD)?.takeIf { it < IntervalLabel.DAY_MS }
                    delay?.let { Waiting(key, now + it, it, 1) }
                }
                else -> null // Good from the second step, or Easy: graduated
            }
        }
        state = State(
            seen = seen,
            done = if (comeBack == null) state.done + key else state.done - key,
            waiting = if (comeBack == null) rest else rest + comeBack,
            answers = answers
        )
    }

    /** [key] was answered elsewhere (online): it no longer needs showing here. */
    fun markDone(key: CardKey) {
        if (key !in byKey) return
        state = state.copy(done = state.done + key, waiting = state.waiting.filterNot { it.key == key })
    }

    private fun labelMs(card: PackCard, grade: Grade): Long? =
        grade.ease(card.buttonCount)?.let { card.nextReviewTimes.getOrNull(it - 1) }?.let(IntervalLabel::millis)

    /** Drops whatever in a stored state no longer matches the pack, or itself. */
    private fun sanitize(s: State): State {
        val done = s.done.filterTo(LinkedHashSet()) { it in byKey }
        val waiting = s.waiting.filter { it.key in byKey && it.key !in done && it.delayMs >= 0 }.distinctBy { it.key }
        val seen = LinkedHashSet<CardKey>()
        s.seen.filterTo(seen) { it in byKey }
        waiting.mapTo(seen) { it.key }
        return State(seen, done, waiting, s.answers.coerceAtLeast(0))
    }

    /** The four grades, whichever button numbers a card uses for them. */
    private enum class Grade {
        AGAIN, HARD, GOOD, EASY;

        fun ease(buttonCount: Int): Int? = when (buttonCount) {
            2 -> when (this) { AGAIN -> 1; GOOD -> 2; else -> null }
            3 -> when (this) { AGAIN -> 1; GOOD -> 2; EASY -> 3; else -> null }
            else -> ordinal + 1
        }

        companion object {
            fun of(ease: Int, buttonCount: Int): Grade =
                entries.firstOrNull { it.ease(buttonCount) == ease } ?: GOOD
        }
    }

    companion object {
        /** AnkiDroid's default learn-ahead limit. */
        const val LEARN_AHEAD_MS = 20 * IntervalLabel.MINUTE_MS

        /** Again on a card whose labels don't say: AnkiDroid's default first step. */
        const val DEFAULT_AGAIN_MS = IntervalLabel.MINUTE_MS

        private const val MAGIC = 0x41575131 // "AWQ1"
        private const val VERSION = 1
        private const val MAX_KEYS = OfflinePackCodec.MAX_CARDS

        /** A state as bytes, tagged with the pack it belongs to. */
        fun encodeState(packId: Long, state: State): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION)
                out.writeLong(packId)
                out.writeInt(state.answers)
                fun keys(set: Collection<CardKey>) {
                    out.writeInt(set.size)
                    set.forEach { out.writeLong(it.noteId); out.writeInt(it.cardOrd) }
                }
                keys(state.seen)
                keys(state.done)
                out.writeInt(state.waiting.size)
                state.waiting.forEach {
                    out.writeLong(it.key.noteId); out.writeInt(it.key.cardOrd)
                    out.writeLong(it.dueAt); out.writeLong(it.delayMs); out.writeInt(it.step)
                }
            }
            return bytes.toByteArray()
        }

        /**
         * The state stored for [packId], or null when [bytes] belong to another pack or are
         * damaged: the caller then starts the pack afresh rather than failing.
         */
        fun decodeState(packId: Long, bytes: ByteArray): State? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
                if (input.readLong() != packId) return null
                val answers = input.readInt()
                fun keys(): Set<CardKey>? {
                    val n = input.readInt()
                    if (n < 0 || n > MAX_KEYS) return null
                    return (0 until n).mapTo(LinkedHashSet()) { CardKey(input.readLong(), input.readInt()) }
                }
                val seen = keys() ?: return null
                val done = keys() ?: return null
                val n = input.readInt()
                if (n < 0 || n > MAX_KEYS) return null
                val waiting = List(n) {
                    Waiting(CardKey(input.readLong(), input.readInt()), input.readLong(), input.readLong(), input.readInt())
                }
                if (input.read() != -1) return null
                State(seen, done, waiting, answers)
            }
        } catch (e: EOFException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
