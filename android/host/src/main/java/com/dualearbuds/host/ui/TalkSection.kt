package com.dualearbuds.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.audio.NoiseCancel

/** Talk-over (mic mixed in, music 12 dB down) and smart feature #1, mic noise cancelling. */
@Composable
internal fun TalkSection(state: HostUiState, onTalk: (Boolean) -> Unit) {
    val context = LocalContext.current
    SectionLabel("Talk-over", if (state.talking) "mic on" else "mic off")
    Panel {
        ToggleRow(
            "Talk to your listeners",
            "Mixes this phone's mic into what they hear; the music drops 12 dB while it's on.",
            state.talking,
            onChange = onTalk
        )
        PanelDivider()
        PanelRow("Mic noise cancelling", "Removes fans, traffic and hum from your voice, on this phone.", trailing = {
            Segmented(
                options = NoiseCancel.entries.map { it to it.label },
                selected = state.prefs.noiseCancel,
                onSelect = { HostController.setNoiseCancel(context, it) }
            )
        })
    }
}
