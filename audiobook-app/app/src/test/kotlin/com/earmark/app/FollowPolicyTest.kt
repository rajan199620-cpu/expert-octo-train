package com.earmark.app

import com.earmark.app.ui.FollowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowPolicyTest {
    private val viewport = 2000

    @Test
    fun lineInTheComfortZoneDoesNotScroll() {
        assertEquals(0, FollowPolicy.scrollDelta(600, 660, viewport))
    }

    @Test
    fun lineBelowTheZoneScrollsItUpToAThird() {
        val delta = FollowPolicy.scrollDelta(1800, 1860, viewport)
        assertEquals(1800 - 600, delta)
    }

    @Test
    fun lineFarOffScreenInsideAHugeParagraphIsBroughtBack() {
        // The old logic never scrolled here because the paragraph itself was still visible.
        val delta = FollowPolicy.scrollDelta(9000, 9060, viewport)
        assertTrue(delta > 8000)
    }

    @Test
    fun lineAboveTheTopScrollsBackDown() {
        assertTrue(FollowPolicy.scrollDelta(-400, -340, viewport) < 0)
        assertTrue(FollowPolicy.scrollDelta(100, 160, viewport) < 0)
    }

    @Test
    fun emptyViewportIsIgnored() {
        assertEquals(0, FollowPolicy.scrollDelta(500, 560, 0))
    }
}
