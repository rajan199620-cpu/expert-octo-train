package com.ankiwear.wear.model

/**
 * Represents a deck received from the phone.
 */
data class DeckInfo(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int
) {
    val totalDue: Int get() = newCount + learnCount + reviewCount
}
