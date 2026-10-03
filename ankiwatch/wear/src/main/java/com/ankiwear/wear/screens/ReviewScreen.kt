package com.ankiwear.wear.screens

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.ankiwatch.core.Block
import com.ankiwatch.core.BlockKind
import com.ankiwatch.core.CardRenderer
import com.ankiwatch.core.ClozeParser
import com.ankiwatch.core.ParsedField
import com.ankiwatch.core.RenderOptions
import com.ankiwatch.core.RenderedField
import com.ankiwatch.core.Wire
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.CardType
import com.ankiwear.wear.review.GradeLayout
import com.ankiwear.wear.review.Press
import com.ankiwear.wear.review.SideButtons
import com.ankiwear.wear.theme.DeckLearnColor
import com.ankiwear.wear.theme.DeckNewColor
import com.ankiwear.wear.theme.DeckReviewColor
import com.ankiwear.wear.theme.EaseAgainColor
import com.ankiwear.wear.theme.EaseGoodColor
import kotlinx.coroutines.flow.first

/** Test tags used by the instrumented UI tests. */
object ReviewTags {
    const val CONTENT = "review-content"
    const val SHOW_ANSWER = "show-answer"
    const val FOCUS_TOGGLE = "focus-toggle"
    const val BURY = "bury"
    fun block(index: Int) = "block-$index"
    fun extraBlock(extra: Int, index: Int) = "extra-$extra-$index"
    fun ease(ease: Int) = "ease-$ease"
}

@Composable
fun ReviewScreen(
    card: CardData?,
    newRemaining: Int = 0,
    learnRemaining: Int = 0,
    reviewRemaining: Int = 0,
    currentCardType: CardType = CardType.NEW,
    revisionKey: Any,
    isFetching: Boolean = false,
    /** Show only the part of a long note that holds the tested cloze. */
    focusMode: Boolean = true,
    onFocusModeChange: (Boolean) -> Unit = {},
    /** Offer Bury; it reaches [onAnswer] with ease [Wire.EASE_BURY]. */
    allowBury: Boolean = true,
    onAnswer: (noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long) -> Unit,
    onFinished: () -> Unit
) {
    if (card == null) {
        // Waiting for more cards from the phone, or truly done. The side button must not
        // fall through to Back here: a quick press while the next batch loads would
        // otherwise drop the user out of the review. Loading: ignored. Done: back to decks.
        val currentOnFinished by rememberUpdatedState(onFinished)
        DisposableEffect(isFetching) {
            val handler: (Press) -> Unit = { if (!isFetching) currentOnFinished() }
            SideButtons.handler = handler
            onDispose {
                if (SideButtons.handler === handler) SideButtons.handler = null
            }
        }
        if (isFetching) {
            LoadingScreen()
        } else {
            CompletionScreen(onFinished = onFinished)
        }
        return
    }

    // Key everything to revisionKey so that re-answering the same card (e.g. after "Again"
    // returns the same note) fully resets the question view and the hasAnswered guard.
    var showAnswer by remember(revisionKey) { mutableStateOf(false) }
    var hasAnswered by remember(revisionKey) { mutableStateOf(false) }
    var toggled by remember(revisionKey) { mutableStateOf(emptySet<Int>()) }
    // elapsedRealtime is monotonic — unlike wall-clock time it can't jump on an NTP
    // correction mid-review and hand AnkiDroid a negative or wildly inflated duration.
    var startTime by remember(revisionKey) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(revisionKey) {
        startTime = SystemClock.elapsedRealtime()
        showAnswer = false
        hasAnswered = false
        toggled = emptySet()
    }

    val parsed = remember(card) { ParsedCard.from(card) }
    val rendered = remember(parsed, showAnswer, toggled) { parsed.render(showAnswer, toggled) }

    val onToggle: (Int) -> Unit = { id ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        toggled = if (id in toggled) toggled - id else toggled + id
    }
    val reveal: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // A tested cloze peeked on the question side must stay open on the answer side, so
        // drop the genuine spans from the toggles (their default flips with the side).
        toggled = toggled - rendered.genuineIds
        showAnswer = true
    }
    val layout = remember(card.buttonCount) { GradeLayout.forButtonCount(card.buttonCount) }
    val grade: (Int) -> Unit = { ease ->
        // Guard against a second tap arriving before the next card replaces us, which
        // would otherwise send an answer for the wrong card.
        if (!hasAnswered) {
            hasAnswered = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val timeTaken = SystemClock.elapsedRealtime() - startTime
            onAnswer(card.noteId, card.cardOrd, ease, timeTaken)
        }
    }

    // The watch's side button while a card is up: press = show answer, then press = Good,
    // hold = Again. Everywhere else it stays the normal Back button.
    val currentReveal by rememberUpdatedState(reveal)
    val currentGrade by rememberUpdatedState(grade)
    val currentLayout by rememberUpdatedState(layout)
    DisposableEffect(revisionKey) {
        val handler: (Press) -> Unit = { press ->
            when {
                hasAnswered -> Unit
                !showAnswer -> currentReveal()
                press == Press.LONG -> currentGrade(currentLayout.again)
                else -> currentGrade(currentLayout.good)
            }
        }
        SideButtons.handler = handler
        onDispose {
            if (SideButtons.handler === handler) SideButtons.handler = null
        }
    }

    val insets = rememberScreenInsets()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        // Colored per-category counter — matches AnkiDroid's blue·red·green display.
        // The current card type's number is underlined so the user knows which queue
        // they're working through.
        QueueCounterRow(
            newCount = newRemaining,
            learnCount = learnRemaining,
            reviewCount = reviewRemaining,
            activeType = currentCardType,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        CardBody(
            rendered = rendered,
            showAnswer = showAnswer,
            focusMode = focusMode,
            revisionKey = revisionKey,
            onToggle = onToggle,
            // Cards without any tappable cloze keep the original "tap anywhere" reveal.
            onTapContent = if (!showAnswer && !rendered.hasTappableCloze) reveal else null,
            onFocusModeChange = onFocusModeChange,
            // Bury hides the card until tomorrow; once per card, like a grade.
            onBury = if (allowBury) ({ grade(Wire.EASE_BURY) }) else null,
            buryEnabled = !hasAnswered,
            insets = insets,
            // Under the answer rather than above the buttons: the card keeps the room.
            footer = if (showAnswer) layout.holdCaption(card.nextReviewTimes) else null,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        if (!showAnswer) {
            ShowAnswerButton(onClick = reveal)
        } else {
            GradeButtons(
                layout = layout,
                nextReviewTimes = card.nextReviewTimes,
                enabled = !hasAnswered,
                onEase = grade,
                sidePadding = insets.controlsSide
            )
        }
        Spacer(modifier = Modifier.height(insets.bottom))
    }
}

