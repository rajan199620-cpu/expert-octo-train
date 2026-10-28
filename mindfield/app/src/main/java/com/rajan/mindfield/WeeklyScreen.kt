package com.rajan.mindfield

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.BlindSpot
import com.rajan.mindfield.core.Entry
import com.rajan.mindfield.core.Kept
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Texts
import com.rajan.mindfield.core.Verdict
import com.rajan.mindfield.core.Weekly
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The weekly review's three looks back; one is left out when there's nothing for it to show. */
private enum class Look(val title: String) { BLIND_SPOT("Your blind spot"), AFTER_ACTION("One moment, one change"), LOOK_BACK("Looking back") }

/**
 * The weekly review (see [Weekly] for the research behind it): your blind spot from the journal,
 * one moment from the week turned into an if-then plan for the next, and an old note judged again.
 * One sentence to write; everything else is a tap. Nothing is saved until it's finished; then it
 * closes back to where it was opened, where the new plan now shows.
 */
@Composable
fun WeeklyScreen(state: AppState, today: LocalDate, nav: Nav, onClose: () -> Unit) {
    val p = palette
    val week = Weekly.weekFor(today)
    val existing = state.reflections[week]
    val reports = remember(state.entries, week) { Weekly.reportsIn(state, week) }
    // Picked once, so saving the review (which marks the old note as seen) doesn't swap it under you.
    val note = remember(week) {
        existing?.noteId?.let { id -> state.liveEntries.firstOrNull { it.id == id } } ?: Weekly.lookBack(state, today)
    }
    val moment = remember(state.entries, week) { Weekly.moment(state, week) }
    val blind = remember(state.entries, today) { Weekly.blindSpot(state, today) }
    val previous = Weekly.previousPlan(state, week)
    val looks = listOfNotNull(Look.BLIND_SPOT.takeIf { blind != null }, Look.AFTER_ACTION, Look.LOOK_BACK.takeIf { note != null })

    val context = LocalContext.current
    var step by rememberSaveable(week) { mutableIntStateOf(0) }
    var plan by rememberSaveable(week) { mutableStateOf(existing?.plan.orEmpty()) }
    var kept by rememberSaveable(week) { mutableStateOf(existing?.kept?.key) }
    var verdict by rememberSaveable(week) { mutableStateOf(existing?.verdict?.key) }

    val scroll = rememberScrollState()
    // Each look starts at its top, not wherever the last one was scrolled to.
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackRow(onClose)
        val range = "${week.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} – ${week.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}"
        if (existing == null && reports.size < Weekly.MIN_REPORTS) {
            Headline("Weekly review", "Week of $range")
            Panel {
                SectionLabel("Not yet", Glyph.CALENDAR)
                Text("A week needs at least ${Weekly.MIN_REPORTS} field reports to look back on. This one has ${reports.size}.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reflecting helps once there's experience to reflect on; before that, it's guesswork. The review opens on Sunday once the week has enough.",
                    style = MaterialTheme.typography.bodyMedium, color = p.muted,
                )
            }
        } else {
            val at = step.coerceIn(0, looks.size - 1)
            val look = looks[at]
            Headline("Weekly review", "Week of $range · ${reports.size} field ${if (reports.size == 1) "report" else "reports"} · ${at + 1} of ${looks.size}")
            Text(look.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when (look) {
                Look.BLIND_SPOT -> BlindSpotLook(blind!!, nav)
                Look.AFTER_ACTION -> AfterActionLook(moment, previous, kept?.let { Kept.of(it) }, { kept = it.key }, plan) { plan = Texts.cap(plan, it, Weekly.PLAN_MAX) }
                Look.LOOK_BACK -> LookBackLook(note!!, today, verdict?.let { Verdict.of(it) }) { verdict = it.key }
            }
            val last = at >= looks.size - 1
            PrimaryButton(if (!last) "Next" else if (existing == null) "Finish review" else "Save", p.brand) {
                if (last) {
                    Store.reflect(week, plan, kept?.let { Kept.of(it) }, note?.id, verdict?.let { Verdict.of(it) })
                    // Opened again, it starts from the top, with what was saved.
                    step = 0
                    val done = if (plan.isBlank()) "Weekly review saved." else "Saved. Your plan is on Today all week; next Sunday asks if you kept it."
                    Toast.makeText(context, done, Toast.LENGTH_LONG).show()
                    onClose()
                } else {
                    step = at + 1
                }
            }
            if (at > 0) SoftButton("Back a step", Modifier.fillMaxWidth(), color = p.muted) { step = at - 1 }
        }
    }
}

