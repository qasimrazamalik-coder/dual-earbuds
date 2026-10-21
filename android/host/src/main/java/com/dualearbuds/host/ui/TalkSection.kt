package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.audio.NoiseCancel

/** Talk-over (mic mixed in, music ducked 12 dB) and smart feature #1, mic noise cancelling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalkSection(state: HostUiState, onTalk: (Boolean) -> Unit) {
    val context = LocalContext.current
    HostCard(title = "Talk-over") {
        SwitchRow(
            "Talk to your listeners",
            "Mixes this phone's mic into what they hear; the music gets quieter while it's on.",
            state.talking,
            onChange = onTalk
        )
        Text("Mic noise cancelling", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in NoiseCancel.entries) {
                FilterChip(
                    selected = state.prefs.noiseCancel == level,
                    onClick = { HostController.setNoiseCancel(context, level) },
                    label = { Text(level.label) }
                )
            }
        }
        Hint("Removes steady background noise (fans, traffic, hum) from your voice, on this phone. Light keeps more of the room.")
    }
}
