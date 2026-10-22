package com.dualearbuds.companion.ui.screens

import com.dualearbuds.companion.ui.components.SliderRow
import com.dualearbuds.companion.ui.components.QuietButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.audio.BufferMode
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.dsp.DialogueMode
import com.dualearbuds.companion.dsp.SmartEqMode
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.Segmented
import com.dualearbuds.companion.ui.theme.AppTheme
import kotlin.math.roundToInt

/** Settings → Smart features (docs/smart-features.md). Everything runs on the phone; each feature is optional. */
@Composable
fun SmartFeaturesScreen(onBack: () -> Unit, onOpenHearing: () -> Unit) {
    val smartEq by SoundSettings.smartEq.collectAsStateWithLifecycle()
    val dialogue by SoundSettings.dialogue.collectAsStateWithLifecycle()
    val eqManual by SoundSettings.eqManual.collectAsStateWithLifecycle()
    val buffer by PlaybackController.buffer.collectAsStateWithLifecycle()
    val player by PlaybackController.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Smart features", onBack = onBack) {
        Explain("Runs on this phone; nothing is sent anywhere. Each switch applies at once, playback keeps going.")

        SectionCard(title = "Smart EQ") {
            Explain("Picks the EQ from what is playing: by the app the host shares, by listening to the sound, or both.")
            Segmented(
                options = SmartEqMode.entries.map { it to it.label },
                selected = smartEq,
                onSelect = { SoundSettings.setSmartEq(it) }
            )
            if (eqManual != null && smartEq != SmartEqMode.OFF) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your manual EQ (${eqManual?.label}) wins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.warning,
                        modifier = Modifier.weight(1f)
                    )
                    QuietButton("Use Auto", onClick = { SoundSettings.setEqManual(null) })
                }
            } else if (player.running && smartEq != SmartEqMode.OFF) {
                Explain("Now: ${player.eqInUse.label}" + (player.soundClass?.let { " · hears ${it.name.lowercase()}" } ?: ""))
            }
        }

        SectionCard(title = "Dialogue boost") {
            Explain("Makes voices clearer over music and effects. Night also evens out loud and quiet scenes.")
            Segmented(
                options = DialogueMode.entries.map { it to it.label },
                selected = dialogue,
                onSelect = { SoundSettings.setDialogue(it) }
            )
        }

        SectionCard(title = "Smart buffer") {
            Explain(
                when (buffer.mode) {
                    BufferMode.SMART -> "Learns this Wi-Fi's hiccups and keeps the delay as low as is safe."
                    BufferMode.STEADY -> "Fixed rules: starts at 60 ms, grows after a dropout, shrinks slowly."
                    BufferMode.MANUAL -> "You choose the buffer. Lower = less delay, higher = fewer dropouts."
                }
            )
            Segmented(
                options = listOf(BufferMode.SMART to "Smart", BufferMode.STEADY to "Steady", BufferMode.MANUAL to "Manual"),
                selected = buffer.mode,
                onSelect = { PlaybackController.setBufferMode(it) }
            )
            if (buffer.mode == BufferMode.MANUAL) {
                SliderRow(
                    label = "Buffer",
                    valueText = "${buffer.manualMs} ms",
                    value = buffer.manualMs.toFloat(),
                    onValueChange = { PlaybackController.setManualBufferMs((it / 10f).roundToInt() * 10) },
                    valueRange = Prefs.MIN_MANUAL_BUFFER_MS.toFloat()..Prefs.MAX_MANUAL_BUFFER_MS.toFloat()
                )
            }
            if (player.synced) Explain("The host keeps everyone in sync right now, so the buffer only matters if sync drops.")
        }

        SectionCard(title = "Hearing profile") {
            Explain("A short tone test per ear that tunes the sound to you, saved per pair of earbuds. Not a medical hearing test.")
            QuietButton("Open hearing profiles", onClick = onOpenHearing)
        }
    }
}

@Composable
private fun Explain(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
}
