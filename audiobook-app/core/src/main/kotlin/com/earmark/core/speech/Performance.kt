package com.earmark.core.speech

/**
 * A piece of an utterance spoken with its own delivery. [offset] is where [text] starts in the
 * utterance's speech text, so word positions reported per piece can be mapped back.
 */
data class SpeechSegment(
    val text: String,
    val offset: Int,
    val pitch: Float = 1f,
    val rate: Float = 1f,
    val pauseAfterMillis: Int = 0,
)

/**
 * Makes a flat on-device voice perform a little like a narrator. Phone TTS engines read every
 * sentence with the same melody and no breathing room; human narrators pause between
 * paragraphs and chapters, slow down for headings, give dialogue a different colour, and lift
 * questions and exclamations. Android's TTS can only change pitch and rate per request, so the
 * planner splits each sentence into narration and dialogue pieces and assigns each a delivery.
 */
object ProsodyPlanner {
    const val DIALOGUE_PITCH = 1.07f
    const val DIALOGUE_RATE = 1.02f
    const val QUESTION_PITCH = 1.04f
    const val EXCLAIM_PITCH = 1.05f
    const val EXCLAIM_RATE = 1.04f
    const val HEADING_RATE = 0.93f
    const val HEADING_PAUSE = 750
    const val PARAGRAPH_PAUSE = 450
    const val CHAPTER_PAUSE = 1100
    const val ELLIPSIS_PAUSE = 280
    /** A breath after ";" and ":" so long legal clauses don't run together. */
    const val CLAUSE_PAUSE = 200
    private const val MAX_SEGMENTS = 10

    fun plan(
        speech: String,
        isHeading: Boolean = false,
        endsParagraph: Boolean = false,
        endsChapter: Boolean = false,
        perform: Boolean = true,
    ): List<SpeechSegment> {
        if (speech.isBlank()) return listOf(SpeechSegment(speech, 0))
        if (!perform) return listOf(trimmed(speech, 0, speech.length) ?: SpeechSegment(speech, 0))
        if (isHeading) {
            val seg = trimmed(speech, 0, speech.length) ?: SpeechSegment(speech, 0)
            return listOf(seg.copy(rate = HEADING_RATE, pitch = 0.98f, pauseAfterMillis = if (endsChapter) CHAPTER_PAUSE else HEADING_PAUSE))
        }

        val pieces = ArrayList<SpeechSegment>()
        for ((start, end, dialogue) in splitDialogue(speech)) {
            if (dialogue) {
                trimmed(speech, start, end)?.let { pieces += it.copy(pitch = DIALOGUE_PITCH, rate = DIALOGUE_RATE) }
            } else {
                pieces += splitPauses(speech, start, end)
            }
        }
        var segs = mergeTiny(speech, pieces).ifEmpty { listOf(SpeechSegment(speech, 0)) }
        if (segs.size > MAX_SEGMENTS) segs = listOf(trimmed(speech, 0, speech.length) ?: SpeechSegment(speech, 0))

        // Questions lift and exclamations brighten wherever they end (a quote or the sentence).
        segs = segs.map { seg ->
            when (terminal(seg.text)) {
                '?' -> seg.copy(pitch = seg.pitch * QUESTION_PITCH)
                '!' -> seg.copy(pitch = seg.pitch * EXCLAIM_PITCH, rate = seg.rate * EXCLAIM_RATE)
                else -> seg
            }
        }
        val endPause = when {
            endsChapter -> CHAPTER_PAUSE
            endsParagraph -> PARAGRAPH_PAUSE
            else -> 0
        }
        val last = segs.last()
        return segs.dropLast(1) + last.copy(pauseAfterMillis = maxOf(last.pauseAfterMillis, endPause))
    }

    private val PAUSE_POINT = Regex("""(\.\.\.|…|[;:])(?=\s+\S)""")

    private data class Span(val start: Int, val end: Int, val dialogue: Boolean)

    /** Double-quoted spans of at least two words are dialogue; everything else is narration. */
    private fun splitDialogue(s: String): List<Span> {
        val out = ArrayList<Span>()
        var cursor = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val close = when (c) {
                '“' -> s.indexOf('”', i + 1)
                '"' -> s.indexOf('"', i + 1)
                else -> -1
            }
            if (close > i) {
                val inner = s.substring(i + 1, close).trim()
                if (inner.contains(' ')) {
                    if (i > cursor) out += Span(cursor, i, false)
                    out += Span(i, close + 1, true)
                    cursor = close + 1
                }
                i = close + 1
                continue
            }
            i++
        }
        if (cursor < s.length) out += Span(cursor, s.length, false)
        return out
    }

    /**
     * "He waited... Then he ran" gets a beat of silence after the ellipsis, and "; " or ": " a
     * short breath. Phone voices barely pause there, so clause-heavy text blurs together.
     */
    private fun splitPauses(s: String, start: Int, end: Int): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        var from = start
        for (m in PAUSE_POINT.findAll(s.substring(start, end))) {
            val cut = start + m.range.last + 1
            val pause = if (m.value == ";" || m.value == ":") CLAUSE_PAUSE else ELLIPSIS_PAUSE
            trimmed(s, from, cut)?.let { out += it.copy(pauseAfterMillis = pause) }
            from = cut
        }
        trimmed(s, from, end)?.let { out += it }
        return out
    }

    /** Pieces with almost no letters (a lone dash or quote mark) join the previous piece. */
    private fun mergeTiny(s: String, pieces: List<SpeechSegment>): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        for (p in pieces) {
            val prev = out.lastOrNull()
            val tiny = p.text.count { it.isLetterOrDigit() } < 2
            if (prev != null && tiny) {
                // Pieces are slices of the same string, so the merged piece is one slice too.
                val merged = s.substring(prev.offset, p.offset + p.text.length)
                out[out.lastIndex] = prev.copy(text = merged, pauseAfterMillis = maxOf(prev.pauseAfterMillis, p.pauseAfterMillis))
            } else {
                out += p
            }
        }
        return out
    }

    private fun trimmed(s: String, start: Int, end: Int): SpeechSegment? {
        var a = start
        var b = end
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        return if (a < b) SpeechSegment(s.substring(a, b), a) else null
    }

    private fun terminal(text: String): Char? =
        text.trimEnd().trimEnd('"', '”', '’', '\'', ')', ']').lastOrNull()?.takeIf { it == '?' || it == '!' }
}

