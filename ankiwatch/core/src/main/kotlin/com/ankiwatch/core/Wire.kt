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

    // Legacy message-based answer path, still accepted by the phone.
    const val PATH_REVIEW_ANSWER = "/review/answer"

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

    // Cloze cards: raw cloze field, tested cloze number (0 = not a cloze card), and the
    // answer-side extras as parallel label/value arrays.
    const val KEY_CLOZE_CONTENT = "cloze_content"
    const val KEY_CLOZE_NUMBER = "cloze_number"
    const val KEY_EXTRA_LABELS = "extra_labels"
    const val KEY_EXTRA_VALUES = "extra_values"
    const val KEY_MODEL_NAME = "model_name"
}
