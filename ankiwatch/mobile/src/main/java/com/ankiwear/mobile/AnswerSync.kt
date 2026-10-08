package com.ankiwear.mobile

import android.net.Uri
import android.util.Log
import com.ankiwatch.core.Wire
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One grade from the watch, as its DataItem (or legacy message) carries it. */
data class WatchAnswer(
    val uuid: String?,
    val noteId: Long,
    val cardOrd: Int,
    /** A grade 1–4 or [Wire.EASE_BURY]; -1 when the item carried none. */
    val ease: Int,
    val timeTaken: Long,
    val deckId: Long,
    /** Given from an offline download: applied without sending cards back. */
    val offline: Boolean = false,
    /** The watch's running number for its answers; 0 from builds before offline review. */
    val seq: Long = 0,
    /** Watch clock when it was given. */
    val timestamp: Long = 0
) {
    companion object {
        fun from(map: DataMap): WatchAnswer = WatchAnswer(
            uuid = map.getString(Wire.KEY_ANSWER_UUID)?.takeIf { it.isNotEmpty() },
            noteId = map.getLong(Wire.KEY_NOTE_ID),
            cardOrd = map.getInt(Wire.KEY_CARD_ORD),
            // -1, not getInt's default 0: an answer without an ease must never read as Bury.
            ease = map.getInt(Wire.KEY_EASE, -1),
            timeTaken = map.getLong(Wire.KEY_TIME_TAKEN),
            deckId = map.getLong(Wire.KEY_DECK_ID),
            offline = map.getBoolean(Wire.KEY_OFFLINE, false),
            seq = map.getLong(Wire.KEY_SEQ, 0L),
            timestamp = map.getLong(Wire.KEY_TIMESTAMP, 0L)
        )

        /** The order the watch gave its answers in. */
        val ORDER: Comparator<WatchAnswer> = compareBy<WatchAnswer>({ it.seq }, { it.timestamp })
    }
}

/**
 * Puts the watch's grades into AnkiDroid, each once. AnkiDroid works out a graded card's
 * new schedule from that card's own state, so grades can arrive late and in a batch (an
 * offline session's worth) and still schedule exactly as if given one by one.
 */
class AnswerApplier(
    private val anki: AnkiDroidHelper,
    private val processed: AnswerDedupeStore
) {
    enum class Result { APPLIED, DUPLICATE, NOT_APPLIED, MALFORMED }

    // Deck setup is redone only when the deck changes or an answer doesn't go through, so a
    // batch of hundreds doesn't pay for it every time.
    private var preparedDeck: Long? = null

    suspend fun apply(answer: WatchAnswer): Result {
        if (!Wire.isAnswerEase(answer.ease)) return Result.MALFORMED
        val uuid = answer.uuid
        if (uuid != null && processed.isProcessed(uuid)) return Result.DUPLICATE
        val ok = if (answer.ease == Wire.EASE_BURY) {
            anki.buryCard(answer.noteId, answer.cardOrd)
        } else {
            grade(answer)
        }
        if (!ok) {
            Log.w(TAG, "Not applied (card gone or not accepted): note=${answer.noteId} ord=${answer.cardOrd} ease=${answer.ease}")
            return Result.NOT_APPLIED
        }
        uuid?.let { processed.markProcessed(it) }
        return Result.APPLIED
    }

    private suspend fun grade(a: WatchAnswer): Boolean {
        if (a.deckId != 0L && preparedDeck != a.deckId) prepare(a.deckId)
        if (anki.answerCard(a.noteId, a.cardOrd, a.ease, a.timeTaken)) return true
        if (a.deckId == 0L) return false
        // Cold start: AnkiDroid's scheduler wasn't ready for this deck yet. Set it up again.
        for (attempt in 2..3) {
            delay(100L * attempt)
            prepare(a.deckId)
            if (anki.answerCard(a.noteId, a.cardOrd, a.ease, a.timeTaken)) return true
        }
        return false
    }

    /** Selects the deck and reads one card, as AnkiDroid's reviewer would before answering. */
    private suspend fun prepare(deckId: Long) {
        anki.setSelectedDeck(deckId)
        delay(50)
        anki.warmUpScheduler(deckId)
        preparedDeck = deckId
    }

    private companion object {
        const val TAG = "AnswerApplier"
    }
}

/**
 * Applies every grade the watch has queued (DataItems under /answer/), in the order it was
 * given on the watch, deleting each item once AnkiDroid has it and then acknowledging them
 * to the watch: hundreds at once when the watch is back from an offline session, one at a
 * time while reviewing live. Nothing is consumed while AnkiDroid can't be reached, and a
 * failure part-way leaves the rest queued for the next run.
 *
 * Both the listener service and the phone app's screen run this, so one lock for the whole
 * process keeps two runs from applying the same items twice.
 */
