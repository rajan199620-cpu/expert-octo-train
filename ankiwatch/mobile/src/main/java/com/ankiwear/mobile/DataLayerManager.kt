package com.ankiwear.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ankiwatch.core.Link
import com.ankiwatch.core.OfflinePack
import com.ankiwatch.core.OfflinePackCodec
import com.ankiwatch.core.Wire
import com.ankiwatch.core.PayloadBudget
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages communication with the Wear OS watch via the Wearable Data Layer API.
 */
class DataLayerManager(context: Context) : AnswerQueue {

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private val prefs = context.getSharedPreferences("data_layer", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DataLayerManager"

        private const val KEY_LAST_ACK_PRUNE = "last_ack_prune"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val ACK_KEEP_MS = 14 * DAY_MS

        // Message paths
        const val PATH_REQUEST_DECKS = Wire.PATH_REQUEST_DECKS
        const val PATH_REQUEST_CARDS = Wire.PATH_REQUEST_CARDS
        const val PATH_RESPONSE_DECKS = Wire.PATH_RESPONSE_DECKS
        const val PATH_RESPONSE_CARDS = Wire.PATH_RESPONSE_CARDS
        // Legacy message-based answer path. Kept so an older watch build still works, but
        // the current watch sends answers as DataItems under PATH_ANSWER_PREFIX instead.
        const val PATH_REVIEW_ANSWER = Wire.PATH_REVIEW_ANSWER
        const val PATH_STATUS_ERROR = Wire.PATH_STATUS_ERROR
        // Review answers are written by the watch as DataItems under this prefix, one per
        // answer with a UUID suffix (e.g. "/answer/3f2c…"). DataItems are queued and
        // guaranteed-delivery, so a grade tapped while the phone is unreachable is applied
        // once the link returns instead of being silently dropped like a message would be.
        const val PATH_ANSWER_PREFIX = Wire.PATH_ANSWER_PREFIX

        // Capabilities (declared in each module's res/values/wear.xml)
        const val CAPABILITY_PHONE = Wire.CAPABILITY_PHONE
        const val CAPABILITY_WATCH = Wire.CAPABILITY_WATCH

        // Data keys
        const val KEY_DECK_ID = Wire.KEY_DECK_ID
        const val KEY_DECK_NAME = Wire.KEY_DECK_NAME
        const val KEY_NEW_COUNT = Wire.KEY_NEW_COUNT
        const val KEY_LEARN_COUNT = Wire.KEY_LEARN_COUNT
        const val KEY_REVIEW_COUNT = Wire.KEY_REVIEW_COUNT
        const val KEY_NOTE_ID = Wire.KEY_NOTE_ID
        const val KEY_CARD_ORD = Wire.KEY_CARD_ORD
        const val KEY_QUESTION = Wire.KEY_QUESTION
        const val KEY_ANSWER = Wire.KEY_ANSWER
        const val KEY_BUTTON_COUNT = Wire.KEY_BUTTON_COUNT
        const val KEY_EASE = Wire.KEY_EASE
        const val KEY_TIME_TAKEN = Wire.KEY_TIME_TAKEN
        const val KEY_ERROR_MESSAGE = Wire.KEY_ERROR_MESSAGE
        const val KEY_DECKS = Wire.KEY_DECKS
        const val KEY_CARDS = Wire.KEY_CARDS
        const val KEY_TIMESTAMP = Wire.KEY_TIMESTAMP
        const val KEY_REMAINING = Wire.KEY_REMAINING
        const val KEY_NEW_REMAINING = Wire.KEY_NEW_REMAINING
        const val KEY_LEARN_REMAINING = Wire.KEY_LEARN_REMAINING
        const val KEY_REVIEW_REMAINING = Wire.KEY_REVIEW_REMAINING
        const val KEY_NEXT_REVIEW_TIMES = Wire.KEY_NEXT_REVIEW_TIMES
        // Cloze cards: raw cloze field, tested cloze number (0 = not a cloze card) and the
        // answer-side extras as parallel label/value arrays.
        const val KEY_CLOZE_CONTENT = Wire.KEY_CLOZE_CONTENT
        const val KEY_CLOZE_NUMBER = Wire.KEY_CLOZE_NUMBER
        const val KEY_EXTRA_LABELS = Wire.KEY_EXTRA_LABELS
        const val KEY_EXTRA_VALUES = Wire.KEY_EXTRA_VALUES
        const val KEY_MODEL_NAME = Wire.KEY_MODEL_NAME
        // Unique id the watch stamps on each answer DataItem, used for dedupe + ack.
        const val KEY_ANSWER_UUID = Wire.KEY_ANSWER_UUID
        // UUID of the answer that produced a given cards response, echoed back so the watch
        // can delete its queued answer DataItem now that the phone has applied it.
        const val KEY_ACKED_ANSWER_UUID = Wire.KEY_ACKED_ANSWER_UUID
    }