@Composable
private fun BlindSpotLook(b: BlindSpot, nav: Nav) {
    val p = palette
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Count(Mode.SPOTTED, b.others, "in others", Modifier.weight(1f))
            Count(Mode.MYSELF, b.self, "in yourself", Modifier.weight(1f))
        }
        Text(
            "Everyone finds bias easier to see in others than in themselves: the bias blind spot (Pronin, Lin & Ross 2002). The gap isn't a score. What matters is where it is.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted,
        )
        val recent = b.recentSelfPercent
        val earlier = b.earlierSelfPercent
        if (recent != null) {
            Text(
                "In the last four weeks, $recent% of your sightings were in yourself" + (earlier?.let { " (before that, $it%)." } ?: "."),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val concept = b.conceptId?.let { Store.library[it] }
    if (concept != null) {
        val accent = concept.category.accent(p.dark)
        Panel(border = accent.copy(alpha = 0.5f), onClick = { nav.openConcept(concept.id) }, onClickLabel = "Read about it") {
            SectionLabel("Look for it in you", Glyph.MIRROR, color = accent)
            Text(concept.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Spotted in others ${times(b.conceptOthers)}, " + if (b.conceptSelf == 0) "never caught in yourself." else "caught in yourself ${times(b.conceptSelf)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("This week, when you see it in someone else, ask where you did the same.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

private fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "$n times"

@Composable
private fun Count(mode: Mode, n: Int, label: String, modifier: Modifier) {
    val p = palette
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.raised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(mode.glyph, p.brand, 16.dp)
            Text(mode.short, style = MaterialTheme.typography.labelLarge, color = p.muted)
        }
        Text("$n", style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun AfterActionLook(moment: Entry?, previous: String?, kept: Kept?, onKept: (Kept) -> Unit, plan: String, onPlan: (String) -> Unit) {
    val p = palette
    if (previous != null) {
        Panel {
            SectionLabel("Last week's plan", Glyph.COMPASS)
            Text("“$previous”", style = Quote.copy(fontSize = 16.sp))
            Text("Did you keep it?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kept.entries.forEach { k -> ChoiceChip(k.label, kept == k, p.brand) { onKept(k) } }
            }
        }
    }
    if (moment != null) {
        val concept = Store.library[moment.conceptId]
        val accent = concept?.category?.accent(p.dark) ?: p.brand
        Panel(border = accent.copy(alpha = 0.5f)) {
            SectionLabel(
                moment.day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + " · " + moment.mode.label + (moment.outcome?.let { " · ${it.label}" } ?: ""),
                moment.mode.glyph, color = accent,
            )
            Text(concept?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Store.state.value.plans[moment.day]?.text?.takeIf { it.isNotBlank() }?.let { Text("You planned: “$it”", style = MaterialTheme.typography.bodyMedium, color = p.muted) }
            if (moment.note.isNotBlank()) Text("“${moment.note}”", style = Quote.copy(fontSize = 16.sp))
        }
    }
    Text(
        if (moment != null) "What exactly happened, and what will you do next time? Not why: what, and when."
        else "Think of one moment this week you'd handle differently. What will you do next time?",
        style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
        value = plan,
        onValueChange = onPlan,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("When … , I'll …", color = p.faint) },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(p.brand),
    )
    Text("An if-then plan, with the when and where, is the kind people carry out.", style = MaterialTheme.typography.bodySmall, color = p.muted)
}

@Composable
private fun LookBackLook(note: Entry, today: LocalDate, verdict: Verdict?, onVerdict: (Verdict) -> Unit) {
    val p = palette
    val concept = Store.library[note.conceptId]
    val accent = concept?.category?.accent(p.dark) ?: p.brand
    val days = ChronoUnit.DAYS.between(note.day, today)
    Panel(border = accent.copy(alpha = 0.5f)) {
        SectionLabel("$days days ago · ${note.mode.label}", Glyph.CLOCK, color = accent)
        Text(concept?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text("“${note.note}”", style = Quote.copy(fontSize = 16.sp))
    }
    Text("Reading it now: still true? Would you handle it the same?", style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Verdict.entries.forEach { v -> ChoiceChip(v.label, verdict == v, p.brand) { onVerdict(v) } }
    }
    Text(
        "Your words from then, not a memory of them: hindsight quietly rewrites what we remember thinking.",
        style = MaterialTheme.typography.bodySmall, color = p.muted,
    )
}

/** The Journal's weekly review card: ready to start, or this week's plan to come back to. */
@Composable
fun WeeklyCard(state: AppState, today: LocalDate, nav: Nav) {
    val p = palette
    val due = Weekly.due(state, today)
    val active = Weekly.activePlan(state, today)
    if (due == null && active == null) return
    Panel(
        border = p.brand.copy(alpha = if (due != null) 0.6f else 0.3f),
        onClick = nav.weekly,
        onClickLabel = if (due != null) "Start the weekly review" else "Open the weekly review",
    ) {
        SectionLabel("Weekly review", Glyph.COMPASS, color = p.brand)
        if (due != null) {
            Text("Your week, in five minutes", style = MaterialTheme.typography.titleMedium)
            Text("Where your blind spot is, one moment to learn from, and one plan for next week.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
        if (active != null) {
            Text("This week: “${active.second.plan}”", style = MaterialTheme.typography.bodyMedium, color = if (due != null) p.muted else p.ink)
        }
    }
}

/** This week's plan on Today, one line under the path; tap for the review it came from. */
@Composable
fun WeeklyPlanLine(state: AppState, today: LocalDate, nav: Nav) {
    val p = palette
    val active = Weekly.activePlan(state, today) ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open the weekly review", onClick = nav.weekly).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(Glyph.COMPASS, p.brand, 15.dp, Modifier.padding(top = 2.dp))
        Text("This week: “${active.second.plan}”", style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
