package com.rajan.meditationtimer

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/** Lets a screen take over the volume keys while it is showing (e.g. the breath-count check). */
object VolumeKeys {
    /** Called with true for volume-up, false for volume-down. */
    var handler: ((up: Boolean) -> Unit)? = null
}

enum class Tab(val label: String) { SIT("Sit"), BREATHE("Breathe"), MALA("Mala"), HISTORY("History") }

class MainActivity : ComponentActivity() {
    private lateinit var chime: Chime
    private lateinit var prefs: Prefs

    private var tab by mutableStateOf(Tab.SIT)
    private var mala by mutableStateOf(MalaCount())
    private var pendingQuickStart = false
    /** The day's reading is waiting to be shown; set each time the app comes to the front. */
    private var readingDue by mutableStateOf(false)
    private var readingDay by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light system-bar icons: the app is a night sky whatever the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
            App(
                prefs = prefs,
                tab = tab,
                onTab = { tab = it },
                mala = mala,
                onMalaTap = ::malaBead,
                onMalaChange = { mala = it; prefs.mala = it },
                onTestBell = { volume, mode -> chime.ring(volume, mode) },
                onBreathDone = { chime.ring(prefs.volume, AlertMode.BELL) },
                reading = readingDue,
                readingDay = readingDay,
                onReadingDone = {
                    readingDue = false
                    prefs.readingSeenOn = LocalDate.now().toString()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        val quickStart = pendingQuickStart
        // Started here rather than in onCreate: a foreground service must be started while we're visible.
        if (pendingQuickStart) {
            pendingQuickStart = false
            if (SessionRepository.state.value !is SessionState.Running) {
                // One tap means one tap: no check-in on the way in, counting as usual.
                SessionRepository.pendingBefore = 0
                SessionRepository.startCounting(prefs.countDistractions)
                MeditationService.start(this, prefs.timerConfig, prefs.volume, prefs.alertMode, prefs.autoDnd)
            }
        }
        // Whenever the app comes to the front, the day's reading opens first until it has been read
        // or skipped today: not over a sit that's running or just ended, and not when a shortcut
        // asked for a sit straight away.
        val today = LocalDate.now()
        if (!quickStart && SessionRepository.state.value is SessionState.Idle &&
            Reading.due(prefs.dailyReading, prefs.readingSeenOn, today)
        ) {
            readingDay = today.toString()
            readingDue = true
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
        VolumeKeys.handler?.let { handler ->
            if (volumeKey) {
                if (event.repeatCount == 0) handler(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                return true
            }
        }
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

    companion object {
        private const val KEY_TAB = "tab"
        /** Starts your usual sit at once: app shortcut, widget and reminder. */
        const val ACTION_QUICK_SIT = "com.rajan.meditationtimer.QUICK_SIT"
        private const val ACTION_OPEN_BREATHE = "com.rajan.meditationtimer.OPEN_BREATHE"
        private const val ACTION_OPEN_MALA = "com.rajan.meditationtimer.OPEN_MALA"
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
    reading: Boolean,
    readingDay: String,
    onReadingDone: () -> Unit,
) {
    val session by SessionRepository.state.collectAsStateWithLifecycle()
    // A running or just-finished sit takes over the whole screen.
    val inSession = session !is SessionState.Idle
    val target = if (inSession) Accents.SIT else tab.accent
    // The ambient light cross-fades as you move between tabs.
    val main by animateColorAsState(target.main, tween(700), label = "accent")
    val second by animateColorAsState(target.second, tween(700), label = "accent2")
    val accent = Accent(main, second)
    var showPrinciples by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPrinciples && !inSession) { showPrinciples = false }
    // First run only (or when asked for again from the Guide): two questions and how to sit.
    val context = LocalContext.current
    var welcome by rememberSaveable {
        mutableStateOf(Welcome.needed(prefs.welcomeDone, prefs.everSaved, SessionLog.get(context).records.value.isNotEmpty()))
    }
    var beginFirstSit by rememberSaveable { mutableStateOf(false) }
    // A sit started some other way (the launcher shortcut) means the welcome isn't needed.
    LaunchedEffect(inSession) {
        if (inSession && welcome) {
            welcome = false
            prefs.welcomeDone = true
        }
    }

    MeditationTheme(accent) {
        AmbientBackground(accent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // A session takes over whole; otherwise the principles archive or the chosen tab.
                    val screen: Any = when {
                        inSession -> session
                        welcome -> WELCOME
                        reading -> READING
                        showPrinciples -> PRINCIPLES
                        else -> tab
                    }
                    AnimatedContent(
                        screen,
                        transitionSpec = { fadeIn(tween(400, delayMillis = 100)) togetherWith fadeOut(tween(200)) },
                        contentAlignment = Alignment.TopCenter,
                        label = "screen",
                        // Pause, resume and finish are handled inside the session screen, not here.
                        contentKey = { if (it is SessionState) SESSION else it },
                    ) { target ->
                        when (target) {
                            is SessionState -> TimerTab(target, prefs, onTestBell, onHistory = { onTab(Tab.HISTORY) }, onPrinciples = {})
                            PRINCIPLES -> PrinciplesScreen(onBack = { showPrinciples = false })
                            WELCOME -> WelcomeScreen(prefs) { begin ->
                                welcome = false
                                showPrinciples = false
                                // The welcome already taught how to sit: no reading on top of it today.
                                onReadingDone()
                                onTab(Tab.SIT)
                                beginFirstSit = begin
                            }
                            READING -> {
                                val lesson = rememberLessonIndex()
                                // A new day moves on to the next common problem; the same day keeps it.
                                val problem = remember(readingDay) {
                                    val today = LocalDate.now()
                                    val i = Reading.problemIndex(prefs.readingProblem, prefs.readingProblemDay, today)
                                    prefs.readingProblem = i
                                    prefs.readingProblemDay = today.toString()
                                    Problems.forIndex(i)
                                }
                                key(readingDay) {
                                    ReadingScreen(lesson, problem) {
                                        showPrinciples = false
                                        onReadingDone()
                                    }
                                }
                            }
                            Tab.SIT -> TimerTab(
                                SessionState.Idle,
                                prefs,
                                onTestBell,
                                onHistory = { onTab(Tab.HISTORY) },
                                onPrinciples = { showPrinciples = true },
                                onWelcome = { welcome = true },
                                autoBegin = beginFirstSit,
                                onAutoBegin = { beginFirstSit = false },
                            )
                            Tab.BREATHE -> BreathTab(prefs, onBreathDone)
                            Tab.MALA -> MalaTab(mala, onMalaTap, onMalaChange)
                            else -> HistoryTab()
                        }
                    }
                }
                if (!inSession && !welcome && !reading) {
                    TabBar(tab) {
                        showPrinciples = false
                        onTab(it)
                    }
                }
            }
        }
    }
}

private const val SESSION = "session"
private const val PRINCIPLES = "principles"
private const val WELCOME = "welcome"
private const val READING = "reading"

/** Floating glass bar; the selected tab glows in its own colour. */
@Composable
private fun TabBar(selected: Tab, onTab: (Tab) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(6.dp),
    ) {
        for (tab in Tab.entries) {
            val isSelected = tab == selected
            val color = if (isSelected) tab.accent.main else InkMuted
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSelected) tab.accent.main.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onTab(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabIcon(tab, color)
                Text(tab.label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}
