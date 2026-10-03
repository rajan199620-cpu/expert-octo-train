package com.ankiwear.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ankiwatch.core.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone companion screen: shows whether everything the watch needs is in place (AnkiDroid,
 * its permission, a connected watch) and the decks the watch will see. Reviewing itself
 * happens on the watch, via [WearListenerService].
 */
class MainActivity : ComponentActivity() {

    private lateinit var ankiHelper: AnkiDroidHelper
    private lateinit var dataLayerManager: DataLayerManager
    private lateinit var exchangeLog: ExchangeLog

    private var uiState by mutableStateOf(PhoneUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        uiState = uiState.copy(permissionGranted = granted)
        if (granted) refreshDecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ankiHelper = AnkiDroidHelper(this)
        dataLayerManager = DataLayerManager(this)
        exchangeLog = ExchangeLog(this)

        setContent {
            MaterialTheme {
                PhoneScreen(state = uiState, currentVersion = BuildConfig.VERSION_NAME)
            }
        }

        // Keep the watch row live while this screen is open, so installing or opening the
        // watch app (or reconnecting Bluetooth) shows up without reopening the phone app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    // Query first, then copy the state as it is now: copying before the
                    // suspending call could write back a deck list replaced meanwhile.
                    val link = dataLayerManager.watchLink()
                    uiState = uiState.copy(watchLink = link, lastExchange = exchangeLog.summary())
                    delay(WATCH_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkStateAndRefresh()
    }

    private fun checkStateAndRefresh() {
        val installed = ankiHelper.isAnkiDroidInstalled()
        val hasPermission = ankiHelper.hasPermission()

        uiState = uiState.copy(
            ankiDroidInstalled = installed,
            permissionGranted = hasPermission
        )

        if (!installed) return

        if (!hasPermission) {
            permissionLauncher.launch(AnkiDroidHelper.READ_WRITE_PERMISSION)
            return
        }

        refreshDecks()
    }

    private fun refreshDecks() {
        lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { ankiHelper.getDecks() }
            uiState = uiState.copy(decks = decks)
        }
    }

    private companion object {
        const val WATCH_CHECK_INTERVAL_MS = 3_000L
    }
}

data class PhoneUiState(
    val ankiDroidInstalled: Boolean = false,
    val permissionGranted: Boolean = false,
    /** Null until the first check has finished. */
    val watchLink: Link.Status? = null,
    /** The watch's last request and what the phone did with it; null if it never asked. */
    val lastExchange: String? = null,
    val decks: List<DeckData> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(state: PhoneUiState, currentVersion: String = "") {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AnkiWatch Phone") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            StatusRow(
                label = "AnkiDroid",
                ok = state.ankiDroidInstalled,
                detail = if (state.ankiDroidInstalled) "Installed" else "Not installed"
            )
            StatusRow(
                label = "Permission",
                ok = state.permissionGranted,
                detail = if (state.permissionGranted) "Granted" else "Not granted"
            )
            StatusRow(
                label = "Watch",
                ok = state.watchLink?.state == Link.State.READY,
                detail = Link.phoneRow(state.watchLink)
            )
            if (currentVersion.isNotEmpty()) {
                StatusRow(label = "Version", ok = true, detail = currentVersion)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = Link.phoneHint(state.watchLink),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.watchLink == null || state.watchLink.state == Link.State.READY) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Shows whether the watch's requests reach this phone, and how they ended.
            Text(
                text = "Last watch request: " + (state.lastExchange ?: "none yet (the watch hasn't asked this phone for anything)"),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (state.decks.isNotEmpty()) {
                Text(
                    text = "Decks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    items(state.decks) { deck ->
                        DeckCard(deck)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            } else if (state.permissionGranted) {
                Text(
                    text = "No decks found. Make sure AnkiDroid has decks with due cards.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun DeckCard(deck: DeckData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = deck.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("New: ${deck.newCount}", style = MaterialTheme.typography.bodySmall)
                Text("Learn: ${deck.learnCount}", style = MaterialTheme.typography.bodySmall)
                Text("Review: ${deck.reviewCount}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
