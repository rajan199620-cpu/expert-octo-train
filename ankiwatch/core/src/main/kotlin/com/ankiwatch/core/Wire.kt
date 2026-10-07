package com.ankiwatch.core

/**
 * Message paths, capability names and DataMap keys shared by the phone and watch apps, so
 * the two sides of the Wearable Data Layer can never disagree on a name.
 *
 * The capability names must also match res/values/wear.xml in each app.
 */
object Wire {
    // Watch → phone requests (messages)
    const val PATH_REQUEST_DECKS = "/request/decks"
    const val PATH_REQUEST_CARDS = "/request/cards"

    // Phone → watch responses (DataItems) and errors (messages)
    const val PATH_RESPONSE_DECKS = "/response/decks"
    const val PATH_RESPONSE_CARDS = "/response/cards"
    const val PATH_STATUS_ERROR = "/status/error"

    // Watch → phone answers: guaranteed-delivery DataItems under this prefix + a UUID.
    const val PATH_ANSWER_PREFIX = "/answer/"

    /**
     * Phone → watch: the answers the phone is done with (put into AnkiDroid, or dropped as
     * unusable), as DataItems under this prefix listing the answers' names ([KEY_ACKED]).
     * The phone deletes each answer too, but Android can take half an hour to pass a deletion
     * on, and until then the watch would count the grade as still waiting. So the watch
     * deletes its own copies as soon as an ack arrives, and then the ack.
     */
    const val PATH_ANSWER_ACK_PREFIX = "/ack/answers/"

    /** Most answer names in one ack: a thousand UUIDs keep it well under a DataItem's 100 KB. */
    const val ACK_CHUNK = 1_000

    /** The name (UUID) of the answer stored at [path], or null if [path] isn't an answer's. */
    fun answerName(path: String?): String? = path
        ?.takeIf { it.startsWith(PATH_ANSWER_PREFIX) }
        ?.substring(PATH_ANSWER_PREFIX.length)
        ?.takeIf { it.isNotEmpty() && '/' !in it }

    /** [names] split into acks of at most [ACK_CHUNK] names, each name once, order kept. */
    fun ackChunks(names: Collection<String>): List<List<String>> = names.distinct().chunked(ACK_CHUNK)

    /**
     * Answer ease meaning "bury this card until tomorrow" instead of a grade. AnkiDroid's
     * grades are 1–4, so 0 is free, and bury rides the same queued, deduplicated path.
     */
    const val EASE_BURY = 0

    /**
     * A grade (1–4) or [EASE_BURY]. Anything else is a malformed answer; in particular a
     * DataMap without an ease reads as 0, so readers must default to -1, never to bury.
     */
    fun isAnswerEase(ease: Int): Boolean = ease == EASE_BURY || ease in 1..4

    // Legacy message-based answer path, still accepted by the phone.
    const val PATH_REVIEW_ANSWER = "/review/answer"

    // Reviewing without the phone: the watch asks for a deck's whole due queue (message);
    // the phone answers with one DataItem carrying the encoded OfflinePack as an Asset.
    const val PATH_REQUEST_OFFLINE = "/request/offline"
    const val PATH_OFFLINE_PACK = "/offline/pack"

    /** Most cards one offline download holds; AnkiDroid's daily limits usually give fewer. */
    const val OFFLINE_CARD_LIMIT = 1_000

    const val CAPABILITY_PHONE = "ankiwatch_phone"
    const val CAPABILITY_WATCH = "ankiwatch_watch"

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
    const val KEY_ANSWER_UUID = "answer_uuid"
    const val KEY_ACKED_ANSWER_UUID = "acked_answer_uuid"
    /** On an ack ([PATH_ANSWER_ACK_PREFIX]): the names of the answers the phone is done with. */
    const val KEY_ACKED = "acked"

    // Offline downloads and the answers given from them.
    const val KEY_PACK = "pack"
    const val KEY_PACK_ID = "pack_id"
    const val KEY_CARD_COUNT = "card_count"
    /** On an answer given from a download: the phone applies it without sending cards back. */
    const val KEY_OFFLINE = "offline"
    /** The watch's running number for its answers: the phone applies them in this order. */
    const val KEY_SEQ = "seq"

    // Cloze cards: raw cloze field, tested cloze number (0 = not a cloze card), and the
    // answer-side extras as parallel label/value arrays.
    const val KEY_CLOZE_CONTENT = "cloze_content"
    const val KEY_CLOZE_NUMBER = "cloze_number"
    const val KEY_EXTRA_LABELS = "extra_labels"
    const val KEY_EXTRA_VALUES = "extra_values"
    const val KEY_MODEL_NAME = "model_name"
}
