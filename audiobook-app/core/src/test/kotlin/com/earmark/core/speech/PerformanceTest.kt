package com.earmark.core.speech

import com.earmark.core.FakeServer
import com.earmark.core.player.Utterance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {
    private fun assertSlices(speech: String, segs: List<SpeechSegment>) {
        var last = -1
        for (seg in segs) {
            assertTrue(seg.text.isNotBlank(), "blank segment in $segs")
            assertEquals(seg.text, speech.substring(seg.offset, seg.offset + seg.text.length), "segment must be a slice of the speech text")
            assertTrue(seg.offset > last, "segments out of order: $segs")
            last = seg.offset
        }
        assertEquals(speech.filterNot { it.isWhitespace() }, segs.joinToString("") { it.text }.filterNot { it.isWhitespace() }, "text lost or duplicated")
    }

    @Test
    fun `dialogue gets its own voice colour and exclamations lift`() {
        val s = "He turned and said, “Come here at once, Mira!” and walked away."
        val segs = ProsodyPlanner.plan(s)
        assertSlices(s, segs)
        assertEquals(3, segs.size)
        assertEquals("He turned and said,", segs[0].text)
        assertEquals(1f, segs[0].pitch)
        assertEquals("“Come here at once, Mira!”", segs[1].text)
        assertEquals(ProsodyPlanner.DIALOGUE_PITCH * ProsodyPlanner.EXCLAIM_PITCH, segs[1].pitch, 1e-4f)
        assertTrue(segs[1].rate > 1f)
        assertEquals("and walked away.", segs[2].text)
    }

    @Test
    fun `straight quotes work and scare quotes stay narration`() {
        val s = "She asked, \"Where did the keeper go?\""
        val segs = ProsodyPlanner.plan(s)
        assertSlices(s, segs)
        assertEquals(ProsodyPlanner.DIALOGUE_PITCH * ProsodyPlanner.QUESTION_PITCH, segs.last().pitch, 1e-4f)
        val scare = "The so-called \"experts\" disagreed about everything."
        assertEquals(1, ProsodyPlanner.plan(scare).size)
        val unclosed = "He said “wait for me and then left."
        assertEquals(1, ProsodyPlanner.plan(unclosed).size)
    }

    @Test
    fun `questions lift the pitch, plain statements do not`() {
        assertEquals(ProsodyPlanner.QUESTION_PITCH, ProsodyPlanner.plan("Who lit the lamp?").single().pitch, 1e-4f)
        assertEquals(1f, ProsodyPlanner.plan("Nobody lit the lamp.").single().pitch)
    }

    @Test
    fun `pauses mark headings, paragraph and chapter ends and dramatic ellipses`() {
        val heading = ProsodyPlanner.plan("Chapter 2. The Storm.", isHeading = true).single()
        assertEquals(ProsodyPlanner.HEADING_PAUSE, heading.pauseAfterMillis)
        assertEquals(ProsodyPlanner.HEADING_RATE, heading.rate)
        assertEquals(ProsodyPlanner.PARAGRAPH_PAUSE, ProsodyPlanner.plan("It ended.", endsParagraph = true).last().pauseAfterMillis)
        assertEquals(ProsodyPlanner.CHAPTER_PAUSE, ProsodyPlanner.plan("It ended.", endsParagraph = true, endsChapter = true).last().pauseAfterMillis)
        assertEquals(0, ProsodyPlanner.plan("It ended.").last().pauseAfterMillis)
        val s = "He waited... and then the door opened."
        val segs = ProsodyPlanner.plan(s)
        assertSlices(s, segs)
        assertEquals(2, segs.size)
        assertEquals(ProsodyPlanner.ELLIPSIS_PAUSE, segs[0].pauseAfterMillis)
    }

    @Test
    fun `performance can be switched off`() {
        val s = "He said, “Come here now!”"
        val seg = ProsodyPlanner.plan(s, endsParagraph = true, perform = false).single()
        assertEquals(1f, seg.pitch)
        assertEquals(0, seg.pauseAfterMillis)
    }

    @Test
    fun `fuzz - segments are always ordered slices that keep every character`() {
        val rnd = Random(21)
        val alphabet = "abc def “”\"\"...…!?,. Mira ".toCharArray()
        repeat(3000) {
            val s = String(CharArray(rnd.nextInt(1, 160)) { alphabet[rnd.nextInt(alphabet.size)] })
            if (s.isBlank()) return@repeat
            val segs = ProsodyPlanner.plan(s, isHeading = rnd.nextInt(10) == 0, endsParagraph = rnd.nextBoolean())
            assertSlices(s, segs)
            assertTrue(segs.size in 1..6)
            segs.forEach { assertTrue(it.pitch in 0.9f..1.2f && it.rate in 0.9f..1.1f, "delivery out of range: $it") }
        }
    }

    @Test
    fun `mood detection`() {
        assertEquals(Mood.TENSE, MoodDetector.detect("Suddenly the thunder cracked and she ran, heart pounded, sirens screaming in the storm!"))
        assertEquals(Mood.SAD, MoodDetector.detect("She wept at the funeral, alone with her grief and her tears."))
        assertEquals(Mood.JOYFUL, MoodDetector.detect("They laughed and danced, delighted, grinning in the sunlight."))
        assertEquals(Mood.EXPLANATORY, MoodDetector.detect("Section 103 provides that whoever commits murder shall be punished; the punishment is defined in the Act and the court shall consider the evidence."))
        assertEquals(Mood.NEUTRAL, MoodDetector.detect("The table stood in the corner of the room next to the window and a chair."))
        Mood.values().forEach { assertTrue(MoodDetector.directionFor(it).isNotBlank()) }
    }

    private fun chunk(text: String) = ChunkPlanner.plan(listOf(Utterance("0:0", 0, 0, text)))[0]

    @Test
    fun `elevenlabs expressiveness lowers stability and raises style`() {
        val flat = ElevenLabsVoice("k", "v", expressiveness = 0f).requestBody(chunk("Hi there."), 1f)["voice_settings"]!!.jsonObject
        val dramatic = ElevenLabsVoice("k", "v", expressiveness = 1f).requestBody(chunk("Hi there."), 1f)["voice_settings"]!!.jsonObject
        assertTrue(flat["stability"]!!.jsonPrimitive.content.toDouble() > dramatic["stability"]!!.jsonPrimitive.content.toDouble())
        assertTrue(flat["style"]!!.jsonPrimitive.content.toDouble() < dramatic["style"]!!.jsonPrimitive.content.toDouble())
        val default = ElevenLabsVoice("k", "v").requestBody(chunk("Hi there."), 1f)["voice_settings"]!!.jsonObject
        assertTrue(default["style"]!!.jsonPrimitive.content.toDouble() > 0.0, "the default must not be the flat, style-0 delivery")

        fun settings(e: Float) = ElevenLabsVoice("k", "v", expressiveness = e).requestBody(chunk("Hi there."), 1f)["voice_settings"]!!.jsonObject
        fun num(o: kotlinx.serialization.json.JsonObject, key: String) = o[key]!!.jsonPrimitive.content.toDouble()
        assertEquals(0.65, num(flat, "stability"), 1e-9)
        assertEquals(0.25, num(dramatic, "stability"), 1e-9)
        assertEquals(0.55, num(dramatic, "style"), 1e-9)
        assertEquals(0.25, num(settings(7f), "stability"), 1e-9, "out-of-range values are clamped")
        // Every slider step is at least as expressive as the one before it.
        (0..10).map { settings(it / 10f) }.zipWithNext().forEach { (a, b) ->
            assertTrue(num(b, "style") >= num(a, "style") && num(b, "stability") <= num(a, "stability"))
        }
    }

    @Test
    fun `eleven v3 uses stability presets, no stitching, and falls back to v2 if rejected`() {
        val c = ChunkPlanner.plan(listOf(Utterance("0:0", 0, 0, "One."), Utterance("0:1", 1, 1, "Two.")))[1]
        val body = ElevenLabsVoice("k", "v", ElevenLabsVoice.MODEL_V3, expressiveness = 0.9f).requestBody(c, 1f)
        assertNull(body["previous_text"])
        assertEquals(0.0, body["voice_settings"]!!.jsonObject["stability"]!!.jsonPrimitive.content.toDouble())
        fun v3Stability(e: Float) = ElevenLabsVoice("k", "v", ElevenLabsVoice.MODEL_V3, expressiveness = e).requestBody(c, 1f)["voice_settings"]!!.jsonObject["stability"]!!.jsonPrimitive.content.toDouble()
        assertEquals(0.5, v3Stability(0.5f))
        assertEquals(1.0, v3Stability(0.1f))
        assertEquals("One.", ElevenLabsVoice("k", "v").requestBody(c, 1f)["previous_text"]!!.jsonPrimitive.content, "v2 keeps request stitching")

        val ok = """{"audio_base64":"${Base64.getEncoder().encodeToString(byteArrayOf(1))}","alignment":{"characters":[],"character_start_times_seconds":[0.0,0.1,0.2,0.3],"character_end_times_seconds":[]}}"""
        FakeServer { req ->
            val model = Json.parseToJsonElement(req.body).jsonObject["model_id"]!!.jsonPrimitive.content
            if (model == ElevenLabsVoice.MODEL_V3) FakeServer.Response(422, """{"detail":"unsupported"}""") else FakeServer.Response(200, ok)
        }.use { server ->
            val voice = ElevenLabsVoice("k", "v", ElevenLabsVoice.MODEL_V3, baseUrl = server.url)
            val out = voice.synthesize(chunk("Four"), 1f)
            assertEquals(ElevenLabsVoice.MULTILINGUAL_V2, voice.modelId)
            assertEquals(listOf(0L, 100L, 200L, 300L), out.charStartMillis)
            voice.synthesize(chunk("Four"), 1f)
            assertEquals(3, server.requests.size, "v3 is tried once, then v2 is used directly")
        }
        FakeServer { FakeServer.Response(401, "{}") }.use { server ->
            val voice = ElevenLabsVoice("k", "v", ElevenLabsVoice.MODEL_V3, baseUrl = server.url)
            assertEquals(VoiceException.Kind.AUTH, assertThrows<VoiceException> { voice.synthesize(chunk("Four"), 1f) }.kind)
            assertEquals(ElevenLabsVoice.MODEL_V3, voice.modelId, "a rejected key is not a reason to change model")
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `openai instructions follow the mood of each chunk`() {
        val v = OpenAiVoice("k", expressiveness = 0.8f)
        assertContains(v.instructionsFor(chunk("She wept at the funeral, alone with her grief and her tears.")), "sad")
        assertContains(v.instructionsFor(chunk("Section 103 provides that whoever commits murder shall be punished under this Act.")), "teacher")
        assertContains(OpenAiVoice("k", expressiveness = 0f).instructionsFor(chunk("She wept.")), "even and understated")

        val chase = chunk("Suddenly the sirens screamed and she ran, desperate, as blood pounded in her ears.")
        val bold = OpenAiVoice("k", expressiveness = 0.9f).instructionsFor(chase)
        assertTrue(bold.startsWith(OpenAiVoice.DEFAULT_INSTRUCTIONS) && "tense" in bold && "dramatic" in bold, bold)
        val subtle = OpenAiVoice("k", expressiveness = 0.3f).instructionsFor(chase)
        assertTrue("tense" in subtle && "subtle" in subtle, subtle)
        assertFalse("tense" in OpenAiVoice("k", expressiveness = 0.1f).instructionsFor(chase), "an even reading gets no mood direction")
    }
}
