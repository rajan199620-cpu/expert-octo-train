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
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.runBlocking
import com.ankiwatch.core.CardRenderer
import com.ankiwatch.core.IntervalLabel
import com.ankiwatch.core.OfflinePackCodec
import com.ankiwatch.core.OfflineQueue
import com.ankiwatch.core.PayloadBudget
import com.ankiwatch.core.RenderOptions
import com.ankiwatch.core.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
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
        // Click through first-run screens: intro, permission prompts, "get started". CI has
        // already granted "All files access", which is what enables AnkiDroid's Continue.
        val buttons = Pattern.compile("(?i)(get started|continue|ok|allow|accept|skip|next|done)")
        repeat(12) { step ->
            if (providerWorks()) return
            val onScreen = device.findObjects(By.text(Pattern.compile(".+"))).joinToString(" | ") { it.text }
            Log.i(TAG, "first-run step $step, screen: $onScreen")
            val button = device.findObject(By.text(buttons).enabled(true))
            if (button != null) {
                Log.i(TAG, "tapping '${button.text}'")
                button.click()
            }
            SystemClock.sleep(2_500)
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
    fun buryHidesTheCardUntilTomorrowAndLeavesItsSiblings() {
        val model = stockCloze()
        val deckId = createDeck("AnkiWatch CI::bury ${System.nanoTime()}")
        addNote(model, deckId, listOf("{{c1::one}} {{c2::two}} {{c3::three}}", "") + List(model.fields.size - 2) { "" })
        helper.setSelectedDeck(deckId)
        val first = helper.getScheduledCards(deckId).first()

        assertTrue(helper.buryCard(first.noteId, first.cardOrd))
        val after = helper.getDeckDueBreakdown(deckId)!!
        Log.i(TAG, "after bury: $after")
        assertEquals(2, after.newCount)
        val next = helper.getScheduledCards(deckId, limit = 10)
        assertFalse("the buried card is still scheduled: $next",
            next.any { it.noteId == first.noteId && it.cardOrd == first.cardOrd })
        assertTrue("its siblings went with it: $next", next.isNotEmpty())
    }

    @Test
    fun buryingEveryCardEmptiesTodaysQueue() {
        val model = stockCloze()
        // Top level, so the shared "AnkiWatch CI" parent's daily new-card limit can't interfere.
        val deckId = createDeck("AnkiWatch bury-all ${System.nanoTime()}")
        var total = 0
        for (n in 0 until 6) {
            val k = 1 + n % 3
            addNote(model, deckId, listOf((1..k).joinToString(" ") { "w$n {{c$it::s$n-$it}}" }, "") + List(model.fields.size - 2) { "" })
            total += k
        }
        helper.setSelectedDeck(deckId)
        assertEquals(total, helper.getDeckDueBreakdown(deckId)!!.newCount)
        val buried = HashSet<Pair<Long, Int>>()
        while (true) {
            val next = helper.getScheduledCards(deckId, limit = 10)
            assertFalse("a buried card came back: $next", next.any { (it.noteId to it.cardOrd) in buried })
            val card = next.firstOrNull() ?: break
            assertTrue("bury failed for ${card.noteId}/${card.cardOrd}", helper.buryCard(card.noteId, card.cardOrd))
            buried += card.noteId to card.cardOrd
            assertTrue("more buries (${buried.size}) than cards ($total)", buried.size <= total)
        }
        Log.i(TAG, "buried all $total cards")
        assertEquals(total, buried.size)
        val counts = helper.getDeckDueBreakdown(deckId)!!
        assertEquals("$counts", 0, counts.totalDue)
    }

    @Test
    fun staleOrBogusBuriesFailWithoutHarm() {
        val model = stockCloze()
        val deckId = createDeck("AnkiWatch bury-stale ${System.nanoTime()}")
        val noteId = addNote(model, deckId, listOf("{{c1::one}} {{c2::two}}", "") + List(model.fields.size - 2) { "" })
        helper.setSelectedDeck(deckId)
        // A card the note doesn't have, and a note that no longer exists (deleted on the phone
        // while the watch still showed it): no crash, just false.
        assertFalse(helper.buryCard(noteId, 7))
        assertFalse(helper.buryCard(Long.MAX_VALUE - 1, 0))
        assertEquals(2, helper.getDeckDueBreakdown(deckId)!!.newCount)

        // AnkiDroid still answers, and the same bury delivered twice is harmless.
        val card = helper.getScheduledCards(deckId).first()
        assertTrue(helper.buryCard(card.noteId, card.cardOrd))
        helper.buryCard(card.noteId, card.cardOrd)
        assertEquals(1, helper.getDeckDueBreakdown(deckId)!!.newCount)
        val left = helper.getScheduledCards(deckId, limit = 10)
        assertEquals(listOf(noteId to 1 - card.cardOrd), left.map { it.noteId to it.cardOrd })
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
        val buried = HashSet<Pair<Long, Int>>()
        val answerStart = SystemClock.uptimeMillis()
        for (deckId in decks) {
            helper.setSelectedDeck(deckId)
            repeat(30) {
                val card = helper.getScheduledCards(deckId).firstOrNull() ?: return@repeat
                assertNotNull(card.cloze)
                val key = card.noteId to card.cardOrd
                assertFalse("buried card $key came back", key in buried)
                val ease = listOf(Wire.EASE_BURY, 1, 3, 3, 3, 4)[rnd.nextInt(6)]
                if (ease == Wire.EASE_BURY) {
                    assertTrue("bury failed for $key", helper.buryCard(card.noteId, card.cardOrd))
                    buried += key
                } else {
                    assertTrue("answer failed for $key", helper.answerCard(card.noteId, card.cardOrd, ease, 3_000))
                }
                answered++
            }
        }
        val perAnswer = (SystemClock.uptimeMillis() - answerStart) / maxOf(answered, 1)
        Log.i(TAG, "stress: answered $answered cards (${buried.size} buried), ${perAnswer}ms per fetch+answer")
        assertTrue(answered >= 30)
        assertTrue("no buries in the mix", buried.isNotEmpty())
        assertTrue("fetch+answer took ${perAnswer}ms", perAnswer < 2_000)
        assertTrue(PayloadBudget.MAX_BYTES < 100 * 1024)
    }

    // ── Offline review: download, then grades applied later in a batch ──────────────────

    /** A deck of cloze notes; [textOf] maps each note to its text, the same in a twin deck. */
    private class Deck(val id: Long, val textOf: Map<Long, String>)

    private fun deckWith(name: String, texts: List<String>): Deck {
        val model = stockCloze()
        val id = createDeck(name)
        // Top-level decks: the shared "AnkiWatch CI" parent's daily limits can't interfere.
        return Deck(id, texts.associateBy { addNote(model, id, listOf(it, "") + List(model.fields.size - 2) { "" }) })
    }

    /** What the watch writes for an answer, as the phone will read it from the Data Layer. */
    private fun queued(noteId: Long, ord: Int, ease: Int?, deckId: Long, seq: Long, offline: Boolean = true): QueuedAnswer {
        val uuid = UUID.randomUUID().toString()
        val map = DataMap().apply {
            putString(Wire.KEY_ANSWER_UUID, uuid)
            putLong(Wire.KEY_NOTE_ID, noteId)
            putInt(Wire.KEY_CARD_ORD, ord)
            ease?.let { putInt(Wire.KEY_EASE, it) }
            putLong(Wire.KEY_TIME_TAKEN, 4_000)
            putLong(Wire.KEY_DECK_ID, deckId)
            putLong(Wire.KEY_TIMESTAMP, 1_700_000_000_000 + seq * 1_000)
            putBoolean(Wire.KEY_OFFLINE, offline)
            putLong(Wire.KEY_SEQ, seq)
        }
        return Uri.parse("wear://watch${Wire.PATH_ANSWER_PREFIX}$uuid") to map
    }

    /** The Data Layer's queue of answers, in memory: what the phone sees once the watch is back. */
    private class FakeQueue(items: List<QueuedAnswer>) : AnswerQueue {
        val items = items.toMutableList()
        /** Each ack sent to the watch: the names of the answers it covers. */
        val acks = ArrayList<List<String>>()
        /** The watch can't be told: acknowledge throws, as the Data Layer would. */
        var failAcks = false
        override suspend fun pendingAnswers(): List<QueuedAnswer> = items.toList()
        override suspend fun deleteAnswerItem(uri: Uri) {
            // The watch may never hear of a deletion: it must have been told first.
            val name = Wire.answerName(uri.path)!!
            check(acks.any { name in it }) { "deleted $name before acknowledging it" }
            items.removeAll { it.first == uri }
        }
        override suspend fun acknowledge(names: List<String>) {
            if (failAcks) throw IllegalStateException("Wearable API unavailable")
            acks += names.toList()
        }
    }

    private fun names(items: List<QueuedAnswer>): Set<String> = items.map { Wire.answerName(it.first.path)!! }.toSet()

    private fun sync(queue: FakeQueue): AnswerSync.Report =
        runBlocking { AnswerSync(helper, queue, AnswerDedupeStore(context), ExchangeLog(context)).run() }!!

    /** Each due card's labels for Again, Hard and Good (Easy may carry AnkiDroid's random fuzz). */
    private fun dueLabels(deck: Deck): Map<Pair<String, Int>, List<String>> =
        helper.getScheduledCards(deck.id, limit = 100).associate { (deck.textOf.getValue(it.noteId) to it.cardOrd) to it.nextReviewTimes.take(3) }

    private fun counts(deck: Deck) = helper.getDeckDueBreakdown(deck.id)!!.let { Triple(it.newCount, it.learnCount, it.reviewCount) }

    @Test
    fun anOfflineDownloadIsTheDeckAsAnkiDroidWouldServeIt() {
        val deck = deckWith("AnkiWatch offline pack ${System.nanoTime()}", (1..8).map { n ->
            (1..(1 + n % 3)).joinToString(" ") { "w$n {{c$it::s$n-$it}}" }
        })
        val start = SystemClock.uptimeMillis()
        val pack = buildOfflinePack(helper, deck.id, "Offline")
        val ms = SystemClock.uptimeMillis() - start
        assertEquals(17, pack.cards.size)
        assertEquals(helper.getDeckDueBreakdown(deck.id)!!.totalDue, pack.cards.size)
        val live = helper.getScheduledCards(deck.id, limit = 100)
        assertEquals("not AnkiDroid's order", live.map { it.noteId to it.cardOrd }, pack.cards.map { it.noteId to it.cardOrd })
        for (card in pack.cards) {
            assertEquals(card.cardOrd + 1, card.clozeNumber)
            assertEquals(4, card.nextReviewTimes.size)
            // The watch times learning cards from these: every one must be readable.
            for (label in card.nextReviewTimes) assertNotNull("unreadable label '$label'", IntervalLabel.millis(label))
        }
        val bytes = OfflinePackCodec.encode(pack)
        assertEquals(pack, OfflinePackCodec.decode(bytes))
        Log.i(TAG, "offline pack: ${pack.cards.size} cards in ${ms}ms, ${bytes.size} bytes, labels ${pack.cards.first().nextReviewTimes}")
    }

    /**
     * The same first answers, given live on one deck and offline on its twin (recorded by the
     * watch's queue, applied later in a shuffled batch): AnkiDroid must end up with the very
     * same schedule on both.
     */
    @Test
    fun offlineGradesScheduleExactlyLikeLiveOnes() {
        val texts = (1..10).map { n -> "w$n {{c1::a$n}} {{c2::b$n}}" }
        val nano = System.nanoTime()
        val live = deckWith("AnkiWatch live $nano", texts)
        val offline = deckWith("AnkiWatch offline $nano", texts)
        val grades = listOf(1, 2, 3, 4, Wire.EASE_BURY)

        val applier = AnswerApplier(helper, AnswerDedupeStore(context))
        helper.setSelectedDeck(live.id)
        val liveOrder = helper.getScheduledCards(live.id, limit = 100)
        assertEquals(20, liveOrder.size)
        runBlocking {
            liveOrder.forEachIndexed { i, card ->
                val answer = WatchAnswer(UUID.randomUUID().toString(), card.noteId, card.cardOrd, grades[i % grades.size], 4_000, live.id)
                assertEquals(AnswerApplier.Result.APPLIED, applier.apply(answer))
            }
        }

        val pack = buildOfflinePack(helper, offline.id, "Offline")
        val queue = OfflineQueue(pack)
        val recorded = ArrayList<QueuedAnswer>()
        pack.cards.forEachIndexed { i, card ->
            queue.answer(card.key, grades[i % grades.size], i * 5_000L)
            recorded += queued(card.noteId, card.cardOrd, grades[i % grades.size], offline.id, seq = i + 1L)
        }
        SystemClock.sleep(1_500) // grades reach the phone a while later
        val fake = FakeQueue(recorded.shuffled(Random(1)))
        val report = sync(fake)
        assertEquals(20, report.applied)
        assertEquals(null, report.stopped)
        assertEquals(null, report.replyTo)
        assertTrue("items left behind", fake.items.isEmpty())

        assertEquals(counts(live), counts(offline))
        val liveDue = dueLabels(live)
        Log.i(TAG, "after first answers: ${counts(live)}, due: $liveDue")
        assertEquals(12, liveDue.size) // Again, Hard and Good leave cards in learning
        assertEquals(liveDue, dueLabels(offline))
    }

    /**
     * A whole session: Again/Hard/Good/Easy/Bury on first sight, then Good until each card is
     * done. Offline, the watch decides when a learning card comes back; it must ask for exactly
     * as many answers per card as AnkiDroid does live, and leave the deck in the same state.
     */
    @Test
    fun aWholeOfflineSessionAsksForWhatAnkiDroidWould() {
        val texts = (1..6).map { n -> "v$n {{c1::a$n}} {{c2::b$n}}" }
        val nano = System.nanoTime()
        val live = deckWith("AnkiWatch live session $nano", texts)
        val offline = deckWith("AnkiWatch offline session $nano", texts)
        val grades = listOf(1, 2, 3, 4, Wire.EASE_BURY)

        val applier = AnswerApplier(helper, AnswerDedupeStore(context))
        helper.setSelectedDeck(live.id)
        val position = helper.getScheduledCards(live.id, limit = 100)
            .mapIndexed { i, c -> (live.textOf.getValue(c.noteId) to c.cardOrd) to i }.toMap()
        val liveCounts = HashMap<Pair<String, Int>, Int>()
        runBlocking {
            var guard = 0
            while (true) {
                val top = helper.getScheduledCards(live.id, limit = 1).firstOrNull() ?: break
                val key = live.textOf.getValue(top.noteId) to top.cardOrd
                val n = liveCounts[key] ?: 0
                val ease = if (n == 0) grades[position.getValue(key) % grades.size] else 3
                applier.apply(WatchAnswer(UUID.randomUUID().toString(), top.noteId, top.cardOrd, ease, 3_000, live.id))
                liveCounts[key] = n + 1
                assertTrue("live session doesn't end", ++guard < 200)
            }
        }

        val pack = buildOfflinePack(helper, offline.id, "Offline")
        val queue = OfflineQueue(pack)
        val offlineCounts = HashMap<Pair<String, Int>, Int>()
        val recorded = ArrayList<QueuedAnswer>()
        var now = 0L
        var seq = 0L
        loop@ while (true) {
            when (val next = queue.next(now)) {
                is OfflineQueue.Next.Show -> {
                    val card = next.card
                    val key = offline.textOf.getValue(card.noteId) to card.cardOrd
                    val n = offlineCounts[key] ?: 0
                    val ease = if (n == 0) grades[pack.cards.indexOf(card) % grades.size] else 3
                    queue.answer(card.key, ease, now)
                    recorded += queued(card.noteId, card.cardOrd, ease, offline.id, ++seq)
                    offlineCounts[key] = n + 1
                    now += 8_000
                }
                is OfflineQueue.Next.Wait -> now = next.until
                OfflineQueue.Next.Finished -> break@loop
            }
            assertTrue("offline session doesn't end", seq < 200)
        }
        Log.i(TAG, "live answers per card: $liveCounts")
        Log.i(TAG, "offline answers per card: $offlineCounts")
        assertEquals(liveCounts, offlineCounts)

        val report = sync(FakeQueue(recorded.shuffled(Random(2))))
        assertEquals(recorded.size, report.applied)
        assertEquals(counts(live), counts(offline))
        assertEquals(dueLabels(live), dueLabels(offline))
    }

    @Test
    fun queuedGradesGoInOnceAndInTheOrderGiven() {
        val deck = deckWith("AnkiWatch order ${System.nanoTime()}", listOf("x {{c1::one}}", "y {{c1::two}}", "z {{c1::three}}"))
        helper.setSelectedDeck(deck.id)
        val (c1, c2, c3) = helper.getScheduledCards(deck.id, limit = 10)
        val items = listOf(
            queued(c1.noteId, 0, 1, deck.id, seq = 1), // Again …
            queued(c1.noteId, 0, 3, deck.id, seq = 2), // … then Good: the second learning step
            queued(c2.noteId, 0, 4, deck.id, seq = 3), // Easy: graduated
            queued(c3.noteId, 0, Wire.EASE_BURY, deck.id, seq = 4)
        )
        // Delivered backwards: applying Good before Again would leave c1 relearning instead.
        val firstQueue = FakeQueue(items.reversed())
        val first = sync(firstQueue)
        assertEquals(4, first.applied)
        assertEquals(Triple(0, 1, 0), counts(deck))
        val due = helper.getScheduledCards(deck.id, limit = 10)
        assertEquals(listOf(c1.noteId), due.map { it.noteId })
        val afterFirst = dueLabels(deck)
        // The watch is told about all four at once, in one ack.
        assertEquals(1, firstQueue.acks.size)
        assertEquals(names(items), firstQueue.acks.single().toSet())

        // Redelivered (a delete that didn't stick): nothing is applied twice, and the watch
        // is told again, so its copies go too.
        val againQueue = FakeQueue(items)
        val again = sync(againQueue)
        assertEquals(0, again.applied)
        assertEquals(4, again.duplicates)
        assertEquals(afterFirst, dueLabels(deck))
        assertEquals(names(items), againQueue.acks.flatten().toSet())

        // An item without an ease is skipped, not read as Bury; a live answer last gets a reply.
        // Both are done with, so both are acknowledged.
        val broken = queued(c1.noteId, 0, null, deck.id, seq = 10)
        val liveOne = queued(c1.noteId, 0, 3, deck.id, seq = 11, offline = false)
        val fake = FakeQueue(listOf(liveOne, broken))
        val third = sync(fake)
        assertEquals(1, third.malformed)
        assertEquals(1, third.applied)
        assertEquals(c1.noteId, third.replyTo?.noteId)
        assertTrue(fake.items.isEmpty())
        assertEquals(names(listOf(liveOne, broken)), fake.acks.flatten().toSet())
        assertEquals(Triple(0, 0, 0), counts(deck)) // Good on the second step graduated c1

        // Nothing queued: nothing to acknowledge.
        val empty = FakeQueue(emptyList())
        assertEquals(null, runBlocking { AnswerSync(helper, empty, AnswerDedupeStore(context), ExchangeLog(context)).run() })
        assertTrue(empty.acks.isEmpty())
    }

    @Test
    fun answersThatArriveGoInEvenIfTheQueueCantBeListed() {
        val deck = deckWith("AnkiWatch unlisted ${System.nanoTime()}", listOf("q {{c1::one}}", "r {{c1::two}}"))
        helper.setSelectedDeck(deck.id)
        val (a, b) = helper.getScheduledCards(deck.id, limit = 10)
        val broken = object : AnswerQueue {
            val deleted = ArrayList<Uri>()
            val acked = ArrayList<String>()
            override suspend fun pendingAnswers(): List<QueuedAnswer> = throw IllegalStateException("Wearable API unavailable")
            override suspend fun deleteAnswerItem(uri: Uri) {
                check(Wire.answerName(uri.path)!! in acked) { "deleted $uri before acknowledging it" }
                deleted += uri
            }
            override suspend fun acknowledge(names: List<String>) {
                acked += names
            }
        }
        val arrived = listOf(queued(b.noteId, 0, 4, deck.id, seq = 2, offline = false), queued(a.noteId, 0, 4, deck.id, seq = 1))
        val report = runBlocking {
            AnswerSync(helper, broken, AnswerDedupeStore(context), ExchangeLog(context)).run(arrived = arrived)
        }!!
        assertEquals(2, report.applied)
        assertEquals(b.noteId, report.replyTo?.noteId) // the live one came last
        assertEquals(arrived.map { it.first }.reversed(), broken.deleted) // applied in the order given
        assertEquals(names(arrived), broken.acked.toSet())
        assertEquals(Triple(0, 0, 0), counts(deck))
    }

    @Test
    fun aBigOfflineBacklogGoesInQuickly() {
        // Ten decks of twenty new cards, all answered offline and delivered at once.
        val nano = System.nanoTime()
        val decks = (1..10).map { d -> deckWith("AnkiWatch backlog $d $nano", (1..10).map { n -> "d$d n$n {{c1::a}} {{c2::b}}" }) }
        val rnd = Random(3)
        var seq = 0L
        val recorded = ArrayList<QueuedAnswer>()
        for (deck in decks) {
            val pack = buildOfflinePack(helper, deck.id, "Backlog")
            assertEquals(20, pack.cards.size)
            for (card in pack.cards) recorded += queued(card.noteId, card.cardOrd, listOf(3, 4, 4, Wire.EASE_BURY)[rnd.nextInt(4)], deck.id, ++seq)
        }
        val start = SystemClock.uptimeMillis()
        val queue = FakeQueue(recorded.shuffled(rnd))
        val report = sync(queue)
        val perAnswer = (SystemClock.uptimeMillis() - start) / recorded.size
        Log.i(TAG, "backlog: ${recorded.size} offline grades applied in ${perAnswer}ms each: ${report.outcome}")
        assertEquals(200, report.applied)
        assertTrue("${perAnswer}ms per grade", perAnswer < 500)
        for (deck in decks) assertEquals(0, counts(deck).first)
        // All 200 acknowledged, each once, a hundred at a time (each batch before its deletes).
        assertEquals(listOf(100, 100), queue.acks.map { it.size })
        assertEquals(names(recorded), queue.acks.flatten().toSet())
    }

    @Test
    fun gradesStayQueuedUntilTheWatchCanBeTold() {
        val deck = deckWith("AnkiWatch unconfirmed ${System.nanoTime()}", listOf("u {{c1::one}}", "v {{c1::two}}"))
        helper.setSelectedDeck(deck.id)
        val (a, b) = helper.getScheduledCards(deck.id, limit = 10)
        val items = listOf(queued(a.noteId, 0, 4, deck.id, seq = 1), queued(b.noteId, 0, 4, deck.id, seq = 2, offline = false))
        val queue = FakeQueue(items)
        queue.failAcks = true
        val first = sync(queue)
        // In AnkiDroid, but kept queued (and no next card sent) while the watch can't be told.
        assertEquals(2, first.applied)
        assertEquals(2, first.unconfirmed)
        assertEquals(null, first.replyTo)
        assertEquals(2, queue.items.size)
        assertEquals(Triple(0, 0, 0), counts(deck))

        queue.failAcks = false
        val second = sync(queue)
        assertEquals(0, second.applied) // not twice
        assertEquals(2, second.duplicates)
        assertEquals(0, second.unconfirmed)
        assertEquals(b.noteId, second.replyTo?.noteId)
        assertTrue(queue.items.isEmpty())
        assertEquals(names(items), queue.acks.flatten().toSet())
    }
}
