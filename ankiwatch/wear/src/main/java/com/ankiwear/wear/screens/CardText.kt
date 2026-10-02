package com.ankiwear.wear.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.ankiwatch.core.Accent
import com.ankiwatch.core.Block
import com.ankiwatch.core.ClozeMark
import com.ankiwatch.core.ClozeState
import com.ankiwatch.core.Style

// Cloze colours follow the Enhanced Cloze template (pink = the cloze being tested,
// blue = its siblings), tuned for an OLED-black watch face.
val GenuineHiddenBackground = Color(0xFFFF96AF)
val GenuineHiddenText = Color(0xFF1A1A1A)
val GenuineShownText = Color(0xFFFF8FAB)
val PseudoHiddenBackground = Color(0xFF213F94)
val PseudoHiddenText = Color(0xFFE8EEFF)
val PseudoShownText = Color(0xFF8AB4F8)
val DimText = Color(0xFF9E9E9E)

fun accentColor(accent: Accent): Color = when (accent) {
    Accent.BLUE -> Color(0xFF8AB4F8)
    Accent.RED -> Color(0xFFF28B82)
    Accent.GREEN -> Color(0xFF81C995)
    Accent.YELLOW -> Color(0xFFFDD663)
    Accent.NONE -> Color.Unspecified
}

/** Link tag for a tappable cloze span; [clozeIdFromTag] reverses it. */
fun clozeTag(id: Int): String = "cloze:$id"

fun clozeIdFromTag(tag: String): Int? = tag.removePrefix("cloze:").toIntOrNull()

private fun spanStyleFor(style: Int): SpanStyle {
    val decorations = buildList {
        if (style and Style.UNDERLINE != 0) add(TextDecoration.Underline)
        if (style and Style.STRIKE != 0) add(TextDecoration.LineThrough)
    }
    val superOrSub = style and (Style.SUPERSCRIPT or Style.SUBSCRIPT) != 0
    return SpanStyle(
        color = if (style and Style.DIM != 0) DimText else Color.Unspecified,
        fontSize = if (superOrSub) 0.75.em else TextUnit.Unspecified,
        fontWeight = if (style and Style.BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (style and Style.ITALIC != 0) FontStyle.Italic else null,
        fontFamily = if (style and Style.CODE != 0) FontFamily.Monospace else null,
        baselineShift = when {
            style and Style.SUPERSCRIPT != 0 -> BaselineShift.Superscript
            style and Style.SUBSCRIPT != 0 -> BaselineShift.Subscript
            else -> null
        },
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
    )
}

private fun clozeStyle(mark: ClozeMark): SpanStyle = when (mark.state) {
    ClozeState.GENUINE_HIDDEN -> SpanStyle(
        color = GenuineHiddenText,
        background = GenuineHiddenBackground,
        fontWeight = FontWeight.Medium
    )
    ClozeState.PSEUDO_HIDDEN -> SpanStyle(color = PseudoHiddenText, background = PseudoHiddenBackground)
    ClozeState.GENUINE_SHOWN -> SpanStyle(
        color = GenuineShownText,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline
    )
    ClozeState.PSEUDO_SHOWN -> SpanStyle(color = PseudoShownText)
    ClozeState.CONTEXT -> SpanStyle()
}

/**
 * One rendered block as Compose text. Tappable cloze spans become link annotations whose
 * clicks call [onToggle] with the span's id.
 */
@OptIn(ExperimentalTextApi::class)
fun blockText(block: Block, onToggle: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    block.label?.let { label ->
        withStyle(SpanStyle(color = DimText)) { append(label) }
        append(' ')
    }
    for (run in block.runs) {
        val base = spanStyleFor(run.style)
        val mark = run.cloze
        if (mark == null || mark.state == ClozeState.CONTEXT) {
            withStyle(base) { append(run.text) }
            continue
        }
        val style = base.merge(clozeStyle(mark))
        if (mark.toggleable) {
            val link = LinkAnnotation.Clickable(
                tag = clozeTag(mark.id),
                styles = TextLinkStyles(style = style),
                linkInteractionListener = { onToggle(mark.id) }
            )
            withLink(link) { append(run.text) }
        } else {
            withStyle(style) { append(run.text) }
        }
    }
}
