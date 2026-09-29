package com.earmark.core.player

import com.earmark.core.model.Book
import com.earmark.core.text.SpeechNormalizer

/** One sentence handed to a speech engine. [id] encodes a generation so stale callbacks can be dropped. */
data class Utterance(
    val id: String,
    val sentenceIndex: Int,
    val paragraph: Int,
    val text: String,
    /** Structure a narrator performs with pauses: headings, paragraph and chapter ends. */
    val isHeading: Boolean = false,
    val endsParagraph: Boolean = false,
    val endsChapter: Boolean = false,
)

/**
 * What the platform voice must do. Implementations: Android system TTS, ElevenLabs/OpenAI
 * cloud voices. They report back through [PlaybackController.onUtteranceStarted] and
 * [PlaybackController.onUtteranceDone] (from any thread).
 */
interface SpeechEngine {
    /** Queue [utterances]; when [flush] is true, drop anything queued or playing first. */
    fun speak(utterances: List<Utterance>, flush: Boolean)
    fun stop()
    fun setSpeed(speed: Float)
}

enum class PlaybackStatus { IDLE, PLAYING, PAUSED, FINISHED }

data class PlayerState(
    val position: Int,
    val status: PlaybackStatus,
    val speed: Float,
    val sleepDeadlineMillis: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
)

/**
 * Engine-agnostic playback brain: position, queueing with lookahead, stale-callback
 * protection, speed, sleep timers, time-based skipping and "which sentence did the user mean
 * when they pressed the mic" anchoring.
 */