    /**
     * Sends the deck list to the watch via DataClient, cut to fit one DataItem. Returns what
     * was sent, for the phone's status screen; throws if the Data Layer refused it.
     */
    suspend fun sendDecks(decks: List<DeckData>): String {
        val keep = PayloadBudget.decksThatFit(decks.map { it.name }, decks.map { it.totalDue > 0 })
        val sent = keep.map { decks[it] }
        val request = PutDataMapRequest.create(PATH_RESPONSE_DECKS).apply {
            val deckMaps = ArrayList<DataMap>()
            for (deck in sent) {
                val map = DataMap().apply {
                    putLong(KEY_DECK_ID, deck.id)
                    putString(KEY_DECK_NAME, deck.name)
                    putInt(KEY_NEW_COUNT, deck.newCount)
                    putInt(KEY_LEARN_COUNT, deck.learnCount)
                    putInt(KEY_REVIEW_COUNT, deck.reviewCount)
                }
                deckMaps.add(map)
            }
            dataMap.putDataMapArrayList(KEY_DECKS, deckMaps)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        request.setUrgent()
        val kb = (request.dataMap.toByteArray().size + 1023) / 1024
        dataClient.putDataItem(request.asPutDataRequest()).await()
        Log.d(TAG, "Sent ${sent.size}/${decks.size} decks to watch ($kb KB)")
        return if (sent.size == decks.size) "sent ${sent.size} decks ($kb KB)"
        else "sent ${sent.size} of ${decks.size} decks ($kb KB, size limit)"
    }

    /**
     * Sends card data to the watch via DataClient.
     * @param remaining authoritative total count of cards still due in this deck.
     * @param newRemaining new cards remaining.
     * @param learnRemaining learning cards remaining.
     * @param reviewRemaining review cards remaining.
     * @param deckId the deck these cards belong to, so the watch can discard a slow
     *   response for a deck it has already navigated away from.
     * @param ackedAnswerUuid UUID of the answer that triggered this response (post-answer
     *   path only), so the watch can clean up its queued answer DataItem.
     */
    suspend fun sendCards(
        cards: List<CardData>,
        remaining: Int,
        newRemaining: Int = 0,
        learnRemaining: Int = 0,
        reviewRemaining: Int = 0,
        deckId: Long,
        ackedAnswerUuid: String? = null
    ): String {
        val fitted = fitToDataItem(cards)
        val request = PutDataMapRequest.create(PATH_RESPONSE_CARDS).apply {
            dataMap.putDataMapArrayList(KEY_CARDS, ArrayList(fitted.map { cardToDataMap(it) }))
            dataMap.putInt(KEY_REMAINING, remaining)
            dataMap.putInt(KEY_NEW_REMAINING, newRemaining)
            dataMap.putInt(KEY_LEARN_REMAINING, learnRemaining)
            dataMap.putInt(KEY_REVIEW_REMAINING, reviewRemaining)
            dataMap.putLong(KEY_DECK_ID, deckId)
            ackedAnswerUuid?.let { dataMap.putString(KEY_ACKED_ANSWER_UUID, it) }
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        request.setUrgent()
        dataClient.putDataItem(request.asPutDataRequest()).await()
        Log.d(TAG, "Sent ${fitted.size}/${cards.size} cards to watch (deck=$deckId remaining=$remaining)")
        return "sent ${fitted.size} card${if (fitted.size == 1) "" else "s"}, $remaining due"
    }

    /**
     * Sends a deck's offline download: one DataItem whose Asset holds the encoded pack (an
     * Asset may be far larger than a DataItem's 100 KiB). Returns what was sent, for the
     * status screen; throws if the Data Layer refused it.
     */
    suspend fun sendOfflinePack(pack: OfflinePack): String {
        val bytes = OfflinePackCodec.encode(pack)
        val request = PutDataMapRequest.create(Wire.PATH_OFFLINE_PACK).apply {
            dataMap.putAsset(Wire.KEY_PACK, Asset.createFromBytes(bytes))
            dataMap.putLong(Wire.KEY_PACK_ID, pack.id)
            dataMap.putLong(KEY_DECK_ID, pack.deckId)
            dataMap.putString(KEY_DECK_NAME, pack.deckName)
            dataMap.putInt(Wire.KEY_CARD_COUNT, pack.cards.size)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        request.setUrgent()
        dataClient.putDataItem(request.asPutDataRequest()).await()
        val kb = (bytes.size + 1023) / 1024
        Log.d(TAG, "Sent offline pack ${pack.id}: ${pack.cards.size} cards, $kb KB")
        return "sent ${pack.cards.size} card${if (pack.cards.size == 1) "" else "s"} for offline review ($kb KB)"
    }

    /** Every answer the watch has queued that hasn't been applied and deleted yet. */
    override suspend fun pendingAnswers(): List<QueuedAnswer> {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_ANSWER_PREFIX).build()
        val buffer = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
        try {
            return buffer.map { it.uri to DataMapItem.fromDataItem(it).dataMap }
        } finally {
            buffer.release()
        }
    }

    /**
     * Deletes a processed answer DataItem. Best-effort — the phone's persistent dedupe is the
     * real guard against double-application if this delete doesn't stick. The watch hears of
     * it only at Android's next sync, so [acknowledge] tells it straight away.
     */
    override suspend fun deleteAnswerItem(uri: Uri) {
        try {
            val removed = dataClient.deleteDataItems(uri).await()
            Log.d(TAG, "Deleted answer item $uri (removed=$removed)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete answer item $uri: ${e.message}")
        }
    }

    /**
     * Tells the watch which answers the phone is done with: urgent DataItems, so they go at
     * once and still arrive if the watch app isn't running. The watch deletes them once it
     * has deleted the answers (see [Wire.PATH_ANSWER_ACK_PREFIX]). Throws if the Data Layer
     * refused one, so the answers stay queued until the watch can be told.
     */
    override suspend fun acknowledge(names: List<String>) {
        for (chunk in Wire.ackChunks(names)) {
            val request = PutDataMapRequest.create("${Wire.PATH_ANSWER_ACK_PREFIX}${UUID.randomUUID()}").apply {
                dataMap.putStringArray(Wire.KEY_ACKED, chunk.toTypedArray())
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }
            request.setUrgent()
            dataClient.putDataItem(request.asPutDataRequest()).await()
            Log.d(TAG, "Acknowledged ${chunk.size} answers to the watch")
        }
        pruneAcks()
    }

    /**
     * Deletes this phone's acks older than [ACK_KEEP_MS], at most once a day. The watch deletes
     * each ack once it has used it, but its deletion may never reach the phone, and the acks
     * would pile up here. Two weeks leaves the watch plenty of time to read one.
     */
    private suspend fun pruneAcks() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_ACK_PRUNE, 0L)
        if (now in last until last + DAY_MS) return
        prefs.edit().putLong(KEY_LAST_ACK_PRUNE, now).apply()
        try {
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(Wire.PATH_ANSWER_ACK_PREFIX).build()
            val buffer = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
            val old = try {
                buffer.filter { DataMapItem.fromDataItem(it).dataMap.getLong(KEY_TIMESTAMP, 0L) < now - ACK_KEEP_MS }.map { it.uri }
            } finally {
                buffer.release()
            }
            for (item in old) dataClient.deleteDataItems(item).await()
            if (old.isNotEmpty()) Log.d(TAG, "Pruned ${old.size} old acks")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't prune old acks: ${e.message}")
        }
    }

