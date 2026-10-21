package com.dualearbuds.companion.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.R
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.bt.EarbudsState
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.net.Errors
import com.dualearbuds.companion.net.InfoClient
import com.dualearbuds.companion.net.InfoResult
import com.dualearbuds.companion.net.LinkParser
import com.dualearbuds.companion.net.LinkTarget
import com.dualearbuds.companion.net.Net
import com.dualearbuds.companion.net.ParseResult
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlayerState
import com.dualearbuds.companion.service.PlayerStatus
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.BatteryRing
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.components.StartStopButton
import com.dualearbuds.companion.ui.components.StatPill
import com.dualearbuds.companion.ui.theme.Palette
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Result of the "Check link" step shown under the link field. */
private sealed interface CheckState {
    data object None : CheckState
    data object Checking : CheckState
    data class Found(val pc: String, val rate: Int) : CheckState
    data class Problem(val message: String) : CheckState
}

private fun scanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Point the camera at the QR code in the Dual Earbuds Windows app")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Runtime permissions worth asking for before starting playback (none of them are hard requirements). */
private fun startPermissions(context: Context): List<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    ) {
        list += Manifest.permission.BLUETOOTH_CONNECT
    }
    return list
}

private fun showSystemVolumePanel(context: Context) {
    try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    } catch (_: Throwable) {
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenGuide: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val player by PlaybackController.state.collectAsStateWithLifecycle()
    val volume by PlaybackController.volume.collectAsStateWithLifecycle()
    val bufferMs by PlaybackController.bufferMs.collectAsStateWithLifecycle()
    val earbuds by EarbudsMonitor.state.collectAsStateWithLifecycle()

    var linkText by rememberSaveable { mutableStateOf(Prefs.lastLink) }
    var check by remember { mutableStateOf<CheckState>(CheckState.None) }
    var batteryOk by remember { mutableStateOf(BackgroundSettings.isIgnoringBatteryOptimizations(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = BackgroundSettings.isIgnoringBatteryOptimizations(context)
        EarbudsMonitor.refresh()
    }

    fun parseOrReport(text: String): LinkTarget? = when (val r = LinkParser.parse(text)) {
        is ParseResult.Ok -> r.target
        is ParseResult.Error -> {
            check = CheckState.Problem(r.message)
            null
        }
    }

    fun runCheck(text: String) {
        val target = parseOrReport(text) ?: return
        check = CheckState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { InfoClient.fetch(Net.infoHttp, target) }
            check = when (result) {
                is InfoResult.Ok -> {
                    Prefs.lastLink = target.toLink()
                    CheckState.Found(result.info.pc, result.info.rate)
                }
                else -> CheckState.Problem(
                    Errors.from(result, context, target)?.message ?: "Couldn't reach the laptop."
                )
            }
        }
    }

    fun doStart() {
        val target = parseOrReport(linkText) ?: return
        linkText = target.toLink()
        PlaybackController.start(context, target)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            linkText = contents.trim()
            PlaybackController.clearError()
            runCheck(linkText)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                scanLauncher.launch(scanOptions())
            } catch (_: Throwable) {
                check = CheckState.Problem("Couldn't open the camera. Paste the link instead.")
            }
        } else {
            check = CheckState.Problem("Camera permission is needed to scan the QR code. You can paste the link instead.")
        }
    }

    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        EarbudsMonitor.refresh()
        doStart()
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        EarbudsMonitor.refresh()
    }

    fun launchScanner() {
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            try {
                scanLauncher.launch(scanOptions())
            } catch (_: Throwable) {
                check = CheckState.Problem("Couldn't open the camera. Paste the link instead.")
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun onStartStop() {
        if (player.running) {
            PlaybackController.stop(context)
        } else {
            val perms = startPermissions(context)
            if (perms.isEmpty()) doStart() else startPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_earbuds),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Dual Earbuds", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.Filled.Lock, contentDescription = "Keep running in background")
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!batteryOk) {
                Banner(
                    text = "Android may stop the sound when the screen is off. Tap to set up “Keep running in background”.",
                    color = Palette.Amber,
                    icon = rememberVectorPainterWarning(),
                    onClick = onOpenGuide
                )
            }

            // ------------------------------------------------ connection
            SectionCard(title = "Laptop", icon = painterResource(R.drawable.ic_qr)) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = {
                        linkText = it
                        check = CheckState.None
                        PlaybackController.clearError()
                    },
                    label = { Text("Link from the Windows app") },
                    placeholder = { Text("http://192.168.1.20:8790/?k=ABCD2345") },
                    singleLine = true,
                    enabled = !player.running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runCheck(linkText) }),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(onClick = { launchScanner() }, enabled = !player.running) {
                        Icon(painterResource(R.drawable.ic_qr), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR")
                    }
                    OutlinedButton(
                        onClick = {
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            if (pasted.isNotEmpty()) {
                                linkText = pasted
                                PlaybackController.clearError()
                                runCheck(pasted)
                            } else {
                                check = CheckState.Problem("The clipboard is empty. Copy the link in the Windows app first.")
                            }
                        },
                        enabled = !player.running
                    ) { Text("Paste") }
                    TextButton(onClick = { runCheck(linkText) }, enabled = !player.running) { Text("Check link") }
                }
                CheckLine(check)
            }

            // ------------------------------------------------ player
            PlayerCard(player = player, onStartStop = { onStartStop() })

            // ------------------------------------------------ earbuds
            EarbudsCard(
                earbuds = earbuds,
                onAllowBluetooth = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onOpenBluetooth = { BackgroundSettings.openBluetoothSettings(context) }
            )

            // ------------------------------------------------ volume & buffer
            SectionCard(title = "Sound", icon = painterResource(R.drawable.ic_volume_up)) {
                LabeledValue("App volume", "$volume%")
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { PlaybackController.setVolume((it / 5f).roundToInt() * 5) },
                    valueRange = 0f..Prefs.MAX_VOLUME.toFloat(),
                    colors = sliderColors()
                )
                Text(
                    if (volume > 100) "Boost above 100% can distort loud sounds." else "Software volume for the laptop sound on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (volume > 100) Palette.Amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { showSystemVolumePanel(context) }) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Phone media volume")
                }
                Spacer(Modifier.height(4.dp))
                LabeledValue("Buffer", "$bufferMs ms")
                Slider(
                    value = bufferMs.toFloat(),
                    onValueChange = { PlaybackController.setBufferMs((it / 10f).roundToInt() * 10) },
                    valueRange = Prefs.MIN_BUFFER_MS.toFloat()..Prefs.MAX_BUFFER_MS.toFloat(),
                    colors = sliderColors()
                )
                Text(
                    "Lower = less delay. Higher = fewer dropouts on busy Wi-Fi. 180 ms suits most homes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onOpenGuide, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (batteryOk) "Background setup ✓" else "Keep running in background →")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberVectorPainterWarning(): VectorPainter = rememberVectorPainter(Icons.Filled.Warning)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
)

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CheckLine(check: CheckState) {
    when (check) {
        CheckState.None -> Unit
        CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking the link…", style = MaterialTheme.typography.bodyMedium)
        }
        is CheckState.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Teal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val rateText = if (check.rate > 0) {
                String.format(Locale.US, "%.1f kHz", check.rate / 1000.0)
            } else {
                "not sharing sound yet"
            }
            Text("Found ${check.pc} · $rateText", style = MaterialTheme.typography.bodyMedium)
        }
        is CheckState.Problem -> Banner(text = check.message)
    }
}

