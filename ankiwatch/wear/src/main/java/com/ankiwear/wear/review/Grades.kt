package com.ankiwear.wear.review

/**
 * Which AnkiDroid ease value each control sends, for a card with [buttonCount] answer
 * buttons. The watch shows two big buttons, Again and Good; Hard and Easy are reached by
 * holding Again and Good respectively.
 */
data class GradeLayout(val again: Int, val good: Int, val hard: Int?, val easy: Int?) {

    /** AnkiDroid's predicted interval for [ease] (its list is ordered by ease, 1-based). */
    fun interval(times: List<String>, ease: Int?): String? =
        ease?.let { times.getOrNull(it - 1) }?.takeIf { it.isNotBlank() }

    /** "hold: Hard 2d · Easy 9d", or null when there is nothing to hold for. */
    fun holdCaption(times: List<String>): String? {
        val parts = buildList {
            hard?.let { add(listOfNotNull("Hard", interval(times, it)).joinToString(" ")) }
            easy?.let { add(listOfNotNull("Easy", interval(times, it)).joinToString(" ")) }
        }
        return if (parts.isEmpty()) null else "hold: " + parts.joinToString(" · ")
    }

    companion object {
        fun forButtonCount(buttonCount: Int): GradeLayout = when (buttonCount) {
            2 -> GradeLayout(again = 1, good = 2, hard = null, easy = null)
            3 -> GradeLayout(again = 1, good = 2, hard = null, easy = 3)
            else -> GradeLayout(again = 1, good = 3, hard = 2, easy = 4)
        }
    }
}

enum class Press { SHORT, LONG }

/**
 * Turns the key events of the watch's side button into short and long presses.
 *
 * A long press fires as soon as the key has been held long enough (on the first key
 * repeat, or by elapsed time), so the haptic confirms it while the finger is still down;
 * the matching key-up is then swallowed.
 */
class SideButtonTracker(private val longPressMs: Long = LONG_PRESS_MS) {

    private var longFired = false

    fun onDown(repeatCount: Int, isLongPress: Boolean, downTime: Long, eventTime: Long): Press? {
        if (repeatCount == 0) {
            longFired = false
            return null
        }
        if (!longFired && (isLongPress || eventTime - downTime >= longPressMs)) {
            longFired = true
            return Press.LONG
        }
        return null
    }

    fun onUp(canceled: Boolean, downTime: Long, eventTime: Long): Press? {
        val alreadyFired = longFired
        longFired = false
        return when {
            canceled || alreadyFired -> null
            eventTime - downTime >= longPressMs -> Press.LONG
            else -> Press.SHORT
        }
    }

    companion object {
        const val LONG_PRESS_MS = 500L
    }
}

/**
 * The review screen registers here while a card is on screen; the activity routes the
 * side button to it instead of treating it as Back.
 */
object SideButtons {
    @Volatile
    var handler: ((Press) -> Unit)? = null
}
