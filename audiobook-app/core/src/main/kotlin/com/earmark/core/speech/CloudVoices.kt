package com.earmark.core.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Audio for one [SpeechChunk], when each of its utterances starts, and (when the service
 * provides timestamps) when each character of the chunk text starts, for word highlighting.
 */
class SynthesizedAudio(
    val bytes: ByteArray,
    val mimeType: String,
    val utteranceStartMillis: List<Long>,
    val charStartMillis: List<Long> = emptyList(),
)

class VoiceException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { AUTH, QUOTA, RATE_LIMIT, NETWORK, BAD_REQUEST, SERVER }
}

interface CloudVoice {
    val name: String
    fun synthesize(chunk: SpeechChunk, speed: Float): SynthesizedAudio
}

/** Minimal JSON-over-HTTPS POST shared by the voice clients (HttpURLConnection works on JVM and Android). */
internal object HttpPost {
    fun send(url: String, headers: Map<String, String>, body: String, timeoutMillis: Int): ByteArray {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (e: IOException) {
            throw VoiceException(VoiceException.Kind.NETWORK, "Could not reach the voice service.", e)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.use { it.readBytes() }
            val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty().take(500)
            throw when (code) {
                401, 403 -> VoiceException(VoiceException.Kind.AUTH, "The voice service rejected the API key ($code).")
                402 -> VoiceException(VoiceException.Kind.QUOTA, "The voice service quota is used up.")
                429 -> if (err.contains("quota", ignoreCase = true)) {
                    VoiceException(VoiceException.Kind.QUOTA, "The voice service quota is used up.")
                } else {
                    VoiceException(VoiceException.Kind.RATE_LIMIT, "The voice service is rate limiting requests.")
                }
                in 400..499 -> VoiceException(VoiceException.Kind.BAD_REQUEST, "The voice service refused the request ($code): $err")
                else -> VoiceException(VoiceException.Kind.SERVER, "The voice service had an error ($code).")
            }
        } catch (e: SocketTimeoutException) {
            throw VoiceException(VoiceException.Kind.NETWORK, "The voice service timed out.", e)
        } catch (e: VoiceException) {
            throw e
        } catch (e: IOException) {
            throw VoiceException(VoiceException.Kind.NETWORK, "Network error talking to the voice service.", e)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * ElevenLabs "with-timestamps" synthesis: returns audio plus per-character timings, which is
 * what lets the app highlight the exact sentence (and word) being spoken with a cloud voice.
 *
 * [expressiveness] (0 = even, 1 = dramatic) lowers stability and raises style exaggeration.
 * The first version shipped stability 0.5 / style 0, ElevenLabs' most neutral delivery, which
 * listeners found flat. `eleven_v3` is the most emotional model but newer; if it rejects a
 * request, synthesis falls back to Multilingual v2 for the rest of the session.
 */
class ElevenLabsVoice(
    private val apiKey: String,
    private val voiceId: String,
    modelId: String = MULTILINGUAL_V2,
    private val expressiveness: Float = 0.6f,
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val timeoutMillis: Int = 30_000,
) : CloudVoice {
    override val name = "ElevenLabs"

    /** The model actually in use; changes if [MODEL_V3] is rejected. */
    @Volatile
    var modelId: String = modelId
        private set

    @Serializable
    private data class Alignment(
        val characters: List<String> = emptyList(),
        @SerialName("character_start_times_seconds") val starts: List<Double> = emptyList(),
    )

    @Serializable
    private data class Response(
        @SerialName("audio_base64") val audio: String,
        val alignment: Alignment? = null,
    )

    fun requestBody(chunk: SpeechChunk, speed: Float, model: String = modelId): JsonObject = buildJsonObject {
        val e = expressiveness.coerceIn(0f, 1f)
        val v3 = model == MODEL_V3
        put("text", chunk.text)
        put("model_id", model)
        if (!v3) {
            // Request stitching keeps intonation continuous across chunks (not offered for v3).
            chunk.previousText?.let { put("previous_text", it) }
            chunk.nextText?.let { put("next_text", it) }
        }
        putJsonObject("voice_settings") {
            if (v3) {
                // v3 exposes three stability presets: Creative (0), Natural (0.5), Robust (1).
                put("stability", if (e >= 0.67f) 0.0 else if (e >= 0.34f) 0.5 else 1.0)
            } else {
                put("stability", round2(0.65 - 0.4 * e))
                put("similarity_boost", 0.75)
                put("style", round2(0.55 * e))
                put("use_speaker_boost", true)
            }
            // ElevenLabs accepts 0.7-1.2; faster listening is applied at playback instead.
            put("speed", speed.coerceIn(0.7f, 1.2f).toDouble())
        }
    }

    override fun synthesize(chunk: SpeechChunk, speed: Float): SynthesizedAudio = try {
        synthesizeWith(modelId, chunk)
    } catch (e: VoiceException) {
        if (modelId == MODEL_V3 && e.kind == VoiceException.Kind.BAD_REQUEST) {
            modelId = MULTILINGUAL_V2
            synthesizeWith(modelId, chunk)
        } else {
            throw e
        }
    }

    private fun synthesizeWith(model: String, chunk: SpeechChunk): SynthesizedAudio {
        val url = "$baseUrl/v1/text-to-speech/${URLEncoder.encode(voiceId, "UTF-8")}/with-timestamps?output_format=mp3_44100_128"
        val raw = HttpPost.send(url, mapOf("xi-api-key" to apiKey, "Accept" to "application/json"), requestBody(chunk, 1.0f, model).toString(), timeoutMillis)
        val parsed = try {
            JSON.decodeFromString(Response.serializer(), raw.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw VoiceException(VoiceException.Kind.SERVER, "Unexpected response from ElevenLabs.", e)
        }
        val audio = try { Base64.getDecoder().decode(parsed.audio) } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceException.Kind.SERVER, "ElevenLabs returned invalid audio.", e)
        }
        val charStarts = parsed.alignment?.starts.orEmpty()
        val starts = AlignmentMapper.utteranceStartMillis(chunk, charStarts)
        return SynthesizedAudio(audio, "audio/mpeg", starts, charStarts.map { (it * 1000).toLong() })
    }

    companion object {
        const val MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_V3 = "eleven_v3"
        private val JSON = Json { ignoreUnknownKeys = true }
        private fun round2(x: Double) = Math.round(x * 100) / 100.0
    }
}

/** OpenAI speech: steerable narration style via [instructions]; no timestamps, so timings are estimated. */
class OpenAiVoice(
    private val apiKey: String,
    private val voice: String = "sage",
    private val model: String = "gpt-4o-mini-tts",
    private val instructions: String = DEFAULT_INSTRUCTIONS,
    /** Above ~0.2, each chunk gets a direction matching its mood ("this passage is tense..."). */
    private val expressiveness: Float = 0.6f,
    private val baseUrl: String = "https://api.openai.com",
    private val timeoutMillis: Int = 30_000,
    /** Decodes the returned audio's duration; the app supplies a MediaMetadataRetriever-based one. */
    private val durationOf: (ByteArray) -> Long = { 0L },
) : CloudVoice {
    override val name = "OpenAI"

    /** Base narration style plus a mood-specific direction for this chunk. */
    fun instructionsFor(chunk: SpeechChunk): String {
        if (expressiveness < 0.2f) return "$instructions Keep the delivery even and understated."
        val mood = MoodDetector.detect(chunk.text)
        val intensity = when {
            expressiveness >= 0.75f -> "Be bold and dramatic with emotion and dialogue."
            expressiveness >= 0.45f -> "Let real emotion colour the reading."
            else -> "Keep emotion subtle."
        }
        return "$instructions ${MoodDetector.directionFor(mood)} $intensity"
    }

    fun requestBody(chunk: SpeechChunk): JsonObject = buildJsonObject {
        put("model", model)
        put("input", chunk.text)
        put("voice", voice)
        put("instructions", instructionsFor(chunk))
        put("response_format", "mp3")
    }

    override fun synthesize(chunk: SpeechChunk, speed: Float): SynthesizedAudio {
        val audio = HttpPost.send("$baseUrl/v1/audio/speech", mapOf("Authorization" to "Bearer $apiKey"), requestBody(chunk).toString(), timeoutMillis)
        if (audio.isEmpty()) throw VoiceException(VoiceException.Kind.SERVER, "OpenAI returned no audio.")
        return SynthesizedAudio(audio, "audio/mpeg", AlignmentMapper.estimateStartMillis(chunk, durationOf(audio)))
    }

    companion object {
        const val DEFAULT_INSTRUCTIONS =
            "You are narrating an audiobook. Read in a warm, natural, human storytelling voice with " +
                "varied, expressive intonation, as a professional narrator would. Pause naturally at " +
                "commas and sentence ends. Give each speaker in dialogue a distinct colour. Never add " +
                "or skip words."
    }
}
