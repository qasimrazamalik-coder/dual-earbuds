package com.dualearbuds.host.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.net.NetKind
import kotlinx.coroutines.delay

/** QR code, link, copy/share, address choice and New link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSection(state: HostUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    HostCard(title = "Scan to listen") {
        val link = state.link
        if (link == null) {
            Note("No network address yet. Join a Wi-Fi network or turn on this phone's hotspot.")
            return@HostCard
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(link, Modifier.widthIn(max = 260.dp).fillMaxWidth())
        }
        SelectionContainer {
            Text(
                link,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(link))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy link") }
            OutlinedButton(onClick = { shareLink(context, link) }) { Text("Share") }
        }
        Hint("Scan with the Dual Earbuds app or the camera, or paste the link in any browser on the same Wi-Fi or hotspot.")

        if (state.addresses.size > 1) {
            Text("This phone's address", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in state.addresses) {
                    FilterChip(
                        selected = a == state.address,
                        onClick = { HostController.chooseAddress(a.address) },
                        label = { Text((if (a.kind == NetKind.HOTSPOT) "Hotspot " else "Wi-Fi ") + a.address) }
                    )
                }
            }
            Hint("Pick the network the listeners are on.")
        } else {
            Hint(
                when (state.netKind) {
                    NetKind.HOTSPOT -> "On this phone's hotspot (${state.address?.address}). Listeners join the hotspot first."
                    NetKind.WIFI -> "On Wi-Fi (${state.address?.address}). Listeners join the same Wi-Fi."
                    NetKind.NONE -> ""
                }
            )
        }

        state.prefs.activePin?.let { pin ->
            Text("Room PIN", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(
                pin.chunked(3).joinToString(" "),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Hint("Tell guests the PIN. It is not in the link or the QR code.", Modifier.fillMaxWidth())
        }

        TextButton(onClick = { HostController.newLink(context) }) { Text("New link") }
        Hint("New link disconnects everyone and the old code stops working.")
    }
}

private fun shareLink(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Listen with me: $link")
    try {
        context.startActivity(Intent.createChooser(send, "Share the link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}
