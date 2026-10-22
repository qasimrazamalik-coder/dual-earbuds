package com.dualearbuds.host.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.net.NetKind
import kotlinx.coroutines.delay

/** How others join: QR, link, PIN, which address, and a line for laptops. */
@Composable
internal fun LinkSection(state: HostUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val t = tokens
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    SectionLabel(
        "Join",
        when (state.netKind) {
            NetKind.HOTSPOT -> "hotspot"
            NetKind.WIFI -> "Wi-Fi"
            NetKind.NONE -> "no network"
        }
    )
    val link = state.link
    if (link == null) {
        Notice("No network yet. Turn on this phone's hotspot or join a Wi-Fi.")
        return
    }
    Panel {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.widthIn(max = 220.dp).fillMaxWidth()) { QrCode(link, Modifier.fillMaxWidth()) }
            SelectionContainer {
                Mono(link, Modifier.padding(top = 12.dp), color = t.text)
            }
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuietButton(if (copied) "Copied" else "Copy link", onClick = {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                })
                QuietButton("Share", onClick = { shareLink(context, link) })
            }
        }
        state.prefs.activePin?.let { pin ->
            PanelDivider()
            PanelRow("Room PIN", "Tell guests. It is not in the link or the code.", trailing = {
                Text(
                    pin.chunked(3).joinToString(" "),
                    style = Type.monoLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = t.text,
                    textAlign = TextAlign.End
                )
            })
        }
        PanelDivider()
        PanelRow(
            "Phones",
            "Scan the code with Dual Earbuds or the camera. iPhone and others: open the link in a browser."
        )
        PanelDivider()
        PanelRow(
            "Laptops",
            if (state.showOnNetwork) "Open Dual Earbuds → Listen, it appears automatically."
            else "Open Dual Earbuds → Listen and paste the link (Show on this network is off)."
        )
        if (state.addresses.size > 1) {
            PanelDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Hint("Address the code uses: pick the network your guests are on")
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    Segmented(
                        options = state.addresses.map { a ->
                            a.address to (if (a.kind == NetKind.HOTSPOT) "Hotspot " else "Wi-Fi ") + a.address
                        },
                        selected = state.address?.address ?: "",
                        onSelect = { HostController.chooseAddress(it) }
                    )
                }
            }
        }
        PanelDivider()
        PanelRow("New link", "Everyone is disconnected and the old code stops working.", trailing = {
            QuietButton("New link", onClick = { HostController.newLink(context) })
        })
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
