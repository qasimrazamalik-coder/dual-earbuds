package com.dualearbuds.companion.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.R
import com.dualearbuds.companion.net.ErrorKind
import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlayerState
import com.dualearbuds.companion.service.PlayerStatus
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.LevelMeter
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.StartStopButton
import com.dualearbuds.companion.ui.components.StatPill
import com.dualearbuds.companion.ui.components.TagChip
import com.dualearbuds.companion.ui.theme.AppTheme
import java.util.Locale

/** Headline + one line under it for every state the listener can be in. */
internal fun statusText(p: PlayerState): Pair<String, String> {
    val host = p.pcName.ifBlank { p.hostNoun }
    val err = p.error
    return when (p.status) {
        PlayerStatus.IDLE -> "Ready" to "Pick a nearby host, scan its QR code or paste its link."
        PlayerStatus.CONNECTING -> "Connecting…" to p.message
        PlayerStatus.BUFFERING -> (if (p.synced) "Syncing…" else "Buffering…") to "from $host"
        PlayerStatus.PLAYING -> (if (p.userMuted) "Muted" else "Playing") to "from $host"
        PlayerStatus.LAPTOP_PAUSED -> "Connected" to "$host isn't sharing sound right now."
        PlayerStatus.RECONNECTING -> "Reconnecting…" to p.message
        PlayerStatus.ERROR -> when (err?.kind) {
            ErrorKind.KICKED -> "Removed" to "${err?.message ?: "Removed by the host"}. Join again when the host lets you."
            ErrorKind.WRONG_PIN -> "Wrong PIN" to "Check the PIN with the host."
            ErrorKind.PIN_NEEDED -> "PIN needed" to "This room is locked with a PIN."
            ErrorKind.NOT_FOUND, ErrorKind.WIFI_OFF -> "Host not found" to ""
            ErrorKind.WRONG_KEY -> "Link expired" to ""
            else -> "Stopped" to ""
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerCard(player: PlayerState, onStartStop: () -> Unit, onEnterPin: () -> Unit) {
    val colors = AppTheme.colors
    SectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StartStopButton(running = player.running, enabled = true, onClick = onStartStop)
            Spacer(Modifier.height(12.dp))
            val (headline, sub) = statusText(player)
            Crossfade(targetState = headline, label = "headline") { h ->
                Text(h, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Who we listen to, and what they share.
        if (player.status != PlayerStatus.IDLE && player.pcName.isNotBlank()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HostChip(player.hostKind, player.pcName)
                if (player.party) TagChip("Party mode", color = MaterialTheme.colorScheme.secondary)
                if (player.source == "app" && player.sourceApp.isNotBlank()) TagChip("Sharing: ${player.sourceApp}")
                if (player.synced) TagChip("In sync", color = MaterialTheme.colorScheme.secondary)
            }
        }
        AnimatedVisibility(visible = player.talking && player.running) {
            Banner(text = "Host is speaking", color = colors.accentText, icon = painterResource(R.drawable.ic_volume_up))
        }

        val error = player.error
        if (error != null && (player.status == PlayerStatus.ERROR || player.status == PlayerStatus.RECONNECTING)) {
            val tint = if (error.kind == ErrorKind.KICKED || error.kind == ErrorKind.PIN_NEEDED) colors.warning else colors.danger
            Banner(text = error.message, color = tint)
            if (error.kind == ErrorKind.PIN_NEEDED || error.kind == ErrorKind.WRONG_PIN) {
                TextButton(onClick = onEnterPin, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Enter PIN") }
            }
        }

        if (player.running && player.status != PlayerStatus.CONNECTING) {
            LevelMeter(PlaybackController.level, active = player.status == PlayerStatus.PLAYING)
            if (player.remote) RemoteRow()
            StatsRow(player)
        }
    }
}

@Composable
private fun HostChip(kind: HostKind, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (kind == HostKind.PHONE) R.drawable.ic_phone_host else R.drawable.ic_laptop),
            contentDescription = kind.label,
            tint = AppTheme.colors.accentText,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        TagChip("${kind.label} · $name")
    }
}

/** v3.1 remote control (only when the host allows it). */
@Composable
private fun RemoteRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteButton("voldown", "Host volume down", R.drawable.ic_volume_down)
        RemoteButton("prev", "Previous", R.drawable.ic_prev)
        RemoteButton("playpause", "Play or pause on the host", R.drawable.ic_play_pause)
        RemoteButton("next", "Next", R.drawable.ic_next)
        RemoteButton("volup", "Host volume up", R.drawable.ic_volume_up)
    }
}

@Composable
private fun RemoteButton(action: String, label: String, icon: Int) {
    FilledTonalIconButton(onClick = { PlaybackController.sendRemote(action) }) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(player: PlayerState) {
    val colors = AppTheme.colors
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatPill("buffer ${player.targetMs} ms")
        StatPill("≈${player.latencyMs} ms delay")
        if (player.rate > 0) StatPill(String.format(Locale.US, "%.1f kHz", player.rate / 1000.0))
        if (player.synced) StatPill(String.format(Locale.US, "sync %+.0f ms", player.syncErrorMs))
        if (player.lost > 0) StatPill("${player.lost} lost", color = colors.warning)
        if (player.underruns > 0) StatPill("${player.underruns} underruns", color = colors.warning)
        if (player.eqInUse.name != "FLAT") StatPill("EQ ${player.eqInUse.label}")
    }
}
