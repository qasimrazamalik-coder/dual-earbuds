package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.server.ListenerInfo
import kotlin.math.roundToInt

/** Who is listening: name, earbuds and battery, sync state, underruns, volume and Remove. */
@Composable
internal fun ListenersSection(state: HostUiState) {
    HostCard(title = "Listeners · ${state.listeners.size} of ${state.maxClients}") {
        if (state.listeners.isEmpty()) {
            Hint("Nobody yet. On the other phone, scan the code with the Dual Earbuds app or the camera.")
            return@HostCard
        }
        state.listeners.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider()
            ListenerRow(l)
        }
    }
}

@Composable
private fun ListenerRow(l: ListenerInfo) {
    var confirm by remember(l.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(describe(l))
            }
            if (confirm) {
                TextButton(onClick = { HostController.kick(l.id) }) { Text("Remove?", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = { confirm = true }) { Text("Remove") }
            }
        }
        VolumeSlider(l)
    }
}

@Composable
private fun VolumeSlider(l: ListenerInfo) {
    // Follow the listener's own reports, except while the host is dragging.
    var dragging by remember(l.id) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: l.volume.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Volume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { HostController.setVolume(l.id, it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..150f,
            modifier = Modifier.weight(1f)
        )
        Text("${shown.roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
    }
}

private fun describe(l: ListenerInfo): String {
    val parts = ArrayList<String>()
    parts += when (l.client) {
        "web" -> "Browser"
        "windows" -> "Laptop"
        else -> "App"
    }
    when {
        l.earbuds.isNotBlank() -> parts += if (l.battery in 0..100) "${l.earbuds} ${l.battery}%" else l.earbuds
        l.battery in 0..100 -> parts += "earbuds ${l.battery}%"
    }
    parts += if (l.synced) "in sync" else "not synced"
    if (l.underruns > 0) parts += "${l.underruns} dropouts"
    if (l.rttMs >= 0) parts += "${l.rttMs} ms"
    if (!l.playing) parts += "paused"
    return parts.joinToString(" · ")
}
