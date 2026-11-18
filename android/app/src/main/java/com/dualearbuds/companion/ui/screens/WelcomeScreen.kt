package com.dualearbuds.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.ui.components.ActionRow
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.SecondaryButton
import com.dualearbuds.companion.ui.components.TopBar
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

/**
 * First-run guide: three compact steps (pair, join or share, calibrate). Shown once on a fresh install; Settings
 * → Help → Getting started opens it again. [onBack] is null on first run (Done/Skip leave it instead).
 */
@Composable
fun WelcomeScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onJoin: () -> Unit,
    onShareThisPhone: () -> Unit,
    onCalibrate: () -> Unit
) {
    val context = LocalContext.current
    // Shown once: leaving with the system back button counts too.
    LaunchedEffect(Unit) { Prefs.firstRunGuideSeen = true }
    fun finish(then: () -> Unit) {
        Prefs.firstRunGuideSeen = true
        then()
    }
    Scaffold(
        containerColor = AppTheme.colors.bg,
        topBar = {
            TopBar(title = "Getting started", onBack = onBack, readout = "3 steps") {
                if (onBack == null) QuietButton("Skip", onClick = { finish(onDone) }, color = AppTheme.colors.muted)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "Play a laptop's or a phone's sound on these earbuds, in sync",
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.text,
                modifier = Modifier.padding(top = 20.dp).semantics { heading() }
            )
            Section("Steps", count = "3") {
                Panel {
                    WelcomeStep(1, "Pair your earbuds", "Pair them in the phone's Bluetooth settings. Each phone plays on its own pair.") {
                        SecondaryButton("Bluetooth settings", onClick = { BackgroundSettings.openBluetoothSettings(context) })
                    }
                    PanelDivider()
                    WelcomeStep(
                        2,
                        "Join, or share this phone",
                        "On Home, scan the QR code or paste the link from the laptop or the sharing phone, or tap it under Nearby. " +
                            "To send this phone's sound to others instead, use Share this phone."
                    ) {
                        SecondaryButton("Join", onClick = { finish(onJoin) })
                        QuietButton("Share this phone", onClick = { finish(onShareThisPhone) })
                    }
                    PanelDivider()
                    WelcomeStep(3, "Calibrate", "Hold one earbud to the phone's microphone. 8 clicks measure the earbuds' delay so every ear hears the same moment.") {
                        SecondaryButton("Calibrate", onClick = { finish(onCalibrate) })
                    }
                }
            }
            Hint("Questions later? Tap ? on Home to ask.", modifier = Modifier.padding(top = 12.dp))
            PrimaryButton("Done", onClick = { finish(onDone) }, modifier = Modifier.padding(top = 16.dp, bottom = 32.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun WelcomeStep(number: Int, title: String, detail: String, actions: @Composable () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "$number",
            style = Mono.value,
            color = c.muted,
            modifier = Modifier.width(20.dp).padding(top = 2.dp).clearAndSetSemantics { contentDescription = "Step $number of 3" }
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
            ActionRow(Modifier.padding(top = 4.dp)) { actions() }
        }
    }
}