/** Broad emotional colour of a passage, used to direct steerable voices (OpenAI). */
enum class Mood { NEUTRAL, TENSE, SAD, JOYFUL, ANGRY, TENDER, MYSTERIOUS, EXPLANATORY }

object MoodDetector {
    private val LEXICON: Map<Mood, List<String>> = mapOf(
        Mood.TENSE to listOf("suddenly", "scream", "screamed", "danger", "blood", "gun", "fear", "afraid", "panic", "storm", "thunder", "crash", "chase", "fled", "flee", "dead", "died", "kill", "killed", "knife", "trembl", "pounded", "race", "raced", "desperate", "hurry", "run", "ran", "sirens", "trapped"),
        Mood.SAD to listOf("tears", "wept", "weep", "cry", "cried", "crying", "grief", "mourn", "alone", "lonely", "sorrow", "funeral", "goodbye", "missed", "broken", "loss", "lost", "sobbed", "regret", "grave", "dying"),
        Mood.JOYFUL to listOf("laugh", "laughed", "laughing", "smile", "smiled", "joy", "delight", "delighted", "happy", "celebrate", "cheered", "grinned", "wonderful", "hooray", "beautiful", "sunlight", "danced"),
        Mood.ANGRY to listOf("angry", "furious", "rage", "raged", "yelled", "slammed", "hate", "hated", "snapped", "glared", "shouted", "betrayed", "damn", "liar", "fury"),
        Mood.TENDER to listOf("love", "loved", "gently", "softly", "kiss", "kissed", "embrace", "embraced", "whisper", "whispered", "tender", "hand in hand", "held her", "held him", "darling", "dear"),
        Mood.MYSTERIOUS to listOf("shadow", "shadows", "silence", "strange", "dark", "darkness", "secret", "fog", "mist", "unknown", "hidden", "mysterious", "creak", "creaked", "footsteps", "midnight", "lantern"),
        Mood.EXPLANATORY to listOf("section", "sections", "shall", "means", "defined", "definition", "therefore", "for example", "punishment", "provided that", "clause", "sub-section", "act", "court", "evidence", "principle", "theory", "chapter", "whereas", "hereinafter", "offence", "liable"),
    )

    fun detect(text: String): Mood {
        val t = " " + text.lowercase() + " "
        val scores = LEXICON.mapValues { (_, words) ->
            words.sumOf { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}""").findAll(t).count() }.toDouble()
        }.toMutableMap()
        val exclamations = text.count { it == '!' }
        if (exclamations > 0) {
            scores[Mood.TENSE] = (scores[Mood.TENSE] ?: 0.0) + exclamations * 0.5
            scores[Mood.JOYFUL] = (scores[Mood.JOYFUL] ?: 0.0) + exclamations * 0.3
        }
        val best = scores.maxByOrNull { it.value } ?: return Mood.NEUTRAL
        val words = text.split(Regex("""\s+""")).size.coerceAtLeast(1)
        // Needs a real signal: at least two cues, or one cue in a short passage.
        return if (best.value >= 2 || (best.value >= 1 && words < 25)) best.key else Mood.NEUTRAL
    }

    fun directionFor(mood: Mood): String = when (mood) {
        Mood.NEUTRAL -> "Keep an engaged, conversational storytelling tone."
        Mood.TENSE -> "This passage is tense: quicken a little, tighten the voice and let the urgency build."
        Mood.SAD -> "This passage is sad: slow down, soften and lower the voice, let pauses linger."
        Mood.JOYFUL -> "This passage is joyful: brighten the voice with warmth and a hint of a smile."
        Mood.ANGRY -> "This passage carries anger: give the dialogue a hard, clipped edge without shouting."
        Mood.TENDER -> "This passage is tender: speak softly and intimately, close to the listener."
        Mood.MYSTERIOUS -> "This passage is mysterious: lower the voice, slow down and leave suspenseful pauses."
        Mood.EXPLANATORY -> "This is explanatory text: sound like an engaging teacher, stress the key terms, and pause clearly between clauses."
    }
}
