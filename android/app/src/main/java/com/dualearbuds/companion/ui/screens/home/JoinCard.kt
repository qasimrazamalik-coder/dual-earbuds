package com.dualearbuds.companion.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.R
import com.dualearbuds.companion.data.RecentHost
import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.net.discovery.DiscoveredHost
import com.dualearbuds.companion.ui.components.ActionRow
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.SecondaryButton
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

internal fun kindIcon(kind: HostKind): Int = if (kind == HostKind.PHONE) R.drawable.ic_phone_host else R.drawable.ic_laptop

/** Nearby hosts (mDNS), the link with Join / Scan QR / Paste, and the last hosts — each list in one panel. */
@Composable
internal fun JoinSection(
    enabled: Boolean,
    nearby: List<DiscoveredHost>,
    searching: Boolean,
    recent: List<RecentHost>,
    linkText: String,
    onLinkChange: (String) -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onSubmitLink: () -> Unit,
    onNearby: (DiscoveredHost) -> Unit,
    onRecent: (RecentHost) -> Unit,
    checkLine: @Composable () -> Unit
) {
    val c = AppTheme.colors
    Section(
        "Nearby",
        count = if (nearby.isEmpty()) null else "${nearby.size}",
        trailing = { if (searching) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.muted) }
    ) {
        Panel {
            if (nearby.isEmpty()) {
                PanelRow(
                    if (searching) "Looking for laptops and phones on this network" else "Nearby search is off on this network",
                    subtitle = "Or scan the host's QR code below."
                )
            } else {
                nearby.forEachIndexed { i, host ->
                    if (i > 0) PanelDivider()
                    PanelRow(
                        host.name,
                        subtitle = "${host.kind.label} · ${host.display}" + if (host.canJoinDirectly) "" else " · needs the link or QR",
                        leading = painterResource(kindIcon(host.kind)),
                        value = if (host.pinRequired) "PIN" else null,
                        enabled = enabled,
                        trailing = { Text(if (host.canJoinDirectly) "Join" else "Link", style = MaterialTheme.typography.labelLarge, color = if (enabled) c.accentText else c.muted) },
                        onClick = { onNearby(host) }
                    )
                }
            }
        }
    }

    Section("Link or QR code") {
        Panel {
            PanelBody {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = onLinkChange,
                    placeholder = { Text("http://192.168.1.20:8790/?k=ABCD2345", style = Mono.value, color = c.muted) },
                    singleLine = true,
                    enabled = enabled,
                    textStyle = Mono.large.copy(color = c.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmitLink() }, onDone = { onSubmitLink() }),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent,
                        unfocusedBorderColor = c.line,
                        disabledBorderColor = c.line,
                        focusedContainerColor = c.surface,
                        unfocusedContainerColor = c.surface,
                        disabledContainerColor = c.surface2,
                        cursorColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                ActionRow {
                    PrimaryButton("Join", onSubmitLink, enabled = enabled && linkText.isNotBlank())
                    SecondaryButton("Scan QR", onScan, enabled = enabled, leading = painterResource(R.drawable.ic_qr))
                    QuietButton("Paste link", onPaste, enabled = enabled)
                }
                checkLine()
                if (!enabled) Hint("Stop playback to join another host.")
            }
        }
    }

    if (recent.isNotEmpty()) {
        Section("Recent", count = "${recent.size}") {
            Panel {
                recent.forEachIndexed { i, r ->
                    if (i > 0) PanelDivider()
                    PanelRow(
                        r.name,
                        leading = painterResource(kindIcon(r.kind)),
                        value = r.kind.label,
                        enabled = enabled,
                        onClick = { onRecent(r) }
                    )
                }
            }
        }
    }
}

