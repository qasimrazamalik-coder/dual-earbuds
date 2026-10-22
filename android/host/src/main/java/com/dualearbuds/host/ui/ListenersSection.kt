package com.dualearbuds.host.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.model.Readout
import com.dualearbuds.host.server.ListenerInfo
import com.dualearbuds.host.server.LocalOutputKind
import com.dualearbuds.host.setup.SetupCheck
import kotlin.math.roundToInt

private val Motion = tween<Float>(durationMillis = 140)

/**
 * Every ear, one row each: this phone first, then the listeners. Each row has the sync scope (offset
 * from the common deadline), how audio gets there (UDP / WS / Browser) and what it needs, in mono.
 * Tap a listener for its volume and Remove.
 */
@Composable
internal fun ListenersSection(state: HostUiState) {
    SectionLabel("Ears", "${state.listeners.size} of ${state.maxClients} listeners")
    Panel {
        OwnEarRow(state)
        for (l in state.listeners) {
            PanelDivider()
            ListenerRow(l)
        }
        if (state.listeners.isEmpty()) {
            PanelDivider()
            PanelRow(
                "Nobody yet",
                "Phones: scan the code with Dual Earbuds or the camera. Any browser: open the link."
            )
        }
    }
}

/**
 * This phone's own ear. The shared app plays here at once and can't be delayed, so when a slower
 * listener pulls P above this phone's own need, this ear is early by the difference.
 */
@Composable
private fun OwnEarRow(state: HostUiState) {
    val out = state.localOutput
    val need = out?.needMs
    val offset = if (need != null && state.playoutMs > 0) need - state.playoutMs else null
    val what = if (out == null) {
        "Not sharing"
    } else {
        when (out.kind) {
            LocalOutputKind.BLUETOOTH -> out.name.ifBlank { "Bluetooth earbuds" } + if (out.isCalibrated) "" else " · not calibrated"
            LocalOutputKind.WIRED -> out.name.ifBlank { "Wired headphones" }
            LocalOutputKind.SPEAKER -> "Speaker"
        }
    }
    EarRow(
        title = "This phone",
        subtitle = what,
        offsetMs = offset,
        tag = "Host",
        figures = if (need != null) "own $need ms" else ""
    )
}

@Composable
private fun ListenerRow(l: ListenerInfo) {
    var open by rememberSaveable(l.id) { mutableStateOf(false) }
    Column {
        EarRow(
            title = l.displayName,
            subtitle = subtitle(l),
            offsetMs = if (l.synced) l.syncErrMs else null,
            tag = Readout.transport(l),
            figures = figures(l),
            warn = SetupCheck.isPoorLink(l),
            modifier = Modifier.clickable { open = !open }
        )
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(Motion) + expandVertically(tween(140)),
            exit = fadeOut(Motion) + shrinkVertically(tween(140))
        ) {
            ListenerControls(l)
        }
    }
}

@Composable
private fun EarRow(
    title: String,
    subtitle: String,
    offsetMs: Int?,
    tag: String,
    figures: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false
) {
    val t = tokens
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.rowTitle, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Mono(tag, color = t.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncScope(offsetMs)
            Spacer(Modifier.width(8.dp))
            Mono(Readout.offset(offsetMs), Modifier.width(56.dp), color = t.text)
            Mono(figures, Modifier.weight(1f), color = if (warn) t.warn else t.muted)
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = Type.hint, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "need 142 ms · rtt 8 ms · 3 lost" in mono. */
private fun figures(l: ListenerInfo): String {
    val parts = ArrayList<String>(4)
    l.needMs?.let { parts += "need $it ms" }
    if (l.rttMs >= 0) parts += "rtt ${l.rttMs} ms"
    if (l.underruns > 0) parts += "${l.underruns} gaps"
    if (l.dropped > 0) parts += "${l.dropped} late"
    return parts.joinToString(" · ")
}

/** Earbuds, battery, playing state, or why there is no sync marker. */
private fun subtitle(l: ListenerInfo): String {
    val parts = ArrayList<String>(4)
    if (l.earbuds.isNotBlank()) parts += if (l.battery in 0..100) "${l.earbuds} ${l.battery}%" else l.earbuds
    else if (l.battery in 0..100) parts += "earbuds ${l.battery}%"
    if (!l.synced) parts += "buffer mode, not on the deadline"
    if (!l.playing) parts += "paused"
    if (l.volume != 100) parts += "volume ${l.volume}%"
    return parts.joinToString(" · ")
}

@Composable
private fun ListenerControls(l: ListenerInfo) {
    // Follow the listener's own reports, except while the host is dragging.
    var dragging by remember(l.id) { mutableStateOf<Float?>(null) }
    var confirm by remember(l.id) { mutableStateOf(false) }
    val shown = dragging ?: l.volume.toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ValueSlider(
            value = shown,
            onChange = { dragging = it },
            onDone = {
                dragging?.let { HostController.setVolume(l.id, it.roundToInt()) }
                dragging = null
            },
            range = 0f..150f,
            valueText = "${shown.roundToInt()}%",
            modifier = Modifier.weight(1f),
            description = "Volume for ${l.displayName}"
        )
        Spacer(Modifier.width(4.dp))
        if (confirm) {
            RowAction("Remove ${l.displayName.take(12)}?", onClick = { HostController.kick(l.id) }, danger = true)
        } else {
            RowAction("Remove", onClick = { confirm = true })
        }
    }
}
