package com.rajan.mindfield

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Entry
import com.rajan.mindfield.core.FieldDay
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Outcome
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.Stats
import com.rajan.mindfield.core.Texts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rajan.mindfield.core.Progress
import com.rajan.mindfield.core.Step
import com.rajan.mindfield.core.PathStep

// --- Today -------------------------------------------------------------------------------------

@Composable
fun TodayScreen(state: AppState, today: LocalDate, nav: Nav) {
    // (A clock reading from before the app existed gets a concept without a day being fixed for it.)
    val concept = state.assignments[today]?.conceptId?.let { Store.library[it] }
        ?: if (today < Curriculum.EARLIEST) Store.todayConcept() else null
    LaunchedEffect(today, concept == null) { if (concept == null) Store.todayConcept() }
    if (concept == null) return
    val p = palette
    val context = LocalContext.current
    val days = Stats.checkInDays(state)
    val streak = Stats.streak(days, today)
    val cloud by GoogleSync.state.collectAsStateWithLifecycle()
    // Re-checked whenever the screen comes back (e.g. after allowing background use in settings).
    var blocked by remember { mutableStateOf(false) }
    var notificationsOff by remember { mutableStateOf(false) }
    // The time of day, read again each time the screen comes back.
    var minuteOfDay by remember { mutableIntStateOf(Store.now().let { it.hour * 60 + it.minute }) }
    LifecycleResumeEffect(today, state.settings) {
        blocked = Health.remindersBlocked(context) && !Health.ignoringBatteryOptimizations(context)
        notificationsOff = Notifier.blocked(context, state.settings)
        minuteOfDay = Store.now().let { it.hour * 60 + it.minute }
        onPauseOrDispose { }
    }
    val turnOnNotifications = rememberEnableNotifications { notificationsOff = Notifier.blocked(context, Store.state.value.settings) }
    val saveFailed by Store.saveFailed.collectAsStateWithLifecycle()
    val journalReset by Store.journalReset.collectAsStateWithLifecycle()
    // From the evening report's time until midnight, the field report comes first: by then the
    // concept has been read, and filing the report is what's left to do.
    val evening = Store.now().toLocalDate() == today && minuteOfDay >= state.settings.eveningMinute
    // Where each step of today's path sits on the page, so tapping a step can scroll to it.
    val anchors = remember { mutableStateMapOf<Step, Int>() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    ConceptPage(
        concept, state, today, isToday = true, nav = nav, scroll = scroll, reportFirst = evening,
        onAnchor = { step, y -> anchors[step] = y },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.labelLarge, color = p.muted)
            Text("Day ${Stats.dayNumber(state, today)} in the field", style = MaterialTheme.typography.headlineSmall)
            // One counter in the header: the day, with the streak beneath it rather than a second badge.
            if (streak > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlyphIcon(Glyph.FLAME, concept.category.accent(p.dark), 14.dp)
                    Text(if (streak == 1) "1-day streak" else "$streak-day streak", style = MaterialTheme.typography.labelLarge, color = p.muted)
                }
            }
        }
        TodayStepper(Progress.todayPath(state, Store.library, today, concept.id), Spacing.due(state, Store.library, today).size) { step ->
            if (step == Step.REVIEW) {
                nav.review()
            } else {
                anchors[step]?.let { y -> scope.launch { scroll.animateScrollTo((y - with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)) } }
            }
        }
        if (notificationsOff) {
            Banner(
                Glyph.BELL_OFF,
                "Notifications are off",
                "Your morning concept and evening field report can't reach you until notifications are back on for Mindfield.",
                "Turn on", turnOnNotifications,
                null, null,
            )
        } else if (blocked) {
            Banner(
                Glyph.BELL_OFF,
                "Your reminders seem to be blocked",
                "Two mornings passed without the daily notification. Phones with strict battery savers stop apps' alarms: in the list that opens, find Mindfield and choose “Don't optimise”.",
                "Open settings", { Health.openBatterySettings(context) },
                "Not now", { Health.snoozeBanner(context); blocked = false },
            )
        }
        if (saveFailed) {
            Banner(
                Glyph.DISK,
                "Couldn't save your latest changes",
                "Your phone's storage may be full. Free up some space, then try again; until they're saved, closing the app could lose them.",
                "Try again", { Store.retrySave() },
                null, null,
            )
        }
        if (journalReset) {
            Banner(
                Glyph.FOLDER,
                "Your saved journal couldn't be read",
                "Mindfield started afresh and kept the damaged file on this phone. If it was backed up to Google, linking the same account brings your notes back.",
                "Link Google", { nav.settings(SettingsSheet.GOOGLE) },
                "OK", { Store.dismissJournalReset() },
            )
        }
        if (Health.backupStale(cloud, System.currentTimeMillis())) {
            Banner(
                Glyph.CLOUD,
                "Google backup is paused",
                cloud.message ?: "Your journal hasn't been backed up for a few days.",
                "Fix", { nav.settings(SettingsSheet.GOOGLE) },
                null, null,
            )
        }
    }
}

