package com.ankiwear.wear

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import com.ankiwear.wear.screens.ReviewTags
import java.io.ByteArrayOutputStream

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
 * Brings a node into the card list's viewport: back towards the top first, then down. The
 * list may have opened scrolled to the tested cloze, so the target can be on either side.
 */
fun ComposeTestRule.scrollContentTo(matcher: SemanticsMatcher) {
    fun found() = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    if (found()) return
    repeat(15) {
        onNodeWithTag(ReviewTags.CONTENT).performTouchInput { swipeDown() }
        waitForIdle()
        if (found()) return
    }
    repeat(40) {
        onNodeWithTag(ReviewTags.CONTENT).performTouchInput { swipeUp() }
        waitForIdle()
        if (found()) return
    }
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
