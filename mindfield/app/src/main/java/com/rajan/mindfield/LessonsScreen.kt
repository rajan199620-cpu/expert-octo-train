package com.rajan.mindfield

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Card
import com.rajan.mindfield.core.Lesson
import com.rajan.mindfield.core.Lessons
import com.rajan.mindfield.core.Rating
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.Texts
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The editor's state for a lesson not yet saved. */
private const val NEW = "new"

/** A new lesson asked for from outside the screen (the tile, the shortcut, shared text); [id] tells two apart. */
data class LessonDraft(val text: String, val id: Long)

/**
 * Your own lessons: what happened, and what you took from it, in your own words. Each becomes a
 * review card (see [Lesson] for why), so this is where they're written, edited and seen at a glance.
 */
@Composable
fun LessonsScreen(state: AppState, today: LocalDate, draft: LessonDraft? = null, onDraftUsed: () -> Unit = {}, onClose: () -> Unit) {
    val p = palette
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var situation by rememberSaveable { mutableStateOf("") }
    var lesson by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val lessons = remember(state.lessons) { state.liveLessons.sortedWith(compareByDescending<Lesson> { it.day }.thenByDescending { it.createdAt }) }
    val cards = remember(state.cards, state.lessons) { Spacing.cards(state, Store.library).filter { Lessons.isLesson(it.conceptId) }.associateBy { it.conceptId } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A lesson deleted on another phone while it was open here closes the editor.
    LaunchedEffect(lessons, editing) { if (editing != null && editing != NEW && lessons.none { it.id == editing }) editing = null }
    val situationFocus = remember { FocusRequester() }
    val lessonFocus = remember { FocusRequester() }
    // Bumped to put the cursor in the first empty box once the editor is on screen.
    var focusAsk by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusAsk) {
        if (focusAsk == 0) return@LaunchedEffect
        list.scrollToItem(0)
        // The editor appears in the frame after the tap; focus it once it's there.
        withFrameNanos { }
        runCatching { (if (situation.isBlank() || lesson.isNotBlank()) situationFocus else lessonFocus).requestFocus() }
    }
    fun open(id: String, s: String, l: String, focus: Boolean = false) {
        editing = id; situation = s; lesson = l; confirmDelete = false
        if (focus) focusAsk++ else scope.launch { list.animateScrollToItem(0) }
    }
    // A new lesson from the tile, the shortcut or shared text. Shared into a lesson already being
    // written, the text is added to it rather than replacing it.
    LaunchedEffect(draft?.id) {
        val d = draft ?: return@LaunchedEffect
        if (editing == NEW && (situation.isNotBlank() || lesson.isNotBlank())) {
            Lessons.addShared(situation, lesson, d.text).let { (s, l) -> situation = s; lesson = l }
            focusAsk++
        } else {
            val (s, l) = Lessons.fromShared(d.text)
            open(NEW, s, l, focus = true)
        }
        onDraftUsed()
    }
    var tileOffered by remember { mutableStateOf(QuickAdd.canOfferTile(context)) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow(onClose) }
        item {
            Headline(
                "My lessons",
                "Your own experiences, in your own words. Each comes back in review: first what happened, then you recall what you took from it.",
            )
        }
        item {
            val id = editing
            if (id == null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Add a lesson", p.brand) { open(NEW, "", "", focus = true) }
                    if (tileOffered) {
                        // One tap from the notification panel next time, wherever you are.
                        SoftButton("Add an “Add lesson” button to quick settings", Modifier.fillMaxWidth(), color = p.muted) {
                            QuickAdd.requestTile(context) { added -> if (added) tileOffered = false }
                        }
                    }
                }
            } else {
                Panel(border = p.brand.copy(alpha = 0.5f)) {
                    SectionLabel(if (id == NEW) "New lesson" else "Edit lesson", Glyph.PEN, color = p.brand)
                    Text("What happened", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = situation,
                        onValueChange = { situation = Texts.cap(situation, it, Lessons.SITUATION_MAX) },
                        modifier = Modifier.fillMaxWidth().focusRequester(situationFocus),
                        placeholder = { Text("The situation, in a line or two", color = p.faint) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(p.brand),
                    )
                    Text("The lesson", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = lesson,
                        onValueChange = { lesson = Texts.cap(lesson, it, Lessons.LESSON_MAX) },
                        modifier = Modifier.fillMaxWidth().focusRequester(lessonFocus),
                        placeholder = { Text("What you'll remember, or do next time", color = p.faint) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(p.brand),
                    )
                    Text(
                        "Both in your own words: writing it yourself is what makes it stick, and the situation is what brings it back in review.",
                        style = MaterialTheme.typography.bodySmall, color = p.muted,
                    )
                    PrimaryButton("Save lesson", p.brand, enabled = situation.isNotBlank() && lesson.isNotBlank()) {
                        if (id == NEW) Store.addLesson(situation, lesson) else Store.editLesson(id, situation, lesson)
                        editing = null
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Cancel", color = p.muted) { editing = null }
                        if (id != NEW) {
                            // Two taps: a lesson is your own words, and can't be brought back once gone.
                            SoftButton(if (confirmDelete) "Tap again to delete" else "Delete", color = p.bad) {
                                if (confirmDelete) {
                                    Store.deleteLesson(id)
                                    editing = null
                                } else {
                                    confirmDelete = true
                                }
                            }
                        }
                    }
                }
            }
        }
        if (lessons.isEmpty() && editing == null) {
            item {
                Panel {
                    Text("No lessons yet.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "For example: what happened, “Agreed to a deadline in the meeting, then couldn't meet it.” The lesson, “Ask for a day to check before committing.”",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted,
                    )
                }
            }
        }
        items(lessons, key = { it.id }) { l ->
            LessonRow(l, cards[Lessons.cardId(l.id)], today, editing = editing == l.id) { open(l.id, l.situation, l.lesson) }
        }
    }
}

