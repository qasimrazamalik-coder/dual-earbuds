package com.dualearbuds.companion.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.calib.CalibrationResult
import com.dualearbuds.companion.calib.CalibrationRunner
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.Permissions
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.ReasonDialog
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.StepLine
import com.dualearbuds.companion.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.Locale

private sealed interface CalState {
    data object Ready : CalState
    data object Running : CalState
    data class Done(val result: CalibrationResult) : CalState
}

/** "Calibrate my earbuds" (docs/protocol.md v3.1): 8 clicks into the mic, stored per Bluetooth device name. */
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val earbuds by EarbudsMonitor.state.collectAsStateWithLifecycle()
    val latencies by SoundSettings.outputLatency.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<CalState>(CalState.Ready) }
    var askMic by remember { mutableStateOf(false) }
    val device = if (earbuds.connected && earbuds.name.isNotBlank()) earbuds.name else SoundSettings.PHONE_OUTPUT
    val saved = latencies[device]

    fun runNow() {
        state = CalState.Running
        scope.launch {
            PlaybackController.setTestMute(true)
            val r = try {
                CalibrationRunner.run()
            } finally {
                PlaybackController.setTestMute(false)
            }
            if (r is CalibrationResult.Ok) SoundSettings.setLatency(device, r.latencyMs)
            state = CalState.Done(r)
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runNow() else state = CalState.Done(
            CalibrationResult.Failed("The microphone is needed only to hear the clicks. Nothing is recorded or kept.", 0, 8)
        )
    }

    ScreenScaffold(title = "Calibrate my earbuds", onBack = onBack) {
        SectionCard(title = device) {
            Text(
                "Earbuds add their own delay. Measuring it lets this phone play in step with every other listener.",
                style = MaterialTheme.typography.bodyMedium
            )
            StepLine(1, "Sit somewhere quiet and put the volume at a normal level.")
            StepLine(2, "Take one earbud out and hold it right against the phone's microphone (bottom edge).")
            StepLine(3, "Tap Start. You'll hear 8 clicks — about 6 seconds.")
            if (!earbuds.connected) {
                Banner(text = "No Bluetooth earbuds connected — this will measure the phone's own speaker.", color = AppTheme.colors.warning)
            }
            Text(
                if (saved != null) String.format(Locale.US, "Saved: %.0f ms", saved) else "Not calibrated yet",
                style = MaterialTheme.typography.titleSmall,
                color = if (saved != null) AppTheme.colors.accentText else AppTheme.colors.textDim
            )
            when (val s = state) {
                CalState.Ready -> Unit
                CalState.Running -> {
                    Text("Listening for the clicks…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is CalState.Done -> when (val r = s.result) {
                    is CalibrationResult.Ok -> Banner(
                        text = String.format(Locale.US, "Done: %.0f ms (heard %d of %d clicks, spread %.0f ms). Saved for %s.", r.latencyMs, r.found, r.total, r.spreadMs, device),
                        color = AppTheme.colors.accentText
                    )
                    is CalibrationResult.Failed -> Banner(text = r.reason, color = AppTheme.colors.warning)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (Permissions.has(context, Manifest.permission.RECORD_AUDIO)) runNow() else askMic = true
                    },
                    enabled = state != CalState.Running
                ) { Text(if (state is CalState.Done) "Try again" else "Start") }
                if (saved != null) {
                    TextButton(onClick = { SoundSettings.setLatency(device, null) }, enabled = state != CalState.Running) { Text("Forget") }
                }
            }
        }
    }

    if (askMic) {
        ReasonDialog(
            title = "Use the microphone?",
            reason = "Only to hear the 8 clicks for a few seconds — nothing is recorded, kept or sent.",
            confirmLabel = "Allow",
            onConfirm = {
                askMic = false
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { askMic = false }
        )
    }
}
