package com.dualearbuds.host.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dualearbuds.host.device.SetupFixes

/** "For the best sound": five short, specific tips. Collapsed after the first look. */
@Composable
internal fun GuideSection() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    SectionLabel("For the best sound", "5 tips")
    Panel {
        PanelRow(
            title = if (open) "Hide tips" else "Show tips",
            hint = if (open) null else "Network, calibration, room, Bluetooth codec, Samsung",
            modifier = Modifier.clickable { open = !open },
            trailing = { Mono(if (open) "−" else "+", color = tokens.text) }
        )
        if (open) {
            PanelDivider()
            PanelRow(
                "Use this phone's hotspot or 5 GHz Wi-Fi",
                "2.4 GHz is crowded and drops packets, so everyone has to wait longer."
            )
            PanelDivider()
            PanelRow(
                "Calibrate each pair of earbuds",
                "Every phone: Dual Earbuds → Calibrate. Then all ears play on the same deadline."
            )
            PanelDivider()
            PanelRow(
                "Keep guests in one room",
                "Walls and distance weaken Wi-Fi; one weak phone makes everyone wait for it."
            )
            PanelDivider()
            PanelRow(
                "Prefer AAC or SBC over LDAC",
                "LDAC adds delay. Developer options → Bluetooth audio codec.",
                trailing = { QuietButton("Open", onClick = { SetupFixes.openDeveloperOptions(context) }) }
            )
            PanelDivider()
            PanelRow(
                "Samsung: Netflix and protected apps",
                "They block sharing, so listeners hear silence. Use Samsung Dual Audio in Bluetooth settings for those."
            )
        }
    }
}
