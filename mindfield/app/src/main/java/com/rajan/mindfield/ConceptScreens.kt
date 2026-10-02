package com.rajan.mindfield

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Entry
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Outcome
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.Stats
import com.rajan.mindfield.core.Texts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Today -------------------------------------------------------------------------------------

@Composable
fun TodayScreen(state: AppState, today: LocalDate, nav: Nav) {
    val concept = state.assignments[today]?.conceptId?.let { Store.library[it] }
    LaunchedEffect(today, concept == null) { if (concept == null) Store.todayConcept() }
    if (concept == null) return
    val p = palette
    val context = LocalContext.current
    val days = Stats.checkInDays(state)
    val streak = Stats.streak(days, today)
    val due = Spacing.due(state, Store.library, today).size
    val cloud by GoogleSync.state.collectAsStateWithLifecycle()
    // Re-checked whenever the screen comes back (e.g. after allowing background use in settings).
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(today) {
        blocked = Health.remindersBlocked(context) && !Health.ignoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    val loggedToday = state.entriesOn(today).isNotEmpty()
    Box(Modifier.fillMaxSize()) {
    ConceptPage(concept, state, today, isToday = true, nav = nav) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), style = MaterialTheme.typography.labelLarge, color = p.muted)
                Text("Day ${Stats.dayNumber(state, today)} in the field", style = MaterialTheme.typography.headlineSmall)
            }
            if (streak > 0) Pill("🔥 $streak", concept.category.accent(p.dark))
        }
        if (blocked) {
            Banner(
                "🔕",
                "Your reminders seem to be blocked",
                "Two mornings passed without the daily notification. Phones with strict battery savers stop apps' alarms; allow Mindfield to run in the background.",
                "Allow", { Health.requestUnrestricted(context) },
                "Not now", { Health.snoozeBanner(context); blocked = false },
            )
        }
        if (Health.backupStale(cloud, System.currentTimeMillis())) {
            Banner(
                "☁️",
                "Google backup is paused",
                cloud.message ?: "Your journal hasn't been backed up for a few days.",
                "Fix", { nav.tab(Tab.ME) },
                null, null,
            )
        }
        if (due > 0) {
            Panel(onClick = { nav.tab(Tab.REVIEW) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🃏", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(if (due == 1) "1 quick review waiting" else "$due quick reviews waiting", style = MaterialTheme.typography.titleSmall)
                        Text("Recalling beats rereading. About a minute.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Text("→", style = MaterialTheme.typography.titleMedium, color = p.muted)
                }
            }
        }
    }
    // Always within reach, so logging never means scrolling to the bottom.
    val accent = concept.category.accent(p.dark)
    Text(
        if (loggedToday) "✓ Logged · add more" else "📓  Log today",
        Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .clip(RoundedCornerShape(50))
            .background(if (loggedToday) p.surface else accent)
            .border(1.dp, if (loggedToday) p.line else accent, RoundedCornerShape(50))
            .clickable(role = Role.Button) { nav.log(LogRequest(concept.id)) }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        color = if (loggedToday) p.ink else Color.White,
        style = MaterialTheme.typography.titleSmall,
    )
    }
}

/** A notice that needs attention, with up to two actions. */
@Composable
fun Banner(emoji: String, title: String, text: String, action: String, onAction: () -> Unit, second: String?, onSecond: (() -> Unit)?) {
    val p = palette
    Panel(border = p.bad.copy(alpha = 0.45f), background = p.bad.copy(alpha = if (p.dark) 0.12f else 0.06f), padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(emoji, fontSize = 20.sp)
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
    val p = palette
    val isToday = state.assignments[today]?.conceptId == concept.id
    ConceptPage(concept, state, today, isToday = isToday, nav = nav) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←  Back", style = MaterialTheme.typography.labelLarge, color = p.muted)
        }
    }
}

