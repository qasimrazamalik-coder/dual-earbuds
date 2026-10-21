package com.dualearbuds.companion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.R
import com.dualearbuds.companion.data.AppSettings
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.ThemeMode
import com.dualearbuds.companion.diag.Diagnostics
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.components.NavCard
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.Segmented
import com.dualearbuds.companion.ui.components.SwitchRow
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.update.AppUpdater
import com.dualearbuds.companion.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSmart: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenHearing: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val autoUpdate by AppSettings.autoUpdate.collectAsStateWithLifecycle()
    val buffer by PlaybackController.buffer.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Settings", onBack = onBack) {
        SectionCard(title = "Appearance") {
            Segmented(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = theme,
                onSelect = { AppSettings.setTheme(it) }
            )
        }

        NavCard(
            icon = painterResource(R.drawable.ic_tune),
            title = "Smart features",
            subtitle = "Smart EQ, dialogue boost, smart buffer, hearing profiles",
            onClick = onOpenSmart
        )
        NavCard(
            icon = painterResource(R.drawable.ic_mic),
            title = "Calibrate my earbuds",
            subtitle = "Measures your earbuds' delay so every listener hears the same moment",
            onClick = onOpenCalibration
        )
        NavCard(
            icon = painterResource(R.drawable.ic_earbuds),
            title = "Hearing profiles",
            subtitle = "A short tone test that tunes the sound to you",
            onClick = onOpenHearing
        )

        SectionCard(title = "Delay") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Extra delay", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${buffer.delayMs} ms", style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.accentText)
            }
            Slider(
                value = buffer.delayMs.toFloat(),
                onValueChange = { PlaybackController.setDelayMs((it / 5f).roundToInt() * 5) },
                valueRange = 0f..Prefs.MAX_DELAY_MS.toFloat()
            )
            Text(
                "Lines this phone up with other speakers by hand. Never changed automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textDim
            )
        }

        UpdatesCard(autoUpdate)

        SectionCard(title = "Help") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGuide) { Text("Keep running in background") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyDiagnostics(context) }) { Text("Copy diagnostics") }
                TextButton(onClick = onOpenAbout) { Text("About") }
            }
            Text(
                "Diagnostics include versions, phone model, buffer and sync stats and the last 200 log lines — never your link key or PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textDim
            )
        }
    }
}

private fun copyDiagnostics(context: Context) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dual Earbuds diagnostics", Diagnostics.collect()))
        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(context, "Couldn't copy diagnostics", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun UpdatesCard(autoUpdate: Boolean) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    SectionCard(title = "Updates") {
        Text("Version ${AppUpdater.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        SwitchRow(
            title = "Check once a day",
            subtitle = "Only checks GitHub. Nothing is downloaded until you tap Download.",
            checked = autoUpdate,
            onCheckedChange = { AppSettings.setAutoUpdate(it) }
        )
        when (val s = state) {
            UpdateState.Idle -> Button(onClick = { AppUpdater.checkNow() }) { Text("Check for updates") }
            UpdateState.Checking -> {
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.UpToDate -> {
                Text("You have the latest version (${s.latest}).", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.accentText)
                TextButton(onClick = { AppUpdater.checkNow() }) { Text("Check again") }
            }
            is UpdateState.Available -> {
                Text("Version ${s.release.version} is available.", style = MaterialTheme.typography.titleSmall)
                if (s.release.notes.isNotBlank()) {
                    Text(s.release.notes.take(600), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AppUpdater.download(context, s.release) }, enabled = s.release.apk != null) { Text("Download") }
                    if (s.release.pageUrl.startsWith("https://github.com/")) {
                        TextButton(onClick = { openUrl(context, s.release.pageUrl) }) { Text("What's new") }
                    }
                }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${s.release.version}…", style = MaterialTheme.typography.bodyMedium)
                if (s.progress >= 0f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.ReadyToInstall -> {
                Text("Ready to install ${s.release.version}.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { AppUpdater.install(context, s.release, s.file) }) { Text("Install") }
            }
            is UpdateState.NeedsInstallPermission -> {
                Text(
                    "Android needs your OK to install updates from this app. Turn on “Allow from this source”, then come back.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { AppUpdater.openInstallPermissionSettings(context) }) { Text("Open setting") }
            }
            is UpdateState.Problem -> {
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.warning)
                TextButton(onClick = { AppUpdater.dismissProblem() }) { Text("OK") }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}
