package com.rajan.mindfield

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Card
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Question
import com.rajan.mindfield.core.Quiz
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.Stats
import com.rajan.mindfield.core.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// --- Field guide -------------------------------------------------------------------------------

private const val MYTHS = "myths"
private const val SPOTTED = "spotted"

/** The collection: every concept as a specimen card; undiscovered ones stay sealed until their day. */
@Composable
fun GuideScreen(state: AppState, nav: Nav) {
    val p = palette
    val library = Store.library
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val unlocked = state.unlocked
    val showAll = state.settings.showAll
    val sightings = remember(state.entries) {
        state.liveEntries.filter { it.mode.isSighting }.groupingBy { it.conceptId }.eachCount()
    }
    val q = query.trim().lowercase()
    val shown = library.all.filter { c ->
        val open = showAll || c.id in unlocked
        val matchesFilter = when (filter) {
            null -> true
            MYTHS -> c.isMyth && open
            SPOTTED -> (sightings[c.id] ?: 0) > 0
            else -> c.category.key == filter
        }
        val matchesQuery = q.isEmpty() || (open && (c.title.lowercase().contains(q) || c.hook.lowercase().contains(q) || c.aka?.lowercase()?.contains(q) == true))
        matchesFilter && matchesQuery
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Headline(
                    "Field guide",
                    "${Stats.discovered(state, library)} of ${library.size} discovered · ${Stats.lifeList(state).size} seen in the wild",
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search what you've discovered", color = p.faint) },
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(p.brand),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("All", filter == null, p.brand) { filter = null }
                    ChoiceChip("👀 Spotted", filter == SPOTTED, p.brand) { filter = if (filter == SPOTTED) null else SPOTTED }
                    ChoiceChip("💥 Myths", filter == MYTHS, p.bad) { filter = if (filter == MYTHS) null else MYTHS }
                    Category.entries.forEach { c ->
                        ChoiceChip("${Palettes.emoji(c)} ${c.short}", filter == c.key, c.accent(p.dark)) { filter = if (filter == c.key) null else c.key }
                    }
                }
                if (filter != null && filter != MYTHS && filter != SPOTTED) {
                    Category.entries.firstOrNull { it.key == filter }?.let { Text(it.blurb, style = MaterialTheme.typography.bodyMedium, color = p.muted) }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (q.isNotEmpty()) "Nothing you've discovered matches “$query”." else "Nothing here yet. Keep spotting!",
                    style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(shown, key = { it.id }) { c ->
            val open = showAll || c.id in unlocked
            if (open) SpecimenTile(c, sightings[c.id] ?: 0, isNew = c.id in unlocked && state.guesses[c.id] == null) { nav.openConcept(c.id) }
            else SealedTile(c)
        }
    }
}

@Composable
private fun SpecimenTile(concept: Concept, sightings: Int, isNew: Boolean, onClick: () -> Unit) {
    val p = palette
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.line, shape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(104.dp).background(Brush.linearGradient(listOf(Palettes.plate(concept.category), Palettes.plateEnd(concept.category)))),
        ) {
            Emblem(concept, Modifier.size(78.dp).align(Alignment.Center))
            Text(concept.numberLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(10.dp))
            if (sightings > 0) {
                Text(
                    "👀 $sightings",
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                )
            } else if (isNew) {
                Text(
                    "NEW",
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Palettes.plate(concept.category),
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(concept.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EvidenceMeter(concept.evidence, Palettes.evidence(concept.evidence, p.dark))
                Text(concept.category.short, style = MaterialTheme.typography.labelSmall, color = concept.category.accent(p.dark))
            }
        }
    }
}

@Composable
private fun SealedTile(concept: Concept) {
    val p = palette
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.raised.copy(alpha = 0.6f)).border(1.dp, p.line, shape)) {
        Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
            Text("?", style = MaterialTheme.typography.displayMedium, color = p.faint.copy(alpha = 0.6f))
            Text(concept.numberLabel, style = MaterialTheme.typography.labelSmall, color = p.faint, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Undiscovered", style = MaterialTheme.typography.titleSmall, color = p.faint)
            Text(concept.category.short, style = MaterialTheme.typography.labelSmall, color = concept.category.accent(p.dark).copy(alpha = 0.7f))
        }
    }
}