class AnswerSync(
    private val anki: AnkiDroidHelper,
    private val dataLayer: AnswerQueue,
    private val processed: AnswerDedupeStore,
    private val exchangeLog: ExchangeLog
) {
    class Report(
        val total: Int,
        val offline: Int,
        val applied: Int,
        val duplicates: Int,
        val notApplied: Int,
        val malformed: Int,
        /** Why the run stopped early, leaving the rest queued; null when it got through. */
        val stopped: String?,
        /** The last answer, when it was given live: the watch is waiting for the next card. */
        val replyTo: WatchAnswer?,
        /** Put into AnkiDroid but left queued, as the watch couldn't be told: acked next run. */
        val unconfirmed: Int = 0
    ) {
        val outcome: String
            get() = buildList {
                add("applied $applied")
                if (duplicates > 0) add("$duplicates already in")
                if (notApplied > 0) add("$notApplied not accepted (card gone?)")
                if (malformed > 0) add("$malformed unreadable")
                if (unconfirmed > 0) add("$unconfirmed not yet confirmed to the watch")
                stopped?.let { add("stopped: $it; ${total - applied - duplicates - notApplied - malformed} still queued") }
            }.joinToString(", ")
    }

    /**
     * Runs over everything queued; null when nothing was. [arrived] are the answers the
     * triggering event carried: applied even if listing the queue fails, as before there was
     * a queue to list. [log] records the run as a watch request.
     */
    suspend fun run(log: Boolean = true, arrived: List<QueuedAnswer> = emptyList()): Report? = LOCK.withLock {
        val listed = try {
            dataLayer.pendingAnswers()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list queued answers; using the ones that arrived", e)
            emptyList()
        }
        val pending = (listed + arrived).distinctBy { it.first.toString() }
        if (pending.isEmpty()) return@withLock null
        val ordered = pending
            .map { (uri, map) -> uri to WatchAnswer.from(map) }
            .sortedWith(compareBy(WatchAnswer.ORDER) { it.second })
        val answers = ordered.map { it.second }
        val offline = answers.count { it.offline }
        if (log) exchangeLog.request(describe(answers))

        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            val report = Report(answers.size, offline, 0, 0, 0, 0,
                stopped = "AnkiDroid isn't installed or AnkiWatch Phone has no permission", replyTo = null)
            if (log) exchangeLog.outcome(report.outcome)
            return@withLock report
        }

        val applier = AnswerApplier(anki, processed)
        var applied = 0
        var duplicates = 0
        var notApplied = 0
        var malformed = 0
        var stopped: String? = null
        var done = 0
        var unconfirmed = 0
        // In batches, each acknowledged to the watch before its items are deleted here. The
        // watch may hear of a deletion late or never, and would then show the grade as
        // waiting for good: so no item goes before the watch has been told.
        for (batch in ordered.chunked(BATCH)) {
            val handled = ArrayList<Uri>()
            for ((uri, answer) in batch) {
                val result = try {
                    applier.apply(answer)
                } catch (e: Exception) {
                    Log.e(TAG, "Stopped applying answers", e)
                    stopped = e.message ?: e.javaClass.simpleName
                    break
                }
                when (result) {
                    AnswerApplier.Result.APPLIED -> applied++
                    AnswerApplier.Result.DUPLICATE -> duplicates++
                    AnswerApplier.Result.NOT_APPLIED -> notApplied++
                    AnswerApplier.Result.MALFORMED -> malformed++
                }
                handled += uri
            }
            if (handled.isNotEmpty()) {
                try {
                    dataLayer.acknowledge(handled.mapNotNull { Wire.answerName(it.path) })
                } catch (e: Exception) {
                    // Leave the batch queued and stop: the next run finds these answers already
                    // in (no later batch has pushed them out of the dedupe store), and acks and
                    // deletes them then.
                    Log.w(TAG, "Couldn't acknowledge ${handled.size} answers; left queued", e)
                    unconfirmed = handled.size
                    break
                }
                for (uri in handled) dataLayer.deleteAnswerItem(uri)
                done += handled.size
            }
            if (stopped != null) break
        }
        val last = answers.lastOrNull()
        val report = Report(
            total = answers.size,
            offline = offline,
            applied = applied,
            duplicates = duplicates,
            notApplied = notApplied,
            malformed = malformed,
            stopped = stopped,
            replyTo = last?.takeIf { stopped == null && done == answers.size && !it.offline && Wire.isAnswerEase(it.ease) },
            unconfirmed = unconfirmed
        )
        if (log) exchangeLog.outcome(report.outcome)
        Log.d(TAG, "Applied ${answers.size} queued answers: ${report.outcome}")
        report
    }

    /** One answer that came as a message from an older watch build: there is no item to delete. */
    suspend fun applyOne(answer: WatchAnswer): AnswerApplier.Result =
        LOCK.withLock { AnswerApplier(anki, processed).apply(answer) }

    private fun describe(answers: List<WatchAnswer>): String {
        val offline = answers.count { it.offline }
        val single = answers.singleOrNull()
        return when {
            single != null && !single.offline -> if (single.ease == Wire.EASE_BURY) "bury" else "answer"
            single != null -> "1 offline grade"
            offline == answers.size -> "${answers.size} offline grades"
            offline == 0 -> "${answers.size} answers"
            else -> "${answers.size} grades ($offline offline)"
        }
    }

    companion object {
        private const val TAG = "AnswerSync"
        private val LOCK = Mutex()

        /** Answers applied per ack: well inside the dedupe store, should the app die mid-batch. */
        private const val BATCH = 100
    }
}

/** A queued answer's item, as the phone reads it from the Data Layer. */
typealias QueuedAnswer = Pair<Uri, DataMap>

/** Where the watch's answers wait: the Data Layer, or a stand-in in tests. */
interface AnswerQueue {
    suspend fun pendingAnswers(): List<QueuedAnswer>
    suspend fun deleteAnswerItem(uri: Uri)

    /**
     * Tells the watch the phone is done with the answers [names] ([Wire.answerName]), before
     * their items are deleted.
     */
    suspend fun acknowledge(names: List<String>)
}
