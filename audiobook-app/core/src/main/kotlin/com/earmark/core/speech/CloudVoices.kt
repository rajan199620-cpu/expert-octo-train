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

/** Audio for one [SpeechChunk] plus when each of its utterances starts. */
class SynthesizedAudio(val bytes: ByteArray, val mimeType: String, val utteranceStartMillis: List<Long>)

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
 * what lets the app highlight the exact sentence being spoken with a cloud voice.
 */
class ElevenLabsVoice(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = "eleven_multilingual_v2",
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val timeoutMillis: Int = 30_000,
) : CloudVoice {
    override val name = "ElevenLabs"

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

    fun requestBody(chunk: SpeechChunk, speed: Float): JsonObject = buildJsonObject {
        put("text", chunk.text)
        put("model_id", modelId)
        chunk.previousText?.let { put("previous_text", it) }
        chunk.nextText?.let { put("next_text", it) }
        putJsonObject("voice_settings") {
            put("stability", 0.5)
            put("similarity_boost", 0.75)
            put("style", 0.0)
            put("use_speaker_boost", true)
            // ElevenLabs accepts 0.7-1.2; faster listening is applied at playback instead.
            put("speed", speed.coerceIn(0.7f, 1.2f).toDouble())
        }
    }

    override fun synthesize(chunk: SpeechChunk, speed: Float): SynthesizedAudio {
        val url = "$baseUrl/v1/text-to-speech/${URLEncoder.encode(voiceId, "UTF-8")}/with-timestamps?output_format=mp3_44100_128"
        val raw = HttpPost.send(url, mapOf("xi-api-key" to apiKey, "Accept" to "application/json"), requestBody(chunk, 1.0f).toString(), timeoutMillis)
        val parsed = try {
            JSON.decodeFromString(Response.serializer(), raw.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw VoiceException(VoiceException.Kind.SERVER, "Unexpected response from ElevenLabs.", e)
        }
        val audio = try { Base64.getDecoder().decode(parsed.audio) } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceException.Kind.SERVER, "ElevenLabs returned invalid audio.", e)
        }
        val starts = AlignmentMapper.utteranceStartMillis(chunk, parsed.alignment?.starts.orEmpty())
        return SynthesizedAudio(audio, "audio/mpeg", starts)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** OpenAI speech: steerable narration style via [instructions]; no timestamps, so timings are estimated. */
class OpenAiVoice(
    private val apiKey: String,
    private val voice: String = "sage",
    private val model: String = "gpt-4o-mini-tts",
    private val instructions: String = DEFAULT_INSTRUCTIONS,
    private val baseUrl: String = "https://api.openai.com",
    private val timeoutMillis: Int = 30_000,
    /** Decodes the returned audio's duration; the app supplies a MediaMetadataRetriever-based one. */
    private val durationOf: (ByteArray) -> Long = { 0L },
) : CloudVoice {
    override val name = "OpenAI"

    fun requestBody(chunk: SpeechChunk): JsonObject = buildJsonObject {
        put("model", model)
        put("input", chunk.text)
        put("voice", voice)
        put("instructions", instructions)
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
                "expressive but unexaggerated intonation. Pause naturally at commas and sentence ends. " +
                "Voice dialogue slightly differently from narration. Never add or skip words."
    }
}
