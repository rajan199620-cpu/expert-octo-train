package com.ankiwear.wear.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Calls [onDoubleTap] for two quick taps close together anywhere inside, without taking the
 * taps from what is under them: a cloze or a button still gets each tap as usual. A drag, a
 * long press or a second finger is not a tap. A new [key] (the next card) starts afresh, so
 * one tap on each of two cards is never a double tap.
 */
fun Modifier.doubleTapAnywhere(key: Any, onDoubleTap: () -> Unit): Modifier = pointerInput(key) {
    val config = viewConfiguration
    val nearby = 40.dp.toPx()
    // Where and when the last single tap ended; null when the last gesture wasn't a tap.
    var lastTap: Pair<Offset, Long>? = null
    awaitEachGesture {
        // Seen before anything inside, and left for them: nothing is consumed here.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val previous = lastTap
        lastTap = null
        var upAt: Long? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.id != down.id && it.pressed }) break
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.uptimeMillis - down.uptimeMillis > config.longPressTimeoutMillis) break
            if ((change.position - down.position).getDistance() > config.touchSlop) break
            if (change.changedToUpIgnoreConsumed()) {
                upAt = change.uptimeMillis
                break
            }
        }
        val up = upAt ?: return@awaitEachGesture
        if (previous != null) {
            val (where, endedAt) = previous
            val gap = down.uptimeMillis - endedAt
            if (gap in config.doubleTapMinTimeMillis..config.doubleTapTimeoutMillis &&
                (down.position - where).getDistance() <= nearby
            ) {
                onDoubleTap()
                return@awaitEachGesture
            }
        }
        lastTap = down.position to up
    }
}
