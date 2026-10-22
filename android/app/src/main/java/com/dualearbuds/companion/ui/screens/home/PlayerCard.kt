package com.dualearbuds.companion.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.R
import com.dualearbuds.companion.audio.sync.LatencySource
import com.dualearbuds.companion.net.ErrorKind
import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlayerState
import com.dualearbuds.companion.service.PlayerStatus
import com.dualearbuds.companion.ui.components.ActionRow
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.LevelMeter
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.ScopeMath
import com.dualearbuds.companion.ui.components.SecondaryButton
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.StatusWord
import com.dualearbuds.companion.ui.components.SyncGrade
import com.dualearbuds.companion.ui.components.SyncScope
import com.dualearbuds.companion.ui.components.Tone
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono
import java.util.Locale

/** State headline + one line under it for every state the listener can be in. Plain, specific. */
internal fun statusText(p: PlayerState): Pair<String, String> {
    val host = p.pcName.ifBlank { p.hostNoun }
    val err = p.error
    return when (p.status) {
        PlayerStatus.IDLE -> "Not playing" to "Pick a nearby host, scan its QR code or paste its link."
        PlayerStatus.CONNECTING -> "Connecting" to p.message
        PlayerStatus.BUFFERING -> (if (p.synced) "Syncing to $host" else "Buffering from $host") to ""
        PlayerStatus.PLAYING -> (if (p.userMuted) "Muted, $host" else "Playing from $host") to ""
        PlayerStatus.LAPTOP_PAUSED -> "Connected to $host" to "$host isn't sharing sound right now."
        PlayerStatus.RECONNECTING -> "Reconnecting to $host" to p.message
        PlayerStatus.ERROR -> when (err?.kind) {
            ErrorKind.KICKED -> "Removed by the host" to "Join again when the host lets you."
            ErrorKind.WRONG_PIN -> "Wrong PIN" to "Check the PIN with the host."
            ErrorKind.PIN_NEEDED -> "PIN needed" to "This room is locked with a PIN."
            ErrorKind.NOT_FOUND, ErrorKind.WIFI_OFF -> "Host not found" to ""
            ErrorKind.WRONG_KEY -> "Link expired" to "Scan the host's new QR code."
            else -> "Stopped" to ""
        }
    }
}

private fun statusTone(p: PlayerState): Tone = when (p.status) {
    PlayerStatus.PLAYING -> if (p.userMuted) Tone.IDLE else Tone.LIVE
    PlayerStatus.BUFFERING, PlayerStatus.CONNECTING, PlayerStatus.RECONNECTING -> Tone.WARN
    PlayerStatus.LAPTOP_PAUSED, PlayerStatus.IDLE -> Tone.IDLE
    PlayerStatus.ERROR -> Tone.BAD
}

private fun statusWord(p: PlayerState): String = when (p.status) {
    PlayerStatus.PLAYING -> if (p.userMuted) "Muted" else "Live"
    PlayerStatus.BUFFERING -> if (p.synced) "Syncing" else "Buffering"
    PlayerStatus.CONNECTING -> "Connecting"
    PlayerStatus.RECONNECTING -> "Reconnecting"
    PlayerStatus.LAPTOP_PAUSED -> "No sound shared"
    PlayerStatus.IDLE -> "Idle"
    PlayerStatus.ERROR -> "Stopped"
}

private fun hostLabel(kind: HostKind): String = when (kind) {
    HostKind.LAPTOP -> "Laptop (Windows)"
    HostKind.PHONE -> "Phone (Android)"
    HostKind.UNKNOWN -> "Host"
}

