package com.rajan.meditationtimer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * The day's reading, first thing when the app opens: today's lesson with all of its research,
 * Next, then one common problem with what to do about it, then on to the sit. Skip is always
 * there; either way it won't show again until tomorrow.
 */
@Composable
fun ReadingScreen(lesson: Int, problem: Problem, onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler { if (page > 0) page-- else onDone() }
    val principle = Principles.forLesson(lesson)

    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("‹ Back") } else Spacer(Modifier.size(48.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                repeat(2) { i ->
                    Box(
                        Modifier
                            .size(if (i == page) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (i <= page) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f)),
                    )
                }
            }
            TextButton(onClick = onDone) { Text("Skip") }
        }
        AnimatedContent(
            page,
            Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeIn(tween(350, delayMillis = 80)) togetherWith fadeOut(tween(150)) },
            label = "reading",
        ) { shown ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (shown == 0) {
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
        }
        GradientButton(
            if (page == 0) "Next" else "Continue",
            onClick = { if (page == 0) page = 1 else onDone() },
            modifier = Modifier.padding(vertical = 8.dp),
        )
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
