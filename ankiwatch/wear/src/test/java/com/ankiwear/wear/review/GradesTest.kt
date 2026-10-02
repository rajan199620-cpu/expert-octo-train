package com.ankiwear.wear.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeLayoutTest {

    private val times = listOf("<1m", "6m", "1d", "4d")

    @Test
    fun fourButtonCardsHoldForHardAndEasy() {
        val layout = GradeLayout.forButtonCount(4)
        assertEquals(GradeLayout(again = 1, good = 3, hard = 2, easy = 4), layout)
        assertEquals("1d", layout.interval(times, layout.good))
        assertEquals("<1m", layout.interval(times, layout.again))
        assertEquals("hold: Hard 6m · Easy 4d", layout.holdCaption(times))
    }

    @Test
    fun olderSchedulersWithFewerButtons() {
        assertEquals(GradeLayout(1, 2, null, 3), GradeLayout.forButtonCount(3))
        assertEquals("hold: Easy", GradeLayout.forButtonCount(3).holdCaption(emptyList()))
        assertEquals(GradeLayout(1, 2, null, null), GradeLayout.forButtonCount(2))
        assertNull(GradeLayout.forButtonCount(2).holdCaption(times))
        // Anything unexpected falls back to the four-button layout.
        assertEquals(GradeLayout.forButtonCount(4), GradeLayout.forButtonCount(0))
    }

    @Test
    fun missingIntervalsAreTolerated() {
        val layout = GradeLayout.forButtonCount(4)
        assertNull(layout.interval(emptyList(), layout.good))
        assertNull(layout.interval(listOf("", ""), layout.hard))
        assertEquals("hold: Hard · Easy", layout.holdCaption(listOf("1m")))
    }
}

class SideButtonTrackerTest {

    @Test
    fun quickPressIsShort() {
        val t = SideButtonTracker(longPressMs = 500)
        assertNull(t.onDown(repeatCount = 0, isLongPress = false, downTime = 1000, eventTime = 1000))
        assertEquals(Press.SHORT, t.onUp(canceled = false, downTime = 1000, eventTime = 1120))
    }

    @Test
    fun heldKeyFiresLongOnceWhileStillDown() {
        val t = SideButtonTracker(longPressMs = 500)
        t.onDown(0, false, 1000, 1000)
        assertNull(t.onDown(1, false, 1000, 1400)) // key repeat before the threshold
        assertEquals(Press.LONG, t.onDown(2, true, 1000, 1500))
        assertNull(t.onDown(3, true, 1000, 1550)) // only once
        assertNull(t.onUp(false, 1000, 2000)) // the release is swallowed
        // The next press starts fresh.
        t.onDown(0, false, 3000, 3000)
        assertEquals(Press.SHORT, t.onUp(false, 3000, 3100))
    }

    @Test
    fun longReleaseWithoutRepeatsStillCountsAsLong() {
        val t = SideButtonTracker(longPressMs = 500)
        t.onDown(0, false, 1000, 1000)
        assertEquals(Press.LONG, t.onUp(false, 1000, 1700))
    }

    @Test
    fun canceledPressDoesNothing() {
        val t = SideButtonTracker(longPressMs = 500)
        t.onDown(0, false, 1000, 1000)
        assertNull(t.onUp(canceled = true, downTime = 1000, eventTime = 1050))
    }

    @Test
    fun upWithoutDownIsHarmless() {
        val t = SideButtonTracker(longPressMs = 500)
        assertEquals(Press.SHORT, t.onUp(false, 1000, 1010))
    }
}
