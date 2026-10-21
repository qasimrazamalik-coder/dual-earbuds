package com.dualearbuds.host.ui

import android.content.Context
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** "Sound source": all apps, or one app only (addMatchingUid). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSection(state: HostUiState) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val app = state.prefs.app
    HostCard(title = "Sound source") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = app == null, onClick = { HostController.setSource(context, null) }, label = { Text("All apps") })
            FilterChip(selected = app != null, onClick = { picking = true }, label = { Text("One app only") })
        }
        if (app != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(context, app.packageName)
                Spacer(Modifier.width(10.dp))
                Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { picking = true }) { Text("Change") }
            }
            Hint("Listeners hear only ${app.label}. Notifications and other apps stay on this phone.")
        } else {
            Hint("Listeners hear music, videos and games from every app.")
        }
        Hint(
            "Some apps block sharing their sound, so listeners hear silence from them: Netflix and other " +
                "DRM video apps, some banking apps. Samsung phones can use the built-in Dual Audio (Bluetooth settings) for those."
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
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share one app", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth()
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                                Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AppIcon(context: Context, packageName: String) {
    val px = with(LocalDensity.current) { 36.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { AppCatalog.icon(context, packageName, px)?.asImageBitmap() }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Spacer(Modifier.size(36.dp))
    }
}
