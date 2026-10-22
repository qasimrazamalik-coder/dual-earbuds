package com.dualearbuds.companion.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.dualearbuds.companion.R
import com.dualearbuds.companion.bt.EarbudsState
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.theme.AppTheme
import java.util.Locale

/** The phone's Bluetooth earbuds: name, battery and measured delay, as rows. */
@Composable
internal fun EarbudsSection(
    earbuds: EarbudsState,
    calibratedMs: Double?,
    onAllowBluetooth: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onCalibrate: () -> Unit
) {
    val c = AppTheme.colors
    Section("Earbuds on this phone") {
        Panel {
            when {
                earbuds.permissionMissing -> PanelRow(
                    "Bluetooth access is off",
                    subtitle = "Allow it to show which earbuds are connected and use their measured delay.",
                    leading = painterResource(R.drawable.ic_bluetooth),
                    trailing = { QuietButton("Allow", onAllowBluetooth) }
                )
                earbuds.noBluetooth -> PanelRow("No Bluetooth on this phone", subtitle = "Sound plays on the speaker or wired headphones.")
                earbuds.bluetoothOff -> PanelRow(
                    "Bluetooth is off",
                    subtitle = "Turn it on and connect your earbuds, or use wired headphones.",
                    leading = painterResource(R.drawable.ic_bluetooth),
                    trailing = { QuietButton("Bluetooth", onOpenBluetooth) }
                )
                !earbuds.connected -> PanelRow(
                    "No earbuds connected",
                    subtitle = "Sound plays wherever this phone's audio goes.",
                    leading = painterResource(R.drawable.ic_bluetooth),
                    trailing = { QuietButton("Bluetooth", onOpenBluetooth) }
                )
                else -> {
                    PanelRow(
                        earbuds.name,
                        leading = painterResource(R.drawable.ic_earbuds),
                        value = if (earbuds.battery in 0..100) "${earbuds.battery}%" else "battery —",
                        valueColor = if (earbuds.battery in 0..20) c.warn else c.muted
                    )
                    PanelDivider()
                    PanelRow(
                        "Measured delay",
                        subtitle = if (calibratedMs == null) "Not calibrated: sync uses an estimate" else null,
                        value = calibratedMs?.let { String.format(Locale.US, "%.0f ms", it) },
                        trailing = { QuietButton(if (calibratedMs == null) "Calibrate" else "Again", onCalibrate) }
                    )
                }
            }
        }
    }
}