private fun ms(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.0f ms", v)

/** Now playing: state headline, live meter, the sync scope, and the numbers that explain delay. */
@Composable
internal fun NowPlayingSection(player: PlayerState, onStop: () -> Unit, onRetry: () -> Unit, onEnterPin: () -> Unit) {
    val c = AppTheme.colors
    val (headline, sub) = statusText(player)
    Section("Now playing", count = if (player.hostKind != HostKind.UNKNOWN) player.hostKind.label else null) {
        Panel {
            PanelBody {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusWord(statusTone(player), statusWord(player))
                    if (player.party) {
                        Spacer(Modifier.width(12.dp))
                        StatusWord(Tone.IDLE, "Party")
                    }
                    if (player.talking && player.running) {
                        Spacer(Modifier.width(12.dp))
                        StatusWord(Tone.LIVE, "Host talking")
                    }
                }
                Text(headline, style = MaterialTheme.typography.headlineSmall, color = c.text)
                if (sub.isNotBlank()) Hint(sub)
                if (player.source == "app" && player.sourceApp.isNotBlank()) Hint("Sharing only ${player.sourceApp}")
                val error = player.error
                if (error != null && (player.status == PlayerStatus.ERROR || player.status == PlayerStatus.RECONNECTING)) {
                    val tint = if (error.kind == ErrorKind.KICKED || error.kind == ErrorKind.PIN_NEEDED) c.warn else c.bad
                    Banner(text = error.message, color = tint)
                }
                if (player.running && player.status != PlayerStatus.CONNECTING) {
                    LevelMeter(PlaybackController.level, active = player.status == PlayerStatus.PLAYING)
                }
                if (player.powerSave && player.running) Hint("Battery saver is on. Turn it off if the sound stutters.", color = c.warn)
                ActionRow {
                    when {
                        player.running -> SecondaryButton("Stop", onStop)
                        error?.kind == ErrorKind.PIN_NEEDED || error?.kind == ErrorKind.WRONG_PIN -> PrimaryButton("Enter PIN", onEnterPin)
                        player.target != null && error?.kind != ErrorKind.KICKED -> PrimaryButton("Try again", onRetry)
                    }
                }
            }

            if (player.running && player.status != PlayerStatus.CONNECTING) {
                PanelDivider()
                SyncRow(player)
                PanelDivider()
                PanelRow("Connection", value = player.transport.ifBlank { "—" }, valueColor = if (player.transport == "UDP") c.accentText else c.muted)
                PanelDivider()
                PanelRow("Host", value = hostLabel(player.hostKind) + if (player.hostVersion.isNotBlank()) " ${player.hostVersion}" else "")
                PanelDivider()
                PanelRow(
                    "Common deadline",
                    subtitle = "When every ear plays a sound, after the host captured it",
                    value = if (player.playoutMs > 0) "${player.playoutMs} ms" else "—"
                )
                PanelDivider()
                PanelRow(
                    "This phone needs",
                    subtitle = "Network jitter + earbuds delay + 10 ms, sent to the host",
                    value = player.needMs?.let { "$it ms" } ?: "—"
                )
                PanelDivider()
                val src = when (player.latencySource) {
                    LatencySource.CALIBRATED -> "calibrated"
                    LatencySource.TIMESTAMP -> "measured"
                    LatencySource.DEFAULT_BLUETOOTH -> "estimate, calibrate"
                    LatencySource.DEFAULT_WIRED -> "estimate"
                    null -> ""
                }
                PanelRow("Output delay", value = ms(player.usedLatencyMs) + if (src.isNotBlank()) " · $src" else "")
                PanelDivider()
                PanelRow(
                    "Buffer",
                    value = "${player.bufferMs}/${player.targetMs} ms" + if (player.rate > 0) " · ${ScopeMath.kHz(player.rate)}" else ""
                )
                if (player.lost > 0 || player.underruns > 0 || player.concealed > 0) {
                    PanelDivider()
                    PanelRow(
                        "Network",
                        value = "lost ${player.lost} · filled ${player.concealed} · gaps ${player.underruns}",
                        valueColor = if (player.underruns > 0) c.warn else c.muted
                    )
                }
                if (player.remote) {
                    PanelDivider()
                    RemoteRow()
                }
            }
        }
    }
}

/** The signature element: this phone's offset from the common deadline on a ±50 ms scope. */
@Composable
private fun SyncRow(player: PlayerState) {
    val c = AppTheme.colors
    val offset = if (player.synced) player.syncErrorMs else null
    val grade = ScopeMath.grade(offset)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            val word = when (grade) {
                SyncGrade.IN_SYNC -> "in sync"
                SyncGrade.CLOSE -> ScopeMath.direction(offset)
                SyncGrade.OFF -> ScopeMath.direction(offset)
                SyncGrade.UNKNOWN -> "not synced"
            }
            Text(
                if (offset != null) "${ScopeMath.signedMs(offset)}  $word" else word,
                style = Mono.large,
                color = when (grade) {
                    SyncGrade.IN_SYNC -> c.text
                    SyncGrade.CLOSE -> c.warn
                    SyncGrade.OFF -> c.bad
                    SyncGrade.UNKNOWN -> c.muted
                }
            )
        }
        SyncScope(offset)
        if (!player.synced) Hint("Plays on the adaptive buffer until the host's clock is known.")
    }
}

/** v3.1 remote control (only when the host allows it). */
@Composable
private fun RemoteRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
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
    IconButton(onClick = { PlaybackController.sendRemote(action) }) {
        Icon(painterResource(icon), contentDescription = label, tint = AppTheme.colors.text, modifier = Modifier.size(18.dp))
    }
}
