package com.ankiwear.wear.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.ankiwatch.core.OfflineQueue
import com.ankiwatch.core.PackCard
import com.ankiwear.wear.offline.Download
import com.ankiwear.wear.offline.OfflineStore
import com.ankiwear.wear.offline.toCardData

/** Test tags for the offline screens. */
object OfflineTags {
    const val LIST = "offline-list"
    const val SYNC = "offline-sync"
    const val DOWNLOAD = "offline-download"
    const val DOWNLOAD_STATUS = "offline-download-status"
    const val WAIT = "offline-wait"
    const val SHOW_NOW = "offline-show-now"
    const val DONE = "offline-done"
    fun pack(deckId: Long) = "offline-pack-$deckId"
    fun remove(deckId: Long) = "offline-remove-$deckId"
}

/**
 * Decks downloaded for reviewing without the phone, whether the grades given from them have
 * reached the phone yet, and the way to download another deck.
 */
@Composable
fun OfflineScreen(
    packs: List<OfflineStore.Summary>,
    pendingGrades: Int,
    download: Download,
    phoneReachable: Boolean,
    now: Long,
    onReview: (OfflineStore.Summary) -> Unit,
    onDownload: () -> Unit,
    onRemove: (OfflineStore.Summary) -> Unit,
    onDismissDownload: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    var confirmRemove by remember { mutableStateOf<Long?>(null) }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().testTag(OfflineTags.LIST), state = listState) {
        item {
            ListHeader { Text("Offline", style = MaterialTheme.typography.title3) }
        }
        item {
            val text = when {
                pendingGrades > 0 -> "${grades(pendingGrades)} waiting for your phone"
                packs.any { it.answers > 0 } -> "All grades are in AnkiDroid"
                else -> "Review a downloaded deck without your phone"
            }
            Text(
                text = text,
                fontSize = 12.sp,
                color = if (pendingGrades > 0) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .testTag(OfflineTags.SYNC)
            )
        }
        if (download !is Download.Idle) {
            item { DownloadStatus(download, now, onDismissDownload) }
        }
        items(packs, key = { it.deckId }) { pack ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Chip(
                    onClick = { onReview(pack) },
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(pack.deckName.substringAfterLast("::"), maxLines = 1) },
                    secondaryLabel = {
                        Text("${if (pack.remaining == 0) "done" else "${pack.remaining} left"} · ${age(now - pack.createdAt)}", maxLines = 1)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OfflineTags.pack(pack.deckId))
                )
                CompactChip(
                    onClick = {
                        if (confirmRemove == pack.deckId) {
                            confirmRemove = null
                            onRemove(pack)
                        } else {
                            confirmRemove = pack.deckId
                        }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(if (confirmRemove == pack.deckId) "Tap again to remove" else "Remove", fontSize = 11.sp, maxLines = 1)
                    },
                    modifier = Modifier.testTag(OfflineTags.remove(pack.deckId))
                )
            }
        }
        item {
            val waiting = download is Download.Waiting
            Chip(
                onClick = onDownload,
                enabled = phoneReachable && pendingGrades == 0 && !waiting,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Download a deck", maxLines = 1) },
                secondaryLabel = {
                    Text(
                        text = when {
                            !phoneReachable -> "Needs your phone nearby"
                            pendingGrades > 0 -> "Once your grades reach the phone"
                            else -> "While your phone is near"
                        },
                        maxLines = 2
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .testTag(OfflineTags.DOWNLOAD)
            )
        }
        item {
            Text(
                text = "Download a deck while your phone is near, then review it anywhere. " +
                    "Your grades go into AnkiDroid when the phone is back.",
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun DownloadStatus(download: Download, now: Long, onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(OfflineTags.DOWNLOAD_STATUS)
    ) {
        when (download) {
            is Download.Waiting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Getting ${download.deckName.substringAfterLast("::")}…", fontSize = 12.sp, maxLines = 2)
                }
                if (now - download.since > SLOW_DOWNLOAD_MS) {
                    Text(
                        "Still waiting. Keep the phone close.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    CompactChip(onClick = onDismiss, label = { Text("Stop waiting", fontSize = 11.sp) })
                }
            }
            is Download.Ready -> Text(
                "${download.summary.deckName.substringAfterLast("::")}: ${cards(download.summary.total)} ready",
                fontSize = 12.sp,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
            is Download.Failed -> {
                Text(download.message, fontSize = 11.sp, color = MaterialTheme.colors.error, textAlign = TextAlign.Center)
                CompactChip(onClick = onDismiss, label = { Text("OK", fontSize = 11.sp) })
            }
            Download.Idle -> Unit
        }
    }
}

/**
 * Reviews a downloaded deck without the phone, in the order [OfflineQueue] decides. Each
 * answer goes to [onAnswered] after the queue has taken it, to be stored and sent.
 * [clock] (epoch millis) and [tickMs], how often a waiting screen looks at it again, are
 * there for tests.
 */
@Composable
fun OfflineReviewScreen(
    queue: OfflineQueue,
    pendingGrades: Int = 0,
    focusMode: Boolean = true,
    onFocusModeChange: (Boolean) -> Unit = {},
    clock: () -> Long = { System.currentTimeMillis() },
    tickMs: Long = 15_000,
    onAnswered: (card: PackCard, ease: Int, timeTakenMs: Long) -> Unit,
    onExit: () -> Unit
) {
    var answers by remember(queue) { mutableIntStateOf(queue.state.answers) }
    var now by remember(queue) { mutableLongStateOf(clock()) }
    var early by remember(queue) { mutableStateOf<OfflineQueue.Next.Show?>(null) }
    val next = remember(queue, answers, now, early) { early ?: queue.next(now) }

    when (next) {
        is OfflineQueue.Next.Show -> {
            val card = next.card
            ReviewScreen(
                card = remember(card, next.repeat) { card.toCardData(withLabels = !next.repeat) },
                header = "Offline · ${queue.remaining} left",
                revisionKey = "offline:$answers",
                focusMode = focusMode,
                onFocusModeChange = onFocusModeChange,
                onAnswer = { _, _, ease, timeTaken ->
                    val at = clock()
                    queue.answer(card.key, ease, at)
                    onAnswered(card, ease, timeTaken)
                    early = null
                    now = at
                    answers++
                },
                onFinished = onExit
            )
        }
        is OfflineQueue.Next.Wait -> {
            Ticker(tickMs) { now = clock() }
            OfflineWaitScreen(
                waitMs = next.until - now,
                waiting = next.count,
                onShowNow = { early = queue.showNow() },
                onBack = onExit
            )
        }
        OfflineQueue.Next.Finished -> OfflineDoneScreen(pendingGrades = pendingGrades, onBack = onExit)
    }
}

/**
 * Calls [onTick] every [periodMs] while on screen. A main-thread Handler rather than a
 * coroutine loop: it never keeps a UI test from going idle.
 */
@Composable
private fun Ticker(periodMs: Long, onTick: () -> Unit) {
    val current by rememberUpdatedState(onTick)
    DisposableEffect(periodMs) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                current()
                handler.postDelayed(this, periodMs)
            }
        }
        handler.postDelayed(tick, periodMs)
        onDispose { handler.removeCallbacks(tick) }
    }
}

