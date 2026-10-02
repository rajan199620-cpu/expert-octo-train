package com.ankiwear.wear.model

/**
 * Represents a single flashcard received from the phone.
 */
data class CardData(
    val noteId: Long,
    val cardOrd: Int,
    /** Template-rendered question HTML (non-cloze cards). Empty for cloze cards. */
    val question: String,
    /** Template-rendered answer HTML (non-cloze cards). Empty for cloze cards. */
    val answer: String,
    val buttonCount: Int,
    /**
     * AnkiDroid's predicted next-review labels per ease button (e.g. "<1m", "10m", "1d").
     * Indexed in the same order AnkiDroid presents the buttons. May be empty if the
     * column wasn't available on the phone side.
     */
    val nextReviewTimes: List<String> = emptyList(),
    /** Raw cloze field + tested cloze number; null for cards shown via their template. */
    val cloze: ClozeCard? = null
) {
    /**
     * Infers the card's queue type from its button count.
     * - 4 buttons (Again/Hard/Good/Easy) → REVIEW
     * - fewer buttons → NEW or LEARNING (caller disambiguates with deck counts)
     */
    val isReviewCard: Boolean get() = buttonCount >= 4
}

/**
 * A cloze card as sent by the phone: the note's raw cloze field (e.g. Enhanced Cloze
 * `Content`), which cloze this card tests, and the answer-side fields (Note, Extra…).
 */
data class ClozeCard(
    val content: String,
    val clozeNumber: Int,
    val extras: List<Pair<String, String>> = emptyList(),
    val modelName: String = ""
)

/**
 * Which Anki queue the current card belongs to — used to highlight the active
 * counter in the review screen, matching AnkiDroid's blue/red/green indicator.
 */
enum class CardType { NEW, LEARNING, REVIEW }