/**
 * Today's path as a stepper: Predict, Plan, Report (and Review when cards are due), ticked off as
 * they're done; tap a step to jump to it. Seeing what's done and what's left helps people finish
 * (the goal-gradient effect, Kivetz et al. 2006).
 */
@Composable
fun TodayStepper(steps: List<PathStep>, due: Int, onStep: (Step) -> Unit) {
    val p = palette
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, s ->
            if (i > 0) {
                // The line between two steps fills in once the step before it is done.
                Box(
                    Modifier.weight(1f).padding(top = 15.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (steps[i - 1].done) p.brand else p.line),
                )
            }
            val label = if (s.step == Step.REVIEW && due > 0) "Review $due" else s.step.label
            Column(
                Modifier
                    .widthIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { view.tick(); onStep(s.step) }
                    .semantics { stateDescription = if (s.done) "done" else "to do" }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (s.done) p.brand else p.surface)
                        .border(1.5.dp, if (s.done) p.brand else p.line, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.done) GlyphIcon(Glyph.CHECK, onColor(p.brand), 15.dp)
                    else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (s.done) p.ink else p.muted, maxLines = 1)
            }
        }
    }
}

/** A notice that needs attention, with up to two actions. */
@Composable
fun Banner(icon: Glyph, title: String, text: String, action: String, onAction: () -> Unit, second: String?, onSecond: (() -> Unit)?) {
    val p = palette
    Panel(border = p.bad.copy(alpha = 0.45f), background = p.bad.copy(alpha = if (p.dark) 0.12f else 0.06f), padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphIcon(icon, p.bad, 20.dp, Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(action, color = p.bad, onClick = onAction)
            if (second != null && onSecond != null) SoftButton(second, color = p.muted, onClick = onSecond)
        }
    }
}

@Composable
fun ConceptScreen(concept: Concept, state: AppState, today: LocalDate, nav: Nav, onBack: () -> Unit) {
    val isToday = state.assignments[today]?.conceptId == concept.id
    ConceptPage(concept, state, today, isToday = isToday, nav = nav) { BackRow(onBack) }
}

/** "Back", top left, on screens shown over the tabs. */
@Composable
fun BackRow(onBack: () -> Unit) {
    val p = palette
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(Glyph.ARROW_LEFT, p.muted, 16.dp)
        Text("Back", style = MaterialTheme.typography.labelLarge, color = p.muted)
    }
}

/**
 * One concept, top to bottom: the plate, predict-first, what it is and how to spot it, the study,
 * the real-world case (folded), the mission and the report. With [reportFirst] (Today, in the
 * evening) the report comes straight after the header.
 */
