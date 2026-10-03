package com.rajan.meditationtimer

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

val RATING_LABELS = listOf("Restless", "Scattered", "Okay", "Calm", "Deep")

@Composable
fun TimerTab(
    session: SessionState,
    prefs: Prefs,
    onTestBell: (Float, AlertMode) -> Unit,
    onHistory: () -> Unit,
    onPrinciples: () -> Unit,
) {
    val context = LocalContext.current
    val log = remember { SessionLog.get(context) }
    val records by log.records.collectAsStateWithLifecycle()
    val streak = remember(records) {
        History.currentStreak(records.map { it.day(ZoneId.systemDefault()) }.toSet(), LocalDate.now())
    }
    // Looking back: a note from this date a while ago, and last month's review once it's ready.
    val today = LocalDate.now()
    val memory = remember(records, today) { Recap.onThisDay(records, ZoneId.systemDefault(), today) }
    val recapReady = remember(records, today) { Recap.recapReady(records, ZoneId.systemDefault(), today) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val restore = rememberRestoreAction()
    val settingsVersion by Prefs.version.collectAsStateWithLifecycle()

    // Setup, sit and reflection cross-fade into one another instead of cutting.
    AnimatedContent(
        session,
        Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(tween(500, delayMillis = 150)) togetherWith fadeOut(tween(300)) },
        contentAlignment = Alignment.Center,
        label = "session",
        contentKey = { it::class },
    ) { state ->
        when (state) {
            // Re-created when settings are restored, so the restored values show at once.
            SessionState.Idle -> key(settingsVersion) { SetupScreen(
                prefs, streak, hasHistory = records.isNotEmpty(), memory, recapReady, onTestBell, onHistory, onRestore = restore, onPrinciples,
            ) { config, volume, mode, dnd, before ->
                // Only for the lock-screen countdown; the session runs either way.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SessionRepository.pendingBefore = before
                SessionRepository.startCounting(prefs.countDistractions)
                MeditationService.start(context, config, volume, mode, dnd)
                SitWidget.refresh(context)
            } }
            is SessionState.Running -> RunningScreen(state)
            is SessionState.Finished -> FinishedScreen(
                state,
                streak,
                onSave = { rating, note, after ->
                    if (rating > 0 || note.isNotBlank() || after > 0) log.annotate(state.startedAtMs, rating, note, after)
                    SessionRepository.reset()
                },
            )
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Quiet night"
}

@Composable
private fun SetupScreen(
    prefs: Prefs,
    streak: Int,
    hasHistory: Boolean,
    memory: Memory?,
    recapReady: YearMonth?,
    onTestBell: (Float, AlertMode) -> Unit,
    onHistory: () -> Unit,
    onRestore: () -> Unit,
    onPrinciples: () -> Unit,
    onBegin: (SessionConfig, Float, AlertMode, Boolean, Int) -> Unit,
) {
    val context = LocalContext.current
    val saved = remember { prefs.timerConfig }
    // Set while the "how do you feel right now?" check-in is showing on the way into a sit.
    var checkingIn by remember { mutableStateOf<SessionConfig?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(saved.durationSec / 60) }
    var opening by rememberSaveable { mutableIntStateOf(saved.openingBellSec) }
    var closing by rememberSaveable { mutableIntStateOf(saved.closingBellSec) }
    var bellAtEnd by rememberSaveable { mutableStateOf(saved.bellAtEnd) }
    var interval by rememberSaveable { mutableIntStateOf(saved.intervalMin) }
    var volume by rememberSaveable { mutableFloatStateOf(prefs.volume) }
    var alertMode by rememberSaveable { mutableStateOf(prefs.alertMode) }
    var autoDnd by rememberSaveable { mutableStateOf(prefs.autoDnd) }
    val dnd = remember { Dnd(context) }
    var dndAccess by remember { mutableStateOf(dnd.hasAccess) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dndAccess = dnd.hasAccess }

    val lesson = rememberLessonIndex()

    // Begin stays pinned at the bottom; everything else scrolls above it.
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (streak > 0) "✦\u00A0${streakLabel(streak)}" else "Settle in whenever you're ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onHistory) { Text("History") }
        }

        // After an update (which currently needs a reinstall), one tap brings the history back.
        if (!hasHistory && AutoBackup.supported) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Updated or reinstalled the app?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bring back your sessions from the automatic backup in ${AutoBackup.LOCATION}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRestore) { Text("Restore history") }
            }
        }

        DurationDial(minutes, onMinus = { minutes = (minutes - 1).coerceAtLeast(1) }, onPlus = { minutes = (minutes + 1).coerceAtMost(180) })
        ChipRow(listOf(5, 10, 15, 20, 30, 45, 60), minutes, { "$it" }, center = true) { minutes = it }

        // The sit comes first; today's lesson and anything from the past follow below it.
        PrincipleCard(lesson, onOpenAll = onPrinciples)

        val today = remember { LocalDate.now() }
        var memoryHidden by remember { mutableStateOf(prefs.memoryHiddenOn == today.toString()) }
        if (memory != null && !memoryHidden) {
            MemoryCard(memory, ZoneId.systemDefault()) { memoryHidden = true; prefs.memoryHiddenOn = today.toString() }
        }
        var recapSeen by remember { mutableStateOf(prefs.recapSeen) }
        if (recapReady != null && recapSeen != recapReady.toString()) {
            fun seen() { recapSeen = recapReady.toString(); prefs.recapSeen = recapSeen }
            RecapReadyCard(recapReady, today, onOpen = { seen(); onHistory() }, onHide = { seen() })
        }

        // Bells, sound and tools are set once and rarely changed: they fold into one line, so the
        // screen is about the sit itself (Insight Timer and Oak do the same). One tap opens them.
        var showSettings by rememberSaveable { mutableStateOf(false) }
        GlassCard(Modifier.fillMaxWidth().clickable { showSettings = !showSettings }, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Bells, sound & tools", style = MaterialTheme.typography.titleMedium)
                    Text(
                        settingsSummary(opening, closing, interval, bellAtEnd, alertMode, autoDnd, prefs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (showSettings) "Done" else "Change",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        AnimatedVisibility(showSettings) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("Bells")
                    SectionLabel("Opening bell", "Rings this long after you tap Begin")
                    ChipRow(listOf(5, 10, 15, 30), opening, { "${it}s" }) { opening = it }
                    SectionLabel("Closing bell", "Rings this long before the session ends")
                    ChipRow(listOf(5, 10, 30, 60), closing, ::secondsLabel) { closing = it }
                    SectionLabel("Interval bells", "A soft reminder to come back to the breath")
                    ChipRow(listOf(0, 5, 10, 15), interval, { if (it == 0) "Off" else "Every $it min" }) { interval = it }
                    SwitchRow("Also ring when time is up", bellAtEnd) { bellAtEnd = it }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("Sound & stillness")
                    SectionLabel("How cues reach you", "Vibrate only is for sitting next to someone")
                    ChipRow(AlertMode.entries, alertMode, { it.label }) { alertMode = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // "Bell" rather than "Volume": the background sound has its own slider below.
                        Text("Bell")
                        Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton(onClick = { onTestBell(volume, alertMode) }) { Text("Test") }
                    }
                    BackgroundSoundSection(prefs)
                    SwitchRow("Silence notifications while I sit", autoDnd) {
                        autoDnd = it
                        if (it && !dndAccess) context.startActivity(Dnd.accessSettings)
                    }
                    if (autoDnd && !dndAccess) {
                        TextButton(onClick = { context.startActivity(Dnd.accessSettings) }) {
                            Text("Needs Do Not Disturb access — tap to allow")
                        }
                    }
                }

                PracticeToolsCard(prefs)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
    GradientButton(
        "Begin  ·  $minutes min",
        onClick = {
            val config = SessionConfig(minutes * 60, opening, closing, bellAtEnd, interval)
            prefs.saveTimer(config, volume, alertMode, autoDnd)
            if (prefs.checkIns) checkingIn = config else onBegin(config, volume, alertMode, autoDnd, 0)
        },
        modifier = Modifier.padding(vertical = 8.dp),
    )
    }
    // Choosing a feeling only records it; the sit starts when you tap Begin, not before.
    checkingIn?.let { config ->
        CheckInDialog(minutes = config.durationSec / 60, onDismiss = { checkingIn = null }) { before ->
            checkingIn = null
            onBegin(config, volume, alertMode, autoDnd, before)
        }
    }
}

