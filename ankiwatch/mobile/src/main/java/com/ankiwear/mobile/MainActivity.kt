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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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

        setContent {
            MaterialTheme {
                PhoneScreen(state = uiState, currentVersion = BuildConfig.VERSION_NAME)
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
        checkWatchConnection()
    }

    private fun refreshDecks() {
        lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { ankiHelper.getDecks() }
            uiState = uiState.copy(decks = decks)
        }
    }

    private fun checkWatchConnection() {
        lifecycleScope.launch {
            val connected = dataLayerManager.isWatchConnected()
            uiState = uiState.copy(watchConnected = connected)
        }
    }
}

data class PhoneUiState(
    val ankiDroidInstalled: Boolean = false,
    val permissionGranted: Boolean = false,
    val watchConnected: Boolean = false,
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
                ok = state.watchConnected,
                detail = if (state.watchConnected) "Connected" else "Not connected"
            )
            if (currentVersion.isNotEmpty()) {
                StatusRow(label = "Version", ok = true, detail = currentVersion)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Open AnkiWatch on your watch to review. This phone app only needs to stay " +
                    "installed; it doesn't have to be open.",
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
