package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.server.RoomPin

/** Party mode: 16 listeners instead of 8, with an optional 4–6 digit room PIN. */
@Composable
internal fun PartySection(state: HostUiState) {
    val context = LocalContext.current
    val p = state.prefs
    SectionLabel("Party", if (p.party) "16 listeners" else "off")
    Panel {
        ToggleRow("Party mode", "Up to 16 listeners instead of 8. Use the hotspot or 5 GHz Wi-Fi.", p.party) {
            HostController.setParty(context, it)
        }
        if (p.party) {
            PanelDivider()
            ToggleRow("Room PIN", "Guests also type a PIN you tell them. Never in the link or code.", p.pinOn) {
                HostController.setPinOn(context, it)
            }
            if (p.pinOn) {
                PanelDivider()
                PinField(p.pin) { HostController.setPin(context, it) }
            }
        }
    }
}

@Composable
private fun PinField(pin: String, onChange: (String) -> Unit) {
    val t = tokens
    val valid = RoomPin.isValid(pin)
    OutlinedTextField(
        value = pin,
        onValueChange = onChange,
        label = { Text("PIN, 4 to 6 digits", style = Type.hint) },
        singleLine = true,
        isError = pin.isNotEmpty() && !valid,
        textStyle = Type.monoLarge.copy(color = t.text),
        supportingText = {
            Text(
                if (valid) "Listeners already in stay connected." else "Until it has 4 digits there is no PIN.",
                style = Type.hint
            )
        },
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = t.accent,
            unfocusedBorderColor = t.line,
            focusedLabelColor = t.accent,
            cursorColor = t.accent
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
