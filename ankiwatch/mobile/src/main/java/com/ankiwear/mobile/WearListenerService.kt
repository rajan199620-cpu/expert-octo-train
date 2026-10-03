package com.ankiwear.mobile

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.ankiwatch.core.Wire
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Listens for messages/data from the Wear OS watch and handles card requests and review
 * answers.
 *
 * IMPORTANT — why the work runs synchronously here:
 * A bound [WearableListenerService] is kept alive by the framework only for the duration
 * of the callback. Play Services unbinds right after [onMessageReceived]/[onDataChanged]
 * returns, and Android then destroys the service. The previous version dispatched the real
 * work to a background CoroutineScope and returned immediately — so the service was often
 * torn down (and the scope cancelled) mid-write. That truncated answers and dropped
 * responses, which is the intermittent "had to tap twice / stuck on Loading" behaviour.
 *
 * Doing the work synchronously (via [runBlocking]) inside the callback keeps the service
 * bound until we're done. The callbacks are already delivered on a background thread and
 * one-at-a-time, so this doesn't block the main thread. A short wake lock guards against
 * the phone dozing mid-operation.
 */
class WearListenerService : WearableListenerService() {

    // Serializes processing across the message and data callbacks. Largely belt-and-
    // suspenders now that work is synchronous and the framework delivers events serially,
    // but it costs nothing and protects against any overlap between the two callbacks.
    private val messageMutex = Mutex()
    private lateinit var ankiHelper: AnkiDroidHelper
    private lateinit var dataLayerManager: DataLayerManager
    private lateinit var answerDedupe: AnswerDedupeStore
    private lateinit var powerManager: PowerManager
    private lateinit var exchangeLog: ExchangeLog

