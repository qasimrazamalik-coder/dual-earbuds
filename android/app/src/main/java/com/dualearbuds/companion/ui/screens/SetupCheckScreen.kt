package com.dualearbuds.companion.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.power.OpenResult
import com.dualearbuds.companion.setup.CheckGroup
import com.dualearbuds.companion.setup.CheckId
import com.dualearbuds.companion.setup.CheckItem
import com.dualearbuds.companion.setup.CheckStatus
import com.dualearbuds.companion.setup.FixAction
import com.dualearbuds.companion.setup.SetupChecks
import com.dualearbuds.companion.setup.SetupFactsReader
import com.dualearbuds.companion.ui.Permissions
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ReasonDialog
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.StatusWord
import com.dualearbuds.companion.ui.components.Tone
import com.dualearbuds.companion.ui.theme.AppTheme

internal fun CheckStatus.tone(): Tone = when (this) {
    CheckStatus.OK -> Tone.GOOD
    CheckStatus.ATTENTION, CheckStatus.TODO -> Tone.WARN
    CheckStatus.INFO -> Tone.IDLE
}

/**
 * Setup check: every thing that makes playback work better, with its status, a one-line reason and the fix.
 * Nothing is asked up front; each permission is requested only when its row's button is tapped.
 */
@Composable
fun SetupCheckScreen(
    onBack: () -> Unit,
    onOpenBrandSteps: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenBestSound: () -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val earbuds by EarbudsMonitor.state.collectAsStateWithLifecycle()
    val latencies by SoundSettings.outputLatency.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf<String?>(null) }
    var askLocation by remember { mutableStateOf(false) }
    var brandDone by remember { mutableStateOf(Prefs.brandStepsDone) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        EarbudsMonitor.refresh()
        refresh++
    }
    // Re-read whenever something the checks look at may have changed.
    val items = remember(refresh, earbuds, latencies, brandDone) { SetupChecks.evaluate(SetupFactsReader.read(context)) }
    LaunchedEffect(items) { hint = null }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        EarbudsMonitor.refresh()
        refresh++
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    fun report(result: OpenResult) {
        hint = when (result) {
            OpenResult.EXACT -> null
            OpenResult.FALLBACK -> "That screen didn't open directly, so App info opened. Look for Battery or Background there."
            OpenResult.FAILED -> "Couldn't open settings. Open Settings → Apps → Dual Earbuds yourself."
        }
    }

    fun act(item: CheckItem) {
        when (item.action) {
            FixAction.NONE -> Unit
            FixAction.REQUEST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Prefs.askedNotifications = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            FixAction.OPEN_NOTIFICATION_SETTINGS -> report(BackgroundSettings.openNotificationSettings(context))
            FixAction.REQUEST_BATTERY_EXEMPTION -> report(BackgroundSettings.requestIgnoreBatteryOptimizations(context))
            FixAction.OPEN_APP_INFO -> report(BackgroundSettings.openAppDetails(context))
            FixAction.OPEN_BATTERY_SAVER -> report(BackgroundSettings.openBatterySaverSettings(context))
            FixAction.OPEN_BRAND_STEPS -> onOpenBrandSteps()
            FixAction.REQUEST_BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (Permissions.permanentlyDenied(context, Manifest.permission.BLUETOOTH_CONNECT, Prefs.askedBluetooth)) {
                    report(BackgroundSettings.openAppDetails(context))
                } else {
                    Prefs.askedBluetooth = true
                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            FixAction.OPEN_BLUETOOTH -> report(BackgroundSettings.openBluetoothSettings(context))
            FixAction.CALIBRATE -> onOpenCalibration()
            FixAction.REQUEST_LOCATION_FOR_BAND -> askLocation = true
            FixAction.OPEN_WIFI -> report(BackgroundSettings.openWifiSettings(context))
        }
    }

    val open = SetupChecks.attentionCount(items)
    ScreenScaffold(
        title = "Setup check",
        onBack = onBack,
        readout = if (open == 0) "all set" else "$open to check"
    ) {
        hint?.let { Banner(text = it, color = AppTheme.colors.warn, modifier = Modifier.padding(top = 16.dp)) }

        CheckGroup.entries.forEach { group ->
            val rows = items.filter { it.group == group }
            if (rows.isEmpty()) return@forEach
            val ok = rows.count { it.status == CheckStatus.OK }
            Section(group.label, count = "$ok of ${rows.size} ok") {
                Panel {
                    rows.forEachIndexed { i, item ->
                        if (i > 0) PanelDivider()
                        CheckRow(
                            item = item,
                            onAction = { act(item) },
                            doneToggle = if (item.id == CheckId.BRAND) brandDone else null,
                            onToggleDone = {
                                Prefs.brandStepsDone = !brandDone
                                brandDone = !brandDone
                            }
                        )
                    }
                }
            }
        }

        Section("Tips") {
            Panel {
                PanelRow(
                    "For the best sound",
                    subtitle = "Five habits that cut delay and dropouts",
                    chevron = true,
                    onClick = onOpenBestSound
                )
            }
        }
    }

    if (askLocation) {
        ReasonDialog(
            title = "Read the Wi-Fi band?",
            reason = "This Android version shows whether Wi-Fi is 2.4 or 5 GHz only to apps with location access. " +
                "It is used for this check and nothing else.",
            confirmLabel = "Allow",
            onConfirm = {
                askLocation = false
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onDismiss = { askLocation = false }
        )
    }
}

@Composable
private fun CheckRow(item: CheckItem, onAction: () -> Unit, doneToggle: Boolean?, onToggleDone: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(item.reason, style = MaterialTheme.typography.bodySmall, color = c.muted)
            StatusWord(item.status.tone(), item.state, Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val label = item.actionLabel
            if (label != null && (item.status != CheckStatus.OK || item.id == CheckId.CALIBRATION || item.id == CheckId.BRAND)) {
                QuietButton(label, onAction)
            }
            if (doneToggle != null) {
                QuietButton(if (doneToggle) "Not done" else "Mark done", onToggleDone, color = c.muted)
            }
        }
    }
}
