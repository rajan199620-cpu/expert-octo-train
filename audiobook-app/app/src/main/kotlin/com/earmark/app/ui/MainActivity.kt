package com.earmark.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import com.earmark.app.earmark
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Files shared/opened into the app ("Open with Earmark"). */
    private val incoming = MutableStateFlow<Uri?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            EarmarkTheme {
                AppRoot(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incoming.value = uri
    }
}

@Composable
private fun AppRoot(incoming: MutableStateFlow<Uri?>) {
    val app = LocalContext.current.earmark
    var screen by rememberSaveable { mutableStateOf("library") }

    BackHandler(enabled = screen != "library") {
        screen = when (screen) {
            "annotations", "settings-from-reader" -> "reader"
            else -> "library"
        }
    }

    when (screen) {
        "reader" -> ReaderScreen(
            hub = app.hub,
            onBack = { screen = "library" },
            onAnnotations = { screen = "annotations" },
            onSettings = { screen = "settings-from-reader" },
        )
        "annotations" -> AnnotationsScreen(app.hub, onBack = { screen = "reader" }, onJump = { screen = "reader" })
        "settings", "settings-from-reader" -> SettingsScreen(
            app.settings,
            app.hub,
            onBack = { screen = if (screen == "settings-from-reader") "reader" else "library" },
        )
        else -> LibraryScreen(
            library = app.library,
            incoming = incoming,
            onOpen = { id, autoplay ->
                app.hub.open(id, autoplay)
                screen = "reader"
            },
            onSettings = { screen = "settings" },
        )
    }
}
