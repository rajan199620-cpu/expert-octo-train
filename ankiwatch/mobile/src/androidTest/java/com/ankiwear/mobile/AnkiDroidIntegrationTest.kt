package com.ankiwear.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ankiwatch.core.CardRenderer
import com.ankiwatch.core.PayloadBudget
import com.ankiwatch.core.RenderOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * Runs the phone companion's AnkiDroid code against the real AnkiDroid app (installed on the
 * emulator by CI): creates decks and cloze notes through AnkiDroid's public API, then checks
 * what the watch would be sent and that answers really land in AnkiDroid's scheduler.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidIntegrationTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(AnkiDroidHelper.READ_WRITE_PERMISSION)

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val helper = AnkiDroidHelper(context)

    private companion object {
        const val TAG = "AnkiWatchIT"
        const val AUTHORITY = "content://com.ichi2.anki.flashcards"
        val DECKS: Uri = Uri.parse("$AUTHORITY/decks")
        val MODELS: Uri = Uri.parse("$AUTHORITY/models")
        val NOTES: Uri = Uri.parse("$AUTHORITY/notes")
        const val SEP = "\u001f"
        var initialized = false
    }

    // ── Setup: AnkiDroid must have a collection before its provider answers ──────────────

    @Before
    fun ensureAnkiDroidReady() {
        assertTrue("AnkiDroid not visible to the app (package <queries>?)", helper.isAnkiDroidInstalled())
        if (initialized) return
        if (!providerWorks()) {
            Log.i(TAG, "Provider not ready; launching AnkiDroid once to create its collection")
            launchAnkiDroidOnce()
        }
        assertTrue("AnkiDroid's provider never became usable", providerWorks())
        initialized = true
    }

    private fun providerWorks(): Boolean = try {
        resolver.query(MODELS, arrayOf("_id", "name"), null, null, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "provider check failed: $e")
        false
    }

    private fun launchAnkiDroidOnce() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertNotNull(
            "No launcher intent for AnkiDroid",
            context.packageManager.getLaunchIntentForPackage(AnkiDroidHelper.ANKIDROID_PACKAGE)
        )
        // Started from the shell: a background app can't start another app's activity.
        device.executeShellCommand("monkey -p ${AnkiDroidHelper.ANKIDROID_PACKAGE} -c android.intent.category.LAUNCHER 1")
        device.wait(Until.hasObject(By.pkg(AnkiDroidHelper.ANKIDROID_PACKAGE).depth(0)), 15_000)
        // Click through first-run screens: intro, permission prompts, "get started".
        val buttons = Pattern.compile("(?i)(get started|continue|ok|allow|accept|skip|next|done)")
        repeat(8) {
            if (providerWorks()) return
            val button = device.findObject(By.text(buttons))
            if (button != null) {
                Log.i(TAG, "tapping '${button.text}'")
                button.click()
            }
            SystemClock.sleep(2_000)
        }
    }

    // ── AnkiDroid API helpers (same calls AnkiDroid's own AddContentApi makes) ───────────

    private fun createDeck(name: String): Long {
        val uri = resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) })
        return uri!!.lastPathSegment!!.toLong()
    }

    private data class Model(val id: Long, val name: String, val fields: List<String>, val type: Int?)

    private fun models(): List<Model> {
        val out = ArrayList<Model>()
        val withType = try {
            resolver.query(MODELS, arrayOf("_id", "name", "field_names", "type"), null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "model 'type' column unavailable: $e")
            null
        }
        val cursor = withType ?: resolver.query(MODELS, arrayOf("_id", "name", "field_names"), null, null, null)
        cursor!!.use {
            val typeIdx = it.getColumnIndex("type")
            while (it.moveToNext()) {
                out.add(
                    Model(
                        id = it.getLong(0),
                        name = it.getString(1),
                        fields = it.getString(2).split(SEP),
                        type = if (typeIdx >= 0) it.getInt(typeIdx) else null
                    )
                )
            }
        }
        return out
    }

    private fun stockCloze(): Model {
        val all = models()
        Log.i(TAG, "models: ${all.map { "${it.name}(type=${it.type}, fields=${it.fields})" }}")
        return all.firstOrNull { it.type == 1 && "Text" in it.fields }
            ?: all.first { it.name.contains("Cloze", ignoreCase = true) && "Text" in it.fields }
    }

    private fun stockBasic(): Model = models().first { it.fields == listOf("Front", "Back") }

    /** Adds a note and moves all its cards into [deckId]. */
    private fun addNote(model: Model, deckId: Long, fields: List<String>): Long {
        val noteUri = resolver.insert(NOTES, ContentValues().apply {
            put("mid", model.id)
            put("flds", fields.joinToString(SEP))
            put("tags", "ankiwatch-ci")
        })
        assertNotNull("note insert failed for model ${model.name}", noteUri)
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val ords = ArrayList<String>()
        resolver.query(cardsUri, null, null, null, null)!!.use {
            val ordIdx = it.getColumnIndexOrThrow("ord")
            while (it.moveToNext()) ords.add(it.getString(ordIdx))
        }
        for (ord in ords) {
            resolver.update(Uri.withAppendedPath(cardsUri, ord), ContentValues().apply { put("deck_id", deckId) }, null, null)
        }
        return noteUri!!.lastPathSegment!!.toLong()
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    private val lawText = """<div class="header header-blue">Act §999 — Example offence</div>
        |<p>{{c1::#A person commits the example offence::which act?}} when:</p>
        |<ol><li><b>First limb:</b> {{c2::by persuading another person::first mode}}</li>
        |<li><b>Second limb:</b> {{c3::by agreeing with others::second mode}}</li></ol>""".trimMargin()

    @Test
    fun clozeCardsReachTheWatchAsRawFieldsWithTheRightClozeNumber() {
        val model = stockCloze()
        val deckId = createDeck("AnkiWatch CI::cloze ${System.nanoTime()}")
        val noteId = addNote(model, deckId, listOf(lawText, "Back extra text") + List(model.fields.size - 2) { "" })

        val counts = helper.getDeckDueBreakdown(deckId)
        Log.i(TAG, "fresh deck counts: $counts")
        assertNotNull(counts)
        // Three new cloze cards, nothing in learning or review: proves the [learn, review, new]
        // column order the phone app assumes for deck_count.
        assertEquals(3, counts!!.newCount)
        assertEquals(0, counts.learnCount)
        assertEquals(0, counts.reviewCount)

        assertTrue(helper.setSelectedDeck(deckId))
        val cards = helper.getScheduledCards(deckId, limit = 10)
        Log.i(TAG, "scheduled: ${cards.map { "${it.noteId}/${it.cardOrd} cloze=${it.cloze?.clozeNumber}" }}")
        assertTrue("no cards scheduled", cards.isNotEmpty())
        for (card in cards) {
            assertEquals(noteId, card.noteId)
            val cloze = card.cloze
            assertNotNull("card ord ${card.cardOrd} was not sent as a cloze card", cloze)
            assertEquals(card.cardOrd + 1, cloze!!.clozeNumber)
            assertTrue(cloze.content.contains("{{c2::by persuading another person::first mode}}"))
            assertEquals(listOf("Back Extra" to "Back extra text"), cloze.extras)
            assertEquals("", card.question) // no template HTML travels for cloze cards
            assertEquals(4, card.buttonCount)
            assertEquals(4, card.nextReviewTimes.size)

            // What the watch shows on the question side never contains the tested answer.
            val front = CardRenderer.render(cloze.content, RenderOptions(activeOrd = cloze.clozeNumber)).plainText()
            when (cloze.clozeNumber) {
                1 -> assertFalse(front, front.contains("A person commits"))
                2 -> assertFalse(front, front.contains("persuading"))
                3 -> assertFalse(front, front.contains("agreeing"))
            }
            // And the DataItem stays far below the 100 KiB limit.
            val bytes = cardToDataMap(card).toByteArray().size
            assertTrue("DataMap $bytes bytes", bytes < 20_000)
        }
    }

    @Test
    fun answersReallyLandInAnkiDroid() {
        val model = stockCloze()
        val deckId = createDeck("AnkiWatch CI::answers ${System.nanoTime()}")
        addNote(model, deckId, listOf("{{c1::one}} {{c2::two}} {{c3::three}}", "") + List(model.fields.size - 2) { "" })
        helper.setSelectedDeck(deckId)
        val first = helper.getScheduledCards(deckId).first()

        // Good on a new card: it leaves the new queue.
        assertTrue(helper.answerCard(first.noteId, first.cardOrd, ease = 3, timeTakenMs = 4_000))
        val afterGood = helper.getDeckDueBreakdown(deckId)!!
        Log.i(TAG, "after Good: $afterGood")
        assertEquals(2, afterGood.newCount)
        val next = helper.getScheduledCards(deckId)
        assertTrue("the answered card came straight back: $next",
            next.firstOrNull()?.let { it.noteId == first.noteId && it.cardOrd == first.cardOrd } != true)

        // Again on the next card: it goes to learning and is due again shortly.
        val second = next.first()
        assertTrue(helper.answerCard(second.noteId, second.cardOrd, ease = 1, timeTakenMs = 2_000))
        val afterAgain = helper.getDeckDueBreakdown(deckId)!!
        Log.i(TAG, "after Again: $afterAgain")
        assertEquals(1, afterAgain.newCount)
        assertTrue(afterAgain.learnCount >= 1)

        // The retry path used after "Again" on the last card can rebuild a card on demand.
        val retried = helper.synthesizeCardForRetry(second.noteId, second.cardOrd)
        assertNotNull(retried)
        assertEquals(second.cardOrd + 1, retried!!.cloze!!.clozeNumber)
    }

    @Test
    fun hugeNotesAreTrimmedToFitTheDataLayer() {
        val model = stockCloze()
        val deckId = createDeck("AnkiWatch CI::huge ${System.nanoTime()}")
        val image = "<img src=\"data:image/png;base64,${"A".repeat(150_000)}\" alt=\"diagram\">"
        val bigText = image + "<p>" + "statute words {{c1::answer}} ".repeat(4_000) + "</p>"
        addNote(model, deckId, listOf(bigText, "x".repeat(30_000)) + List(model.fields.size - 2) { "" })
        val cards = helper.getScheduledCards(deckId)
        val card = cards.first()
        assertFalse("base64 image data reached the payload", card.cloze!!.content.contains("base64"))
        val fitted = fitToDataItem(cards)
        val bytes = cardToDataMap(fitted.first()).toByteArray().size
        Log.i(TAG, "huge note: raw=${bigText.length} chars, sent=$bytes bytes")
        assertTrue("DataMap is $bytes bytes", bytes < 100 * 1024)
        val front = CardRenderer.render(fitted.first().cloze!!.content, RenderOptions(activeOrd = 1)).plainText()
        assertFalse(front.contains("answer"))
    }

    @Test
    fun basicCardsUseSanitizedTemplateHtml() {
        val model = stockBasic()
        val deckId = createDeck("AnkiWatch CI::basic ${System.nanoTime()}")
        addNote(model, deckId, listOf("Front <b>bold</b> <img src=\"data:image/png;base64,AAAA\">", "Back text"))
        val card = helper.getScheduledCards(deckId).first()
        Log.i(TAG, "basic question: ${card.question.take(300)}")
        assertEquals(null, card.cloze)
        assertTrue(card.question.contains("Front"))
        assertFalse(card.question.contains("base64"))
        assertFalse(card.question.contains("<script", ignoreCase = true))
        val answer = CardRenderer.render(card.answer, RenderOptions()).plainText()
        assertTrue(answer, answer.contains("Back text"))
    }

    @Test
    fun stressManyNotesAndAnswers() {
        val model = stockCloze()
        val rnd = Random(7)
        val decks = (1..3).map { createDeck("AnkiWatch CI::stress $it ${System.nanoTime()}") }
        var notes = 0
        val addStart = SystemClock.uptimeMillis()
        for (deckId in decks) repeat(12) {
            val clozes = (1..(1 + rnd.nextInt(4))).joinToString(" ") { n -> "w{{c$n::s${rnd.nextInt(1000)}::h$n}}" }
            addNote(model, deckId, listOf("<p>$clozes</p>", "") + List(model.fields.size - 2) { "" })
            notes++
        }
        Log.i(TAG, "added $notes notes in ${SystemClock.uptimeMillis() - addStart}ms")

        var answered = 0
        val answerStart = SystemClock.uptimeMillis()
        for (deckId in decks) {
            helper.setSelectedDeck(deckId)
            repeat(30) {
                val card = helper.getScheduledCards(deckId).firstOrNull() ?: return@repeat
                assertNotNull(card.cloze)
                val ease = listOf(1, 3, 3, 3, 4)[rnd.nextInt(5)]
                assertTrue("answer failed for ${card.noteId}/${card.cardOrd}", helper.answerCard(card.noteId, card.cardOrd, ease, 3_000))
                answered++
            }
        }
        val perAnswer = (SystemClock.uptimeMillis() - answerStart) / maxOf(answered, 1)
        Log.i(TAG, "stress: answered $answered cards, ${perAnswer}ms per fetch+answer")
        assertTrue(answered >= 30)
        assertTrue("fetch+answer took ${perAnswer}ms", perAnswer < 2_000)
        assertTrue(PayloadBudget.MAX_BYTES < 100 * 1024)
    }
}
