package com.rajan.mindfield

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rajan.mindfield.core.Entry
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.ThemeMode
import java.time.Duration
import java.time.LocalDate

enum class Tab(val label: String) { TODAY("Today"), GUIDE("Guide"), REVIEW("Review"), JOURNAL("Journal"), ME("You") }

/** Opens the field-report sheet: for a concept, optionally pre-set to a mode, or editing an entry. */
data class LogRequest(val conceptId: String, val mode: Mode? = null, val edit: Entry? = null)

/** What screens can ask the app to do. */
class Nav(
    val openConcept: (String) -> Unit,
    val log: (LogRequest) -> Unit,
    val tab: (Tab) -> Unit,
    /** Opens a settings sheet on the You tab (see [SettingsSheet]). */
    val settings: (String) -> Unit = {},
)

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(Tab.TODAY)
    private var logRequest by mutableStateOf<LogRequest?>(null)
    private var concept by mutableStateOf<String?>(null)
    private var today by mutableStateOf(LocalDate.now())
    /** The settings sheet open on the You tab, if any. */
    private var settingsSheet by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store.init(this)
        today = store.today()
        Notifier.channels(this)
        GoogleSync.load(this)
        // Arms what isn't armed; a reminder that's due but hasn't arrived yet keeps its time.
        Scheduler.ensureAll(this)
        tab = savedInstanceState?.getString(KEY_TAB)?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: Tab.TODAY
        concept = savedInstanceState?.getString(KEY_CONCEPT)
        // A turned phone, a theme change or the system reclaiming the app keeps an open sheet and its draft.
        settingsSheet = savedInstanceState?.getString(KEY_SHEET)
        logRequest = savedInstanceState?.let { restoreLog(it) }
        // Reopened from Recents, the intent is the one that first opened the app: don't act on it again.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handle(intent)

        // Open past midnight: turn to the new day and its concept, as coming back to the app would.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = Store.now()
                    val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                    delay(Duration.between(now, midnight).toMillis().coerceIn(1_000, 3_600_000))
                    newDay()
                }
            }
        }

        // System bar icons follow the app's theme (which can differ from the phone's); set before
        // the first frame and again whenever the theme setting changes, never from inside composition.
        edgeToEdge(store.state.value.settings.theme)
        lifecycleScope.launch {
            store.state.map { it.settings.theme }.distinctUntilChanged().collect { edgeToEdge(it) }
        }

        setContent {
            val state by Store.state.collectAsStateWithLifecycle()
            val dark = when (state.settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MindfieldTheme(dark) {
                if (!state.settings.onboarded) {
                    Onboarding(onDone = {
                        today = Store.today()
                        Store.settings { it.copy(onboarded = true) }
                        Store.todayConcept()
                    })
                } else {
                    Root(
                        tab = tab,
                        today = today,
                        openConcept = concept,
                        logRequest = logRequest,
                        settingsSheet = settingsSheet,
                        onSettingsSheet = { settingsSheet = it },
                        nav = Nav(
                            openConcept = { concept = it },
                            log = { logRequest = it },
                            tab = { tab = it; concept = null; settingsSheet = null },
                            settings = { tab = Tab.ME; concept = null; settingsSheet = it },
                        ),
                        onCloseConcept = { concept = null },
                        onCloseLog = { logRequest = null },
                    )
                }
            }
        }
    }

    private var barsTheme: ThemeMode? = null

    private fun edgeToEdge(theme: ThemeMode) {
        if (theme == barsTheme) return
        barsTheme = theme
        val dark = when (theme) {
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val bar = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
    }

    override fun onResume() {
        super.onResume()
        // Coming back after midnight: move to the new day and pick its concept.
        newDay()
        // A save that failed (a full phone) and a backup that didn't get through try again.
        Store.retrySave()
        GoogleSync.retryIfPending(this)
    }

    private fun newDay() {
        val day = Store.today()
        if (Store.state.value.settings.onboarded) Store.todayConcept()
        today = day
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
        concept?.let { outState.putString(KEY_CONCEPT, it) }
        settingsSheet?.let { outState.putString(KEY_SHEET, it) }
        logRequest?.let { r ->
            outState.putString(KEY_LOG_CONCEPT, r.conceptId)
            r.mode?.let { outState.putString(KEY_LOG_MODE, it.key) }
            r.edit?.let { outState.putString(KEY_LOG_EDIT, it.id) }
        }
    }

    /** The field-report sheet that was open, looked up again; an entry deleted meanwhile closes it. */
    private fun restoreLog(saved: Bundle): LogRequest? {
        val id = saved.getString(KEY_LOG_CONCEPT) ?: return null
        val edit = saved.getString(KEY_LOG_EDIT)?.let { e -> Store.state.value.liveEntries.firstOrNull { it.id == e } ?: return null }
        return LogRequest(id, saved.getString(KEY_LOG_MODE)?.let { Mode.of(it) }, edit)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_TODAY -> { tab = Tab.TODAY; concept = null }
            ACTION_LOG, ACTION_SHORTCUT_LOG -> {
                // A report is being written: a notification or the widget mustn't throw it away.
                if (logRequest != null) return
                tab = Tab.TODAY
                concept = null
                if (Store.state.value.settings.onboarded) {
                    val id = intent.getStringExtra(Notifier.EXTRA_CONCEPT)?.takeIf { Store.library.contains(it) } ?: Store.todayConcept().id
                    logRequest = LogRequest(id)
                }
            }
            ACTION_JOURNAL -> { tab = Tab.JOURNAL; concept = null }
            ACTION_ACCOUNT -> { tab = Tab.ME; concept = null; settingsSheet = SettingsSheet.GOOGLE }
            ACTION_SHORTCUT_REVIEW -> { tab = Tab.REVIEW; concept = null }
        }
    }

    companion object {
        private const val KEY_TAB = "tab"
        private const val KEY_CONCEPT = "concept"
        private const val KEY_SHEET = "sheet"
        private const val KEY_LOG_CONCEPT = "log_concept"
        private const val KEY_LOG_MODE = "log_mode"
        private const val KEY_LOG_EDIT = "log_edit"
        const val ACTION_TODAY = "com.rajan.mindfield.OPEN_TODAY"
        const val ACTION_LOG = "com.rajan.mindfield.OPEN_LOG_FOR"
        const val ACTION_JOURNAL = "com.rajan.mindfield.OPEN_JOURNAL"
        const val ACTION_ACCOUNT = "com.rajan.mindfield.OPEN_ACCOUNT"
        const val ACTION_SHORTCUT_LOG = "com.rajan.mindfield.OPEN_LOG"
        const val ACTION_SHORTCUT_REVIEW = "com.rajan.mindfield.OPEN_REVIEW"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(
    tab: Tab,
    today: LocalDate,
    openConcept: String?,
    logRequest: LogRequest?,
    settingsSheet: String?,
    onSettingsSheet: (String?) -> Unit,
    nav: Nav,
    onCloseConcept: () -> Unit,
    onCloseLog: () -> Unit,
) {
    val p = palette
    val state by Store.state.collectAsStateWithLifecycle()
    val todayConcept = state.assignments[today]?.conceptId?.let { Store.library[it] }
    val tint = todayConcept?.category?.accent(p.dark) ?: p.brand
    // Back from Guide, Review, Journal or You goes to Today, the start screen, rather than out of the app.
    BackHandler(enabled = tab != Tab.TODAY && openConcept == null && logRequest == null) { nav.tab(Tab.TODAY) }
    BackHandler(enabled = openConcept != null && logRequest == null) { onCloseConcept() }
    BackHandler(enabled = logRequest != null) { onCloseLog() }

    // Each tab and concept page keeps its place (scroll, a review round, a search) while you're elsewhere.
    val saved = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().paper(p, tint)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // While the field-report sheet is open, a screen reader stays in it.
                .then(if (logRequest != null) Modifier.clearAndSetSemantics { } else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val screen: Any = openConcept ?: tab
                AnimatedContent(
                    targetState = screen,
                    // Comfortable line lengths on tablets, unfolded phones and in landscape.
                    modifier = Modifier.widthIn(max = 640.dp),
                    transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
                    contentKey = { screenKey(it) },
                    label = "screen",
                ) { target ->
                    saved.SaveableStateProvider(screenKey(target)) {
                        when (target) {
                            is String -> Store.library[target]?.let { ConceptScreen(it, state, today, nav, onBack = onCloseConcept) }
                            Tab.TODAY -> TodayScreen(state, today, nav)
                            Tab.GUIDE -> GuideScreen(state, nav)
                            Tab.REVIEW -> ReviewScreen(state, today, nav)
                            Tab.JOURNAL -> JournalScreen(state, today, nav)
                            Tab.ME -> MeScreen(state, today, nav, settingsSheet, onSettingsSheet)
                            else -> Unit
                        }
                    }
                }
            }
            // Out of the way while typing, so a plan or a search has the room above the keyboard.
            if (!WindowInsets.isImeVisible) TabBar(tab, tint) { nav.tab(it) }
        }
        // AnimatedContent keeps the closing sheet's request while it animates away.
        AnimatedContent(
            targetState = logRequest,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 4 }) togetherWith
                    (fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 4 })
            },
            contentKey = { if (it == null) "closed" else "open" },
            label = "log",
        ) { req ->
            if (req != null) LogSheet(req, state, today, onClose = onCloseLog)
        }
    }
}

