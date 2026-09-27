package com.rajan.meditationtimer

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Tab(val label: String) { SIT("Sit"), BREATHE("Breathe"), MALA("Mala"), HISTORY("History") }

class MainActivity : ComponentActivity() {
    private lateinit var chime: Chime
    private lateinit var prefs: Prefs

    private var tab by mutableStateOf(Tab.SIT)
    private var mala by mutableStateOf(MalaCount())
    private var pendingQuickStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Bells use the alarm stream, so the volume keys should adjust that while the app is open.
        volumeControlStream = AudioManager.STREAM_ALARM
        chime = Chime(applicationContext)
        prefs = Prefs(this)
        mala = prefs.mala
        tab = savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.SIT

        // If the app was killed mid-session, make sure we don't leave the phone stuck in Do Not Disturb.
        if (SessionRepository.state.value !is SessionState.Running) Dnd(this).restore()
        if (savedInstanceState == null) handleShortcut(intent)

        setContent {
            MeditationTheme {
                App(
                    prefs = prefs,
                    tab = tab,
                    onTab = { tab = it },
                    mala = mala,
                    onMalaTap = ::malaBead,
                    onMalaChange = { mala = it; prefs.mala = it },
                    onTestBell = { volume, mode -> chime.ring(volume, mode) },
                    onBreathDone = { chime.ring(prefs.volume, AlertMode.BELL) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        // Started here rather than in onCreate: a foreground service must be started while we're visible.
        if (pendingQuickStart) {
            pendingQuickStart = false
            if (SessionRepository.state.value !is SessionState.Running) {
                MeditationService.start(this, prefs.timerConfig, prefs.volume, prefs.alertMode, prefs.autoDnd)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onDestroy() {
        chime.release()
        super.onDestroy()
    }

    /** On the Mala tab either volume key counts a bead, so you can count eyes-closed, phone in hand. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (tab == Tab.MALA && volumeKey && SessionRepository.state.value !is SessionState.Running) {
            if (event.repeatCount == 0) malaBead()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun malaBead() {
        val (next, roundDone) = mala.tap()
        mala = next
        prefs.mala = next
        val view = window.decorView
        if (roundDone) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            chime.ring(prefs.volume, AlertMode.BELL)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_SIT -> {
                tab = Tab.SIT
                pendingQuickStart = true
            }
            ACTION_OPEN_BREATHE -> tab = Tab.BREATHE
            ACTION_OPEN_MALA -> tab = Tab.MALA
        }
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val ACTION_QUICK_SIT = "com.rajan.meditationtimer.QUICK_SIT"
        const val ACTION_OPEN_BREATHE = "com.rajan.meditationtimer.OPEN_BREATHE"
        const val ACTION_OPEN_MALA = "com.rajan.meditationtimer.OPEN_MALA"
    }
}

@Composable
private fun App(
    prefs: Prefs,
    tab: Tab,
    onTab: (Tab) -> Unit,
    mala: MalaCount,
    onMalaTap: () -> Unit,
    onMalaChange: (MalaCount) -> Unit,
    onTestBell: (Float, AlertMode) -> Unit,
    onBreathDone: () -> Unit,
) {
    val session by SessionRepository.state.collectAsStateWithLifecycle()
    // A running or just-finished sit takes over the whole screen.
    val inSession = session !is SessionState.Idle

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    inSession || tab == Tab.SIT -> TimerTab(session, prefs, onTestBell, onHistory = { onTab(Tab.HISTORY) })
                    tab == Tab.BREATHE -> BreathTab(prefs, onBreathDone)
                    tab == Tab.MALA -> MalaTab(mala, onMalaTap, onMalaChange)
                    else -> HistoryTab()
                }
            }
            if (!inSession) TabBar(tab, onTab)
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onTab: (Tab) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (tab in Tab.entries) {
            val isSelected = tab == selected
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { onTab(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.padding(top = 6.dp).width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}
