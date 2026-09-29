package com.earmark.core.speech

import com.earmark.core.player.Utterance

/**
 * Groups sentences into synthesis requests for cloud voices.
 *
 * Synthesising sentence by sentence sounds choppy (every sentence starts with a fresh
 * "reading voice" intonation); synthesising a whole chapter makes the first audio arrive
 * too late. Paragraph-sized chunks with previous/next context (ElevenLabs request stitching)
 * give natural prosody with ~1-2 s start latency.
 */
data class SpeechChunk(
    val utterances: List<Utterance>,
    val text: String,
    /** Start offset of each utterance's text within [text]. */
    val offsets: List<Int>,
    val previousText: String?,
    val nextText: String?,
)

object ChunkPlanner {
    fun plan(utterances: List<Utterance>, maxChars: Int = 900, contextChars: Int = 300): List<SpeechChunk> {
        require(maxChars > 0)
        val groups = ArrayList<List<Utterance>>()
        var current = ArrayList<Utterance>()
        var len = 0
        for (u in utterances) {
            val newParagraph = current.isNotEmpty() && current.last().paragraph != u.paragraph
            val tooLong = current.isNotEmpty() && len + 1 + u.text.length > maxChars
            if (newParagraph || tooLong) {
                groups += current
                current = ArrayList()
                len = 0
            }
            if (current.isNotEmpty()) len += 1
            current += u
            len += u.text.length
        }
        if (current.isNotEmpty()) groups += current

        return groups.mapIndexed { gi, group ->
            val offsets = ArrayList<Int>()
            val sb = StringBuilder()
            for (u in group) {
                if (sb.isNotEmpty()) sb.append(' ')
                offsets += sb.length
                sb.append(u.text)
            }
            val prev = groups.getOrNull(gi - 1)?.joinToString(" ") { it.text }?.takeLast(contextChars)
            val next = groups.getOrNull(gi + 1)?.joinToString(" ") { it.text }?.take(contextChars)
            SpeechChunk(group, sb.toString(), offsets, prev, next)
        }
    }
}

object AlignmentMapper {
    /**
     * Converts per-character start times (ElevenLabs "with-timestamps") into the start time of
     * each utterance in [chunk], so highlighting follows the audio exactly.
     */
    fun utteranceStartMillis(chunk: SpeechChunk, characterStartSeconds: List<Double>): List<Long> {
        if (characterStartSeconds.isEmpty()) return estimateStartMillis(chunk, 0)
        return chunk.offsets.map { off ->
            val i = off.coerceIn(0, characterStartSeconds.lastIndex)
            (characterStartSeconds[i] * 1000).toLong()
        }
    }

    /** Without timestamps, assume constant speaking rate across the chunk. */
    fun estimateStartMillis(chunk: SpeechChunk, totalMillis: Long): List<Long> {
        val total = chunk.text.length.coerceAtLeast(1)
        val duration = if (totalMillis > 0) totalMillis else (chunk.text.length / 15.0 * 1000).toLong()
        return chunk.offsets.map { off -> duration * off / total }
    }
}

/** What a full book costs to voice, shown before the user commits to a paid engine. */
object CostEstimator {
    data class Estimate(val characters: Long, val audioHours: Double, val usd: Double)

    enum class Voice(val label: String, val usdPerMillionChars: Double) {
        SYSTEM("On-device voice", 0.0),
        ELEVENLABS_MULTILINGUAL("ElevenLabs Multilingual v2 / v3", 100.0),
        ELEVENLABS_FLASH("ElevenLabs Flash / Turbo", 50.0),
        OPENAI_MINI_TTS("OpenAI gpt-4o-mini-tts (approx.)", 15.0),
    }

    fun estimate(characters: Long, voice: Voice, speed: Double = 1.0): Estimate {
        val words = characters / 5.7
        val hours = words / 155.0 / 60.0 / speed
        return Estimate(characters, hours, characters / 1_000_000.0 * voice.usdPerMillionChars)
    }
}