/** One concept, top to bottom: the plate, predict-first, the study, the mission and the report. */
@Composable
fun ConceptPage(
    concept: Concept,
    state: AppState,
    today: LocalDate,
    isToday: Boolean,
    nav: Nav,
    header: @Composable () -> Unit,
) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    val guess = state.guesses[concept.id]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header()
        HeroCard(concept)
        PredictCard(concept, guess?.choice)
        Section("What's going on", "💡") { Body(concept.what) }
        StudySection(concept, unlocked = guess != null)
        Section("Spot it in the wild", "🔍") { Body(concept.spot) }
        MissionSection(concept, state, today, isToday)
        Section("Watch out", "⚠️") { Body(concept.guard) }
        FieldReport(concept, state, today, isToday, nav)
        SeeAlso(concept, state, nav)
        ShareCard(concept)
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Source", style = MaterialTheme.typography.labelMedium, color = p.faint)
            Text(concept.source, style = MaterialTheme.typography.bodySmall, color = p.muted)
            Text("${concept.numberLabel} · ${concept.category.label}", style = MaterialTheme.typography.bodySmall, color = accent)
        }
        // Room for the floating "Log today" button.
        Spacer(Modifier.size(80.dp))
    }
}

/** Teach it to someone: explaining an idea is one of the best ways to keep it. */
@Composable
private fun ShareCard(concept: Concept) {
    val p = palette
    val context = LocalContext.current
    Panel {
        SectionLabel("Teach it to someone", "💬")
        Text("Explaining an idea to a friend is one of the surest ways to remember it.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        SoftButton("Share this concept", color = concept.category.accent(p.dark)) { shareText(context, concept.title, Texts.share(concept)) }
    }
}

fun shareText(context: android.content.Context, subject: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The specimen plate: the concept's colour, its emblem and its one-line hook. */
@Composable
fun HeroCard(concept: Concept) {
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
            Text(Palettes.emoji(concept.category) + " " + concept.category.short, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun Section(title: String, emoji: String, border: Color? = null, content: @Composable () -> Unit) {
    Panel(border = border) {
        SectionLabel(title, emoji)
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
        SectionLabel("Predict first", "🔮", color = accent)
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
            .alpha(if (state == OptionState.DIM) 0.55f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val mark = when (state) {
            OptionState.RIGHT -> "✓"
            OptionState.WRONG -> "✗"
            else -> letter.toString()
        }
        val markColor = when (state) {
            OptionState.RIGHT -> p.good
            OptionState.WRONG -> p.bad
            else -> accent
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(markColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(mark, style = MaterialTheme.typography.labelLarge, color = markColor)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StudySection(concept: Concept, unlocked: Boolean) {
    val p = palette
    Section("The study", "🧪") {
        if (!unlocked) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.raised).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("🔒  Make your prediction above to unlock the study.", style = MaterialTheme.typography.bodyMedium, color = p.muted, textAlign = TextAlign.Center)
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

/** Today's mission, with an if-then plan box: "When ..., I'll ..." (implementation intentions). */
@Composable
private fun MissionSection(concept: Concept, state: AppState, today: LocalDate, isToday: Boolean) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    Section(if (isToday) "Today's mission" else "Mission", "🎯", border = accent.copy(alpha = 0.5f)) {
        Body(concept.use)
        if (isToday) {
            val saved = state.plans[today]?.text.orEmpty()
            var editing by rememberSaveable(today) { mutableStateOf(saved.isEmpty()) }
            var text by rememberSaveable(today) { mutableStateOf(saved) }
            if (editing) {
                Text("Make it an if-then plan: people who decide when and where follow through far more often.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("When … , I'll …", color = p.faint) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(accent),
                )
                SoftButton("Save my plan", color = accent) {
                    Store.plan(today, text)
                    editing = text.isBlank()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.10f)).clickable { editing = true }.padding(14.dp),
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
private fun FieldReport(concept: Concept, state: AppState, today: LocalDate, isToday: Boolean, nav: Nav) {
    val p = palette
    val accent = concept.category.accent(p.dark)
    val entries = state.entriesFor(concept.id).let { all -> if (isToday) all.filter { it.day == today } else all }.sortedByDescending { it.createdAt }
    Section(if (isToday) "Tonight's field report" else "Your sightings", "📓") {
        Text(
            if (isToday) "How did it show up in your day?" else if (entries.isEmpty()) "Seen it in the wild? Log a sighting." else "${entries.size} logged so far.",
            style = MaterialTheme.typography.titleMedium,
        )
        entries.forEach { e -> EntryRow(e, showConcept = false) { nav.log(LogRequest(concept.id, edit = e)) } }
        val modes = Mode.entries.filter { isToday || it != Mode.NOT_TODAY }
        modes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { m -> ModeButton(m, accent, Modifier.weight(1f)) { nav.log(LogRequest(concept.id, mode = m)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ModeButton(mode: Mode, accent: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val p = palette
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) accent else p.bg)
            .border(1.dp, if (selected) accent else p.line, shape)
            .clickable(role = Role.Button) { view.tick(); onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(mode.emoji, fontSize = 22.sp)
        Text(mode.label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else p.ink, maxLines = 1)
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
            Text(entry.mode.emoji, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.mode.label, style = MaterialTheme.typography.labelLarge, color = accent)
                entry.outcome?.let { Text("· ${it.label}", style = MaterialTheme.typography.labelLarge, color = p.muted) }
            }
            if (showConcept && concept != null) Text(concept.title, style = MaterialTheme.typography.titleSmall)
            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
            else Text("No note. Tap to add one.", style = MaterialTheme.typography.bodySmall, color = p.faint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeeAlso(concept: Concept, state: AppState, nav: Nav) {
    if (concept.related.isEmpty()) return
    val p = palette
    val showAll = state.settings.showAll
    Section("See also", "🧭") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            concept.related.mapNotNull { Store.library[it] }.forEach { r ->
                val open = showAll || r.id in state.unlocked
                val color = r.category.accent(p.dark)
                if (open) Pill(r.title, color, onClick = { nav.openConcept(r.id) })
                else Pill("🔒 ${r.category.short}", p.faint)
            }
        }
    }
}

// --- The field-report sheet --------------------------------------------------------------------

@Composable
fun LogSheet(req: LogRequest, state: AppState, today: LocalDate, onClose: () -> Unit) {
    // A new request (another concept, or an entry to edit) starts a fresh sheet.
    key(req) { LogSheetContent(req, state, today, onClose) }
}

@Composable
private fun LogSheetContent(req: LogRequest, state: AppState, today: LocalDate, onClose: () -> Unit) {
    val p = palette
    val view = LocalView.current
    val edit = req.edit
    var conceptId by rememberSaveable { mutableStateOf(edit?.conceptId ?: req.conceptId) }
    var mode by rememberSaveable { mutableStateOf(edit?.mode ?: req.mode ?: Mode.SPOTTED) }
    var note by rememberSaveable { mutableStateOf(edit?.note.orEmpty()) }
    var outcome by rememberSaveable { mutableStateOf(edit?.outcome) }
    var confirmDelete by remember { mutableStateOf(false) }
    val concept = Store.library[conceptId] ?: return
    val accent = concept.category.accent(p.dark)
    // Recent concepts to switch between: today's first, then the last few days'.
    val recent = remember(state.assignments) {
        state.assignments.entries.sortedByDescending { it.key }.map { it.value.conceptId }.distinct().take(10).mapNotNull { Store.library[it] }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(p.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
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
                    recent.forEach { c -> ChoiceChip(c.title, c.id == conceptId, c.category.accent(p.dark)) { conceptId = c.id } }
                }
            } else {
                Pill(concept.title, accent)
            }
            Text("How did it show up?", style = MaterialTheme.typography.titleMedium)
            Mode.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { m -> ModeButton(m, accent, Modifier.weight(1f), selected = m == mode) { mode = m } }
                }
            }
            Text(mode.prompt, style = MaterialTheme.typography.bodyMedium, color = p.muted)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("In your own words (optional)", color = p.faint) },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(accent),
            )
            if (mode == Mode.USED) {
                Text("How did it go?", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Outcome.entries.forEach { o -> ChoiceChip(o.label, outcome == o, accent) { outcome = if (outcome == o) null else o } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (edit != null) {
                    SoftButton(if (confirmDelete) "Really delete?" else "Delete", color = p.bad) {
                        if (confirmDelete) { Store.delete(edit.id); onClose() } else confirmDelete = true
                    }
                }
                PrimaryButton(if (edit != null) "Save changes" else "Save to journal", accent, Modifier.weight(1f)) {
                    view.tick()
                    if (edit != null) {
                        Store.edit(edit.copy(conceptId = conceptId, mode = mode, note = note, outcome = outcome))
                    } else {
                        // Writing up yesterday's concept in the small hours counts for yesterday.
                        val shownOn = state.assignments.filterValues { it.conceptId == conceptId }.keys.filter { it <= today }.maxOrNull()
                        val lateNight = Store.now().hour < 4
                        Store.log(conceptId, mode, note, outcome, day = shownOn?.takeIf { lateNight && it == today.minusDays(1) } ?: today)
                    }
                    onClose()
                }
            }
        }
    }
}
