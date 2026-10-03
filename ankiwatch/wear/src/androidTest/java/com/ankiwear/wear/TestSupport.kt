package com.ankiwear.wear

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiwear.wear.screens.ReviewTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Every piece of text currently in the composition, one node per line. */
fun ComposeTestRule.allText(): String {
    val sb = StringBuilder()
    fun walk(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { sb.append(it.text).append('\n') }
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    return sb.toString()
}

/**
 * Nodes matching [matcher] that are laid out on screen. A lazy list also composes the next
 * row ahead of time without placing it; such a row has no real position.
 */
fun ComposeTestRule.placedNodes(matcher: SemanticsMatcher): List<SemanticsNode> =
    onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

/** Where the node is laid out, including any part the list or a parent clips away. */
fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot, size.toSize())

/** Where the card list draws on screen; rows outside it are clipped. */
fun ComposeTestRule.contentViewport(): Rect =
    onNodeWithTag(ReviewTags.CONTENT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

/** The scrollable list inside ScalingLazyColumn (the tag sits on its wrapper). */
private fun ComposeTestRule.contentScroller() =
    onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(ReviewTags.CONTENT)), useUnmergedTree = true)

/** Scrolls the card list by [dy] pixels; positive goes further down the note. */
fun ComposeTestRule.scrollContentBy(dy: Float) {
    contentScroller().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
    waitForIdle()
}

/**
 * Scrolls the card list until the first node matching [matcher] lies wholly inside the
 * list's visible area, clear of its top and bottom tenth (on a round watch those edges are
 * narrow), or as close to that as the list's ends allow. A node taller than that starts near
 * the top. ScalingLazyColumn also composes rows just outside its viewport, so a node merely
 * existing says nothing about what the user can see or tap.
 */
fun ComposeTestRule.scrollContentTo(matcher: SemanticsMatcher) {
    fun node() = placedNodes(matcher).firstOrNull()
    if (node() == null) {
        contentScroller().performScrollToIndex(0)
        waitForIdle()
        var steps = 0
        while (node() == null) {
            check(steps++ < 60) { "nothing matching ${matcher.description} in the card list" }
            scrollContentBy(contentViewport().height * 0.6f)
        }
    }
    var lastTop = Float.NaN
    repeat(8) {
        val view = contentViewport()
        val n = node() ?: error("${matcher.description} scrolled out of the list")
        val top = n.positionInRoot.y
        val bottom = top + n.size.height
        val margin = view.height * 0.1f
        val dy = when {
            n.size.height > view.height - 2 * margin -> top - (view.top + margin)
            top < view.top + margin -> top - (view.top + margin)
            bottom > view.bottom - margin -> bottom - (view.bottom - margin)
            else -> 0f
        }
        val inside = top >= view.top - 1f && bottom <= view.bottom + 1f
        // Placed, or the list could not move any further (its first or last row).
        if (abs(dy) < 1f || (inside && top == lastTop)) return
        lastTop = top
        scrollContentBy(dy)
    }
    error("could not bring ${matcher.description} into view")
}

/**
 * Asserts that the row tagged [tag] is wholly inside the card list's visible area, or, if it
 * is taller than that area, that it starts near the top. Nothing is scrolled first.
 */
fun ComposeTestRule.assertRowInView(tag: String, what: String = tag) {
    val view = contentViewport()
    val node = placedNodes(hasTestTag(tag)).firstOrNull()
    assertTrue("$what is not even laid out", node != null)
    val top = node!!.positionInRoot.y
    val bottom = top + node.size.height
    val whole = top >= view.top - 1f && bottom <= view.bottom + 1f
    val tallFromTop = node.size.height > view.height * 0.8f && top >= view.top - 1f && top <= view.top + view.height * 0.15f
    assertTrue("$what spans $top..$bottom, list shows ${view.top}..${view.bottom}", whole || tallFromTop)
}

fun isRoundScreen(): Boolean =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.isScreenRound

/**
 * Asserts that [needle] can be read where it is: every character of it inside the card
 * list's visible area (for text in the list) and, on a round watch, inside the display
 * circle. Text that is only composed, just outside the viewport or clipped by the round
 * edge, fails. Nothing is scrolled first.
 */
