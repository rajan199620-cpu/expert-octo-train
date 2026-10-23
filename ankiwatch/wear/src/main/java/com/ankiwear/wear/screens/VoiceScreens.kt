package com.ankiwear.wear.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.ankiwatch.core.DeckMatcher

/** Test tags for saying which deck to download. */
object VoiceTags {
    const val LIST = "voice-list"
    const val HEARD = "voice-heard"
    const val DOWNLOAD = "voice-download"
    const val AGAIN = "voice-again"
    const val PICK = "voice-pick"
    fun choice(name: String) = "voice-choice:$name"
}

/** Asking the watch's speech input for a deck name, and reading back what it heard. */
object Voice {
    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a deck, like \"prelims DPSP\"")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    /** What was heard, best guess first; empty when the user backed out or nothing was. */
    fun heard(resultCode: Int, data: Intent?): List<String> {
        if (resultCode != Activity.RESULT_OK) return emptyList()
        return data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.filterNotNull().orEmpty()
    }
}

/**
 * What to do with what was said: [download] these decks now, or show [result] (a choice, or
 * nothing found). [decksKnown] is false while the deck list hasn't come from the phone.
 */
data class VoiceOutcome(val result: DeckMatcher.Result?, val decksKnown: Boolean) {
    val download: List<String>
        get() = when (result) {
            is DeckMatcher.Result.One -> listOf(result.name)
            is DeckMatcher.Result.Many -> result.names
            else -> emptyList()
        }

    companion object {
        /** The watch has no speech input. */
        val NO_SPEECH = VoiceOutcome(null, decksKnown = true)

        fun of(result: DeckMatcher.Result, decksKnown: Boolean) = VoiceOutcome(result, decksKnown)
    }
}

/** "UPSC::Prelims::Polity::DPSP" → "UPSC › Prelims › Polity", or null at the top. */
internal fun parentsOf(name: String): String? =
    name.split("::").dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(" › ")

/**
 * More than one deck fits what was [heard] (or only some of it was understood): the user
 * ticks the ones meant, starting from [chosen], and downloads them.
 */
@Composable
fun VoiceChooseScreen(
    heard: String,
    names: List<String>,
    chosen: Set<String>,
    onDownload: (List<String>) -> Unit,
    onSayAgain: () -> Unit
) {
    var ticked by rememberSaveable(names, chosen) { mutableStateOf(ArrayList(chosen)) }
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().testTag(VoiceTags.LIST), state = listState) {
        item { ListHeader { Text("Which deck?", style = MaterialTheme.typography.title3) } }
        item { Heard(heard) }
        items(names, key = { it }) { name ->
            val on = name in ticked
            ToggleChip(
                checked = on,
                onCheckedChange = { ticked = ArrayList(if (it) ticked + name else ticked - name) },
                label = { Text(name.substringAfterLast("::"), maxLines = 2) },
                secondaryLabel = parentsOf(name)?.let { p -> { Text(p, maxLines = 1) } },
                toggleControl = { Icon(imageVector = ToggleChipDefaults.checkboxIcon(on), contentDescription = if (on) "Ticked" else "Not ticked") },
                modifier = Modifier.fillMaxWidth().testTag(VoiceTags.choice(name))
            )
        }
        item {
            // In the order offered, not the order ticked.
            val picked = names.filter { it in ticked }
            Chip(
                onClick = { onDownload(picked) },
                enabled = picked.isNotEmpty(),
                colors = ChipDefaults.primaryChipColors(),
                label = { Text(if (picked.size > 1) "Download ${picked.size} decks" else "Download", maxLines = 1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(VoiceTags.DOWNLOAD)
            )
        }
        item {
            CompactChip(
                onClick = onSayAgain,
                label = { Text("Say it again", fontSize = 11.sp) },
                modifier = Modifier.testTag(VoiceTags.AGAIN)
            )
        }
    }
}

/**
 * Nothing to download from what was [heard]: no deck fits, the deck list hasn't come from
 * the phone yet ([decksKnown] false), or the watch has no speech input ([heard] null).
 */
@Composable
fun VoiceNothingScreen(heard: String?, decksKnown: Boolean, onSayAgain: () -> Unit, onPick: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().testTag(VoiceTags.LIST), state = listState) {
        item { ListHeader { Text("No deck found", style = MaterialTheme.typography.title3) } }
        item {
            Text(
                text = when {
                    heard == null -> "This watch has no speech input. Pick the deck from the list."
                    !decksKnown -> "Your decks haven't come from the phone yet. Keep it near and try again."
                    heard.isEmpty() -> "Nothing was heard."
                    else -> "No deck is called “$heard”."
                },
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag(VoiceTags.HEARD)
            )
        }
        if (heard != null) item {
            Chip(
                onClick = onSayAgain,
                colors = ChipDefaults.primaryChipColors(),
                label = { Text("Say it again", maxLines = 1) },
                modifier = Modifier.fillMaxWidth().testTag(VoiceTags.AGAIN)
            )
        }
        item {
            Chip(
                onClick = onPick,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Pick from the list", maxLines = 1) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(VoiceTags.PICK)
            )
        }
    }
}

@Composable
private fun Heard(heard: String) {
    Text(
        text = "Heard “$heard”",
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(VoiceTags.HEARD)
    )
}