class PlaybackController(
    private val book: Book,
    private val engine: SpeechEngine,
    private val normalizer: SpeechNormalizer = SpeechNormalizer(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** How many sentences to keep queued ahead of the one playing (gap-free playback). */
    private val lookahead: Int = 3,
    startPosition: Int = 0,
) {
    var listener: ((PlayerState) -> Unit)? = null

    /**
     * Word-level progress from engines that report it (Android TTS onRangeStart, ElevenLabs
     * character timestamps): sentence index plus a character range in that sentence's speech text.
     */
    var rangeListener: ((sentence: Int, speechStart: Int, speechEnd: Int) -> Unit)? = null

    private var position = book.clampIndex(startPosition)
    private var status = if (book.isEmpty) PlaybackStatus.FINISHED else PlaybackStatus.IDLE
    private var speed = 1.0f
    private var generation = 0
    private var lastQueued = -1
    private var currentStartedAt = 0L
    private var sleepDeadline: Long? = null
    private var sleepAtChapterEnd = false
    private var stopAfter: Int? = null

    @get:Synchronized
    val state: PlayerState
        get() = PlayerState(position, status, speed, sleepDeadline, sleepAtChapterEnd)

    @Synchronized
    fun play() {
        if (book.isEmpty) return
        if (status == PlaybackStatus.FINISHED) position = 0
        status = PlaybackStatus.PLAYING
        restartAt(position)
    }

    @Synchronized
    fun pause() {
        if (status != PlaybackStatus.PLAYING) return
        generation++
        lastQueued = -1
        engine.stop()
        status = PlaybackStatus.PAUSED
        notifyChanged()
    }

    @Synchronized
    fun togglePlayPause() = if (status == PlaybackStatus.PLAYING) pause() else play()

    @Synchronized
    fun seekTo(index: Int) {
        if (book.isEmpty) return
        position = book.clampIndex(index)
        if (status == PlaybackStatus.FINISHED) status = PlaybackStatus.PAUSED
        if (status == PlaybackStatus.PLAYING) restartAt(position) else notifyChanged()
    }

    fun skipSentences(delta: Int) = seekTo(state.position + delta)

    @Synchronized
    fun nextChapter() {
        val ch = book.chapterOf(position) ?: return
        val next = book.chapters.getOrNull(ch.index + 1) ?: return
        seekTo(next.firstSentence)
    }

    /** Like a music player: restart the chapter unless we're within its first few sentences. */
    @Synchronized
    fun previousChapter() {
        val ch = book.chapterOf(position) ?: return
        if (position - ch.firstSentence > 2 || ch.index == 0) seekTo(ch.firstSentence)
        else seekTo(book.chapters[ch.index - 1].firstSentence)
    }

    @Synchronized
    fun goToChapter(chapterNumber: Int): Boolean {
        val ch = book.chapters.getOrNull(chapterNumber - 1) ?: return false
        seekTo(ch.firstSentence)
        return true
    }

    @Synchronized
    fun goToPage(page: Int): Boolean {
        if (page < 1 || page > book.pageCount) return false
        val idx = book.firstSentenceOfPage(page) ?: return false
        seekTo(idx)
        return true
    }

    @Synchronized
    fun nextParagraph() {
        val range = book.paragraphRange(position)
        seekTo(range.last + 1)
    }

    @Synchronized
    fun previousParagraph() {
        val range = book.paragraphRange(position)
        if (position - range.first > 1 || range.first == 0) seekTo(range.first)
        else seekTo(book.paragraphRange(range.first - 1).first)
    }

    /** Moves back by roughly [seconds] of audio at the current speed. */
    @Synchronized
    fun rewindSeconds(seconds: Int) {
        var idx = position
        var acc = 0.0
        while (idx > 0 && acc < seconds) {
            idx--
            acc += estimateSeconds(idx)
        }
        seekTo(idx)
    }

    @Synchronized
    fun forwardSeconds(seconds: Int) {
        var idx = position
        var acc = 0.0
        while (idx < book.lastIndex && acc < seconds) {
            acc += estimateSeconds(idx)
            idx++
        }
        seekTo(idx)
    }

    @Synchronized
    fun setSpeed(value: Float) {
        val clamped = (Math.round(value.coerceIn(MIN_SPEED, MAX_SPEED) * 100) / 100f)
        if (clamped == speed) return
        speed = clamped
        engine.setSpeed(speed)
        // Re-speak the current sentence so the change is immediate, not three sentences later.
        if (status == PlaybackStatus.PLAYING) restartAt(position) else notifyChanged()
    }

    @Synchronized
    fun setSleepTimer(minutes: Int) {
        sleepDeadline = clock() + minutes * 60_000L
        sleepAtChapterEnd = false
        stopAfter = null
        notifyChanged()
    }

    @Synchronized
    fun sleepAtEndOfChapter() {
        sleepDeadline = null
        sleepAtChapterEnd = true
        stopAfter = book.chapterOf(position)?.lastSentence
        if (status == PlaybackStatus.PLAYING) restartAt(position) else notifyChanged()
    }

    @Synchronized
    fun cancelSleepTimer() {
        sleepDeadline = null
        sleepAtChapterEnd = false
        stopAfter = null
        notifyChanged()
    }

    /**
     * The sentence the user meant when they pressed the mic / headset button. People react to
     * what they just heard, so in the first moments of a new sentence "this" means the previous one.
     */
    @Synchronized
    fun anchorForInteraction(graceMillis: Long = 1500): Int {
        if (status == PlaybackStatus.PLAYING && position > 0 && clock() - currentStartedAt < graceMillis) return position - 1
        return position
    }

    @Synchronized
    fun onUtteranceStarted(id: String) {
        val (gen, index) = parseId(id) ?: return
        if (gen != generation) return
        position = index
        currentStartedAt = clock()
        topUpQueue()
        notifyChanged()
    }

    /** The engine is speaking characters [start, end) of utterance [id]'s speech text. */
    fun onUtteranceRange(id: String, start: Int, end: Int) {
        val index: Int
        synchronized(this) {
            val (gen, idx) = parseId(id) ?: return
            if (gen != generation || status != PlaybackStatus.PLAYING) return
            // A range for a sentence we never saw start means its start callback got lost.
            if (idx != position) {
                position = idx
                currentStartedAt = clock()
                topUpQueue()
                notifyChanged()
            }
            index = idx
        }
        rangeListener?.invoke(index, start, end)
    }

    @Synchronized
    fun onUtteranceDone(id: String) {
        val (gen, index) = parseId(id) ?: return
        if (gen != generation || status != PlaybackStatus.PLAYING) return
        val deadline = sleepDeadline
        if (deadline != null && clock() >= deadline) {
            // Stop at a sentence boundary rather than mid-word.
            sleepDeadline = null
            position = book.clampIndex(index + 1)
            generation++
            lastQueued = -1
            engine.stop()
            status = PlaybackStatus.PAUSED
            notifyChanged()
            return
        }
        val limit = stopAfter
        if (limit != null && index >= limit) {
            stopAfter = null
            sleepAtChapterEnd = false
            position = book.clampIndex(index + 1)
            generation++
            lastQueued = -1
            status = PlaybackStatus.PAUSED
            notifyChanged()
            return
        }
        if (index >= book.lastIndex) {
            generation++
            lastQueued = -1
            status = PlaybackStatus.FINISHED
            notifyChanged()
            return
        }
        if (index >= lastQueued) {
            // Engine drained without reporting a start for the next item (e.g. it never got one).
            position = index + 1
            restartAt(position)
            return
        }
        if (index == position) {
            // Move the highlight on when a sentence ends, not only when the next one reports its
            // start: some engines deliver start callbacks late or never, which left the highlight
            // stuck while the voice kept reading.
            position = index + 1
            currentStartedAt = clock()
            topUpQueue()
            notifyChanged()
        }
    }

    /** Engine could not speak (e.g. network voice failed). Pause instead of skipping text. */
    @Synchronized
    fun onUtteranceError(id: String) {
        val (gen, index) = parseId(id) ?: return
        if (gen != generation) return
        position = index
        generation++
        lastQueued = -1
        status = PlaybackStatus.PAUSED
        notifyChanged()
    }

    fun speechTextFor(index: Int): String {
        val s = book.sentences[index]
        val text = normalizer.normalize(s.text)
        // Headings rarely end in punctuation; add one so the voice pauses after them.
        return if (s.isHeading && text.isNotEmpty() && text.last().isLetterOrDigit()) "$text." else text
    }

    /** Rough duration: ~15 characters per second at 1x, plus a short pause per sentence. */
    fun estimateSeconds(index: Int): Double = (book.sentences[index].text.length / 15.0 + 0.4) / speed

    private fun restartAt(index: Int) {
        // "Stop at the end of the chapter" follows the listener if they jump elsewhere.
        if (sleepAtChapterEnd) stopAfter = book.chapterOf(index)?.lastSentence
        generation++
        lastQueued = index - 1
        position = index
        currentStartedAt = clock()
        val batch = buildBatch(index, index + lookahead)
        engine.speak(batch, flush = true)
        notifyChanged()
    }

    private fun topUpQueue() {
        if (lastQueued < 0) return
        val target = position + lookahead
        if (lastQueued >= target) return
        val batch = buildBatch(lastQueued + 1, target)
        if (batch.isNotEmpty()) engine.speak(batch, flush = false)
    }

    private fun buildBatch(from: Int, toInclusive: Int): List<Utterance> {
        val end = minOf(toInclusive, book.lastIndex, stopAfter ?: Int.MAX_VALUE)
        if (from > end) return emptyList()
        val out = ArrayList<Utterance>(end - from + 1)
        for (i in from..end) {
            val text = speechTextFor(i)
            val s = book.sentences[i]
            val next = book.sentences.getOrNull(i + 1)
            out += Utterance(
                id = "$generation:$i",
                sentenceIndex = i,
                paragraph = s.paragraph,
                text = text.ifBlank { s.text },
                isHeading = s.isHeading,
                endsParagraph = next == null || next.paragraph != s.paragraph,
                endsChapter = next == null || next.chapter != s.chapter,
            )
        }
        lastQueued = end
        return out
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val (gen, idx) = parseUtteranceId(id) ?: return null
        if (idx !in book.sentences.indices) return null
        return gen to idx
    }

    private fun notifyChanged() {
        listener?.invoke(PlayerState(position, status, speed, sleepDeadline, sleepAtChapterEnd))
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /** "generation:sentenceIndex" -> (generation, sentenceIndex). */
        fun parseUtteranceId(id: String): Pair<Int, Int>? {
            val gen = id.substringBefore(':', "").toIntOrNull() ?: return null
            val idx = id.substringAfter(':', "").toIntOrNull() ?: return null
            return gen to idx
        }
    }
}
