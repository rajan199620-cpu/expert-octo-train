package com.ankiwear.wear.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.ankiwear.wear.model.DeckInfo
import com.ankiwear.wear.model.DeckNode
import com.ankiwear.wear.model.DeckTreeBuilder
import com.ankiwear.wear.theme.DeckLearnColor
import com.ankiwear.wear.theme.DeckNewColor
import com.ankiwear.wear.theme.DeckReviewColor

@Composable
fun DeckListScreen(
    decks: List<DeckInfo>,
    isLoading: Boolean,
    onDeckSelected: (DeckInfo) -> Unit,
    onRefresh: () -> Unit = {},
    /** Epoch millis of the most recent decks response, or null if none yet. */
    lastUpdatedMillis: Long? = null
) {
    val listState = rememberScalingLazyListState()

    if (isLoading && decks.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Loading decks...",
                style = MaterialTheme.typography.body2
            )
        }
        return
    }

    // Build the deck tree from the flat list. Re-runs whenever the deck list
    // changes (counts updated, decks added/removed).
    val tree = remember(decks) { DeckTreeBuilder.build(decks) }
    // Default to fully expanded on first load. Crucially this is NOT keyed on `decks`, so
    // it survives the deck-list refreshes that happen every time the user returns from a
    // review — otherwise a branch the user collapsed would spring back open on every
    // refresh. When the deck set changes we preserve existing expand/collapse choices,
    // auto-expand only genuinely new nodes, and drop names that no longer exist.
    var expandedNames by remember { mutableStateOf(DeckTreeBuilder.allNodeNames(tree)) }
    var knownNames by remember { mutableStateOf(DeckTreeBuilder.allNodeNames(tree)) }
    LaunchedEffect(tree) {
        val all = DeckTreeBuilder.allNodeNames(tree)
        val newlyAppeared = all - knownNames
        expandedNames = (expandedNames + newlyAppeared).intersect(all)
        knownNames = all
    }
    val visible = remember(tree, expandedNames) {
        DeckTreeBuilder.flattenVisible(tree, expandedNames)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState
    ) {
        item {
            ListHeader {
                Text(
                    text = "Decks",
                    style = MaterialTheme.typography.title3
                )
            }
        }

        items(visible, key = { it.deck.name }) { node ->
            DeckItem(
                node = node,
                isExpanded = node.deck.name in expandedNames,
                onClick = { onDeckSelected(node.deck) },
                onToggleExpand = {
                    expandedNames = if (node.deck.name in expandedNames) {
                        expandedNames - node.deck.name
                    } else {
                        expandedNames + node.deck.name
                    }
                }
            )
        }

        // Refresh chip at the end of the list — placed after decks so it doesn't push
        // the list down on initial view, but is reachable with a quick scroll-down to
        // pull fresh deck counts from AnkiDroid without leaving the screen.
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                onClick = onRefresh,
                colors = ChipDefaults.secondaryChipColors(),
                label = {
                    Text(
                        text = "Refresh",
                        style = MaterialTheme.typography.button.copy(fontSize = 12.sp)
                    )
                }
            )
        }

        // "Updated 5s ago" caption beneath Refresh — keeps the user oriented about
        // whether the counts they're looking at are fresh.
        if (lastUpdatedMillis != null) {
            item {
                LastUpdatedLabel(lastUpdatedMillis = lastUpdatedMillis)
            }
        }
    }
}

@Composable
private fun LastUpdatedLabel(lastUpdatedMillis: Long) {
    // Force periodic recomposition so the relative time stays accurate without us
    // having to pipe a clock through the whole tree. Cheap — recomposes one Text.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastUpdatedMillis) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(5000)
        }
    }
    val secondsAgo = ((nowTick - lastUpdatedMillis) / 1000).coerceAtLeast(0)
    val text = formatRelativeTime(secondsAgo)
    Text(
        text = text,
        style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

private fun formatRelativeTime(secondsAgo: Long): String {
    return when {
        secondsAgo < 5 -> "Updated just now"
        secondsAgo < 60 -> "Updated ${secondsAgo}s ago"
        secondsAgo < 3600 -> "Updated ${secondsAgo / 60}m ago"
        secondsAgo < 86400 -> "Updated ${secondsAgo / 3600}h ago"
        else -> "Updated ${secondsAgo / 86400}d ago"
    }
}

/**
 * Width of the chevron tap zone on the left of each row. Big enough to hit reliably
 * on a small watch screen without crowding the deck name.
 */
private val CHEVRON_ZONE_WIDTH = 22.dp

/**
 * Per-depth indent applied to the chevron zone. Keeps subdecks visually nested under
 * their parent while leaving room for the deck name.
 */
private val INDENT_PER_DEPTH = 10.dp

@Composable
private fun DeckItem(
    node: DeckNode,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit
) {
    // Virtual decks (synthesized intermediates) can't be reviewed — only expanded.
    // Route the row body tap to the toggle in that case so the user still gets feedback.
    val bodyClick: () -> Unit = if (node.isVirtual) onToggleExpand else onClick

    Card(
        onClick = bodyClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chevron zone: depth-based indent + a separately-clickable chevron for
            // parents. Leaves (no children) get an empty box of the same size so the
            // deck names line up vertically regardless of children.
            Box(
                modifier = Modifier
                    .width(INDENT_PER_DEPTH * node.depth + CHEVRON_ZONE_WIDTH)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (node.hasChildren) {
                    Box(
                        modifier = Modifier
                            .size(CHEVRON_ZONE_WIDTH)
                            .clickable { onToggleExpand() },
                        contentAlignment = Alignment.Center
                    ) {
                        // Unicode triangles are the lightest-weight way to render a
                        // chevron without pulling in material-icons-extended. ▾ = open,
                        // ▸ = closed; matches the look of file-tree controls.
                        Text(
                            text = if (isExpanded) "▾" else "▸",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = node.displayName,
                style = MaterialTheme.typography.body2.copy(fontSize = 13.sp),
                // Virtual / intermediate decks have nothing to review, so dim the name
                // to communicate that the row is mainly a structural label.
                color = if (node.isVirtual) {
                    MaterialTheme.colors.onSurfaceVariant
                } else MaterialTheme.colors.onSurface,
                fontWeight = if (node.depth == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(width = 6.dp, height = 0.dp))
            CountTriple(
                newCount = node.displayNew,
                learnCount = node.displayLearn,
                reviewCount = node.displayReview
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun CountTriple(newCount: Int, learnCount: Int, reviewCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColoredCount(newCount, DeckNewColor)
        Spacer(modifier = Modifier.size(width = 6.dp, height = 0.dp))
        ColoredCount(learnCount, DeckLearnColor)
        Spacer(modifier = Modifier.size(width = 6.dp, height = 0.dp))
        ColoredCount(reviewCount, DeckReviewColor)
    }
}

@Composable
private fun ColoredCount(count: Int, color: Color) {
    // Dim zeros so the meaningful counts pop visually, just like AnkiDroid does.
    val displayColor = if (count == 0) {
        MaterialTheme.colors.onSurfaceVariant
    } else color
    Text(
        text = count.toString(),
        color = displayColor,
        fontSize = 13.sp,
        fontWeight = if (count == 0) FontWeight.Normal else FontWeight.Bold,
        textAlign = TextAlign.Center
    )
}
