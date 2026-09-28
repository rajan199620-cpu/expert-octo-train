package com.rajan.meditationtimer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val archiveDate = DateTimeFormatter.ofPattern("EEE, d MMM")

/** Today's principle, shown at the top of the Sit screen. */
@Composable
fun PrincipleCard(today: LocalDate, onOpenAll: () -> Unit) {
    val principle = Principles.forDate(today)
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Today’s principle  ·  Day ${Principles.dayNumber(today)}  ·  ${principle.theme}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        PrincipleBody(principle)
        TextButton(onClick = onOpenAll, contentPadding = PaddingValues(0.dp)) { Text("Earlier principles  ›") }
    }
}

@Composable
private fun PrincipleBody(p: Principle) {
    Text(p.title, style = MaterialTheme.typography.headlineSmall)
    Text(p.body, style = MaterialTheme.typography.bodyMedium)
    Text(
        "Try today: ${p.practice}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontStyle = FontStyle.Italic,
    )
    // The research behind it, quiet but always there.
    Column(Modifier.padding(top = 4.dp)) {
        Text("Research", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.finding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every principle reached so far, newest first; tomorrow's stays hidden until tomorrow. */
@Composable
fun PrinciplesScreen(onBack: () -> Unit) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    val entries = remember(today) { Principles.archive(today) }

    LazyColumn(Modifier.widthIn(max = 480.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Back") }
                Text("Principles", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "A ${Principles.ALL.size}-day course, one principle a day in teaching order, each based on " +
                        "published research. A new one appears every day; after the last, the course begins again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries, key = { it.first.toEpochDay() }) { (date, principle) ->
            GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                val label = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(archiveDate)
                }
                Text(
                    "$label  ·  Day ${Principles.dayNumber(date)}  ·  ${principle.theme}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                PrincipleBody(principle)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