/**
 * The check-in on the way into a sit. Tapping a feeling selects it (tap again to clear); nothing
 * starts until Begin, so there's a moment to settle first. Answering is optional.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CheckInDialog(minutes: Int, onDismiss: () -> Unit, onBegin: (Int) -> Unit) {
    var feeling by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.fillMaxWidth().background(NightSky, RoundedCornerShape(24.dp))) {
            Text("Before you begin", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("How do you feel right now?", style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CHECK_IN_LABELS.forEachIndexed { i, label ->
                    FilterChip(
                        selected = feeling == i + 1,
                        onClick = { feeling = if (feeling == i + 1) 0 else i + 1 },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                if (feeling == 0) "Optional. Take a breath, then begin when you're ready."
                else "Noted. Take a breath, then begin when you're ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GradientButton("Begin  ·  $minutes min", onClick = { onBegin(feeling) })
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
        }
    }
}

/** Optional tools around the sit: distraction counting, check-ins and the daily reminder. */
@Composable
private fun PracticeToolsCard(prefs: Prefs) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var counting by remember { mutableStateOf(prefs.countDistractions) }
    var checkIns by remember { mutableStateOf(prefs.checkIns) }
    var reminder by remember { mutableStateOf(prefs.reminder) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun saveReminder(updated: Reminder) {
        reminder = updated
        prefs.reminder = updated
        ReminderScheduler.schedule(context)
    }

    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Practice tools")
        ToggleRow(
            "Count distractions",
            "Each time you notice the mind has wandered, tap the screen or press a volume key, then return. " +
                "The screen stays on while you sit, so taps register.",
            counting,
        ) { counting = it; prefs.countDistractions = it }
        ToggleRow(
            "Check in before and after",
            "One tap: how you feel going in and coming out. History shows what your sits change.",
            checkIns,
        ) { checkIns = it; prefs.checkIns = it }
        ToggleRow(
            "Daily reminder",
            "Tie it to a habit you already have; skipped on days you've already sat.",
            reminder.enabled,
        ) { on ->
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            saveReminder(reminder.copy(enabled = on))
        }
        if (reminder.enabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Around", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> saveReminder(reminder.copy(minuteOfDay = hour * 60 + minute)) },
                        reminder.minuteOfDay / 60,
                        reminder.minuteOfDay % 60,
                        false,
                    ).show()
                }) { Text(reminder.timeLabel) }
            }
            OutlinedTextField(
                value = reminder.cue,
                onValueChange = { saveReminder(reminder.copy(cue = it.limitText(60))) },
                label = { Text("Right after…") },
                placeholder = { Text("After morning tea") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Big glowing ring showing the chosen length; a full ring is one hour. */
@Composable
private fun DurationDial(minutes: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundButton("−", onMinus)
        Box(Modifier.size(200.dp).glow(accent.main), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val stroke = 8.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(stroke))
                rotate(-90f) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.second, accent.main, accent.second)),
                        startAngle = 0f,
                        sweepAngle = 360f * minutes.coerceAtMost(60) / 60f,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$minutes", style = MaterialTheme.typography.displayLarge)
                Text("minutes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RoundButton("+", onPlus)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, center: Boolean = false, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (center) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun SectionLabel(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RunningScreen(session: SessionState.Running) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(200)
        }
    }
    val total = session.config.durationMs
    val elapsed = session.clock.elapsedAt(now).coerceIn(0, total)
    val remaining = total - elapsed
    val next = BellSchedule.next(session.config, elapsed)
    val accent = LocalAccent.current
    // Back while deciding means "not yet": keep sitting rather than leave the app mid-choice.
    BackHandler(enabled = session.isEnding) { MeditationService.resume(context) }
    // The halo swells and fades over ~11 s, about the length of one slow breath.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_500), RepeatMode.Reverse),
        label = "pulse",
    )
    // Paused, the ring dims and stops breathing, so the state reads at a glance.
    val still = session.clock.isPaused
    val ringAlpha by animateFloatAsState(if (still) 0.4f else 1f, tween(600), label = "dim")

    // Distraction counting: a tap anywhere (not on a button) or a volume key, with a light buzz
    // so it registers with eyes closed. Taps closer than half a second count once.
    val counting = SessionRepository.counting
    val countingNow = counting && !still
    val view = LocalView.current
    var lastNotice by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    val notice: () -> Unit = {
        val t = SystemClock.elapsedRealtime()
        if (t - lastNotice > 500) {
            lastNotice = t
            SessionRepository.noticedOnce()
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    DisposableEffect(countingNow) {
        if (countingNow) VolumeKeys.handler = { notice() }
        onDispose { if (countingNow) VolumeKeys.handler = null }
    }
    // The screen has to stay on to take taps; it keeps your own brightness (no dimming).
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(counting, window) {
        if (counting && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            onDispose { }
        }
    }

    // On a short screen or with large text the ring shrinks and the screen scrolls, so Pause and
    // End are always reachable; on a normal phone it is centred exactly as before.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val ring = minOf(300.dp, maxHeight * 0.38f)
    val gap = if (maxHeight < 720.dp) 16.dp else 28.dp
    Column(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .pointerInput(countingNow) { if (countingNow) detectTapGestures { notice() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(ring).glow(accent.main, if (still) 0.3f else pulse), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(16.dp).alpha(ringAlpha)) {
                val stroke = 6.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(stroke))
                rotate(-90f) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.second, accent.main, accent.second)),
                        startAngle = 0f,
                        sweepAngle = 360f * remaining / total,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(remaining), style = MaterialTheme.typography.displayLarge)
                val hint = when {
                    still -> "Paused · bells on hold"
                    next != null -> cueHint(next, elapsed)
                    else -> null
                }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        AnimatedContent(
            when {
                session.isEnding -> 2
                session.isPaused -> 1
                else -> 0
            },
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "controls",
        ) { mode ->
            when (mode) {
                2 -> EndingPrompt(
                    endingAtMs = session.endingAtMs ?: now,
                    now = now,
                    satMs = elapsed,
                    onKeepSitting = { MeditationService.resume(context) },
                    onEndNow = { MeditationService.endNow(context) },
                )
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    if (mode == 1) {
                        Text(
                            "Take your time. Notifications can reach you while you’re paused.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            "Today’s focus: ${Principles.forLesson(rememberLessonIndex()).title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (counting) {
                                "Mind wandered? Tap anywhere or press a volume key, then come back to the breath."
                            } else {
                                "Close your eyes. The bell will call you back."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        if (mode == 1) {
                            ControlButton("Resume", ControlIcon.PLAY) { MeditationService.resume(context) }
                        } else {
                            ControlButton("Pause", ControlIcon.PAUSE) { MeditationService.pause(context) }
                        }
                        ControlButton("End", ControlIcon.STOP) { MeditationService.end(context) }
                    }
                }
            }
        }
    }
    }
}

