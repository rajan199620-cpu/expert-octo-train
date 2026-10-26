package com.rajan.meditationtimer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * The day's reading, first thing when the app opens: one page, either today's lesson with all of
 * its research or one common problem with what to do about it (they take turns, a day each),
 * then on to the sit. Skip is always there; either way it won't show again until tomorrow.
 */
@Composable
fun ReadingScreen(lesson: Int, problem: Problem?, onDone: () -> Unit) {
    BackHandler { onDone() }
    val principle = Principles.forLesson(lesson)

    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Skip") }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (problem == null) {
                Text("Today's research", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Lesson ${Principles.lessonNumber(lesson)}  ·  ${principle.theme}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(principle.title, style = MaterialTheme.typography.headlineMedium)
                Text(principle.body, style = MaterialTheme.typography.bodyLarge)
                GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Text("Try today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(principle.practice, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                }
                StudyLine(Study(principle.finding, principle.source, principle.evidence))
            } else {
                Text("A common problem", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(problem.title, style = MaterialTheme.typography.headlineMedium)
                ProblemDetails(problem)
                Text(
                    "Every common problem is in the Guide on the Sit screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GradientButton("Continue", onClick = onDone, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** A problem's answer, what it does, what to do, and the studies behind it. */
@Composable
fun ProblemDetails(problem: Problem) {
    Column {
        Text(problem.question, style = MaterialTheme.typography.titleMedium)
        Text(problem.answer, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
    Labelled("What it does", problem.impact)
    Labelled("What to do", problem.whatToDo)
    for (study in problem.studies) StudyLine(study)
}

@Composable
private fun Labelled(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** What a study found, where it was published, and how much weight it can bear. */
@Composable
fun StudyLine(study: Study) {
    Column {
        Text(
            "Evidence: ${study.evidence.label}" + if (study.indirect) " · indirect: studied outside meditation" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(study.finding, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(study.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