@Composable
fun ConceptPage(
    concept: Concept,
    state: AppState,
    today: LocalDate,
    isToday: Boolean,
    nav: Nav,
    scroll: ScrollState = rememberScrollState(),
    reportFirst: Boolean = false,
    onAnchor: (Step, Int) -> Unit = { _, _ -> },
    header: @Composable () -> Unit,
) {
    val p = palette
    val guess = state.guesses[concept.id]
    val report: @Composable () -> Unit = {
        Box(Modifier.onGloballyPositioned { onAnchor(Step.REPORT, it.positionInParent().y.toInt()) }) {
            FieldReport(concept, state, today, isToday, nav, evening = reportFirst)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header()
        if (reportFirst) report()
        HeroCard(concept)
        Box(Modifier.onGloballyPositioned { onAnchor(Step.PREDICT, it.positionInParent().y.toInt()) }) {
            PredictCard(concept, guess?.choice)
        }
        Section("What's going on", Glyph.BULB) {
            Body(concept.what)
            SubBlock("Spot it in the wild", Glyph.SEARCH, concept.category.accent(p.dark)) { Text(concept.spot, style = MaterialTheme.typography.bodyMedium) }
        }
        StudySection(concept, unlocked = guess != null)
        if (guess != null) RealWorldSection(concept)
        Box(Modifier.onGloballyPositioned { onAnchor(Step.PLAN, it.positionInParent().y.toInt()) }) {
            MissionSection(concept, state, today, isToday)
        }
        if (!reportFirst) report()
        SeeAlso(concept, state, nav)
        SourcesRow(concept)
        Spacer(Modifier.size(24.dp))
    }
}

/** A titled block inside a card, on a soft wash of [color]. */
@Composable
fun SubBlock(title: String, icon: Glyph, color: Color, content: @Composable () -> Unit) {
    val p = palette
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = if (p.dark) 0.14f else 0.08f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(title, icon, color = color)
        content()
    }
}

/** The sources, folded into one line at the foot of the page; tap to read them. */
@Composable
private fun SourcesRow(concept: Concept) {
    val p = palette
    var open by rememberSaveable(concept.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide sources" else "Show sources") { open = !open }
            .semantics { stateDescription = if (open) "expanded" else "collapsed" }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sources · ${concept.numberLabel} · ${concept.category.label}", style = MaterialTheme.typography.labelMedium, color = p.faint, modifier = Modifier.weight(1f, fill = false))
            GlyphIcon(if (open) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, p.faint, 12.dp)
        }
        if (open) Text(concept.source, style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

fun shareText(context: android.content.Context, subject: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { android.widget.Toast.makeText(context, "Couldn't open sharing.", android.widget.Toast.LENGTH_SHORT).show() }
}

/**
 * The specimen plate: the concept's colour, its emblem and its one-line hook, with a share button
 * (explaining an idea to someone is one of the surest ways to keep it).
 */
@Composable
fun HeroCard(concept: Concept) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Palettes.plate(concept.category), Palettes.plateEnd(concept.category))))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${concept.numberLabel} · ${concept.category.label}".uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
                if (concept.isMyth) {
                    Text(
                        "PLOT TWIST: A MYTH",
                        Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
            Emblem(concept, Modifier.size(92.dp))
        }
        Text(concept.title, style = MaterialTheme.typography.displaySmall, color = Color.White)
        concept.aka?.let { Text(it, style = Quote.copy(fontStyle = FontStyle.Italic, fontSize = 16.sp), color = Color.White.copy(alpha = 0.78f)) }
        Text(concept.hook, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EvidenceBadge(concept.evidence, onPlate = true)
            Text(concept.category.short, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClickLabel = "Share") { shareText(context, concept.title, Texts.share(concept)) }
                    .semantics { contentDescription = "Share this concept" },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.SHARE, Color.White, 18.dp)
            }
        }
    }
}

@Composable
fun Section(title: String, icon: Glyph, border: Color? = null, content: @Composable () -> Unit) {
    Panel(border = border) {
        SectionLabel(title, icon)
        content()
    }
}

@Composable
fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