/** Only cards in a learning step are left, none due yet. */
@Composable
fun OfflineWaitScreen(waitMs: Long, waiting: Int, onShowNow: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(OfflineTags.WAIT),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Next card in ${minutes(waitMs)}", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "${cards(waiting)} still learning",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Chip(
            onClick = onShowNow,
            colors = ChipDefaults.primaryChipColors(),
            label = { Text("Show it now", maxLines = 1) },
            modifier = Modifier.testTag(OfflineTags.SHOW_NOW)
        )
        Spacer(Modifier.height(4.dp))
        CompactChip(onClick = onBack, label = { Text("Back", fontSize = 11.sp) })
    }
}

@Composable
fun OfflineDoneScreen(pendingGrades: Int, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(OfflineTags.DONE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Done!", style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (pendingGrades > 0) "${grades(pendingGrades)} go into AnkiDroid when your phone is back."
            else "All downloaded cards are done.",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Chip(onClick = onBack, colors = ChipDefaults.primaryChipColors(), label = { Text("Back", maxLines = 1) })
    }
}

private const val SLOW_DOWNLOAD_MS = 60_000L

private fun cards(n: Int) = if (n == 1) "1 card" else "$n cards"
private fun grades(n: Int) = if (n == 1) "1 grade" else "$n grades"

/** "under a minute", "8 min", "1 h 20 min". */
internal fun minutes(ms: Long): String {
    val min = (ms + 59_999) / 60_000
    return when {
        min <= 1 -> "under a minute"
        min < 60 -> "$min min"
        min % 60 == 0L -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }
}

/** How long ago a download was made: "just now", "12 min ago", "3 h ago", "2 days ago". */
internal fun age(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}
