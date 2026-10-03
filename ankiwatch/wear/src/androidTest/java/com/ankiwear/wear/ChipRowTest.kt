package com.ankiwear.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiwear.wear.screens.ChipRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The chip row under the card (Whole note/Focus, Bury): side by side and centred while the
 * chips fit across, centred one under another when they don't, and never overlapping or
 * sticking out, at every width from roomy to far too narrow.
 */
@RunWith(AndroidJUnit4::class)
class ChipRowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun bounds(tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().unclippedBounds()

    @Test
    fun sideBySideWhileTheyFitStackedWhenNot() {
        val width = mutableStateOf(301)
        val chips = mutableStateOf(listOf(100, 50))
        rule.setContent {
            Box(Modifier.width(width.value.dp)) {
                ChipRow(Modifier.fillMaxWidth().testTag("row")) {
                    chips.value.forEachIndexed { i, w -> Box(Modifier.size(w.dp, 48.dp).testTag("chip$i")) }
                }
            }
        }
        val density = rule.density.density
        fun px(dp: Int) = (dp * density).roundToInt()
        var sideBySideSeen = 0
        var stackedSeen = 0
        for (set in listOf(listOf(100, 50), listOf(60), listOf(120, 40, 30), listOf(170, 20), listOf(250, 250))) {
            for (w in 301 downTo 61 step 10) {
                rule.runOnIdle {
                    chips.value = set
                    width.value = w
                }
                rule.waitForIdle()
                val what = "chips $set in ${w}dp"
                val row = bounds("row")
                val boxes = set.indices.map { bounds("chip$it") }
                for (b in boxes) {
                    assertTrue("$what: $b sticks out of $row", b.left >= row.left - 1f && b.right <= row.right + 1f)
                }
                for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                    assertFalse("$what: ${boxes[i]} and ${boxes[j]} overlap", boxes[i].overlaps(boxes[j]))
                }
                val across = set.sumOf { px(it) } + px(6) * (set.size - 1)
                if (across <= row.width + 0.5f) {
                    sideBySideSeen++
                    for (b in boxes) assertEquals("$what: not on one line", boxes[0].center.y, b.center.y, 1f)
                    for (i in 1 until boxes.size) {
                        assertTrue("$what: out of order or too close", boxes[i].left - boxes[i - 1].right >= px(6) - 1f)
                    }
                    assertEquals("$what: not centred", boxes.first().left - row.left, row.right - boxes.last().right, 1.5f)
                } else {
                    stackedSeen++
                    for (b in boxes) assertEquals("$what: $b not centred in $row", b.left - row.left, row.right - b.right, 1.5f)
                    for (i in 1 until boxes.size) {
                        assertTrue("$what: not one under another", boxes[i].top >= boxes[i - 1].bottom - 1f)
                    }
                    assertEquals("$what: row height", boxes.sumOf { it.height.toDouble() }.toFloat(), row.height, 1.5f)
                }
            }
        }
        assertTrue("side by side never happened", sideBySideSeen > 20)
        assertTrue("stacking never happened", stackedSeen > 20)
    }
}
