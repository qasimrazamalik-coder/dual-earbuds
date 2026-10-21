package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.server.RoomPin

/** Party mode (16 listeners) with an optional 4–6 digit room PIN. */
@Composable
internal fun PartySection(state: HostUiState) {
    val context = LocalContext.current
    val p = state.prefs
    HostCard(title = "Party mode") {
        SwitchRow("Up to 16 listeners", "Normally 8. More listeners need a strong Wi-Fi signal.", p.party) {
            HostController.setParty(context, it)
        }
        if (p.party) {
            SwitchRow("Room PIN", "Guests also type a PIN you tell them. It is never in the link or QR code.", p.pinOn) {
                HostController.setPinOn(context, it)
            }
            if (p.pinOn) {
                val valid = RoomPin.isValid(p.pin)
                OutlinedTextField(
                    value = p.pin,
                    onValueChange = { HostController.setPin(context, it) },
                    label = { Text("PIN (4–6 digits)") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text(if (valid) "Listeners already in stay connected." else "Type 4 to 6 digits. Until then there is no PIN.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