/** A card parsed once, rendered again for each side and toggle state. */
class ParsedCard private constructor(
    private val main: ParsedField?,
    private val question: ParsedField?,
    private val answer: ParsedField?,
    private val clozeNumber: Int,
    private val extras: List<Pair<String, ParsedField>>
) {
    fun render(showAnswer: Boolean, toggled: Set<Int>): CardView {
        val body = if (main != null) {
            CardRenderer.render(main, RenderOptions(activeOrd = clozeNumber, showAnswer = showAnswer, toggled = toggled))
        } else {
            val field = if (showAnswer) answer else question
            CardRenderer.render(field ?: ClozeParser.parse(""), RenderOptions(toggled = toggled))
        }
        val shownExtras = if (showAnswer) {
            extras.map { (label, field) -> label to CardRenderer.render(field, RenderOptions(plainClozes = true)) }
                .filter { it.second.blocks.isNotEmpty() }
        } else emptyList()
        return CardView(body, shownExtras, isCloze = main != null)
    }

    companion object {
        fun from(card: CardData): ParsedCard {
            val cloze = card.cloze
            return if (cloze != null && cloze.content.isNotBlank()) {
                ParsedCard(
                    main = ClozeParser.parse(cloze.content),
                    question = null,
                    answer = null,
                    clozeNumber = cloze.clozeNumber,
                    extras = cloze.extras.map { (label, value) -> label to ClozeParser.parse(value) }
                )
            } else {
                ParsedCard(
                    main = null,
                    question = ClozeParser.parse(card.question),
                    answer = ClozeParser.parse(card.answer),
                    clozeNumber = 0,
                    extras = emptyList()
                )
            }
        }
    }
}

/** Everything one side of a card shows. */
class CardView(
    val body: RenderedField,
    val extras: List<Pair<String, RenderedField>>,
    val isCloze: Boolean
) {
    val genuineIds: Set<Int> = body.blocks.flatMap { b ->
        b.runs.mapNotNull { r -> r.cloze?.takeIf { it.state.isGenuine }?.id }
    }.toSet()

    val hasTappableCloze: Boolean = body.blocks.any { b -> b.runs.any { it.cloze?.toggleable == true } }
}

