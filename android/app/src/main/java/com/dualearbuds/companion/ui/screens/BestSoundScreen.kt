package com.dualearbuds.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

private class Tip(val title: String, val detail: String, val action: String? = null, val onAction: ((android.content.Context) -> Unit)? = null)

/** "For the best sound": five plain habits, each with the setting it needs. */
@Composable
fun BestSoundScreen(onBack: () -> Unit, onOpenCalibration: () -> Unit) {
    val context = LocalContext.current
    val tips = listOf(
        Tip(
            "Use a hotspot or 5 GHz Wi-Fi",
            "2.4 GHz is crowded and shares the air with Bluetooth earbuds. A phone hotspot or 5 GHz Wi-Fi drops fewer packets.",
            "Wi-Fi"
        ) { BackgroundSettings.openWifiSettings(it) },
        Tip(
            "Calibrate each pair of earbuds",
            "Every model adds its own delay. Once measured, all pairs hear the same moment.",
            "Calibrate"
        ) { onOpenCalibration() },
        Tip(
            "Keep the phones in one room",
            "Close to the host or the router means fewer lost packets and a smaller buffer."
        ),
        Tip(
            "Prefer AAC or SBC over LDAC",
            "LDAC adds delay. Pick the codec in Developer options → Bluetooth audio codec.",
            "Developer options"
        ) { BackgroundSettings.openDeveloperOptions(it) },
        Tip(
            "Keep this app's battery unrestricted",
            "App info → Battery → Unrestricted, so Android doesn't pause the sound with the screen off.",
            "App info"
        ) { BackgroundSettings.openAppDetails(it) }
    )
    ScreenScaffold(title = "For the best sound", onBack = onBack) {
        Section("Do this", count = "${tips.size}") {
            Panel {
                tips.forEachIndexed { i, tip ->
                    if (i > 0) PanelDivider()
                    TipRow(i + 1, tip) { tip.onAction?.invoke(context) }
                }
            }
        }
    }
}

@Composable
private fun TipRow(number: Int, tip: Tip, onAction: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("$number", style = Mono.value, color = c.muted, modifier = Modifier.width(20.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tip.title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(tip.detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        val label = tip.action
        if (label != null) QuietButton(label, onAction)
    }
}
