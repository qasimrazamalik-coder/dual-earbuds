package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostStatus
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.net.LinkBuilder
import com.dualearbuds.host.net.NetKind

/** Discovery, remote control and the port. */
@Composable
internal fun NetworkSection(state: HostUiState) {
    val context = LocalContext.current
    val t = tokens
    val sharing = state.status == HostStatus.SHARING
    SectionLabel("Network", if (sharing) "port ${state.port}" else null)
    Panel {
        if (sharing) {
            ToggleRow(
                "Show on this network",
                when (state.netKind) {
                    NetKind.HOTSPOT -> "Dual Earbuds on your hotspot finds this phone without scanning."
                    else -> "Anyone on this Wi-Fi could join. Leave off in cafés and hotels."
                },
                state.showOnNetwork
            ) { HostController.setShowOnNetwork(it) }
            PanelDivider()
        }
        ToggleRow(
            "Let listeners control playback",
            "They can play, pause, skip and change this phone's volume.",
            state.prefs.remote
        ) { HostController.setRemote(context, it) }
        PanelDivider()
        var portText by remember(state.prefs.port) { mutableStateOf(state.prefs.port.toString()) }
        val valid = LinkBuilder.parsePort(portText) != null
        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter { it.isDigit() }.take(5)
                HostController.setPort(context, portText)
            },
            label = { Text("Port", style = Type.hint) },
            singleLine = true,
            isError = !valid,
            textStyle = Type.monoLarge.copy(color = t.text),
            supportingText = {
                Text(
                    when {
                        !valid -> "Use 1024 to 65535."
                        sharing -> "Applies the next time you start sharing."
                        else -> "Default ${LinkBuilder.DEFAULT_PORT}; the next free one if it is busy. UDP uses the same number."
                    },
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