/**
 * Insets that keep content inside a round display. On a round watch the bottom corners do
 * not exist, so the answer buttons sit higher and narrower, and long text gets wider side
 * margins. Fractions of the screen width, so 40 mm and 44 mm watches both fit.
 */
@Immutable
class ScreenInsets(val listSide: Dp, val listTop: Dp, val controlsSide: Dp, val bottom: Dp)

@Composable
fun rememberScreenInsets(): ScreenInsets {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp.dp
    val round = config.isScreenRound
    return remember(width, round) {
        if (round) {
            ScreenInsets(listSide = width * 0.08f, listTop = 10.dp, controlsSide = width * 0.19f, bottom = width * 0.06f)
        } else {
            ScreenInsets(listSide = 8.dp, listTop = 4.dp, controlsSide = 12.dp, bottom = 8.dp)
        }
    }
}

/** One line of the scrolling card body. */
private sealed class Line {
    class Body(val block: Block) : Line()
    object Gap : Line()
    /** The chip row under the card: Whole note/Focus (if the note can be focused) and Bury. */
    class Actions(val hiddenCount: Int?, val focused: Boolean, val bury: Boolean) : Line()
    class ExtraHeader(val label: String) : Line()
    class Extra(val extraIndex: Int, val block: Block) : Line()
    class Footer(val text: String) : Line()
    object Empty : Line()
}

