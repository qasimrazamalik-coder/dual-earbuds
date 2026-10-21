package com.dualearbuds.companion.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.R
import com.dualearbuds.companion.data.RecentHost
import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.net.discovery.DiscoveredHost
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.SectionLabel
import com.dualearbuds.companion.ui.theme.AppTheme

internal fun kindIcon(kind: HostKind): Int = if (kind == HostKind.PHONE) R.drawable.ic_phone_host else R.drawable.ic_laptop

/** Nearby hosts (mDNS), the link field with Paste link / Scan QR, and the last 5 hosts as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JoinCard(
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
    SectionCard(title = "Join a host", icon = painterResource(R.drawable.ic_wifi)) {
        // ---------------------------------------------------------------- nearby
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Nearby", Modifier.weight(1f))
            if (searching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (nearby.isEmpty()) {
            Text(
                if (searching) "Looking for laptops and phones sharing on this Wi-Fi…" else "Nearby search is off on this network.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textDim
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nearby.forEach { host -> NearbyRow(host, enabled) { onNearby(host) } }
            }
        }

        // ---------------------------------------------------------------- link
        SectionLabel("Link or QR code")
        OutlinedTextField(
            value = linkText,
            onValueChange = onLinkChange,
            label = { Text("Link from the host") },
            placeholder = { Text("http://192.168.1.20:8790/?k=ABCD2345") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmitLink() }, onDone = { onSubmitLink() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onScan, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_qr), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan QR")
            }
            OutlinedButton(onClick = onPaste, enabled = enabled) { Text("Paste link") }
        }
        checkLine()

        // ---------------------------------------------------------------- recent
        if (recent.isNotEmpty()) {
            SectionLabel("Recent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recent.forEach { r ->
                    AssistChip(
                        onClick = { onRecent(r) },
                        enabled = enabled,
                        label = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(painterResource(kindIcon(r.kind)), contentDescription = r.kind.label, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(host: DiscoveredHost, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(kindIcon(host.kind)),
                contentDescription = host.kind.label,
                tint = AppTheme.colors.accentText,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${host.kind.label} · ${host.display}" + if (host.canJoinDirectly) "" else " · needs the link or QR",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textDim
                )
            }
            if (host.pinRequired) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = "PIN required", tint = AppTheme.colors.warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (host.canJoinDirectly) "Join" else "Link…",
                style = MaterialTheme.typography.labelLarge,
                color = AppTheme.colors.accentText
            )
        }
    }
}