fun ComposeTestRule.assertOnScreen(needle: String) {
    val inList = hasAnyAncestor(hasTestTag(ReviewTags.CONTENT))
    val node = placedNodes(hasText(needle, substring = true))
        .firstOrNull { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
    assertTrue("'$needle' is not even laid out:\n${allText()}", node != null)
    val layouts = mutableListOf<TextLayoutResult>()
    node!!.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
    val layout = layouts.first()
    val text = layout.layoutInput.text.text
    val start = text.indexOf(needle)
    assertTrue("'$needle' not in '$text'", start >= 0)
    val origin = node.positionInRoot
    val view = if (inList.matches(node)) contentViewport() else null
    val screen = onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
    val radius = minOf(screen.width, screen.height) / 2f
    val round = isRoundScreen()
    for (i in start until start + needle.length) {
        if (text[i].isWhitespace()) continue
        val box = layout.getBoundingBox(i).translate(origin)
        if (view != null) {
            assertTrue(
                "'${text[i]}' of '$needle' at ${box.top}..${box.bottom} is outside the list's ${view.top}..${view.bottom}",
                box.top >= view.top - 1f && box.bottom <= view.bottom + 1f
            )
        }
        if (round) {
            for (x in listOf(box.left, box.right)) {
                val dx = x - screen.center.x
                val dy = box.center.y - screen.center.y
                assertTrue(
                    "'${text[i]}' of '$needle' at ($x, ${box.center.y}) is cut off by the round screen",
                    sqrt(dx * dx + dy * dy) <= radius + 1f
                )
            }
        }
    }
}

/**
 * Asserts that every piece of text in the control tagged [tag] (a one-line label: a chip, a
 * button) is shown whole: it got the width its text needs (so it isn't wrapped away,
 * ellipsized or clipped) and lies inside the control (so a fixed height doesn't cut it).
 * Nothing is scrolled first.
 */
fun ComposeTestRule.assertLabelWhole(tag: String, what: String) {
    val control = placedNodes(hasTestTag(tag)).firstOrNull()
    assertTrue("$what: $tag is not laid out", control != null)
    val box = control!!.unclippedBounds()
    val labels = placedNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
    assertTrue("$what: $tag shows no text", labels.isNotEmpty())
    for (label in labels) {
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.first()
        val text = layout.layoutInput.text.text
        val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth)
        assertTrue("$what: '$text' needs ${needed}px across, got ${layout.size.width}px", needed <= layout.size.width + 1f)
        assertFalse("$what: '$text' overflows (${layout.lineCount} lines)", layout.hasVisualOverflow)
        val r = label.unclippedBounds()
        assertTrue(
            "$what: '$text' at $r spills out of $tag at $box",
            r.left >= box.left - 1f && r.right <= box.right + 1f && r.top >= box.top - 1f && r.bottom <= box.bottom + 1f
        )
    }
}

/**
 * Shows [content] as on a round watch [screenDp] wide (null: this emulator's own size) with
 * the font size setting at [fontScale]. The pixels stay the same; the density changes, as it
 * does between a 40 mm and a 44 mm watch. Font scaling is linear here, which for scales above
 * 1 is larger than Android's own (non-linear) scaling of big text, so it errs on the safe side.
 */
@Composable
fun WatchSize(screenDp: Int?, fontScale: Float, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val density = if (screenDp == null) base.density else config.screenWidthDp * base.density / screenDp
    val sized = if (screenDp == null) config else Configuration(config).apply {
        screenWidthDp = screenDp
        screenHeightDp = (config.screenHeightDp * base.density / density).roundToInt()
        smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        densityDpi = (density * 160).roundToInt()
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density, base.fontScale * fontScale),
        LocalConfiguration provides sized,
        content = content
    )
}

/** Taps the middle of [needle] inside the text node tagged [tag] (e.g. one cloze span). */
fun ComposeTestRule.tapTextIn(tag: String, needle: String) {
    val node = onNodeWithTag(tag, useUnmergedTree = true)
    val layouts = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
    val layout = layouts.first()
    val text = layout.layoutInput.text.text
    val start = text.indexOf(needle)
    check(start >= 0) { "'$needle' not found in '$text'" }
    val box = layout.getBoundingBox(start + needle.length / 2)
    node.performTouchInput { click(box.center) }
    waitForIdle()
}

/**
 * Logs a downscaled JPEG of the screen as base64 chunks under the ANKIWATCH_SHOT tag. CI
 * collects them from logcat, since emulator files don't survive the test-app uninstall.
 */
fun ComposeTestRule.screenshot(name: String) {
    waitForIdle()
    val full = onRoot().captureToImage().asAndroidBitmap()
    val width = 320
    val scaled = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    val chunks = b64.chunked(3000)
    chunks.forEachIndexed { i, chunk -> Log.i(SHOT_TAG, "SHOT|$name|$i|${chunks.size}|$chunk") }
}

const val SHOT_TAG = "ANKIWATCH_SHOT"
