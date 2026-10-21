package com.dualearbuds.companion.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.net.PinPolicy
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.power.Brand
import com.dualearbuds.companion.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** One PIN policy for the whole app session: wrong PINs are never retried automatically. */
internal object PinGate {
    val policy = PinPolicy()
}

/** Room PIN prompt (v3.1). Digits only; after 3 wrong PINs the user waits 30 s so the host never locks us out. */
@Composable
internal fun PinDialog(hostName: String, wrongBefore: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val wait = PinGate.policy.waitMs(now)
    val cleaned = PinGate.policy.clean(pin)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room PIN", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${hostName.ifBlank { "This host" }} is locked with a 4–6 digit PIN. Ask the host for it — it's never in the link or QR code.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (cleaned != null && wait == 0L) onSubmit(cleaned) }),
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    wait > 0 -> Text(
                        "Too many wrong PINs. Wait ${(wait + 999) / 1000} s before trying again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.warning
                    )
                    wrongBefore -> Text("That PIN was wrong.", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { cleaned?.let(onSubmit) }, enabled = cleaned != null && wait == 0L) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** A nearby host that didn't publish its key: offer the two ways in. */
@Composable
internal fun NeedLinkDialog(hostName: String, onScan: () -> Unit, onPaste: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $hostName", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                "This host only shows its key on private networks. Scan the QR code on its screen, or copy its link and paste it here.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onScan) { Text("Scan QR") } },
        dismissButton = { TextButton(onClick = onPaste) { Text("Paste link") } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** What "keep running" means on this phone's brand, in one short paragraph. */
internal fun batteryAdvice(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "Xiaomi, Redmi and POCO phones close background apps aggressively. Allow Autostart and set Battery saver to “No restrictions” for Dual Earbuds."
    Brand.OPPO -> "Realme and OPPO phones pause background apps. Allow “Auto launch” and “Allow background activity” for Dual Earbuds."
    Brand.SAMSUNG -> "Samsung may put the app to sleep. In Battery, set Dual Earbuds to “Unrestricted” and keep it out of “Sleeping apps”."
    Brand.VIVO -> "Vivo and iQOO phones limit background apps. Allow “High background power consumption” for Dual Earbuds."
    Brand.OTHER -> "Android may pause the sound when the screen is off. Let Dual Earbuds ignore battery optimisation."
}

/** Offered once, after the first successful playback: explains the brand's setting and offers the guide. */
@Composable
internal fun BatteryOfferDialog(onOpenGuide: () -> Unit, onDismiss: () -> Unit) {
    val brand = remember { BackgroundSettings.detectBrand() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep playing with the screen off?", style = MaterialTheme.typography.titleLarge) },
        text = { Text(batteryAdvice(brand), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onOpenGuide) { Text("Show me how") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