/** The few seconds after tapping End: the sit is paused, and doing nothing lets it end. */
@Composable
private fun EndingPrompt(endingAtMs: Long, now: Long, satMs: Long, onKeepSitting: () -> Unit, onEndNow: () -> Unit) {
    val leftMs = (endingAtMs - now).coerceIn(0, SessionClock.END_CONFIRM_MS)
    val seconds = ((leftMs + 999) / 1000).coerceAtLeast(1)
    val accent = LocalAccent.current
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Ending in $seconds…",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        // Drains with the countdown, so the time left is felt as well as read.
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(
                Modifier
                    .fillMaxWidth(leftMs.toFloat() / SessionClock.END_CONFIRM_MS)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(accent.main, accent.second))),
            )
        }
        Text(
            if (satMs >= MIN_LOGGED_SEC * 1000L) {
                "${formatDuration(satMs / 1000)} so far. It’ll be saved to your history."
            } else {
                "Under a minute so far, so this one won’t be logged."
            },
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        GradientButton("Keep sitting", onClick = onKeepSitting)
        TextButton(onClick = onEndNow, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("End now", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FinishedScreen(session: SessionState.Finished, streak: Int, onSave: (Int, String, Int) -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var after by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    val accent = LocalAccent.current
    val focus = LocalFocusManager.current

    Column(
        Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val minutes = session.satSec / 60
        Box(Modifier.size(160.dp).glow(accent.main), contentAlignment = Alignment.Center) {
            Text("$minutes", style = MaterialTheme.typography.displayLarge)
        }
        Text(if (minutes == 1) "minute of stillness" else "minutes of stillness", style = MaterialTheme.typography.headlineSmall)
        if (session.endedEarly) {
            Text(
                "of ${session.config.durationSec / 60} planned. Every minute counts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (streak > 0) Pill("✦\u00A0${streakLabel(streak)}")
        if (session.noticed >= 0) {
            Text(
                when (session.noticed) {
                    0 -> "No wandering noticed this time."
                    1 -> "You caught the mind wandering once, and came back."
                    else -> "You caught the mind wandering ${session.noticed} times, and came back each time."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // The "after" half of the check-in, asked only when the "before" half was answered.
        if (session.before > 0) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("How do you feel now?", "Before the sit you felt ${CHECK_IN_LABELS[session.before - 1].lowercase()}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CHECK_IN_LABELS.forEachIndexed { i, label ->
                        FilterChip(selected = after == i + 1, onClick = { after = if (after == i + 1) 0 else i + 1 }, label = { Text(label) })
                    }
                }
            }
        }

        // Optional and judgement-free: noticing, not scoring.
        GlassCard(Modifier.fillMaxWidth()) {
            // Distinct from the check-in above: that is how you feel now, this is how the sit went.
            SectionLabel("How was the sit itself?", "Your attention while sitting. Optional, helps you spot patterns later")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RATING_LABELS.forEachIndexed { i, label ->
                    FilterChip(
                        selected = rating == i + 1,
                        onClick = { rating = if (rating == i + 1) 0 else i + 1 },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.limitText(500) },
                label = { Text("A line for your journal") },
                modifier = Modifier.fillMaxWidth(),
                // Capital first letter, and a Done key that closes the keyboard so Save is reachable.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
        }
        GradientButton(if (rating > 0 || note.isNotBlank() || after > 0) "Save" else "Done", onClick = { onSave(rating, note, after) })
    }
}

fun streakLabel(days: Int) = when (days) {
    0 -> "No streak yet"
    1 -> "1-day streak"
    else -> "$days-day streak"
}

/** One line saying how the sit is set up, shown while the settings are folded away. */
private fun settingsSummary(opening: Int, closing: Int, interval: Int, bellAtEnd: Boolean, mode: AlertMode, dnd: Boolean, prefs: Prefs): String {
    val reminder = prefs.reminder
    return buildList {
        add(mode.label)
        add("opening ${opening}s")
        add("closing ${secondsLabel(closing)}")
        if (interval > 0) add("every $interval min")
        if (bellAtEnd) add("bell at the end")
        when (prefs.ambience) {
            Ambience.OFF -> {}
            Ambience.CUSTOM -> add("your recording")
            else -> add(prefs.ambience.label.lowercase())
        }
        if (dnd) add("notifications silenced")
        if (prefs.countDistractions) add("counting")
        if (prefs.checkIns) add("check-ins")
        if (reminder.enabled) add("reminder ${reminder.timeLabel}")
    }.joinToString(" · ")
}

private fun secondsLabel(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "${sec}s"

private fun cueHint(next: TimedCue, elapsedMs: Long): String {
    val name = when (next.cue) {
        Cue.OPENING -> "Opening bell"
        Cue.INTERVAL -> "Interval bell"
        Cue.CLOSING -> "Closing bell"
        Cue.END -> "Final bell"
    }
    return "$name in ${formatClock(next.atMs - elapsedMs)}"
}
