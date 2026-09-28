package com.rajan.meditationtimer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId

/** Today's lesson index: one lesson per distinct day you have sat, derived from the history. */
@Composable
fun rememberLessonIndex(): Int {
    val context = LocalContext.current
    val records by remember { SessionLog.get(context).records }.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    return remember(records, today) {
        val zone = ZoneId.systemDefault()
        Principles.lessonIndex(records.map { it.day(zone) }.toSet(), today)
    }
}

/**
 * Today's principle at the top of the Sit screen, kept short so Begin stays close: the title
 * and what to try. Tap for why it works and the research behind it.
 */
@Composable
fun PrincipleCard(lesson: Int, onOpenAll: () -> Unit) {
    val principle = Principles.forLesson(lesson)
    var expanded by rememberSaveable(lesson) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }, padding = 16.dp) {
        Text(
            "Lesson ${Principles.lessonNumber(lesson)}  ·  ${principle.theme}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(principle.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Try today: ${principle.practice}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontStyle = FontStyle.Italic,
        )
        if (expanded) PrincipleDetails(principle)
        Row {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Less" else "Why & research")
            }
            Spacer(Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = onOpenAll, contentPadding = PaddingValues(0.dp)) { Text("Earlier lessons  ›") }
        }
    }
}

@Composable
private fun PrincipleDetails(p: Principle) {
    Text(p.body, style = MaterialTheme.typography.bodyMedium)
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            "Evidence: ${p.evidence.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(p.finding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every lesson reached so far, newest first; later lessons stay hidden until you get there. */
@Composable
fun PrinciplesScreen(onBack: () -> Unit) {
    val lesson = rememberLessonIndex()
    val entries = remember(lesson) { Principles.archive(lesson) }

    LazyColumn(Modifier.widthIn(max = 480.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Back") }
                Text("Principles", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "A ${Principles.ALL.size}-lesson course in teaching order, each based on published research and " +
                        "tagged with how strong that evidence is. You move on one lesson for each day you sit, " +
                        "so a missed day never skips a lesson. After the last, the course begins again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries, key = { it.first }) { (index, principle) ->
            GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Text(
                    (if (index == lesson) "Today  ·  " else "") + "Lesson ${Principles.lessonNumber(index)}  ·  ${principle.theme}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(principle.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Try: ${principle.practice}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic,
                )
                PrincipleDetails(principle)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
