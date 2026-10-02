package com.ankiwear.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val AnkiWearColors = Colors(
    primary = Color(0xFF8AB4F8),       // Light blue
    primaryVariant = Color(0xFF669DF6),
    secondary = Color(0xFF81C995),     // Green
    secondaryVariant = Color(0xFF5BB974),
    error = Color(0xFFF28B82),         // Red
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.Black,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBDBDBD),
    background = Color.Black,
    onBackground = Color.White
)

// Ease button colors
val EaseAgainColor = Color(0xFFE57373)    // Red
val EaseHardColor = Color(0xFFFFB74D)     // Orange
val EaseGoodColor = Color(0xFF81C784)     // Green
val EaseEasyColor = Color(0xFF64B5F6)     // Blue

// Deck count colors — match AnkiDroid's queue color coding so the deck list
// reads at a glance: blue = new, red = learning, green = review.
val DeckNewColor = Color(0xFF4FC3F7)      // Light blue
val DeckLearnColor = Color(0xFFEF5350)    // Red
val DeckReviewColor = Color(0xFF66BB6A)   // Green

@Composable
fun AnkiWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = AnkiWearColors,
        content = content
    )
}