@Composable
private fun PlayerCard(player: PlayerState, onStartStop: () -> Unit) {
    SectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StartStopButton(running = player.running, enabled = true, onClick = onStartStop)
            Spacer(Modifier.height(12.dp))
            val headline = when (player.status) {
                PlayerStatus.IDLE -> "Ready"
                PlayerStatus.CONNECTING -> "Connecting…"
                PlayerStatus.BUFFERING -> "Buffering…"
                PlayerStatus.PLAYING -> if (player.userMuted) "Muted" else "Playing"
                PlayerStatus.LAPTOP_PAUSED -> "Connected"
                PlayerStatus.RECONNECTING -> "Reconnecting…"
                PlayerStatus.ERROR -> "Stopped"
            }
            Text(headline, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            val sub = when (player.status) {
                PlayerStatus.IDLE -> "Scan or paste the laptop link, then press Start."
                PlayerStatus.CONNECTING -> player.message
                PlayerStatus.BUFFERING, PlayerStatus.PLAYING ->
                    "from ${player.pcName.ifBlank { "your laptop" }}"
                PlayerStatus.LAPTOP_PAUSED ->
                    "${player.pcName.ifBlank { "The laptop" }} isn't sharing sound right now. Turn on Phone link / mirroring in the Windows app."
                PlayerStatus.RECONNECTING -> player.message
                PlayerStatus.ERROR -> ""
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        val error = player.error
        if (error != null && (player.status == PlayerStatus.ERROR || player.status == PlayerStatus.RECONNECTING)) {
            Banner(text = error.message)
        }
        if (player.running && player.status != PlayerStatus.CONNECTING) {
            StatsRow(player)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(player: PlayerState) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatPill("${player.packets} packets")
        StatPill("${player.lost} lost", color = if (player.lost > 0) Palette.Amber else Palette.TextDim)
        StatPill("buffer ${player.bufferMs} ms")
        StatPill("≈${player.latencyMs} ms delay")
        if (player.rate > 0) StatPill(String.format(Locale.US, "%.1f kHz", player.rate / 1000.0))
        if (player.underruns > 0) StatPill("${player.underruns} underruns", color = Palette.Amber)
        if (player.dropped > 0) StatPill("${player.dropped} trimmed")
    }
}

@Composable
private fun EarbudsCard(earbuds: EarbudsState, onAllowBluetooth: () -> Unit, onOpenBluetooth: () -> Unit) {
    SectionCard(title = "Earbuds on this phone", icon = painterResource(R.drawable.ic_bluetooth)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(percent = if (earbuds.connected) earbuds.battery else -1)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = when {
                    earbuds.permissionMissing -> "Bluetooth access needed"
                    earbuds.noBluetooth -> "No Bluetooth on this phone"
                    earbuds.bluetoothOff -> "Bluetooth is off"
                    earbuds.connected -> earbuds.name
                    else -> "No earbuds connected"
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                val detail = when {
                    earbuds.permissionMissing -> "Allow it to show your earbuds' name and battery."
                    earbuds.bluetoothOff -> "Turn on Bluetooth and connect your second pair of earbuds to this phone."
                    earbuds.connected && earbuds.battery in 0..100 -> "Battery ${earbuds.battery}%"
                    earbuds.connected -> "Battery not reported"
                    else -> "Pair the second pair of earbuds with this phone. Sound plays wherever the phone's audio goes."
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (earbuds.permissionMissing) {
                Button(
                    onClick = onAllowBluetooth,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text("Allow Bluetooth access") }
            }
            Box {
                TextButton(onClick = onOpenBluetooth) { Text("Bluetooth settings") }
            }
        }
    }
}
