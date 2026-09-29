package com.earmark.core.speech

import com.earmark.core.FakeServer
import com.earmark.core.player.Utterance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechTest {
    private fun u(i: Int, para: Int, text: String) = Utterance("0:$i", i, para, text)

    @Test
    fun `chunks never cross paragraphs, respect max length and carry context`() {
        val us = listOf(u(0, 0, "Aaaa aaaa."), u(1, 0, "Bbbb bbbb."), u(2, 0, "Cccc cccc."), u(3, 1, "Dddd."), u(4, 1, "Eeee."))
        val chunks = ChunkPlanner.plan(us, maxChars = 22)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), chunks.map { c -> c.utterances.map { it.sentenceIndex } })
        assertEquals("Aaaa aaaa. Bbbb bbbb.", chunks[0].text)
        assertEquals(listOf(0, 11), chunks[0].offsets)
        chunks.forEach { c -> c.utterances.forEachIndexed { k, x -> assertTrue(c.text.startsWith(x.text, c.offsets[k])) } }
        assertNull(chunks[0].previousText)
        assertEquals("Cccc cccc.", chunks[0].nextText)
        assertEquals("Aaaa aaaa. Bbbb bbbb.", chunks[1].previousText)
        assertTrue(ChunkPlanner.plan(emptyList()).isEmpty())
        val huge = ChunkPlanner.plan(listOf(u(0, 0, "x".repeat(5000))), maxChars = 100)
        assertEquals(1, huge.size, "an over-long sentence is its own chunk, never dropped")
    }

    @Test
    fun `alignment maps character timings to sentence start times`() {
        val chunk = ChunkPlanner.plan(listOf(u(0, 0, "Hi there."), u(1, 0, "Bye.")))[0]
        val starts = List(chunk.text.length) { it * 0.1 }
        assertEquals(listOf(0L, 1000L), AlignmentMapper.utteranceStartMillis(chunk, starts))
        assertEquals(listOf(0L, 1000L), AlignmentMapper.estimateStartMillis(chunk, 1400))
        assertEquals(2, AlignmentMapper.utteranceStartMillis(chunk, emptyList()).size)
    }

    @Test
    fun `cost estimate for a 300 page book`() {
        val chars = 600_000L
        val eleven = CostEstimator.estimate(chars, CostEstimator.Voice.ELEVENLABS_MULTILINGUAL)
        assertEquals(60.0, eleven.usd, 0.01)
        assertTrue(eleven.audioHours in 10.0..13.0, "${eleven.audioHours}")
        assertEquals(0.0, CostEstimator.estimate(chars, CostEstimator.Voice.SYSTEM).usd)
    }

    @Test
    fun `elevenlabs request and response`() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val chunk = ChunkPlanner.plan(listOf(u(0, 0, "Hi there."), u(1, 0, "Bye.")))[0]
        val starts = (0 until chunk.text.length).joinToString(",") { "%.2f".format(java.util.Locale.US, it * 0.05) }
        val body = """{"audio_base64":"${Base64.getEncoder().encodeToString(audio)}","alignment":{"characters":[],"character_start_times_seconds":[$starts],"character_end_times_seconds":[]},"normalized_alignment":null}"""
        FakeServer { FakeServer.Response(200, body) }.use { server ->
            val v = ElevenLabsVoice("xi-key", "voice 1", baseUrl = server.url)
            val out = v.synthesize(chunk, 2.0f)
            assertContentEquals(audio, out.bytes)
            assertEquals(listOf(0L, 500L), out.utteranceStartMillis)
            val req = server.requests.single()
            assertEquals("/v1/text-to-speech/voice+1/with-timestamps", req.path)
            assertEquals("output_format=mp3_44100_128", req.query)
            assertEquals("xi-key", req.headers["xi-api-key"])
            val json = Json.parseToJsonElement(req.body).jsonObject
            assertEquals("Hi there. Bye.", json["text"]!!.jsonPrimitive.content)
            assertEquals("eleven_multilingual_v2", json["model_id"]!!.jsonPrimitive.content)
            assertEquals(1.0, json["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble(), "fast listening is applied at playback, not synthesis")
        }
    }

    @Test
    fun `voice errors are classified`() {
        val chunk = ChunkPlanner.plan(listOf(u(0, 0, "x")))[0]
        fun kind(status: Int, body: String = "{}"): VoiceException.Kind = FakeServer { FakeServer.Response(status, body) }.use { s ->
            assertThrows<VoiceException> { ElevenLabsVoice("k", "v", baseUrl = s.url).synthesize(chunk, 1f) }.kind
        }
        assertEquals(VoiceException.Kind.AUTH, kind(401))
        assertEquals(VoiceException.Kind.QUOTA, kind(402))
        assertEquals(VoiceException.Kind.QUOTA, kind(429, """{"detail":{"status":"quota_exceeded"}}"""))
        assertEquals(VoiceException.Kind.RATE_LIMIT, kind(429, """{"detail":"too_many_concurrent_requests"}"""))
        assertEquals(VoiceException.Kind.BAD_REQUEST, kind(422))
        assertEquals(VoiceException.Kind.SERVER, kind(503))
        assertEquals(VoiceException.Kind.SERVER, kind(200, "not json"))
        assertEquals(VoiceException.Kind.SERVER, kind(200, """{"audio_base64":"***"}"""))
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(VoiceException.Kind.NETWORK, assertThrows<VoiceException> { ElevenLabsVoice("k", "v", baseUrl = "http://127.0.0.1:$port").synthesize(chunk, 1f) }.kind)
    }

    @Test
    fun `openai request carries narration instructions and estimates timings`() {
        val chunk = ChunkPlanner.plan(listOf(u(0, 0, "One two."), u(1, 0, "Three.")))[0]
        FakeServer { FakeServer.Response(200, byteArrayOf(9, 9), "audio/mpeg") }.use { server ->
            val out = OpenAiVoice("sk", baseUrl = server.url, durationOf = { 1500L }).synthesize(chunk, 1f)
            assertContentEquals(byteArrayOf(9, 9), out.bytes)
            assertEquals(listOf(0L, 900L), out.utteranceStartMillis)
            val req = server.requests.single()
            assertEquals("/v1/audio/speech", req.path)
            assertEquals("Bearer sk", req.headers["authorization"])
            val json = Json.parseToJsonElement(req.body).jsonObject
            assertEquals("gpt-4o-mini-tts", json["model"]!!.jsonPrimitive.content)
            assertTrue(json["instructions"]!!.jsonPrimitive.content.contains("audiobook"))
        }
        FakeServer { FakeServer.Response(200, ByteArray(0), "audio/mpeg") }.use { server ->
            assertEquals(VoiceException.Kind.SERVER, assertThrows<VoiceException> { OpenAiVoice("sk", baseUrl = server.url).synthesize(chunk, 1f) }.kind)
        }
    }
}
