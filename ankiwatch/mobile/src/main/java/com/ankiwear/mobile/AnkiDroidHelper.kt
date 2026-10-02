package com.ankiwear.mobile

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.ankiwatch.core.HtmlSanitizer
import com.ankiwatch.core.NotePlanner

/**
 * Handles all communication with AnkiDroid's ContentProvider.
 *
 * Constants are inlined from AnkiDroid's FlashCardsContract to avoid a dependency
 * on the AnkiDroid API library (which has broken Maven/JitPack artifacts).
 *
 * Source of truth: https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt
 */
class AnkiDroidHelper(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    // ── AnkiDroid ContentProvider constants (inlined from FlashCardsContract) ──

    companion object {
        private const val TAG = "AnkiDroidHelper"
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        // Authority
        private const val AUTHORITY = "com.ichi2.anki.flashcards"

        // Deck URIs & columns
        private val DECK_CONTENT_ALL_URI: Uri = Uri.parse("content://$AUTHORITY/decks")
        // The selected_deck URI is what AnkiDroid's reviewer activity hits when
        // a user taps a deck to start reviewing. Updating it triggers AnkiDroid's
        // internal deck-selection + scheduler-reset path, which is REQUIRED before
        // answerCard will accept an update. Querying the regular deck URI does NOT
        // do this — that's read-only and leaves the scheduler in whatever state
        // it was already in.
        private val SELECTED_DECK_URI: Uri = Uri.parse("content://$AUTHORITY/selected_deck")
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val DECK_COUNTS = "deck_count"

        // ReviewInfo (schedule) URIs & columns
        private val REVIEW_INFO_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")
        private const val NOTE_ID = "note_id"
        private const val CARD_ORD = "ord"
        private const val BUTTON_COUNT = "button_count"
        // JSON-array string of next-review-time predictions, one per ease button.
        // E.g. ["<1m", "10m", "1d", "3d"]. Used to show intervals on the watch.
        private const val NEXT_REVIEW_TIMES = "next_review_times"
        private const val EASE = "answer_ease"
        private const val TIME_TAKEN = "time_taken"

        // Card URIs — template-rendered question/answer, used for non-cloze notes.
        // Pattern: content://com.ichi2.anki.flashcards/notes/{noteId}/cards/{cardOrd}
        private const val CARD_URI_TEMPLATE = "content://$AUTHORITY/notes/%d/cards/%d"
        private const val CARD_QUESTION = "question"
        private const val CARD_ANSWER = "answer"

        // Note and note-type URIs — raw fields, used to lay out cloze notes on the watch.
        private const val NOTE_URI_TEMPLATE = "content://$AUTHORITY/notes/%d"
        private const val NOTE_MODEL_ID = "mid"
        private const val NOTE_FIELDS = "flds"
        private const val MODEL_URI_TEMPLATE = "content://$AUTHORITY/models/%d"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_TYPE = "type"

        const val CONTENT_UNAVAILABLE = "(card content unavailable)"
    }

    /** Note type facts that don't change between cards; cached per request. */
    internal data class ModelInfo(val name: String, val fieldNames: List<String>, val type: Int?)

    fun isAnkiDroidInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasPermission(): Boolean {
        val result = context.checkPermission(
            READ_WRITE_PERMISSION,
            android.os.Process.myPid(),
            android.os.Process.myUid()
        )
        return result == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the total due count (new + learn + review) for a single deck.
     */
    fun getDeckTotalDue(deckId: Long): Int {
        return getDeckDueBreakdown(deckId)?.totalDue ?: 0
    }

    /**
     * Returns per-category remaining counts for a deck, or null if deck not found.
     *
     * Note: AnkiDroid sources these from sched.deckDueTree(), which already aggregates
     * subdeck counts into their parent. So for a parent deck this breakdown covers the
     * whole subtree already — callers must not sum descendants on top of it.
     */
    fun getDeckDueBreakdown(deckId: Long): DeckData? {
        return getDecks().firstOrNull { it.id == deckId }
    }

    /**
     * Returns list of decks with their id, name, and due counts.
     */
    fun getDecks(): List<DeckData> {
        val decks = mutableListOf<DeckData>()
        try {
            val cursor = contentResolver.query(
                DECK_CONTENT_ALL_URI,
                arrayOf(DECK_ID, DECK_NAME, DECK_COUNTS),
                null, null, null
            )
            cursor?.use {
                val idIdx = it.getColumnIndex(DECK_ID)
                val nameIdx = it.getColumnIndex(DECK_NAME)
                val countsIdx = it.getColumnIndex(DECK_COUNTS)

                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx)
                    val countsJson = it.getString(countsIdx)
                    val counts = parseDeckCounts(countsJson)
                    decks.add(DeckData(id, name, counts.new, counts.learn, counts.review))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching decks", e)
        }
        return decks
    }

    /**
     * Fetches up to [limit] scheduled cards for the given deck.
     * First queries ReviewInfo for note IDs, then fetches card content for each.
     */
    fun getScheduledCards(deckId: Long, limit: Int = 3): List<CardData> {
        val cards = mutableListOf<CardData>()
        val models = HashMap<Long, ModelInfo?>()
        try {
            val cursor = contentResolver.query(
                REVIEW_INFO_URI,
                null,
                "limit=?, deckID=?",
                arrayOf(limit.toString(), deckId.toString()),
                null
            )
            cursor?.use {
                val nextReviewIdx = it.getColumnIndex(NEXT_REVIEW_TIMES)
                while (it.moveToNext()) {
                    val noteId = it.getLong(it.getColumnIndexOrThrow(NOTE_ID))
                    val cardOrd = it.getInt(it.getColumnIndexOrThrow(CARD_ORD))
                    val buttonCount = it.getInt(it.getColumnIndexOrThrow(BUTTON_COUNT))
                    val nextReviewTimes = if (nextReviewIdx >= 0) {
                        parseNextReviewTimes(it.getString(nextReviewIdx))
                    } else emptyList()

                    cards.add(buildCard(noteId, cardOrd, buttonCount, nextReviewTimes, models))
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            // Known AnkiDroid bug (#14621) in 2.17alpha
            Log.e(TAG, "AnkiDroid UnsatisfiedLinkError — suggest user update AnkiDroid", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching scheduled cards", e)
        }
        return cards
    }

    /**
     * Builds what the watch needs to show one card. Cloze notes travel as their raw cloze
     * field + tested cloze number; everything else as sanitized template HTML.
     */
    private fun buildCard(
        noteId: Long,
        cardOrd: Int,
        buttonCount: Int,
        nextReviewTimes: List<String>,
        models: MutableMap<Long, ModelInfo?>
    ): CardData {
        val cloze = getClozePayload(noteId, cardOrd, models)
        val (question, answer) = if (cloze != null) "" to "" else getCardContent(noteId, cardOrd)
        return CardData(
            noteId = noteId,
            cardOrd = cardOrd,
            question = question,
            answer = answer,
            buttonCount = buttonCount,
            nextReviewTimes = nextReviewTimes,
            cloze = cloze
        )
    }

    /** Raw-field payload for a cloze card, or null when the card should use its template. */
    internal fun getClozePayload(
        noteId: Long,
        cardOrd: Int,
        models: MutableMap<Long, ModelInfo?> = HashMap()
    ): ClozePayload? {
        return try {
            val (modelId, fields) = getNoteFields(noteId) ?: return null
            val model = models.getOrPut(modelId) { getModelInfo(modelId) } ?: return null
            when (val plan = NotePlanner.plan(model.type, model.fieldNames, fields, cardOrd)) {
                is NotePlanner.Plan.Cloze -> ClozePayload(
                    content = HtmlSanitizer.forWatch(plan.content),
                    clozeNumber = plan.clozeNumber,
                    extras = plan.extras.map { (name, value) -> name to HtmlSanitizer.forWatch(value) },
                    modelName = model.name
                )
                NotePlanner.Plan.Text -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read raw fields for note $noteId — using the card template", e)
            null
        }
    }

    private fun getNoteFields(noteId: Long): Pair<Long, List<String>>? {
        val uri = Uri.parse(String.format(NOTE_URI_TEMPLATE, noteId))
        contentResolver.query(uri, arrayOf(NOTE_MODEL_ID, NOTE_FIELDS), null, null, null)?.use {
            if (!it.moveToFirst()) return null
            val modelId = it.getLong(it.getColumnIndexOrThrow(NOTE_MODEL_ID))
            val fields = NotePlanner.splitFields(it.getString(it.getColumnIndexOrThrow(NOTE_FIELDS)))
            return modelId to fields
        }
        return null
    }

    private fun getModelInfo(modelId: Long): ModelInfo? {
        val uri = Uri.parse(String.format(MODEL_URI_TEMPLATE, modelId))
        val (name, fieldNames) = contentResolver.query(
            uri, arrayOf(MODEL_NAME, MODEL_FIELD_NAMES), null, null, null
        )?.use {
            if (!it.moveToFirst()) return null
            it.getString(it.getColumnIndexOrThrow(MODEL_NAME)).orEmpty() to
                NotePlanner.splitFields(it.getString(it.getColumnIndexOrThrow(MODEL_FIELD_NAMES)))
        } ?: return null
        // Queried separately so an AnkiDroid version without the column can't sink the rest;
        // without it, NotePlanner recognises cloze notes by their content.
        val type = try {
            contentResolver.query(uri, arrayOf(MODEL_TYPE), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(MODEL_TYPE)) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Model type unavailable for $modelId: ${e.message}")
            null
        }
        return ModelInfo(name, fieldNames, type)
    }

    /**
     * Fetches the template-rendered question and answer HTML for a card, minus scripts,
     * styles and image data the watch can't use.
     */
    private fun getCardContent(noteId: Long, cardOrd: Int): Pair<String, String> {
        try {
            val cardUri = Uri.parse(String.format(CARD_URI_TEMPLATE, noteId, cardOrd))
            val cursor = contentResolver.query(
                cardUri,
                arrayOf(CARD_QUESTION, CARD_ANSWER),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val qIdx = it.getColumnIndex(CARD_QUESTION)
                    val aIdx = it.getColumnIndex(CARD_ANSWER)
                    val question = if (qIdx >= 0) HtmlSanitizer.forWatch(it.getString(qIdx).orEmpty()) else ""
                    val answer = if (aIdx >= 0) HtmlSanitizer.forWatch(it.getString(aIdx).orEmpty()) else ""
                    return Pair(question, answer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching card content noteId=$noteId cardOrd=$cardOrd", e)
        }
        return Pair(CONTENT_UNAVAILABLE, CONTENT_UNAVAILABLE)
    }

    /**
     * Builds a CardData for a card we know exists but isn't currently in the schedule
     * (e.g. just got "Again" and is parked in a future learning step). Used to keep the
     * watch showing the card immediately rather than dropping to the Done screen.
     *
     * Returns null if the card content can't be fetched. buttonCount defaults to 4
     * (AnkiDroid's most common setup); the actual scheduling decision happens on the
     * phone when the answer comes back, so this default is harmless.
     */
    fun synthesizeCardForRetry(noteId: Long, cardOrd: Int, buttonCount: Int = 4): CardData? {
        val card = buildCard(noteId, cardOrd, buttonCount, emptyList(), HashMap())
        if (card.cloze == null && card.question == CONTENT_UNAVAILABLE) return null
        return card
    }

    /**
     * Submits a review answer for a card.
     *
     * @return true if the answer was accepted (rows > 0), false otherwise.
     */
    fun answerCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long): Boolean {
        return try {
            val values = ContentValues().apply {
                put(NOTE_ID, noteId)
                put(CARD_ORD, cardOrd)
                put(EASE, ease)
                put(TIME_TAKEN, timeTakenMs)
            }
            val rows = contentResolver.update(REVIEW_INFO_URI, values, null, null)
            Log.d(TAG, "answerCard update returned $rows rows (noteId=$noteId cardOrd=$cardOrd ease=$ease)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error answering card noteId=$noteId cardOrd=$cardOrd", e)
            false
        }
    }

    /**
     * Explicitly sets the selected deck in AnkiDroid by UPDATING the selected_deck URI.
     *
     * This is the critical operation that mirrors what AnkiDroid's UI does when the
     * user taps a deck to start reviewing. It triggers AnkiDroid's:
     *   1. Deck selection in the collection (col.decks.select)
     *   2. Scheduler reset (col.reset) — this is what makes the scheduler ready to
     *      serve and ANSWER cards for this deck
     *
     * Without this, the scheduler may not be properly initialized for the deck and
     * answerCard returns 0 rows. Doing a card on AnkiDroid's UI works around this
     * by triggering the same internal state — that's why users see the bug disappear
     * after a single card answered on the phone.
     *
     * The old `selectDeck` (which QUERIED the deck URI) did NOT do this — queries
     * are read-only and don't change AnkiDroid's review state.
     */
    fun setSelectedDeck(deckId: Long): Boolean {
        return try {
            val values = ContentValues().apply { put(DECK_ID, deckId) }
            val rows = contentResolver.update(SELECTED_DECK_URI, values, null, null)
            Log.d(TAG, "setSelectedDeck($deckId) returned $rows rows")
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "setSelectedDeck($deckId) failed: ${e.message}")
            false
        }
    }

    /**
     * "Warms up" AnkiDroid's scheduler by querying the schedule for the given deck.
     *
     * AnkiDroid's ContentProvider maintains an internal review session that must be
     * initialized before answerCard (an `update` call) will succeed. The session is
     * created lazily when the schedule query cursor is actually READ (not just opened).
     * If AnkiDroid's process was killed between our previous query and the user's
     * answer (common during watch screen-off), the session is gone and answerCard
     * returns 0 rows.
     *
     * This method queries with limit=1 and READS the cursor to force full initialization.
     *
     * @return the noteId of the card that was loaded as "current", or null if nothing loaded.
     */
    fun warmUpScheduler(deckId: Long): Long? {
        try {
            val cursor = contentResolver.query(
                REVIEW_INFO_URI,
                null,
                "limit=?, deckID=?",
                arrayOf("1", deckId.toString()),
                null
            )
            var loadedNoteId: Long? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    loadedNoteId = it.getLong(it.getColumnIndexOrThrow(NOTE_ID))
                    // Read additional columns to ensure full cursor materialization
                    val cardOrd = it.getInt(it.getColumnIndexOrThrow(CARD_ORD))
                    Log.d(TAG, "Scheduler warmed for deck=$deckId: noteId=$loadedNoteId cardOrd=$cardOrd")
                } else {
                    Log.w(TAG, "Scheduler warmup for deck=$deckId returned empty cursor")
                }
            }
            return loadedNoteId
        } catch (e: Exception) {
            Log.e(TAG, "Error warming up scheduler for deckId=$deckId", e)
            return null
        }
    }

    /**
     * Parses AnkiDroid's NEXT_REVIEW_TIMES column. The contract says JSON array,
     * but in practice the format varies across versions — sometimes a JSON string array,
     * sometimes a Kotlin-style toString. Handle both leniently.
     */
    private fun parseNextReviewTimes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        // The contract documents this as a JSON array. Parse it as JSON FIRST so that
        // localized intervals containing a comma — e.g. "1,5 Mo" in comma-decimal
        // locales (de, uk, fr, …) — stay intact. A naive comma split turns "1,5 Mo"
        // into "1" and "5 Mo", shifting every label one button over so Good shows
        // Hard's predicted interval. Fall back to the lenient split only if the payload
        // isn't valid JSON (some AnkiDroid versions emit a Kotlin-style toString).
        try {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            if (out.isNotEmpty()) return out
        } catch (_: Exception) {
            // Not valid JSON — fall through to the lenient parser below.
        }
        return try {
            raw.trim()
                .removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().trim('"').trim('\'') }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class DeckCounts(val new: Int, val learn: Int, val review: Int)

    private fun parseDeckCounts(json: String?): DeckCounts {
        if (json.isNullOrBlank()) return DeckCounts(0, 0, 0)
        return try {
            val cleaned = json.trim().removePrefix("[").removeSuffix("]")
            val parts = cleaned.split(",").map { it.trim().toIntOrNull() ?: 0 }
            // AnkiDroid serializes deck_count as [learn, review, new] — confirmed in
            // FlashCardsContract.Deck. We had it as [new, learn, review], which made
            // review-state cards (green in AnkiDroid) appear under our "learn" (red)
            // slot on the watch.
            DeckCounts(
                learn = parts.getOrElse(0) { 0 },
                review = parts.getOrElse(1) { 0 },
                new = parts.getOrElse(2) { 0 }
            )
        } catch (e: Exception) {
            DeckCounts(0, 0, 0)
        }
    }
}

/** Deck info sent to the watch. */
data class DeckData(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int
) {
    val totalDue: Int get() = newCount + learnCount + reviewCount
}

/** Card info sent to the watch. */
data class CardData(
    val noteId: Long,
    val cardOrd: Int,
    /** Sanitized template HTML; empty for cloze cards, which use [cloze]. */
    val question: String,
    val answer: String,
    val buttonCount: Int,
    /** Predicted next-review labels, one per ease button (e.g. "<1m", "10m", "1d"). */
    val nextReviewTimes: List<String> = emptyList(),
    val cloze: ClozePayload? = null
)

/** A cloze card as raw fields: the watch lays it out with the Enhanced Cloze rules. */
data class ClozePayload(
    val content: String,
    val clozeNumber: Int,
    val extras: List<Pair<String, String>>,
    val modelName: String
)
