package com.dualearbuds.companion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.hearing.Ear
import com.dualearbuds.companion.hearing.HearingProfile
import com.dualearbuds.companion.hearing.HearingTestSession
import com.dualearbuds.companion.hearing.TonePlayer
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.UUID

/** Hearing profiles (smart-features #6): per-person tone test, applied per pair of earbuds. */
@Composable
fun HearingScreen(onBack: () -> Unit) {
    val profiles by SoundSettings.profiles.collectAsStateWithLifecycle()
    val assigned by SoundSettings.profileForEarbuds.collectAsStateWithLifecycle()
    val earbuds by EarbudsMonitor.state.collectAsStateWithLifecycle()
    val output = if (earbuds.connected && earbuds.name.isNotBlank()) earbuds.name else SoundSettings.PHONE_OUTPUT
    var testing by remember { mutableStateOf<HearingProfile?>(null) } // profile being (re)tested; id/name only
    var newName by remember { mutableStateOf("") }

    ScreenScaffold(title = "Hearing profiles", onBack = onBack) {
        Banner(text = "Not a medical hearing test. It only tunes the sound to what you hear best.", color = AppTheme.colors.warning)

        val t = testing
        if (t != null) {
            TestCard(t.name, onDone = { result ->
                SoundSettings.saveProfile(result.copy(id = t.id, name = t.name))
                if (assigned[output] == null) SoundSettings.assignProfile(output, t.id)
                testing = null
            }, onCancel = { testing = null })
            return@ScreenScaffold
        }

        SectionCard(title = "Use for $output") {
            ChoiceRow("None", selected = assigned[output] == null) { SoundSettings.assignProfile(output, null) }
            profiles.forEach { p ->
                ChoiceRow(p.name, selected = assigned[output] == p.id) { SoundSettings.assignProfile(output, p.id) }
            }
            Text("Each pair of earbuds remembers its own choice.", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
        }

        profiles.forEach { p ->
            SectionCard(title = p.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { testing = p }) { Text("Retest") }
                    TextButton(onClick = { SoundSettings.deleteProfile(p.id) }) { Text("Delete") }
                }
            }
        }

        SectionCard(title = "New profile") {
            Text("Takes about 3 minutes with earbuds in, in a quiet room.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.take(30) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    testing = HearingProfile(UUID.randomUUID().toString(), newName.trim().ifEmpty { "Me" }, List(7) { 0.0 }, List(7) { 0.0 }, 0)
                    newName = ""
                }
            ) { Text("Start test") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TestCard(name: String, onDone: (HearingProfile) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { HearingTestSession() }
    var tick by remember { mutableIntStateOf(0) } // bumps after every answer so the card recomposes
    var playing by remember { mutableStateOf(false) }

    fun playCurrent() {
        val c = session.current ?: return
        scope.launch {
            playing = true
            TonePlayer.play(c.first, c.second, c.third)
            playing = false
        }
    }

    LaunchedEffect(Unit) {
        PlaybackController.setTestMute(true)
        playCurrent()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { PlaybackController.setTestMute(false) }
    }

    SectionCard(title = "Hearing test · $name") {
        val c = session.current
        if (tick < 0 || c == null) {
            Text("Finished.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { session.result("", name, System.currentTimeMillis())?.let(onDone) }) { Text("Save profile") }
            return@SectionCard
        }
        Text("Tone ${session.step + 1} of ${session.totalSteps}", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textDim)
        LinearProgressIndicator(progress = { session.step.toFloat() / session.totalSteps }, modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${if (c.first == Ear.LEFT) "Left" else "Right"} ear · ${c.second.toInt()} Hz", style = MaterialTheme.typography.headlineSmall)
            Text("Did you hear the tone?", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { session.answer(true); tick++; playCurrent() }, enabled = !playing) { Text("I hear it") }
            OutlinedButton(onClick = { session.answer(false); tick++; playCurrent() }, enabled = !playing) { Text("Can't hear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { playCurrent() }, enabled = !playing) { Text("Play again") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
