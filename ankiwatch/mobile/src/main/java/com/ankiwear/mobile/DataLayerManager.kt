package com.ankiwear.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ankiwatch.core.PayloadBudget
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Manages communication with the Wear OS watch via the Wearable Data Layer API.
 */
class DataLayerManager(context: Context) {

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    companion object {
        private const val TAG = "DataLayerManager"

        // Message paths
        const val PATH_REQUEST_DECKS = "/request/decks"
        const val PATH_REQUEST_CARDS = "/request/cards"
        const val PATH_RESPONSE_DECKS = "/response/decks"
        const val PATH_RESPONSE_CARDS = "/response/cards"
        // Legacy message-based answer path. Kept so an older watch build still works, but
        // the current watch sends answers as DataItems under PATH_ANSWER_PREFIX instead.
        const val PATH_REVIEW_ANSWER = "/review/answer"
        const val PATH_STATUS_ERROR = "/status/error"
        // Review answers are written by the watch as DataItems under this prefix, one per
        // answer with a UUID suffix (e.g. "/answer/3f2c…"). DataItems are queued and
        // guaranteed-delivery, so a grade tapped while the phone is unreachable is applied
        // once the link returns instead of being silently dropped like a message would be.
        const val PATH_ANSWER_PREFIX = "/answer/"

        // Capabilities (declared in each module's res/values/wear.xml)
        const val CAPABILITY_PHONE = "ankiwatch_phone"
        const val CAPABILITY_WATCH = "ankiwatch_watch"

        // Data keys
        const val KEY_DECK_ID = "deck_id"
        const val KEY_DECK_NAME = "deck_name"
        const val KEY_NEW_COUNT = "new_count"
        const val KEY_LEARN_COUNT = "learn_count"
        const val KEY_REVIEW_COUNT = "review_count"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_CARD_ORD = "card_ord"
        const val KEY_QUESTION = "question"
        const val KEY_ANSWER = "answer"
        const val KEY_BUTTON_COUNT = "button_count"
        const val KEY_EASE = "ease"
        const val KEY_TIME_TAKEN = "time_taken"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_DECKS = "decks"
        const val KEY_CARDS = "cards"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_REMAINING = "remaining"
        const val KEY_NEW_REMAINING = "new_remaining"
        const val KEY_LEARN_REMAINING = "learn_remaining"
        const val KEY_REVIEW_REMAINING = "review_remaining"
        const val KEY_NEXT_REVIEW_TIMES = "next_review_times"
        // Cloze cards: raw cloze field, tested cloze number (0 = not a cloze card) and the
        // answer-side extras as parallel label/value arrays.
        const val KEY_CLOZE_CONTENT = "cloze_content"
        const val KEY_CLOZE_NUMBER = "cloze_number"
        const val KEY_EXTRA_LABELS = "extra_labels"
        const val KEY_EXTRA_VALUES = "extra_values"
        const val KEY_MODEL_NAME = "model_name"
        // Unique id the watch stamps on each answer DataItem, used for dedupe + ack.
        const val KEY_ANSWER_UUID = "answer_uuid"
        // UUID of the answer that produced a given cards response, echoed back so the watch
        // can delete its queued answer DataItem now that the phone has applied it.
        const val KEY_ACKED_ANSWER_UUID = "acked_answer_uuid"
    }

    /**
     * Sends the deck list to the watch via DataClient.
     */
    suspend fun sendDecks(decks: List<DeckData>) {
        try {
            val request = PutDataMapRequest.create(PATH_RESPONSE_DECKS).apply {
                val deckMaps = ArrayList<DataMap>()
                for (deck in decks) {
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
            dataClient.putDataItem(request.asPutDataRequest()).await()
            Log.d(TAG, "Sent ${decks.size} decks to watch")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending decks", e)
        }
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
    ) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error sending cards", e)
        }
    }

    /**
     * Deletes a processed answer DataItem. Doubles as the ACK: once the item is gone the
     * watch's queued grade has been fully applied. Best-effort — the phone's persistent
     * dedupe is the real guard against double-application if this delete doesn't stick.
     */
    suspend fun deleteAnswerItem(uri: Uri) {
        try {
            val removed = dataClient.deleteDataItems(uri).await()
            Log.d(TAG, "Deleted answer item $uri (removed=$removed)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete answer item $uri: ${e.message}")
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
     * Checks whether any watch nodes are connected.
     */
    suspend fun isWatchConnected(): Boolean = watchNodeIds().isNotEmpty()

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
    val shrunk = PayloadBudget.shrink(texts[0])
    val first = cards[0]
    return listOf(
        first.copy(
            question = shrunk.question,
            answer = shrunk.answer,
            cloze = first.cloze?.copy(content = shrunk.content, extras = shrunk.extras)
        )
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