@Composable
private fun CardBody(
    rendered: CardView,
    showAnswer: Boolean,
    focusMode: Boolean,
    revisionKey: Any,
    onToggle: (Int) -> Unit,
    onTapContent: (() -> Unit)?,
    onFocusModeChange: (Boolean) -> Unit,
    onBury: (() -> Unit)?,
    buryEnabled: Boolean,
    insets: ScreenInsets,
    footer: String?,
    modifier: Modifier = Modifier
) {
    val body = rendered.body
    val focus = remember(rendered) { if (rendered.isCloze) body.focusIndices() else null }
    val canFocus = focus != null && focus.size < body.blocks.size
    val focused = focusMode && canFocus

    val canBury = onBury != null
    val rows = remember(rendered, focused, footer, canBury) {
        buildList {
            if (body.blocks.isEmpty()) add(Line.Empty)
            if (focused) {
                var previous = -1
                for (i in focus!!) {
                    if (previous >= 0 && i != previous + 1) add(Line.Gap)
                    add(Line.Body(body.blocks[i]))
                    previous = i
                }
            } else {
                body.blocks.forEach { add(Line.Body(it)) }
            }
            if (canFocus || canBury) {
                add(Line.Actions(if (canFocus) body.blocks.size - focus!!.size else null, focused, canBury))
            }
            rendered.extras.forEachIndexed { k, (label, field) ->
                add(Line.ExtraHeader(label))
                field.blocks.forEach { add(Line.Extra(k, it)) }
            }
            if (footer != null) add(Line.Footer(footer))
        }
    }

    // The rows that must be on screen when a side opens: those holding the tested cloze
    // (covered or open), or on a template card's answer side everything after Anki's answer
    // divider. Null: read from the top.
    val span = remember(rows, showAnswer) {
        val answerStart = if (!rendered.isCloze && showAnswer) body.answerStartBlock() else -1
        fun isTarget(row: Line) = row is Line.Body &&
            (if (answerStart >= 0) row.block.index >= answerStart else rendered.isCloze && row.block.containsGenuine)
        val first = rows.indexOfFirst(::isTarget)
        if (first < 0) null else first..rows.indexOfLast(::isTarget)
    }

    val currentSpan by rememberUpdatedState(span)
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    // Each new card, side or mode opens at the top and then scrolls only as far as it takes
    // to show the span. Tapping clozes open or shut never moves the list.
    LaunchedEffect(revisionKey, showAnswer, focused) {
        listState.scrollToItem(0)
        // A list that was just created lays itself out once before it can scroll; until then
        // its rows report zero alpha.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.alpha > 0f } }.first { it }
        currentSpan?.let { listState.bringIntoView(it) }
    }

    val bottomPadding = 8.dp
    ScalingLazyColumn(
        // ScalingLazyColumn lays rows out a little beyond its own edges; clip there so a
        // scrolled row never draws over the counter or the buttons.
        modifier = modifier
            .testTag(ReviewTags.CONTENT)
            .clipToBounds()
            .fadingEdges(top = insets.listTop, bottom = bottomPadding),
        state = listState,
        anchorType = ScalingLazyListAnchorType.ItemStart,
        autoCentering = null,
        contentPadding = PaddingValues(start = insets.listSide, end = insets.listSide, top = insets.listTop, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        // No shrinking or fading towards the edges: this is text to read, and the tested
        // cloze often sits at the bottom of the screen.
        scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f)
    ) {
        items(rows) { row ->
            when (row) {
                is Line.Body -> BlockView(row.block, onToggle, onTapContent, Modifier.testTag(ReviewTags.block(row.block.index)))
                is Line.Extra -> BlockView(row.block, onToggle, null, Modifier.testTag(ReviewTags.extraBlock(row.extraIndex, row.block.index)), fontSize = 13)
                Line.Gap -> Text(
                    text = "⋯",
                    color = MaterialTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                is Line.ExtraHeader -> Text(
                    text = row.label,
                    color = extraHeaderColor(row.label),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                is Line.Actions -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    if (row.hiddenCount != null) {
                        CompactChip(
                            onClick = { onFocusModeChange(!row.focused) },
                            colors = ChipDefaults.secondaryChipColors(),
                            label = {
                                Text(
                                    text = if (row.focused) "Whole note (+${row.hiddenCount})" else "Focus on cloze",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag(ReviewTags.FOCUS_TOGGLE)
                        )
                    }
                    if (row.bury && onBury != null) {
                        CompactChip(
                            onClick = onBury,
                            enabled = buryEnabled,
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text(text = "Bury", fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.testTag(ReviewTags.BURY)
                        )
                    }
                }
                is Line.Footer -> Text(
                    text = row.text,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Line.Empty -> Text(
                    text = "(empty card)",
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Scrolls the least distance that shows rows [span] whole, so as much as fits of what comes
 * before them (the note's heading, the parent list item) stays in view. A span taller than
 * the list is shown from its first line. Expects the list laid out from the top.
 */
private suspend fun ScalingLazyListState.bringIntoView(span: IntRange) {
    val info = layoutInfo
    val center = info.viewportSize.height / 2
    val top = info.beforeContentPadding
    val bottom = info.viewportSize.height - info.afterContentPadding
    fun rowTop(row: Int) = layoutInfo.visibleItemsInfo.firstOrNull { it.index == row }?.let { center + it.offset }
    // Already whole on screen (the usual case for a short note): leave the top in view.
    val shownLast = layoutInfo.visibleItemsInfo.firstOrNull { it.index == span.last }
    val shownTop = rowTop(span.first)
    if (shownLast != null && shownTop != null && shownTop >= top && center + shownLast.offset + shownLast.size <= bottom) return
    // With ItemStart anchoring, scrollToItem puts a row's top edge on the centre line, less
    // scrollOffset. Lay the span out from the list's top edge first …
    scrollToItem(span.first, center - top)
    val firstTop = rowTop(span.first) ?: return
    val last = layoutInfo.visibleItemsInfo.firstOrNull { it.index == span.last } ?: return
    val spanBottom = center + last.offset + last.size
    if (spanBottom > bottom) return
    // … then, as it fits, slide it down to the bottom edge to bring back the context above.
    scrollToItem(span.first, center - (bottom - (spanBottom - firstTop)))
}

/**
 * Fades rows out across the list's top and bottom padding, so a row scrolled part-way out
 * trails off instead of being sliced in two right under the counter or above the buttons.
 * Rows the list comes to rest on (the first one at the top, the tested cloze at the bottom)
 * sit just inside the padding and are never faded.
 */
private fun Modifier.fadingEdges(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topPx = top.toPx()
        val bottomPx = bottom.toPx()
        if (size.height > topPx + bottomPx) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    topPx / size.height to Color.Black,
                    1f - bottomPx / size.height to Color.Black,
                    1f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

private fun extraHeaderColor(label: String): Color = when (label.lowercase()) {
    "note", "notes" -> Color(0xFFF28B82)
    "mnemonics", "mnemonic" -> Color(0xFF81C995)
    "extra", "back extra" -> Color(0xFFFDD663)
    else -> Color(0xFF8AB4F8)
}

@Composable
private fun BlockView(
    block: Block,
    onToggle: (Int) -> Unit,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fontSize: Int = 15
) {
    if (block.kind == BlockKind.RULE) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(Color(0xFF555555))
        )
        return
    }
    val accent = accentColor(block.accent)
    val indent = (block.depth * 8 + if (block.continuation) 12 else 0).dp
    val text = remember(block) { blockText(block, onToggle) }
    var textModifier = Modifier
        .fillMaxWidth()
        .padding(start = indent)
    if (block.boxed) {
        textModifier = textModifier
            .background(Color(0xFF14202C), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    }
    if (onTap != null) textModifier = textModifier.clickable(onClick = onTap)
    // Caller modifiers (test tags) go last so they share the text's own coordinates.
    textModifier = textModifier.then(modifier)
    Text(
        text = text,
        modifier = textModifier,
        fontSize = when (block.kind) {
            BlockKind.HEADING -> (fontSize + 1).sp
            BlockKind.SUBHEADING -> fontSize.sp
            else -> fontSize.sp
        },
        fontWeight = if (block.kind == BlockKind.TEXT) null else FontWeight.Bold,
        color = if (accent != Color.Unspecified) accent else MaterialTheme.colors.onSurface,
        textAlign = TextAlign.Start,
        lineHeight = (fontSize + 4).sp
    )
}

@Composable
private fun ShowAnswerButton(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CompactChip(
            onClick = onClick,
            colors = ChipDefaults.primaryChipColors(),
            label = { Text(text = "Show answer", fontSize = 12.sp, maxLines = 1) },
            modifier = Modifier.testTag(ReviewTags.SHOW_ANSWER)
        )
    }
}

/**
 * Two big answer buttons, Again and Good. Holding Again answers Hard and holding Good
 * answers Easy, so the rarely used grades stay reachable without crowding the screen. Each
 * pill shows its next interval under the label; the hold intervals are listed under the card.
 */
@Composable
private fun GradeButtons(
    layout: GradeLayout,
    nextReviewTimes: List<String>,
    enabled: Boolean,
    onEase: (Int) -> Unit,
    sidePadding: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val hard = layout.hard
        val easy = layout.easy
        GradeButton(
            label = "Again",
            interval = layout.interval(nextReviewTimes, layout.again),
            color = EaseAgainColor,
            enabled = enabled,
            onClick = { onEase(layout.again) },
            onLongClick = if (hard != null) ({ onEase(hard) }) else null,
            modifier = Modifier
                .weight(1f)
                .testTag(ReviewTags.ease(layout.again))
        )
        GradeButton(
            label = "Good",
            interval = layout.interval(nextReviewTimes, layout.good),
            color = EaseGoodColor,
            enabled = enabled,
            onClick = { onEase(layout.good) },
            onLongClick = if (easy != null) ({ onEase(easy) }) else null,
            modifier = Modifier
                .weight(1f)
                .testTag(ReviewTags.ease(layout.good))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GradeButton(
    label: String,
    interval: String?,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
            maxLines = 1
        )
        // Interval prediction (e.g. "10m", "1d"), the same data AnkiDroid shows on its
        // buttons.
        if (interval != null) {
            Text(
                text = interval,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                color = Color.Black.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

/**
 * AnkiDroid-style queue counter: `12 · 3 · 45` with blue/red/green coloring.
 * The number for [activeType] gets an underline to indicate which queue the
 * current card belongs to.
 */
@Composable
private fun QueueCounterRow(
    newCount: Int,
    learnCount: Int,
    reviewCount: Int,
    activeType: CardType,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        QueueNumber(newCount, DeckNewColor, isActive = activeType == CardType.NEW)
        DotSeparator()
        QueueNumber(learnCount, DeckLearnColor, isActive = activeType == CardType.LEARNING)
        DotSeparator()
        QueueNumber(reviewCount, DeckReviewColor, isActive = activeType == CardType.REVIEW)
    }
}

@Composable
private fun QueueNumber(count: Int, color: Color, isActive: Boolean) {
    Text(
        text = count.toString(),
        fontSize = 12.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textDecoration = if (isActive) TextDecoration.Underline else TextDecoration.None,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DotSeparator() {
    Text(
        text = " · ",
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurfaceVariant
    )
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.wear.compose.material.CircularProgressIndicator(
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Loading next card…",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CompletionScreen(onFinished: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Done!",
            style = MaterialTheme.typography.title2,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No more cards due",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Use a Chip rather than Button — Button is circular and clips longer labels
        // ("Back to decks" wrapped to three lines and got cut off by the round shape).
        Chip(
            onClick = onFinished,
            colors = ChipDefaults.primaryChipColors(),
            label = {
                Text(
                    text = "Back to decks",
                    maxLines = 1
                )
            }
        )
    }
}