// --- Review ------------------------------------------------------------------------------------

/** A round of questions: its cards as they were when it started, and the seed its questions come from. */
private data class Round(val cards: List<Card>, val practice: Boolean, val seed: Long)

/** Keeps a round in progress through a turn of the phone, a trip to another tab or a concept page. */
private val RoundSaver = listSaver<Round?, Any>(
    save = { r ->
        if (r == null) {
            emptyList()
        } else {
            listOf<Any>(r.practice, r.seed) + r.cards.flatMap { c ->
                listOf<Any>(c.conceptId, c.box, c.due.toString(), c.lastReviewed?.toString().orEmpty(), c.right, c.wrong)
            }
        }
    },
    restore = { l ->
        if (l.size < 2) {
            null
        } else {
            val cards = l.drop(2).chunked(6).map { c ->
                Card(
                    conceptId = c[0] as String,
                    box = c[1] as Int,
                    due = LocalDate.parse(c[2] as String),
                    lastReviewed = (c[3] as String).ifEmpty { null }?.let { LocalDate.parse(it) },
                    right = c[4] as Int,
                    wrong = c[5] as Int,
                )
            }
            Round(cards, practice = l[0] as Boolean, seed = l[1] as Long)
        }
    },
)

/** Spaced retrieval: each concept comes back after 1, 3, 7, 16, 35 and 90 days. */
@Composable
fun ReviewScreen(state: AppState, today: LocalDate, nav: Nav) {
    val p = palette
    val library = Store.library
    var round by rememberSaveable(stateSaver = RoundSaver) { mutableStateOf<Round?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var answered by rememberSaveable { mutableStateOf<Int?>(null) }
    var right by rememberSaveable { mutableIntStateOf(0) }
    val current = round
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (current == null) {
            val due = Spacing.due(state, library, today)
            val cards = Spacing.cards(state, library)
            Headline("Review", "Quick questions on concepts you've met, timed to come back just as you'd start to forget.")
            if (due.isNotEmpty()) {
                Panel {
                    SectionLabel("Ready now", "🃏")
                    Text(if (due.size == 1) "1 card to review" else "${due.size} cards to review", style = MaterialTheme.typography.headlineSmall)
                    Text("Name the concept from an everyday story, or recall what the study found.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    PrimaryButton("Start review", p.brand) {
                        round = Round(due, practice = false, seed = today.toEpochDay() * 31)
                        index = 0; answered = null; right = 0
                    }
                }
            } else {
                Panel {
                    SectionLabel("All caught up", "✅")
                    val next = Spacing.nextDue(state, library, today)
                    Text(
                        when {
                            cards.isEmpty() -> "Your first review arrives the day after you meet your first concept."
                            next != null -> "Next review ${relativeDay(next, today)}."
                            else -> "Nothing scheduled."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (cards.isNotEmpty()) {
                        SoftButton("Practise anyway (doesn't change your schedule)") {
                            // A different handful each time, not the same five all day.
                            val seed = System.currentTimeMillis()
                            round = Round(cards.shuffled(kotlin.random.Random(seed)).take(5), practice = true, seed = seed)
                            index = 0; answered = null; right = 0
                        }
                    }
                }
            }
            if (cards.isNotEmpty()) RetentionPanel(cards)
        } else if (index >= current.cards.size) {
            Headline(if (current.practice) "Practice done" else "Review done")
            Panel {
                Text("$right of ${current.cards.size}", style = MaterialTheme.typography.displaySmall)
                Text(
                    when {
                        right == current.cards.size -> "A clean sweep."
                        current.practice -> "Practice doesn't change your schedule. Struggling to recall is what makes it stick."
                        else -> "Misses come back tomorrow. Struggling to recall is what makes it stick."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.size(4.dp))
                PrimaryButton("Done", p.brand) { round = null }
            }
        } else {
            val card = current.cards[index]
            val question = remember(card.conceptId, index, current) {
                Quiz.question(card, library, state.unlocked.keys, seed = current.seed + index)
            }
            val concept = library[card.conceptId]!!
            QuestionView(question, concept, index, current.cards.size, answered, onAnswer = { choice ->
                answered = choice
                val correct = choice == question.answer
                if (correct) right++
                if (!current.practice) Store.grade(card.conceptId, correct)
            }, onNext = {
                index++
                answered = null
            }, onOpen = { nav.openConcept(concept.id) }, onQuit = { round = null })
        }
    }
}

@Composable
private fun QuestionView(
    q: Question,
    concept: Concept,
    index: Int,
    total: Int,
    answered: Int?,
    onAnswer: (Int) -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
) {
    val p = palette
    val view = LocalView.current
    val accent = if (answered == null) p.brand else concept.category.accent(p.dark)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1} of $total", style = MaterialTheme.typography.labelLarge, color = p.muted, modifier = Modifier.weight(1f))
        Box(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = "End this round", onClick = onQuit),
            contentAlignment = Alignment.Center,
        ) {
            Text("End", style = MaterialTheme.typography.labelLarge, color = p.muted, modifier = Modifier.padding(8.dp))
        }
    }
    ProgressBar((index + if (answered != null) 1 else 0) / total.toFloat(), p.brand)
    Panel {
        SectionLabel(if (q.kind == Question.Kind.NAME_IT) "Name the concept" else "Remember the study", if (q.kind == Question.Kind.NAME_IT) "🔍" else "🧪", color = accent)
        if (q.kind == Question.Kind.RECALL) Text(concept.title, style = MaterialTheme.typography.titleSmall, color = concept.category.accent(p.dark))
        Text(q.prompt, style = Quote)
        q.options.forEachIndexed { i, text ->
            val state = when {
                answered == null -> OptionState.OPEN
                i == q.answer -> OptionState.RIGHT
                i == answered -> OptionState.WRONG
                else -> OptionState.DIM
            }
            OptionRow("ABCD"[i], text, state, accent) { view.tick(); onAnswer(i) }
        }
    }
    if (answered != null) {
        val correct = answered == q.answer
        Panel(border = concept.category.accent(p.dark).copy(alpha = 0.5f)) {
            Text(
                if (correct) "✓ Right" else "Not this time",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                color = if (correct) p.good else p.bad,
            )
            Text(concept.title, style = MaterialTheme.typography.headlineSmall)
            Text(concept.hook, style = MaterialTheme.typography.bodyMedium)
            Text("Open the concept →", style = MaterialTheme.typography.labelLarge, color = concept.category.accent(p.dark), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp))
        }
        PrimaryButton(if (index + 1 == total) "Finish" else "Next", p.brand, onClick = onNext)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color) {
    val p = palette
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.raised)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun RetentionPanel(cards: List<Card>) {
    val p = palette
    val fresh = cards.count { it.box <= 1 }
    val growing = cards.count { it.box in 2..3 }
    val strong = cards.count { it.box >= 4 }
    Panel {
        SectionLabel("How well they're sticking", "🌱")
        SplitBar(
            listOf(fresh to p.faint, growing to Palettes.evidence(com.rajan.mindfield.core.Evidence.GOOD, p.dark), strong to p.good),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("New", fresh, p.faint)
            Legend("Growing", growing, Palettes.evidence(com.rajan.mindfield.core.Evidence.GOOD, p.dark))
            Legend("Strong", strong, p.good)
        }
        val reviewed = cards.filter { it.right + it.wrong > 0 }
        if (reviewed.isNotEmpty()) {
            val rate = (reviewed.sumOf { it.right } * 100.0 / reviewed.sumOf { it.right + it.wrong }.coerceAtLeast(1)).roundToInt()
            Text("You've answered $rate% of review questions correctly.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

@Composable
fun Legend(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text("$label $value", style = MaterialTheme.typography.labelLarge, color = palette.muted)
    }
}

fun relativeDay(day: LocalDate, today: LocalDate): String = when (val d = ChronoUnit.DAYS.between(today, day)) {
    0L -> "today"
    1L -> "tomorrow"
    -1L -> "yesterday"
    in 2L..6L -> "on ${day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))}"
    else -> if (d > 0) "on ${day.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}" else day.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}

// --- Journal -----------------------------------------------------------------------------------

/** Every field note, newest first, with "On this day" from a week, a month and a year ago. */
@Composable
fun JournalScreen(state: AppState, today: LocalDate, nav: Nav) {
    val p = palette
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by rememberSaveable { mutableStateOf<Mode?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(60) }
    val q = query.trim().lowercase()
    val all = remember(state.entries) { state.liveEntries.sortedWith(compareByDescending<com.rajan.mindfield.core.Entry> { it.day }.thenByDescending { it.createdAt }) }
    // Lowercased once per change to the journal, not on every keystroke.
    val searchable = remember(all) { all.map { e -> e to (e.note + "\n" + Store.library[e.conceptId]?.title.orEmpty()).lowercase() } }
    val matching = remember(searchable, q, filter) {
        searchable.filter { (e, text) -> (filter == null || e.mode == filter) && (q.isEmpty() || text.contains(q)) }.map { it.first }
    }
    val scope = rememberCoroutineScope()
    // A long journal is too big to hand to another app as text, so it's saved as a file instead.
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = Texts.journal(Store.state.value, Store.library, Store.today())
                        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
                    }.isSuccess
                }
                Toast.makeText(context, if (saved) "Saved your field journal." else "Couldn't save the journal there.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val shown = matching.take(limit)
    val todayConcept = state.assignments[today]?.conceptId
    val looks = remember(state.entries, today) { Stats.onThisDay(state, today) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Headline("Field journal", "${all.size} ${if (all.size == 1) "note" else "notes"} · how psychology showed up in your days", Modifier.weight(1f))
                    if (all.isNotEmpty()) {
                        Pill("Export", p.brand, onClick = {
                            val text = Texts.journal(state, Store.library, today)
                            if (text.length <= SHARE_LIMIT) shareText(context, "Mindfield field journal", text)
                            else saveExport.launch("mindfield-journal-$today.txt")
                        })
                    }
                }
            }
            if (looks.isNotEmpty() && filter == null && q.isEmpty()) {
                item {
                    Panel(border = p.brand.copy(alpha = 0.35f)) {
                        SectionLabel("On this day", "🕰", color = p.brand)
                        looks.forEach { (label, e) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("$label · ${Store.library[e.conceptId]?.title.orEmpty()}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                                Text("“${e.note}”", style = Quote.copy(fontSize = 16.sp))
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (all.size > 5) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Search your notes", color = p.faint) },
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors(p.brand),
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("All", filter == null, p.brand) { filter = null }
                        Mode.entries.forEach { m -> ChoiceChip("${m.emoji} ${m.short}", filter == m, p.brand) { filter = if (filter == m) null else m } }
                    }
                }
            }
            if (all.isEmpty()) {
                item {
                    Panel {
                        Text("Your journal is empty, for now.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Each evening, log how today's concept showed up: spotted in someone else, caught in yourself, or used on purpose. In a few weeks you'll have a record of psychology in your own life.",
                            style = MaterialTheme.typography.bodyMedium, color = p.muted,
                        )
                    }
                }
            } else if (matching.isEmpty()) {
                item { Text("No notes match.", style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            var lastDay: LocalDate? = null
            shown.forEach { e ->
                if (e.day != lastDay) {
                    val d = e.day
                    item(key = "day-$d") {
                        Text(
                            dayHeading(d, today),
                            style = MaterialTheme.typography.labelLarge,
                            color = p.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    lastDay = d
                }
                item(key = e.id) { EntryRow(e, showConcept = true) { nav.log(LogRequest(e.conceptId, edit = e)) } }
            }
            if (matching.size > shown.size) {
                item {
                    SoftButton("Show older notes (${matching.size - shown.size} more)", Modifier.fillMaxWidth()) { limit += 60 }
                }
            }
        }
        if (todayConcept != null) {
            Text(
                "＋  Log",
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(p.brand)
                    .clickable(role = Role.Button) { nav.log(LogRequest(todayConcept)) }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                color = onColor(p.brand),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Sharing passes text through Android in one parcel of about 1 MB; past this a file is the safe way. */
private const val SHARE_LIMIT = 100_000

fun dayHeading(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(day, today)) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "d MMMM yyyy", Locale.ENGLISH))
}