/** A stable name for each screen, for the transition and for keeping its state. */
private fun screenKey(screen: Any): String = if (screen is Tab) screen.name else "concept:$screen"

/** The bottom bar; the selected tab sits in a soft pill of today's colour. */
@Composable
private fun TabBar(selected: Tab, tint: Color, onTab: (Tab) -> Unit) {
    val p = palette
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.line, shape)
            .padding(5.dp)
            .selectableGroup(),
    ) {
        for (t in Tab.entries) {
            val on = t == selected
            val color = if (on) tint else p.muted
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(21.dp))
                    .background(if (on) tint.copy(alpha = 0.14f) else Color.Transparent)
                    // A screen reader says which tab is selected.
                    .selectable(selected = on, role = Role.Tab) { onTab(t) }
                    .semantics { contentDescription = t.label }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TabIcon(t, color)
                // Five labels share the width: let them grow with the system font, but only so far.
                val scale = LocalDensity.current.fontScale
                val style = MaterialTheme.typography.labelSmall
                Text(
                    t.label,
                    style = style.copy(fontSize = style.fontSize * (minOf(scale, 1.15f) / scale)),
                    color = if (on) tint else p.muted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Hand-drawn line icons, so no icon library is needed. */
@Composable
private fun TabIcon(tab: Tab, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val c = center
        when (tab) {
            Tab.TODAY -> {
                // Sun over the horizon.
                drawCircle(color, s * 0.2f, Offset(c.x, c.y + s * 0.08f), style = stroke)
                for (i in 0 until 5) {
                    val a = Math.toRadians(180.0 + 45.0 * i)
                    val r1 = s * 0.31f
                    val r2 = s * 0.44f
                    drawLine(
                        color,
                        Offset(c.x + r1 * Math.cos(a).toFloat(), c.y + s * 0.08f + r1 * Math.sin(a).toFloat()),
                        Offset(c.x + r2 * Math.cos(a).toFloat(), c.y + s * 0.08f + r2 * Math.sin(a).toFloat()),
                        stroke.width, StrokeCap.Round,
                    )
                }
                drawLine(color, Offset(s * 0.08f, s * 0.86f), Offset(s * 0.92f, s * 0.86f), stroke.width, StrokeCap.Round)
            }
            Tab.GUIDE -> {
                // An open book.
                drawRoundRect(color, Offset(s * 0.08f, s * 0.2f), Size(s * 0.4f, s * 0.62f), CornerRadius(s * 0.06f), style = stroke)
                drawRoundRect(color, Offset(s * 0.52f, s * 0.2f), Size(s * 0.4f, s * 0.62f), CornerRadius(s * 0.06f), style = stroke)
            }
            Tab.REVIEW -> {
                // Two stacked cards.
                drawRoundRect(color.copy(alpha = 0.55f), Offset(s * 0.22f, s * 0.1f), Size(s * 0.6f, s * 0.5f), CornerRadius(s * 0.08f), style = stroke)
                drawRoundRect(color, Offset(s * 0.12f, s * 0.36f), Size(s * 0.6f, s * 0.5f), CornerRadius(s * 0.08f), style = stroke)
            }
            Tab.JOURNAL -> {
                // A notebook with lines.
                drawRoundRect(color, Offset(s * 0.18f, s * 0.08f), Size(s * 0.64f, s * 0.84f), CornerRadius(s * 0.08f), style = stroke)
                for (i in 0 until 3) {
                    val y = s * (0.32f + 0.18f * i)
                    drawLine(color, Offset(s * 0.32f, y), Offset(s * 0.68f, y), stroke.width, StrokeCap.Round)
                }
            }
            Tab.ME -> {
                // A person.
                drawCircle(color, s * 0.17f, Offset(c.x, s * 0.3f), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(s * 0.16f, s * 0.58f), Size(s * 0.68f, s * 0.6f), style = stroke)
            }
        }
    }
}
