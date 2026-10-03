package com.rajan.meditationtimer

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Background sound: off, generated rain or birdsong, or a recording of your own. Its volume is
 * separate from the bell's, and Listen plays ten seconds so it can be set before a sit.
 */
@Composable
fun BackgroundSoundSection(prefs: Prefs) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(prefs.ambience) }
    var level by remember { mutableFloatStateOf(prefs.ambienceVolume) }
    var fileName by remember { mutableStateOf(prefs.ambienceName) }
    var previewing by remember { mutableStateOf(false) }
    val preview = remember { AmbientPlayer(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { preview.stopNow() } }
    LaunchedEffect(previewing) {
        if (previewing) {
            delay(PREVIEW_MS)
            preview.stop(1_500)
            previewing = false
        }
    }
    fun stopPreview() { if (previewing) { preview.stop(600); previewing = false } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep the right to read it after a restart, so the sit can play it any day.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Your recording"
        prefs.ambienceUri = uri.toString()
        prefs.ambienceName = name
        fileName = name
        kind = Ambience.CUSTOM
        prefs.ambience = Ambience.CUSTOM
    }

    SectionLabel("Background sound", "Rain or birdsong under the sit, fading in and out. Off is the classic silence")
    ChipRow(Ambience.entries, kind, { it.label }) { choice ->
        stopPreview()
        if (choice == Ambience.CUSTOM && prefs.ambienceUri == null) {
            pick.launch(arrayOf("audio/*"))
        } else {
            kind = choice
            prefs.ambience = choice
        }
    }
    if (kind == Ambience.CUSTOM) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fileName ?: "Your recording",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { stopPreview(); pick.launch(arrayOf("audio/*")) }) { Text("Choose file") }
        }
    }
    if (kind != Ambience.OFF) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sound")
            Slider(
                value = level,
                onValueChange = {
                    level = it
                    prefs.ambienceVolume = it
                    preview.setVolume(it)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            TextButton(onClick = {
                if (previewing) {
                    stopPreview()
                } else {
                    preview.start(kind, level, prefs.ambienceUri?.let(Uri::parse), fadeInMs = 1_500)
                    previewing = true
                }
            }) { Text(if (previewing) "Stop" else "Listen") }
        }
    }
}

private const val PREVIEW_MS = 10_000L