    companion object {
        private const val TAG = "WearListenerService"
        private const val WAKELOCK_TIMEOUT_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WearListenerService onCreate")
        ankiHelper = AnkiDroidHelper(this)
        dataLayerManager = DataLayerManager(this)
        answerDedupe = AnswerDedupeStore(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        exchangeLog = ExchangeLog(this)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Message received: ${messageEvent.path}")
        when (messageEvent.path) {
            DataLayerManager.PATH_REQUEST_DECKS -> {
                exchangeLog.request("deck list")
                runProcessing { handleDeckRequest() }
            }
            DataLayerManager.PATH_REQUEST_CARDS -> {
                exchangeLog.request("cards")
                val data = messageEvent.data
                runProcessing { handleCardRequest(data) }
            }
            // Legacy path: an older watch build that still sends answers as messages. No
            // UUID, so no dedupe/ack — but still applied so those users aren't broken.
            DataLayerManager.PATH_REVIEW_ANSWER -> {
                val data = messageEvent.data
                runProcessing {
                    val req = parseAnswer(DataMap.fromByteArray(data), uuid = null)
                    handleAnswer(req, ackUri = null)
                }
            }
            else -> Log.w(TAG, "Unknown message path: ${messageEvent.path}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // The watch writes review answers as DataItems under /answer/<uuid>. Collect them
        // (a burst of taps can arrive in one buffer) and process in timestamp order so
        // grades are applied in the order the user tapped them.
        val answers = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .mapNotNull { event ->
                val path = event.dataItem.uri.path ?: return@mapNotNull null
                if (!path.startsWith(DataLayerManager.PATH_ANSWER_PREFIX)) return@mapNotNull null
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                event.dataItem.uri to map
            }
            .sortedBy { it.second.getLong(DataLayerManager.KEY_TIMESTAMP) }

        if (answers.isEmpty()) return

        val buries = answers.count { it.second.getInt(DataLayerManager.KEY_EASE) == Wire.EASE_BURY }
        exchangeLog.request(
            when {
                answers.size == 1 -> if (buries == 1) "bury" else "answer"
                else -> "${answers.size} answers"
            }
        )
        runProcessing {
            for ((uri, map) in answers) {
                val uuid = map.getString(DataLayerManager.KEY_ANSWER_UUID)
                val req = parseAnswer(map, uuid = uuid)
                handleAnswer(req, ackUri = uri)
            }
        }
    }

    /**
     * Runs [block] synchronously under a short wake lock and the processing mutex, keeping
     * the service bound and the CPU awake until the AnkiDroid write + response are done.
     */
    private fun runProcessing(block: suspend () -> Unit) {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AnkiWatch:processing"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        try {
            runBlocking { messageMutex.withLock { block() } }
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            exchangeLog.outcome("failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun handleDeckRequest() {
        if (!ankiHelper.isAnkiDroidInstalled()) {
            reportError("AnkiDroid is not installed on this phone.")
            return
        }
        if (!ankiHelper.hasPermission()) {
            reportError("AnkiWatch needs permission to access AnkiDroid. Please open AnkiWatch Phone on your phone.")
            return
        }

        val decks = ankiHelper.getDecks()
        if (decks.isEmpty()) {
            reportError("No decks found in AnkiDroid.")
            return
        }
        try {
            exchangeLog.outcome(dataLayerManager.sendDecks(decks))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending decks", e)
            reportError("The phone couldn't send your decks: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Tells the watch what went wrong and keeps it on the phone's status screen too. */
    private suspend fun reportError(message: String) {
        exchangeLog.outcome("error: $message")
        dataLayerManager.sendError(message)
    }

    private suspend fun handleCardRequest(data: ByteArray) {
        try {
            val dataMap = DataMap.fromByteArray(data)
            val deckId = dataMap.getLong(DataLayerManager.KEY_DECK_ID)

            if (!ankiHelper.hasPermission()) {
                reportError("Permission to access AnkiDroid not granted.")
                return
            }

            sendCardsForDeck(deckId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling card request", e)
            reportError("Error fetching cards: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Queries AnkiDroid for the given deck's next scheduled cards + due breakdown and pushes
     * them to the watch.
     */
    private suspend fun sendCardsForDeck(deckId: Long) {
        // CRITICAL: Update the selected_deck URI to trigger AnkiDroid's internal
        // deck-selection + scheduler-reset path. This is the equivalent of tapping the
        // deck in AnkiDroid's UI. Without it, the scheduler isn't properly initialized
        // for the deck and answerCard returns 0 rows on cold start.
        ankiHelper.setSelectedDeck(deckId)

        val cards = ankiHelper.getScheduledCards(deckId)
        // AnkiDroid's deck_count comes from sched.deckDueTree(), which already rolls
        // subdeck counts up into the parent. So for a parent deck this breakdown ALREADY
        // covers the whole subtree — we must NOT sum the children again.
        val deckCounts = ankiHelper.getDeckDueBreakdown(deckId)
        exchangeLog.outcome(dataLayerManager.sendCards(
            cards,
            remaining = deckCounts?.totalDue ?: cards.size,
            newRemaining = deckCounts?.newCount ?: 0,
            learnRemaining = deckCounts?.learnCount ?: 0,
            reviewRemaining = deckCounts?.reviewCount ?: 0,
            deckId = deckId
        ))
    }

    /** Parsed review-answer request. */
    private data class AnswerRequest(
        val noteId: Long,
        val cardOrd: Int,
        val ease: Int,
        val timeTaken: Long,
        val deckId: Long,
        val uuid: String?
    )

    private fun parseAnswer(dataMap: DataMap, uuid: String?): AnswerRequest =
        AnswerRequest(
            noteId = dataMap.getLong(DataLayerManager.KEY_NOTE_ID),
            cardOrd = dataMap.getInt(DataLayerManager.KEY_CARD_ORD),
            ease = dataMap.getInt(DataLayerManager.KEY_EASE),
            timeTaken = dataMap.getLong(DataLayerManager.KEY_TIME_TAKEN),
            deckId = dataMap.getLong(DataLayerManager.KEY_DECK_ID),
            uuid = uuid
        )

    private suspend fun handleAnswer(req: AnswerRequest, ackUri: Uri?) {
        try {
            val alreadyApplied = req.uuid != null && answerDedupe.isProcessed(req.uuid)
            if (alreadyApplied) {
                Log.d(TAG, "Answer ${req.uuid} already applied — skipping write, refreshing watch")
            } else {
                applyAnswer(req)
                req.uuid?.let { answerDedupe.markProcessed(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting review answer", e)
            // #8: don't leave the watch hanging on a spinner — surface the failure.
            reportError("Couldn't save your answer: ${e.message ?: e.javaClass.simpleName}")
            ackUri?.let { dataLayerManager.deleteAnswerItem(it) }
            return
        }
        try {
            // Always send fresh cards back, even for a duplicate, so the watch re-syncs.
            sendNextCards(req)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending the next cards", e)
            reportError("Your answer was saved, but the next card couldn't be sent: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // ACK by deleting the answer DataItem now that it's applied (or was a dup).
            ackUri?.let { dataLayerManager.deleteAnswerItem(it) }
        }
    }

    /**
     * Applies the grade to AnkiDroid, mirroring what its reviewer UI does (select deck →
     * warm up scheduler → answer), with a couple of retries for the cold-start case.
     */
    private suspend fun applyAnswer(req: AnswerRequest): Boolean {
        if (req.ease == Wire.EASE_BURY) {
            val buried = ankiHelper.buryCard(req.noteId, req.cardOrd)
            Log.d(TAG, "Bury noteId=${req.noteId} cardOrd=${req.cardOrd}: $buried")
            return buried
        }
        if (req.deckId != 0L) {
            val selectedOk = ankiHelper.setSelectedDeck(req.deckId)
            delay(50) // brief settle for AnkiDroid to process the selection
            val warmedNoteId = ankiHelper.warmUpScheduler(req.deckId)
            Log.d(TAG, "Pre-answer setup: selectedOk=$selectedOk warmedNoteId=$warmedNoteId " +
                "want=${req.noteId} (match=${warmedNoteId == req.noteId})")
        }

        var answered = ankiHelper.answerCard(req.noteId, req.cardOrd, req.ease, req.timeTaken)

        if (!answered && req.deckId != 0L) {
            Log.w(TAG, "answerCard attempt 1 failed — re-selecting deck and retrying...")
            for (attempt in 2..3) {
                delay(100L * attempt)
                ankiHelper.setSelectedDeck(req.deckId)
                delay(50)
                ankiHelper.warmUpScheduler(req.deckId)
                delay(50)
                answered = ankiHelper.answerCard(req.noteId, req.cardOrd, req.ease, req.timeTaken)
                if (answered) {
                    Log.d(TAG, "answerCard succeeded on attempt $attempt")
                    break
                }
                Log.w(TAG, "answerCard attempt $attempt failed")
            }
        }

        if (answered) {
            Log.d(TAG, "Answered card noteId=${req.noteId} ease=${req.ease} deckId=${req.deckId}")
        } else {
            Log.w(TAG, "answerCard failed after all retries — card noteId=${req.noteId} " +
                "cardOrd=${req.cardOrd} may be stale or already answered.")
        }
        return answered
    }

    /**
     * Queries the post-answer schedule and sends the next card(s) to the watch, handling
     * AnkiDroid's REVIEW_INFO_URI quirks (see inline comments).
     */
    private suspend fun sendNextCards(req: AnswerRequest) {
        if (req.deckId == 0L) return

        // Brief settle so AnkiDroid's deck-count cache reflects the write before we query.
        delay(150)

        // AnkiDroid's REVIEW_INFO_URI can return the just-answered card back as the "next
        // scheduled" card on Good/Hard/Easy. We filter that out. BUT for Again (ease == 1)
        // the same card legitimately comes back, so we keep it.
        val rawNextCards = ankiHelper.getScheduledCards(req.deckId)
        val deckCounts = ankiHelper.getDeckDueBreakdown(req.deckId)
        val deckTotalDue = deckCounts?.totalDue ?: 0

        val nextCards = if (req.ease == 1) {
            if (rawNextCards.isNotEmpty()) {
                rawNextCards
            } else if (deckTotalDue > 0) {
                // Again parked the card in a future learning step and it's no longer
                // "currently due" — synthesize it so the watch keeps showing it.
                ankiHelper.synthesizeCardForRetry(req.noteId, req.cardOrd)
                    ?.let { listOf(it) }
                    ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            rawNextCards.filterNot { it.noteId == req.noteId && it.cardOrd == req.cardOrd }
        }

        // If we filtered the only card AnkiDroid offered, treat the session as done for now.
        val remaining = if (nextCards.isEmpty()) 0 else deckTotalDue
        Log.d(
            TAG,
            "Post-answer: raw=${rawNextCards.size} filtered=${nextCards.size} " +
                "deckTotalDue=$deckTotalDue remaining=$remaining " +
                "rawFirstNoteId=${rawNextCards.firstOrNull()?.noteId}"
        )
        exchangeLog.outcome(dataLayerManager.sendCards(
            nextCards,
            remaining = remaining,
            newRemaining = if (nextCards.isEmpty()) 0 else deckCounts?.newCount ?: 0,
            learnRemaining = if (nextCards.isEmpty()) 0 else deckCounts?.learnCount ?: 0,
            reviewRemaining = if (nextCards.isEmpty()) 0 else deckCounts?.reviewCount ?: 0,
            deckId = req.deckId,
            ackedAnswerUuid = req.uuid
        ))
    }
}