@Composable
private fun LessonRow(lesson: Lesson, card: Card?, today: LocalDate, editing: Boolean, onEdit: () -> Unit) {
    val p = palette
    Panel(border = if (editing) p.brand.copy(alpha = 0.5f) else null, onClick = onEdit, onClickLabel = "Edit") {
        Text(lesson.situation, style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("“${lesson.lesson}”", style = Quote.copy(fontSize = 16.sp))
        val due = card?.due
        Text(
            lesson.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " · " + when {
                due == null -> ""
                due <= today -> "due for review"
                else -> "next review ${relativeDay(due, today)}"
            },
            style = MaterialTheme.typography.labelMedium, color = p.faint,
        )
    }
}

/** The Journal's way into your lessons: one slim row, so the notes and filters stay near the top. */
@Composable
fun LessonsCard(state: AppState, nav: Nav) {
    val p = palette
    val lessons = state.liveLessons
    Panel(onClick = nav.lessons, onClickLabel = "Open my lessons", padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphIcon(Glyph.BULB, p.brand, 22.dp)
            Column(Modifier.weight(1f)) {
                Text("My lessons", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (lessons.size) {
                        0 -> "Your own experiences and what you took from them, back in review so they stick"
                        1 -> "1 lesson"
                        else -> "${lessons.size} lessons"
                    },
                    style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            GlyphIcon(Glyph.CHEVRON_RIGHT, p.brand, 16.dp)
        }
    }
}

/** A lesson in review: the situation, then your lesson on request, then how well you recalled it. */
@Composable
fun LessonQuestion(lesson: Lesson, index: Int, total: Int, revealed: Boolean, onReveal: () -> Unit, onRate: (Rating) -> Unit, onQuit: () -> Unit) {
    val p = palette
    RoundHeader(index, total, onQuit)
    ProgressBar((index + if (revealed) 1 else 0) / total.toFloat(), p.brand)
    Panel {
        SectionLabel("Your lesson", Glyph.BULB, color = p.brand)
        Text("Written " + lesson.day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.labelLarge, color = p.muted)
        Text(lesson.situation, style = Quote)
        if (!revealed) Text("What did you take from it? Say it to yourself before you look.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
    }
    if (!revealed) {
        PrimaryButton("Show my lesson", p.brand, onClick = onReveal)
    } else {
        Panel(border = p.brand.copy(alpha = 0.5f)) {
            SectionLabel("The lesson", Glyph.CHECK, color = p.brand)
            Text("“${lesson.lesson}”", style = Quote, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("How well did you remember it?", style = MaterialTheme.typography.titleSmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(Rating.GOOD.label, p.brand) { onRate(Rating.GOOD) }
            SoftButton(Rating.HARD.label, Modifier.fillMaxWidth()) { onRate(Rating.HARD) }
            SoftButton(Rating.AGAIN.label, Modifier.fillMaxWidth(), color = p.bad) { onRate(Rating.AGAIN) }
        }
    }
}
