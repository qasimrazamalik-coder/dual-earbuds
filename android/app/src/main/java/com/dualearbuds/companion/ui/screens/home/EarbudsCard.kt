package com.dualearbuds.companion.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.R
import com.dualearbuds.companion.bt.EarbudsState
import com.dualearbuds.companion.ui.components.BatteryRing
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.theme.AppTheme

@Composable
internal fun EarbudsCard(
    earbuds: EarbudsState,
    calibratedMs: Double?,
    onAllowBluetooth: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onCalibrate: () -> Unit
) {
    SectionCard(title = "Earbuds on this phone", icon = painterResource(R.drawable.ic_bluetooth)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(percent = if (earbuds.connected) earbuds.battery else -1)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = when {
                    earbuds.permissionMissing -> "Bluetooth access needed"
                    earbuds.noBluetooth -> "No Bluetooth on this phone"
                    earbuds.bluetoothOff -> "Bluetooth is off"
                    earbuds.connected -> earbuds.name
                    else -> "No earbuds connected"
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                val detail = when {
                    earbuds.permissionMissing -> "Allow it to show your earbuds' name and battery."
                    earbuds.bluetoothOff -> "Turn on Bluetooth and connect your earbuds to this phone."
                    earbuds.connected && earbuds.battery in 0..100 -> "Battery ${earbuds.battery}%"
                    earbuds.connected -> "Battery not reported"
                    else -> "Pair your earbuds with this phone. Sound plays wherever the phone's audio goes."
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
                if (earbuds.connected) {
                    Text(
                        if (calibratedMs != null) "Synced timing calibrated (${calibratedMs.toInt()} ms)" else "Not calibrated yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (calibratedMs != null) AppTheme.colors.accentText else AppTheme.colors.textDim
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (earbuds.permissionMissing) {
                Button(onClick = onAllowBluetooth) { Text("Allow Bluetooth access") }
            } else if (earbuds.connected) {
                Button(onClick = onCalibrate) { Text("Calibrate my earbuds") }
            }
            TextButton(onClick = onOpenBluetooth) { Text("Bluetooth settings") }
        }
    }
}
