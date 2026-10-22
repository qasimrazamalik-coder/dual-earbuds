package com.dualearbuds.host.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dualearbuds.host.AppChoice
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.capture.AppCatalog
import com.dualearbuds.host.capture.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Sound source": every app, or one app only (addMatchingUid). */
@Composable
internal fun SourceSection(state: HostUiState) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val app = state.prefs.app
    SectionLabel("Sound source", if (app == null) "all apps" else "one app")
    Panel {
        PanelRow(
            "Share",
            if (app == null) "Music, video and games from every app." else "Only ${app.label}. Notifications stay on this phone.",
            trailing = {
                Segmented(
                    options = listOf(false to "All apps", true to "One app"),
                    selected = app != null,
                    onSelect = { one ->
                        if (one) {
                            picking = true
                        } else {
                            HostController.setSource(context, null)
                        }
                    }
                )
            }
        )
        if (app != null) {
            PanelDivider()
            PanelRow(
                app.label,
                leading = { AppIcon(context, app.packageName) },
                trailing = { RowAction("Change", onClick = { picking = true }) }
            )
        }
        PanelDivider()
        PanelRow(
            "Some apps block sharing",
            "Netflix and other protected video apps play silence to listeners. Samsung: use Dual Audio for those."
        )
    }
    if (picking) {
        AppPickerDialog(
            onPick = {
                picking = false
                HostController.setSource(context, AppChoice(it.packageName, it.label))
            },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun AppPickerDialog(onPick: (InstalledApp) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { AppCatalog.launchableApps(context) }
    }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.line)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share one app", style = Type.page, color = tokens.text)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search apps", style = Type.hint) },
                    textStyle = Type.body.copy(color = tokens.text),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = tokens.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.packageName }) { a ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(a) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(context, a.packageName)
                                Spacer(Modifier.width(12.dp))
                                Text(a.label, style = Type.body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Box(Modifier.align(Alignment.End)) { RowAction("Cancel", onClick = onDismiss) }
            }
        }
    }
}

@Composable
private fun AppIcon(context: Context, packageName: String) {
    val px = with(LocalDensity.current) { 28.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { AppCatalog.icon(context, packageName, px)?.asImageBitmap() }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(28.dp))
    } else {
        Spacer(Modifier.size(28.dp))
    }
}