/** Predict first: one tap to guess; then the right answer lights up and the study unlocks. */
@Composable
fun PredictCard(concept: Concept, chosen: Int?) {
    val p = palette
    val view = LocalView.current
    val accent = concept.category.accent(p.dark)
    val order = remember(concept.id) { concept.predict.order(concept.id) }
    Panel(border = if (chosen == null) accent.copy(alpha = 0.45f) else null) {
        SectionLabel("Predict first", Glyph.SEARCH, color = accent)
        Text(concept.predict.question, style = Quote)
        if (chosen == null) {
            Text("Guess before you read the study. Even a wrong guess helps the answer stick.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        order.forEachIndexed { shown, canonical ->
            val isAnswer = canonical == concept.predict.answer
            val state = when {
                chosen == null -> OptionState.OPEN
                isAnswer -> OptionState.RIGHT
                canonical == chosen -> OptionState.WRONG
                else -> OptionState.DIM
            }
            OptionRow(letter = "ABCD"[shown], text = concept.predict.options[canonical], state = state, accent = accent) {
                view.tick()
                Store.guess(concept.id, canonical)
            }
        }
        if (chosen != null) {
            val right = chosen == concept.predict.answer
            Text(
                if (right) "✓ You called it. The study is unlocked below." else "Not quite, and that's the point: surprise makes it memorable. The study is unlocked below.",
                // Read out when it appears, so a screen reader hears whether the guess was right.
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (right) p.good else p.muted,
            )
        }
    }
}

enum class OptionState { OPEN, RIGHT, WRONG, DIM }

@Composable
fun OptionRow(letter: Char, text: String, state: OptionState, accent: Color, onClick: () -> Unit) {
    val p = palette
    val target = when (state) {
        OptionState.OPEN -> p.line
        OptionState.RIGHT -> p.good
        OptionState.WRONG -> p.bad
        OptionState.DIM -> p.line
    }
    val border by animateColorAsState(target, tween(300), label = "border")
    val bg = when (state) {
        OptionState.RIGHT -> p.good.copy(alpha = 0.12f)
        OptionState.WRONG -> p.bad.copy(alpha = 0.10f)
        else -> p.bg
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(bg)
            .border(1.5.dp, border, shape)
            .clickable(enabled = state == OptionState.OPEN, role = Role.Button, onClick = onClick)
            .semantics {
                when (state) {
                    OptionState.RIGHT -> stateDescription = "the right answer"
                    OptionState.WRONG -> stateDescription = "your answer, not right"
                    else -> Unit
                }
            }
            .alpha(if (state == OptionState.DIM) 0.55f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val markColor = when (state) {
            OptionState.RIGHT -> p.good
            OptionState.WRONG -> p.bad
            else -> accent
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(markColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            when (state) {
                OptionState.RIGHT -> GlyphIcon(Glyph.CHECK, markColor, 14.dp)
                OptionState.WRONG -> Text("×", style = MaterialTheme.typography.titleMedium, color = markColor)
                else -> Text(letter.toString(), style = MaterialTheme.typography.labelLarge, color = markColor)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StudySection(concept: Concept, unlocked: Boolean) {
    val p = palette
    Section("The study", Glyph.FLASK) {
        if (!unlocked) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.raised).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlyphIcon(Glyph.LOCK, p.muted, 16.dp)
                    Text("Make your prediction above to unlock the study.", style = MaterialTheme.typography.bodyMedium, color = p.muted, textAlign = TextAlign.Center)
                }
            }
        } else {
            AnimatedVisibility(visible = true, enter = fadeIn(tween(400)) + expandVertically(tween(400))) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Body(concept.study)
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palettes.evidence(concept.evidence, p.dark).copy(alpha = 0.09f)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("How solid is it?", style = MaterialTheme.typography.titleSmall)
                            EvidenceBadge(concept.evidence)
                        }
                        Text(concept.proof, style = MaterialTheme.typography.bodyMedium)
                        Text(concept.evidence.meaning, style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
            }
        }
    }
}

/**
 * The idea at work outside the lab, opened with the study so it can't give the prediction away.
 * It starts folded, with its opening lines as a teaser, so Today isn't one long scroll.
 */
@Composable
private fun RealWorldSection(concept: Concept) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    var open by rememberSaveable(concept.id) { mutableStateOf(false) }
    Panel(onClick = { open = !open }, onClickLabel = if (open) "Fold the real-world case" else "Read the real-world case", stateDescription = if (open) "expanded" else "collapsed") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("In the real world", Glyph.GLOBE) }
            GlyphIcon(if (open) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, p.muted, 14.dp)
        }
        if (!open) {
            Text(concept.caseStory, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("Read the case and its nuance", style = MaterialTheme.typography.labelLarge, color = accent)
        } else {
            Body(concept.caseStory)
            concept.caseNuance?.let { nuance ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = if (p.dark) 0.16f else 0.09f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("The nuance", style = MaterialTheme.typography.titleSmall, color = accent)
                    Text(nuance, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Today's mission, with an if-then plan box: "When ..., I'll ..." (implementation intentions). */
@Composable
private fun MissionSection(concept: Concept, state: AppState, today: LocalDate, isToday: Boolean) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    Section(if (isToday) "Today's mission" else "Mission", Glyph.TARGET, border = accent.copy(alpha = 0.5f)) {
        Body(concept.use)
        if (isToday) {
            val saved = state.plans[today]?.text.orEmpty()
            // The box opens on request: an empty field shown up front takes focus when the app starts,
            // which scrolls Today past its own concept and raises the keyboard.
            var editing by rememberSaveable(today) { mutableStateOf(false) }
            var text by rememberSaveable(today) { mutableStateOf(saved) }
            if (editing) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = Texts.cap(text, it, PLAN_MAX) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("When … , I'll …", color = p.faint) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(accent),
                )
                SoftButton("Save my plan", color = accent) {
                    Store.plan(today, text)
                    editing = false
                }
            } else if (saved.isEmpty()) {
                Text("Make it an if-then plan: people who decide when and where follow through far more often.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                SoftButton("Make an if-then plan", color = accent) { text = ""; editing = true }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.10f)).clickable { text = saved; editing = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR PLAN", style = MaterialTheme.typography.labelSmall, color = accent)
                        Text("“$saved”", style = Quote.copy(fontSize = 16.sp))
                    }
                    Text("Edit", style = MaterialTheme.typography.labelLarge, color = p.muted)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlyphIcon(Glyph.WARNING, p.muted, 15.dp, Modifier.padding(top = 3.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("WATCH OUT", style = MaterialTheme.typography.labelMedium, color = p.muted)
                Text(concept.guard, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun fieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = palette.line,
    cursorColor = accent,
    focusedContainerColor = palette.bg,
    unfocusedContainerColor = palette.bg,
)

/** The day's field report: how did it show up? Four one-tap answers, then a note if you like. */
@Composable
private fun FieldReport(concept: Concept, state: AppState, today: LocalDate, isToday: Boolean, nav: Nav, evening: Boolean = false) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    val entries = state.entriesFor(concept.id).let { all -> if (isToday) all.filter { it.day == today } else all }.sortedByDescending { it.createdAt }
    Section(if (isToday) "Tonight's field report" else "Your sightings", Glyph.NOTEBOOK, border = if (evening) accent.copy(alpha = 0.5f) else null) {
        if (evening) {
            // Above the concept in the evening: say which one, and what you planned.
            Text(concept.title, style = MaterialTheme.typography.titleSmall, color = accent)
            state.plans[today]?.text?.takeIf { it.isNotBlank() }?.let { plan ->
                Text("You planned: “$plan”", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        Text(
            if (isToday) "How did it show up in your day?" else if (entries.isEmpty()) "Seen it in the wild? Log a sighting." else "${entries.size} logged so far.",
            style = MaterialTheme.typography.titleMedium,
        )
        entries.forEach { e -> EntryRow(e, showConcept = false) { nav.log(LogRequest(concept.id, edit = e)) } }
        val modes = Mode.entries.filter { isToday || it != Mode.NOT_TODAY }
        modes.chunked(2).forEach { pair ->
            // Equal heights when a long label wraps (large text).
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { m -> ModeButton(m, accent, Modifier.weight(1f)) { nav.log(LogRequest(concept.id, mode = m)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One of the four answers to "how did it show up?": a button, or in the sheet a choice ([choice]). */
@Composable
fun ModeButton(mode: Mode, accent: Color, modifier: Modifier = Modifier, selected: Boolean = false, choice: Boolean = false, onClick: () -> Unit) {
    val p = palette
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    val tap = if (choice) {
        Modifier.selectable(selected = selected, role = Role.RadioButton) { view.tick(); onClick() }
    } else {
        Modifier.clickable(role = Role.Button) { view.tick(); onClick() }
    }
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) accent else p.bg)
            .border(1.dp, if (selected) accent else p.line, shape)
            .then(tap)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        GlyphIcon(mode.glyph, if (selected) onColor(accent) else accent, 22.dp)
        Text(mode.label, style = MaterialTheme.typography.labelLarge, color = if (selected) onColor(accent) else p.ink, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
fun EntryRow(entry: Entry, showConcept: Boolean, onClick: () -> Unit) {
    val p = palette
    val concept = Store.library[entry.conceptId]
    val accent = concept?.category?.accent(p.dark) ?: p.brand
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GlyphIcon(entry.mode.glyph, accent, 17.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.mode.label, style = MaterialTheme.typography.labelLarge, color = accent)
                entry.outcome?.let { Text("· ${it.label}", style = MaterialTheme.typography.labelLarge, color = p.muted) }
            }
            if (showConcept && concept != null) Text(concept.title, style = MaterialTheme.typography.titleSmall)
            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
            else Text("No note. Tap to add one.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeeAlso(concept: Concept, state: AppState, nav: Nav) {
    if (concept.related.isEmpty()) return
    val p = palette
    val showAll = state.settings.showAll
    Section("See also", Glyph.COMPASS) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            concept.related.mapNotNull { Store.library[it] }.forEach { r ->
                val open = showAll || r.id in state.unlocked
                val color = r.category.accent(p.dark)
                if (open) Pill(r.title, color, onClick = { nav.openConcept(r.id) })
                else Pill("Locked · ${r.category.short}", p.faint)
            }
        }
    }
}

// --- The field-report sheet --------------------------------------------------------------------

private const val NOTE_MAX = 2000
private const val PLAN_MAX = 200

@Composable
fun LogSheet(req: LogRequest, state: AppState, today: LocalDate, onClose: () -> Unit) {
    // A new request (another concept, or an entry to edit) starts a fresh sheet. The key is plain
    // text, so a draft is found again after the app is recreated.
    key(req.conceptId, req.mode?.key, req.edit?.id) { LogSheetContent(req, state, today, onClose) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogSheetContent(req: LogRequest, state: AppState, today: LocalDate, onClose: () -> Unit) {
    val p = palette
    val edit = req.edit
    var conceptId by rememberSaveable { mutableStateOf(edit?.conceptId ?: req.conceptId) }
    var mode by rememberSaveable { mutableStateOf(edit?.mode ?: req.mode ?: Mode.SPOTTED) }
    var note by rememberSaveable { mutableStateOf(edit?.note.orEmpty()) }
    var outcome by rememberSaveable { mutableStateOf(edit?.outcome) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteReady by remember { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val concept = Store.library[conceptId]
    if (concept == null) {
        // A concept no longer in the guide: nothing to show, so close rather than swallow Back.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val accent = concept.category.accent(p.dark)
    // Recent concepts to switch between: the one asked for first, then the last few days'.
    val recent = remember(state.assignments, req.conceptId) {
        (listOf(req.conceptId) + state.assignments.entries.sortedByDescending { it.key }.map { it.value.conceptId })
            .distinct().take(10).mapNotNull { Store.library[it] }
    }
    val now = Store.now()
    // Until 4 am, writing up yesterday's concept counts for yesterday (the notification buttons agree).
    val day = FieldDay.of(state, conceptId, now)
    // "Not today" is about the day's own concept, as on the concept page.
    val dayConcepts = setOfNotNull(state.assignments[now.toLocalDate()]?.conceptId, state.assignments[FieldDay.current(now)]?.conceptId)
    val modes = Mode.entries.filter { it != Mode.NOT_TODAY || conceptId in dayConcepts || edit?.mode == Mode.NOT_TODAY }
    // Something typed or changed that closing would throw away.
    val dirty = if (edit != null) {
        note.trim() != edit.note || mode != edit.mode || outcome != edit.outcome || conceptId != edit.conceptId
    } else {
        note.isNotBlank()
    }
    val close: () -> Unit = { if (dirty) askDiscard = true else onClose() }
    BackHandler(enabled = dirty) { askDiscard = true }
    LaunchedEffect(confirmDelete) {
        // The confirming tap only counts after a moment, so a double tap can't delete by accident.
        deleteReady = false
        if (confirmDelete) {
            delay(600)
            deleteReady = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        // The dimmed screen behind: a tap closes the sheet (asking first if there's a draft).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close)
                .semantics { contentDescription = "Close field report" },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                // Clear of the status bar and a camera cutout when the sheet is tall or the phone is sideways.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(p.surface)
                // Taps on the sheet itself stay on the sheet.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = "Field report" }
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(p.line))
            Text(if (edit != null) "Edit field note" else "Field report", style = MaterialTheme.typography.headlineSmall)
            if (edit == null && recent.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { c ->
                        ChoiceChip(c.title, c.id == conceptId, c.category.accent(p.dark)) {
                            conceptId = c.id
                            if (mode == Mode.NOT_TODAY && c.id !in dayConcepts) mode = Mode.SPOTTED
                        }
                    }
                }
            } else {
                Pill(concept.title, accent)
            }
            if (edit == null && day != now.toLocalDate()) {
                Text("This counts for yesterday, the day it was your concept.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Text("How did it show up?", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                modes.chunked(2).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { m -> ModeButton(m, accent, Modifier.weight(1f), selected = m == mode, choice = true) { mode = m } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text(mode.prompt, style = MaterialTheme.typography.bodyMedium, color = p.muted)
            OutlinedTextField(
                value = note,
                // Never splits an emoji, and never cuts a note that's already longer (from a reply or a sync).
                onValueChange = { note = Texts.cap(note, it, NOTE_MAX) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("In your own words (optional)", color = p.faint) },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(accent),
            )
            if (mode == Mode.USED) {
                Text("How did it go?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Outcome.entries.forEach { o -> ChoiceChip(o.label, outcome == o, accent) { outcome = if (outcome == o) null else o } }
                }
            }
            PrimaryButton(if (edit != null) "Save changes" else "Save to journal", p.brand) {
                if (edit != null) {
                    Store.edit(edit.copy(conceptId = conceptId, mode = mode, note = note, outcome = outcome), original = edit)
                } else {
                    Store.log(conceptId, mode, note, outcome, day = FieldDay.of(Store.state.value, conceptId, Store.now()))
                }
                onClose()
            }
            if (edit != null) {
                // Out of the way of Save, and its own line at large text sizes.
                SoftButton(if (confirmDelete) "Really delete this note?" else "Delete note", Modifier.align(Alignment.CenterHorizontally), color = p.bad) {
                    when {
                        !confirmDelete -> confirmDelete = true
                        deleteReady -> {
                            Store.delete(edit.id)
                            onClose()
                        }
                    }
                }
            }
        }
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard this note?") },
            text = { Text("What you've written here hasn't been saved.") },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; onClose() }) { Text("Discard", color = p.bad) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text("Keep writing", color = p.ink) }
            },
            containerColor = p.surface,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}
