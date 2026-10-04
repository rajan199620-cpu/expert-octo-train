package com.rajan.meditationtimer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The Guide's topics, one open at a time: the list fits on a screen, and opening a topic
 * closes the last one so the sheet never grows into a wall of text.
 */
@Composable
fun GuideContent(onReplayWelcome: () -> Unit) {
    val topics = remember { Guide.topics(AutoBackup.LOCATION.takeIf { AutoBackup.supported }) }
    var open by rememberSaveable { mutableIntStateOf(-1) }
    Text(
        "How to sit, and how each part of the app works. Every screen also explains itself as you go.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GlassCard(Modifier.fillMaxWidth(), padding = 6.dp) {
        topics.forEachIndexed { i, topic ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Color.White.copy(alpha = 0.08f))
            val expanded = open == i
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { stateDescription = if (expanded) "Open" else "Closed" }
                        .clickable(role = Role.Button) { open = if (expanded) -1 else i }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(topic.title, style = MaterialTheme.typography.titleMedium)
                        Text(topic.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (expanded) "−" else "+",
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(expanded) {
                    Column(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (point in topic.points) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("•", color = MaterialTheme.colorScheme.primary)
                                Text(point, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    TextButton(onClick = onReplayWelcome, modifier = Modifier.fillMaxWidth()) { Text("Show the welcome again") }
}
