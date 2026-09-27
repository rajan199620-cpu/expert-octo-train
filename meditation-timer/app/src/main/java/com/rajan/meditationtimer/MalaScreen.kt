package com.rajan.meditationtimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Japa counter. Tap the circle or press either volume key; a bell marks each full round. */
@Composable
fun MalaTab(mala: MalaCount, onTap: () -> Unit, onChange: (MalaCount) -> Unit) {
    // The screen must stay on for the volume keys to reach the app.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            delay(3_000)
            confirmReset = false
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Column(
        Modifier.widthIn(max = 480.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mala", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
        ChipRow(listOf(27, 54, 108), mala.target, { "$it beads" }) {
            onChange(mala.copy(target = it, beads = mala.beads.coerceAtMost(it - 1)))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val ringRadius = size.minDimension / 2 * 0.86f
                val beadRadius = (ringRadius * Math.PI / mala.target).toFloat().coerceIn(2.dp.toPx(), 7.dp.toPx()) * 0.7f
                for (i in 0 until mala.target) {
                    val angle = Math.toRadians(-90.0 + 360.0 * i / mala.target)
                    val at = Offset(center.x + ringRadius * cos(angle).toFloat(), center.y + ringRadius * sin(angle).toFloat())
                    drawCircle(if (i < mala.beads) primary else outline, radius = beadRadius, center = at)
                }
                // The guru bead marks where each round starts and ends.
                drawCircle(primary, radius = beadRadius * 2f, center = Offset(center.x, center.y - ringRadius - beadRadius * 3.5f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${mala.beads}", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                Text(
                    "of ${mala.target}  ·  round ${mala.rounds + 1}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Tap the circle or press a volume key — eyes can stay closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                if (confirmReset) {
                    onChange(MalaCount(target = mala.target))
                    confirmReset = false
                } else {
                    confirmReset = true
                }
            }) { Text(if (confirmReset) "Tap again to reset" else "Reset") }
        }
    }
}
