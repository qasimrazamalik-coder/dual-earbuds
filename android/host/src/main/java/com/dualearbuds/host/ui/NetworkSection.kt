package com.dualearbuds.host.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostStatus
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.net.LinkBuilder
import com.dualearbuds.host.net.NetKind

/** Discovery, remote control and the port. */
@Composable
internal fun NetworkSection(state: HostUiState) {
    val context = LocalContext.current
    val sharing = state.status == HostStatus.SHARING
    HostCard(title = "Network") {
        if (sharing) {
            SwitchRow(
                "Show on this network",
                when (state.netKind) {
                    NetKind.HOTSPOT -> "Nearby Dual Earbuds apps on your hotspot can join without scanning."
                    else -> "Off on Wi-Fi you don't control (café, hotel): anyone on it could join. Turn on at home."
                },
                state.showOnNetwork
            ) { HostController.setShowOnNetwork(it) }
        }
        SwitchRow(
            "Let listeners control playback",
            "Listeners can play/pause, skip and change this phone's volume.",
            state.prefs.remote
        ) { HostController.setRemote(context, it) }

        var portText by remember(state.prefs.port) { mutableStateOf(state.prefs.port.toString()) }
        val valid = LinkBuilder.parsePort(portText) != null
        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter { it.isDigit() }.take(5)
                HostController.setPort(context, portText)
            },
            label = { Text("Port") },
            singleLine = true,
            isError = !valid,
            supportingText = {
                Text(
                    when {
                        !valid -> "Use 1024 to 65535."
                        sharing -> "Applies next time you start sharing."
                        else -> "Default ${LinkBuilder.DEFAULT_PORT}. If it is busy the next free port is used."
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