    /**
     * Sends an error status message to all connected watch nodes.
     */
    suspend fun sendError(message: String) {
        try {
            val nodeIds = watchNodeIds()
            for (id in nodeIds) {
                messageClient.sendMessage(
                    id,
                    PATH_STATUS_ERROR,
                    message.toByteArray()
                ).await()
            }
            Log.d(TAG, "Sent error to ${nodeIds.size} nodes: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error message", e)
        }
    }

    /**
     * What the phone can see of the watch app, for the status screen: ready, a watch without
     * a matching AnkiWatch, no watch at all, or the Wear OS link failing.
     */
    suspend fun watchLink(): Link.Status {
        var error: String? = null
        val app = try {
            capabilityClient
                .getCapability(CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes.map { it.displayName }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            null
        }
        val connected = try {
            nodeClient.connectedNodes.await().map { it.displayName }
        } catch (e: Exception) {
            error = error ?: e.message ?: e.javaClass.simpleName
            null
        }
        return Link.classify(app, connected, error)
    }

    /**
     * Resolves the watch node ids. Prefers nodes actually advertising the watch
     * capability (so we know the app is installed there, not just that a watch is paired),
     * and falls back to the raw connected-node list if the capability lookup comes back
     * empty — e.g. right after pairing before capabilities have propagated.
     */
    private suspend fun watchNodeIds(): Set<String> {
        val capIds = try {
            capabilityClient
                .getCapability(CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes.map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        if (capIds.isNotEmpty()) return capIds
        return try {
            nodeClient.connectedNodes.await().map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}

/** The text that dominates a card's size on the wire. */
private fun CardData.toCardText() = PayloadBudget.CardText(
    question = question,
    answer = answer,
    content = cloze?.content.orEmpty(),
    extras = cloze?.extras.orEmpty()
)

/**
 * Keeps a cards response under the 100 KiB DataItem limit: fewer cards first, then a
 * shortened single card. The watch only ever needs the first card to keep going.
 */
internal fun fitToDataItem(cards: List<CardData>): List<CardData> {
    if (cards.isEmpty()) return cards
    val texts = cards.map { it.toCardText() }
    val count = PayloadBudget.cardsThatFit(texts)
    val kept = cards.take(count)
    if (count > 1 || texts[0].byteSize() <= PayloadBudget.MAX_BYTES) return kept
    return listOf(cards[0].fitted())
}

/** The card itself, or shortened to fit one DataItem if it is too long on its own. */
internal fun CardData.fitted(): CardData {
    val text = toCardText()
    if (text.byteSize() <= PayloadBudget.MAX_BYTES) return this
    val shrunk = PayloadBudget.shrink(text)
    return copy(
        question = shrunk.question,
        answer = shrunk.answer,
        cloze = cloze?.copy(content = shrunk.content, extras = shrunk.extras)
    )
}

internal fun cardToDataMap(card: CardData): DataMap = DataMap().apply {
    putLong(DataLayerManager.KEY_NOTE_ID, card.noteId)
    putInt(DataLayerManager.KEY_CARD_ORD, card.cardOrd)
    putString(DataLayerManager.KEY_QUESTION, card.question)
    putString(DataLayerManager.KEY_ANSWER, card.answer)
    putInt(DataLayerManager.KEY_BUTTON_COUNT, card.buttonCount)
    putStringArray(DataLayerManager.KEY_NEXT_REVIEW_TIMES, card.nextReviewTimes.toTypedArray())
    val cloze = card.cloze
    if (cloze != null) {
        putString(DataLayerManager.KEY_CLOZE_CONTENT, cloze.content)
        putInt(DataLayerManager.KEY_CLOZE_NUMBER, cloze.clozeNumber)
        putStringArray(DataLayerManager.KEY_EXTRA_LABELS, cloze.extras.map { it.first }.toTypedArray())
        putStringArray(DataLayerManager.KEY_EXTRA_VALUES, cloze.extras.map { it.second }.toTypedArray())
        putString(DataLayerManager.KEY_MODEL_NAME, cloze.modelName)
    }
}
