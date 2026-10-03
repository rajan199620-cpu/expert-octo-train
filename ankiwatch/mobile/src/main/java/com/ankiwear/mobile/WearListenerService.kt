package com.ankiwear.mobile

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ankiwatch.core.Wire
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
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
    private lateinit var answerSync: AnswerSync

    companion object {
        private const val TAG = "WearListenerService"
        private const val WAKELOCK_TIMEOUT_MS = 30_000L
        // A watch back from an offline session can bring hundreds of grades at once, and an
        // offline download reads hundreds of notes. The lock is released as soon as the work
        // is done; this only caps it.
        private const val LONG_WAKELOCK_TIMEOUT_MS = 10 * 60_000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WearListenerService onCreate")
        ankiHelper = AnkiDroidHelper(this)
        dataLayerManager = DataLayerManager(this)
        answerDedupe = AnswerDedupeStore(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        exchangeLog = ExchangeLog(this)
        answerSync = AnswerSync(ankiHelper, dataLayerManager, answerDedupe, exchangeLog)
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
            Wire.PATH_REQUEST_OFFLINE -> {
                exchangeLog.request("offline download")
                val data = messageEvent.data
                runProcessing(LONG_WAKELOCK_TIMEOUT_MS) { handleOfflineRequest(data) }
            }
            // Legacy path: an older watch build that still sends answers as messages. No
            // UUID, so no dedupe/ack — but still applied so those users aren't broken.
            DataLayerManager.PATH_REVIEW_ANSWER -> {
                val data = messageEvent.data
                runProcessing {
                    val answer = WatchAnswer.from(DataMap.fromByteArray(data))
                    exchangeLog.request("answer")
                    exchangeLog.outcome(answerSync.applyOne(answer).name.lowercase())
                    sendNextCards(answer)
                }
            }
            else -> Log.w(TAG, "Unknown message path: ${messageEvent.path}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // The watch writes review answers as DataItems under /answer/<uuid>. Rather than
        // apply just the ones in this event, apply everything queued, in the order it was
        // given on the watch: after an offline session hundreds arrive over several events.
        val anyAnswer = dataEvents.any { event ->
            event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path?.startsWith(DataLayerManager.PATH_ANSWER_PREFIX) == true
        }
        if (!anyAnswer) return
        runProcessing(LONG_WAKELOCK_TIMEOUT_MS) { syncAnswers() }
    }

    /**
     * Applies the queued answers. Problems are reported to the watch; a live answer gets the
     * next cards back, offline ones don't (the watch already has its cards).
     */
    private suspend fun syncAnswers() {
        val report = answerSync.run() ?: return
        report.stopped?.let {
            reportError("Your grades are safe on the watch but didn't go into AnkiDroid yet: $it. They go in next time.")
            return
        }
        if (report.malformed > 0) {
            reportError("${report.malformed} grade${if (report.malformed == 1) "" else "s"} from the watch couldn't be read and were skipped.")
        }
        val replyTo = report.replyTo ?: return
        try {
            // Always send fresh cards back, even for a duplicate, so the watch re-syncs.
            sendNextCards(replyTo)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending the next cards", e)
            reportError("Your answer was saved, but the next card couldn't be sent: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Runs [block] synchronously under a short wake lock and the processing mutex, keeping
     * the service bound and the CPU awake until the AnkiDroid write + response are done.
     */
    private fun runProcessing(timeoutMs: Long = WAKELOCK_TIMEOUT_MS, block: suspend () -> Unit) {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AnkiWatch:processing"
        )
        wakeLock.acquire(timeoutMs)
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

            // Grades still queued (say, from an offline session) go in first, so the cards
            // sent back don't include ones already answered on the watch.
            answerSync.run(log = false)
            sendCardsForDeck(deckId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling card request", e)
            reportError("Error fetching cards: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Sends the watch a deck's whole due queue to review without the phone. Grades still
     * queued from an earlier session go in first, so the download doesn't bring back cards
     * already answered.
     */
    private suspend fun handleOfflineRequest(data: ByteArray) {
        try {
            val dataMap = DataMap.fromByteArray(data)
            val deckId = dataMap.getLong(DataLayerManager.KEY_DECK_ID)
            val deckName = dataMap.getString(DataLayerManager.KEY_DECK_NAME).orEmpty()
            if (!ankiHelper.isAnkiDroidInstalled()) {
                reportError("AnkiDroid is not installed on this phone.")
                return
            }
            if (!ankiHelper.hasPermission()) {
                reportError("AnkiWatch needs permission to access AnkiDroid. Please open AnkiWatch Phone on your phone.")
                return
            }
            answerSync.run(log = false)
            val pack = buildOfflinePack(ankiHelper, deckId, deckName)
            exchangeLog.outcome(dataLayerManager.sendOfflinePack(pack))
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing the offline download", e)
            reportError("The phone couldn't prepare the offline download: ${e.message ?: e.javaClass.simpleName}")
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

    /**
     * Queries the post-answer schedule and sends the next card(s) to the watch, handling
     * AnkiDroid's REVIEW_INFO_URI quirks (see inline comments).
     */
    private suspend fun sendNextCards(req: WatchAnswer) {
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
