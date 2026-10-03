package com.ankiwear.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ankiwatch.core.Link
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.ClozeCard
import com.ankiwear.wear.model.DeckInfo
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch-side client for communicating with the phone module via the Wearable Data Layer.
 */
class DataLayerClient(context: Context) : DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    // Background scope for fire-and-forget cleanup (deleting acked answer DataItems).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // uuid -> the DataItem uri we wrote for that answer, so we can delete it once the
    // phone acks having applied it. Bounded implicitly: entries are removed on ack.
    private val pendingAnswers = ConcurrentHashMap<String, Uri>()

    // Deck the watch is currently viewing cards for. Used to drop a slow /response/cards
    // that arrives after the user has switched decks (#7).
    @Volatile
    private var activeDeckId: Long? = null

    // Debounce for deck requests — the initial poll loop and ON_RESUME can otherwise fire
    // duplicate requests milliseconds apart.
    @Volatile
    private var lastDecksRequestAt = 0L

    private val _decks = MutableStateFlow<List<DeckInfo>>(emptyList())
    val decks: StateFlow<List<DeckInfo>> = _decks.asStateFlow()

    private val _cards = MutableStateFlow<List<CardData>>(emptyList())
    val cards: StateFlow<List<CardData>> = _cards.asStateFlow()

    /** Authoritative count of cards still due in the deck, reported by the phone with
     *  every cards response. The watch shows this directly so the counter can't drift. */
    private val _cardsRemaining = MutableStateFlow(0)
    val cardsRemaining: StateFlow<Int> = _cardsRemaining.asStateFlow()

    /** Per-category remaining counts, matching AnkiDroid's blue/red/green breakdown. */
    private val _newRemaining = MutableStateFlow(0)
    val newRemaining: StateFlow<Int> = _newRemaining.asStateFlow()
    private val _learnRemaining = MutableStateFlow(0)
    val learnRemaining: StateFlow<Int> = _learnRemaining.asStateFlow()
    private val _reviewRemaining = MutableStateFlow(0)
    val reviewRemaining: StateFlow<Int> = _reviewRemaining.asStateFlow()

    /** Increments every time a cards response arrives, even if the list content is identical.
     *  Used by the UI to force re-processing (e.g., reset card index) on every phone response. */
    private val _cardsResponseCount = MutableStateFlow(0)
    val cardsResponseCount: StateFlow<Int> = _cardsResponseCount.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** True when the phone itself reported a problem (not installed, no permission, no
     *  decks). Distinct from a local "phone not connected" — a phone-reported error means
     *  the link is fine but retrying won't help, so the auto-poll loop should stop. */
    private val _phoneReportedError = MutableStateFlow(false)
    val phoneReportedError: StateFlow<Boolean> = _phoneReportedError.asStateFlow()

    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    /** What the last connection check saw of the phone app; null before the first one. */
    private val _phoneLink = MutableStateFlow<Link.Status?>(null)
    val phoneLink: StateFlow<Link.Status?> = _phoneLink.asStateFlow()

    /** System.currentTimeMillis() of the last decks response we received from the phone,
     *  or null if we haven't gotten one yet this session. The deck list shows this as
     *  "Updated Ns ago" so the user can tell at a glance whether the counts are fresh. */
    private val _decksLastUpdated = MutableStateFlow<Long?>(null)
    val decksLastUpdated: StateFlow<Long?> = _decksLastUpdated.asStateFlow()

    companion object {
        private const val TAG = "DataLayerClient"

        const val PATH_REQUEST_DECKS = Wire.PATH_REQUEST_DECKS
        const val PATH_REQUEST_CARDS = Wire.PATH_REQUEST_CARDS
        const val PATH_RESPONSE_DECKS = Wire.PATH_RESPONSE_DECKS
        const val PATH_RESPONSE_CARDS = Wire.PATH_RESPONSE_CARDS
        const val PATH_STATUS_ERROR = Wire.PATH_STATUS_ERROR
        const val PATH_ANSWER_PREFIX = Wire.PATH_ANSWER_PREFIX

        const val CAPABILITY_PHONE = Wire.CAPABILITY_PHONE

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
        const val KEY_DECKS = Wire.KEY_DECKS
        const val KEY_CARDS = Wire.KEY_CARDS
        const val KEY_REMAINING = Wire.KEY_REMAINING
        const val KEY_NEW_REMAINING = Wire.KEY_NEW_REMAINING
        const val KEY_LEARN_REMAINING = Wire.KEY_LEARN_REMAINING
        const val KEY_REVIEW_REMAINING = Wire.KEY_REVIEW_REMAINING
        const val KEY_NEXT_REVIEW_TIMES = Wire.KEY_NEXT_REVIEW_TIMES
        const val KEY_TIMESTAMP = Wire.KEY_TIMESTAMP
        const val KEY_ANSWER_UUID = Wire.KEY_ANSWER_UUID
        const val KEY_ACKED_ANSWER_UUID = Wire.KEY_ACKED_ANSWER_UUID
        const val KEY_CLOZE_CONTENT = Wire.KEY_CLOZE_CONTENT
        const val KEY_CLOZE_NUMBER = Wire.KEY_CLOZE_NUMBER
        const val KEY_EXTRA_LABELS = Wire.KEY_EXTRA_LABELS
        const val KEY_EXTRA_VALUES = Wire.KEY_EXTRA_VALUES
        const val KEY_MODEL_NAME = Wire.KEY_MODEL_NAME

        private const val DECKS_REQUEST_DEBOUNCE_MS = 700L
    }

    fun register() {
        dataClient.addListener(this)
        messageClient.addListener(this)
    }

    fun unregister() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
    }

    /**
     * Hydrates the deck list from the last persisted DataItem so a cold open shows decks
     * immediately instead of waiting for a fresh round trip. DataItems survive on the
     * watch across app restarts and while the listener is unregistered, so the most recent
     * /response/decks is usually already sitting locally (#4).
     */
    suspend fun loadCachedData() {
        try {
            val buffer = dataClient.dataItems.await()
            try {
                var latestDecks: DataMap? = null
                var latestDecksTime = -1L
                for (item in buffer) {
                    if (item.uri.path == PATH_RESPONSE_DECKS) {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val t = map.getLong(KEY_TIMESTAMP, 0L)
                        if (t >= latestDecksTime) {
                            latestDecksTime = t
                            latestDecks = map
                        }
                    }
                }
                latestDecks?.let {
                    handleDecksResponse(it)
                    Log.d(TAG, "Hydrated decks from cache")
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadCachedData failed: ${e.message}")
        }
    }

    /**
     * Requests the deck list from the phone. Debounced so rapid callers (poll loop +
     * ON_RESUME) don't each fire a request. Does NOT clear the error state — see clearError.
     */
    suspend fun requestDecks() {
        val now = System.currentTimeMillis()
        if (now - lastDecksRequestAt < DECKS_REQUEST_DEBOUNCE_MS) {
            Log.d(TAG, "requestDecks debounced")
            return
        }
        lastDecksRequestAt = now
        sendMessageToPhone(PATH_REQUEST_DECKS, byteArrayOf())
    }

    /**
     * Requests cards for a specific deck from the phone.
     */
    suspend fun requestCards(deckId: Long) {
        activeDeckId = deckId
        val dataMap = DataMap().apply {
            putLong(KEY_DECK_ID, deckId)
        }
        sendMessageToPhone(PATH_REQUEST_CARDS, dataMap.toByteArray())
    }

    /**
     * Queues a review answer as a DataItem. Unlike a message, a DataItem is persisted and
     * guaranteed-delivery: an answer tapped while the phone is unreachable is applied once
     * the link returns rather than silently lost. Each answer gets a unique UUID (in both
     * the path and the payload) so the phone can dedupe redeliveries and ack completion.
     */
    suspend fun sendAnswer(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long, deckId: Long) {
        val uuid = UUID.randomUUID().toString()
        val request = PutDataMapRequest.create("$PATH_ANSWER_PREFIX$uuid").apply {
            dataMap.putString(KEY_ANSWER_UUID, uuid)
            dataMap.putLong(KEY_NOTE_ID, noteId)
            dataMap.putInt(KEY_CARD_ORD, cardOrd)
            dataMap.putInt(KEY_EASE, ease)
            dataMap.putLong(KEY_TIME_TAKEN, timeTakenMs)
            dataMap.putLong(KEY_DECK_ID, deckId)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        request.setUrgent()
        try {
            val item = dataClient.putDataItem(request.asPutDataRequest()).await()
            pendingAnswers[uuid] = item.uri
            Log.d(TAG, "Queued answer $uuid (guaranteed delivery)")
        } catch (e: Exception) {
            Log.e(TAG, "Error queueing answer", e)
            _errorMessage.value = "Couldn't queue your answer: ${e.message}"
        }
    }

    /**
     * Checks phone connection status. Prefers the AnkiWear capability so we can tell
     * "phone paired but app not installed" from "ready", and falls back to the raw node
     * list if the capability lookup is momentarily empty.
     */
    suspend fun checkConnection() {
        val link = phoneLinkStatus()
        _phoneLink.value = link
        // A connected phone without a visible AnkiWatch still gets requests: right after
        // pairing the capability can lag behind. The watch explains APP_MISSING if no answer
        // comes back.
        _isPhoneConnected.value = link.state == Link.State.READY || link.state == Link.State.APP_MISSING
    }

    private suspend fun phoneLinkStatus(): Link.Status {
        var error: String? = null
        val app = try {
            capabilityClient
                .getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
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

    fun clearError() {
        _errorMessage.value = null
        _phoneReportedError.value = false
    }

    // --- Data Layer callbacks ---

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            // Ignore our own answer writes echoing back through the local data store.
            if (path.startsWith(PATH_ANSWER_PREFIX)) continue
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            when (path) {
                PATH_RESPONSE_DECKS -> handleDecksResponse(dataMap)
                PATH_RESPONSE_CARDS -> handleCardsResponse(dataMap)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_STATUS_ERROR -> {
                val errorMsg = String(messageEvent.data)
                Log.e(TAG, "Error from phone: $errorMsg")
                _errorMessage.value = errorMsg
                _phoneReportedError.value = true
            }
        }
    }

    private fun handleDecksResponse(dataMap: DataMap) {
        val deckMaps = dataMap.getDataMapArrayList(KEY_DECKS) ?: return
        val deckList = deckMaps.map { map ->
            DeckInfo(
                id = map.getLong(KEY_DECK_ID),
                name = map.getString(KEY_DECK_NAME, ""),
                newCount = map.getInt(KEY_NEW_COUNT),
                learnCount = map.getInt(KEY_LEARN_COUNT),
                reviewCount = map.getInt(KEY_REVIEW_COUNT)
            )
        }
        _decks.value = deckList
        _decksLastUpdated.value = dataMap.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        // Fresh decks arriving means the phone is reachable and healthy — clear any stale
        // error (e.g. "Phone not connected" from before Bluetooth came back) so the deck
        // list renders without the user having to tap Retry.
        _phoneReportedError.value = false
        _errorMessage.value = null
        _isPhoneConnected.value = true
        Log.d(TAG, "Received ${deckList.size} decks")
    }

    private fun handleCardsResponse(dataMap: DataMap) {
        // #7: drop responses for a deck the user is no longer viewing.
        val respDeckId = dataMap.getLong(KEY_DECK_ID, -1L)
        val wantDeckId = activeDeckId
        if (respDeckId != -1L && wantDeckId != null && respDeckId != wantDeckId) {
            Log.d(TAG, "Ignoring cards response for deck $respDeckId (viewing $wantDeckId)")
            return
        }

        // Clean up the queued answer DataItem now that the phone has applied it.
        val acked = dataMap.getString(KEY_ACKED_ANSWER_UUID, "")
        if (acked.isNotEmpty()) {
            pendingAnswers.remove(acked)?.let { uri ->
                ioScope.launch {
                    try {
                        dataClient.deleteDataItems(uri).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete acked answer $acked: ${e.message}")
                    }
                }
            }
        }

        val cardMaps = dataMap.getDataMapArrayList(KEY_CARDS) ?: return
        val cardList = cardMaps.map { map -> cardFromDataMap(map) }
        val remaining = dataMap.getInt(KEY_REMAINING, cardList.size)
        _cards.value = cardList
        _cardsRemaining.value = remaining
        _newRemaining.value = dataMap.getInt(KEY_NEW_REMAINING, 0)
        _learnRemaining.value = dataMap.getInt(KEY_LEARN_REMAINING, 0)
        _reviewRemaining.value = dataMap.getInt(KEY_REVIEW_REMAINING, 0)
        _cardsResponseCount.value = _cardsResponseCount.value + 1
        Log.d(TAG, "Received ${cardList.size} cards, remaining=$remaining " +
            "(new=${_newRemaining.value} learn=${_learnRemaining.value} " +
            "review=${_reviewRemaining.value}), firstNoteId=${cardList.firstOrNull()?.noteId}")
    }

    private suspend fun sendMessageToPhone(path: String, data: ByteArray) {
        try {
            val nodeIds = phoneNodeIds()
            if (nodeIds.isEmpty()) {
                // Say why: no phone at all, or the Wear OS link itself failing.
                val link = phoneLinkStatus()
                _phoneLink.value = link
                _isPhoneConnected.value = false
                _errorMessage.value = Link.watchMessage(link) ?: "Phone not connected"
                return
            }
            _isPhoneConnected.value = true
            for (id in nodeIds) {
                messageClient.sendMessage(id, path, data).await()
            }
            Log.d(TAG, "Sent message to phone: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to phone", e)
            _errorMessage.value = "Communication error: ${e.message}"
        }
    }

    /**
     * Resolves phone node ids, preferring nodes that advertise the AnkiWear phone
     * capability (proof the companion app is installed), falling back to raw connected
     * nodes if the capability lookup is momentarily empty.
     */
    private suspend fun phoneNodeIds(): Set<String> {
        val capIds = try {
            capabilityClient
                .getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
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

/** Reads one card written by the phone's `cardToDataMap`. */
internal fun cardFromDataMap(map: DataMap): CardData {
    val clozeNumber = map.getInt(DataLayerClient.KEY_CLOZE_NUMBER, 0)
    val cloze = if (clozeNumber > 0) {
        val labels = map.getStringArray(DataLayerClient.KEY_EXTRA_LABELS)?.toList().orEmpty()
        val values = map.getStringArray(DataLayerClient.KEY_EXTRA_VALUES)?.toList().orEmpty()
        ClozeCard(
            content = map.getString(DataLayerClient.KEY_CLOZE_CONTENT, ""),
            clozeNumber = clozeNumber,
            extras = labels.zip(values),
            modelName = map.getString(DataLayerClient.KEY_MODEL_NAME, "")
        )
    } else null
    return CardData(
        noteId = map.getLong(DataLayerClient.KEY_NOTE_ID),
        cardOrd = map.getInt(DataLayerClient.KEY_CARD_ORD),
        question = map.getString(DataLayerClient.KEY_QUESTION, ""),
        answer = map.getString(DataLayerClient.KEY_ANSWER, ""),
        buttonCount = map.getInt(DataLayerClient.KEY_BUTTON_COUNT),
        nextReviewTimes = map.getStringArray(DataLayerClient.KEY_NEXT_REVIEW_TIMES)?.toList()
            ?: emptyList(),
        cloze = cloze
    )
}
