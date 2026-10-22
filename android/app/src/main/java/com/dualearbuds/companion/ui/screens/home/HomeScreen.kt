package com.dualearbuds.companion.ui.screens.home

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.R
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.AppSettings
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.RecentHost
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.dsp.EqPreset
import com.dualearbuds.companion.net.ErrorKind
import com.dualearbuds.companion.net.Errors
import com.dualearbuds.companion.net.InfoClient
import com.dualearbuds.companion.net.InfoResult
import com.dualearbuds.companion.net.LinkParser
import com.dualearbuds.companion.net.LinkTarget
import com.dualearbuds.companion.net.Net
import com.dualearbuds.companion.net.ParseResult
import com.dualearbuds.companion.net.discovery.DiscoveredHost
import com.dualearbuds.companion.net.discovery.NsdBrowser
import com.dualearbuds.companion.net.discovery.target
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlayerStatus
import com.dualearbuds.companion.setup.SetupChecks
import com.dualearbuds.companion.setup.SetupFactsReader
import com.dualearbuds.companion.ui.Permissions
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ScopeMath
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.Segmented
import com.dualearbuds.companion.ui.components.SliderRow
import com.dualearbuds.companion.ui.components.StatusChip
import com.dualearbuds.companion.ui.components.StatusDot
import com.dualearbuds.companion.ui.components.Tone
import com.dualearbuds.companion.ui.components.TopBar
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.theme.AppTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Result of checking a pasted/scanned link (GET /info) before joining. */
private sealed interface CheckState {
    data object None : CheckState
    data object Checking : CheckState
    data class Found(val name: String, val rate: Int) : CheckState
    data class Problem(val message: String) : CheckState
}

/** Who we are about to join when a PIN is needed. */
private data class PendingJoin(val target: LinkTarget, val name: String)

private fun scanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Point the camera at the QR code on the laptop or the sharing phone")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

private fun showSystemVolumePanel(context: Context) {
    try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    } catch (_: Throwable) {
    }
}

@Composable
fun HomeScreen(
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCalibration: () -> Unit,
    onShareThisPhone: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val player by PlaybackController.state.collectAsStateWithLifecycle()
    val volume by PlaybackController.volume.collectAsStateWithLifecycle()
    val earbuds by EarbudsMonitor.state.collectAsStateWithLifecycle()
    val recent by AppSettings.recent.collectAsStateWithLifecycle()
    val nearby by NsdBrowser.hosts.collectAsStateWithLifecycle()
    val searching by NsdBrowser.searching.collectAsStateWithLifecycle()
    val eqManual by SoundSettings.eqManual.collectAsStateWithLifecycle()
    val latencies by SoundSettings.outputLatency.collectAsStateWithLifecycle()

    var linkText by rememberSaveable { mutableStateOf(Prefs.lastLink) }
    var check by remember { mutableStateOf<CheckState>(CheckState.None) }
    var pinFor by remember { mutableStateOf<PendingJoin?>(null) }
    var pinWasWrong by remember { mutableStateOf(false) }
    var needLink by remember { mutableStateOf<DiscoveredHost?>(null) }
    var showBatteryOffer by remember { mutableStateOf(false) }
    var setupTick by remember { mutableIntStateOf(0) }

    // Nearby search only while Home is visible.
    DisposableEffect(Unit) {
        NsdBrowser.start(context)
        onDispose { NsdBrowser.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        EarbudsMonitor.refresh()
        setupTick++
    }
    // Setup check summary for the status chip (shown only when something needs attention).
    val setupChip = remember(setupTick, earbuds, latencies, player.target, player.powerSave) {
        SetupChecks.chipText(SetupChecks.evaluate(SetupFactsReader.read(context)))
    }

    // Errors that need the user: a PIN prompt; a wrong PIN counts once towards the local cool-down.
    LaunchedEffect(player.error) {
        val e = player.error ?: return@LaunchedEffect
        val t = player.target ?: return@LaunchedEffect
        if (PinGate.handledError === e) return@LaunchedEffect
        PinGate.handledError = e
        when (e.kind) {
            ErrorKind.PIN_NEEDED -> {
                pinWasWrong = false
                pinFor = PendingJoin(t.withoutPin(), player.pcName)
            }
            ErrorKind.WRONG_PIN -> {
                PinGate.policy.onWrong(System.currentTimeMillis())
                pinWasWrong = true
                pinFor = PendingJoin(t.withoutPin(), player.pcName)
            }
            else -> Unit
        }
    }
    // First real playback: PIN accepted; offer the Setup check once (never on first launch, never up front).
    LaunchedEffect(player.status) {
        if (player.status == PlayerStatus.PLAYING) {
            PinGate.policy.onAccepted()
            if (!Prefs.batteryOffered && !BackgroundSettings.isIgnoringBatteryOptimizations(context)) {
                Prefs.batteryOffered = true
                showBatteryOffer = true
            }
        }
    }

    /** Playback needs no runtime permission, so nothing is asked here; Setup check asks with reasons. */
    fun start(target: LinkTarget) {
        check = CheckState.None
        linkText = target.withoutPin().toLink()
        PlaybackController.start(context, target)
    }

    fun join(target: LinkTarget, name: String, pinRequired: Boolean) {
        if (pinRequired && target.pin.isNullOrEmpty()) {
            pinWasWrong = false
            pinFor = PendingJoin(target, name)
        } else {
            start(target)
        }
    }

    fun parseOrReport(text: String): LinkTarget? = when (val r = LinkParser.parse(text)) {
        is ParseResult.Ok -> r.target
        is ParseResult.Error -> {
            check = CheckState.Problem(r.message)
            null
        }
    }

    /** Checks the link (GET /info), then joins straight away — asking for the PIN first when the room has one. */
    fun checkAndJoin(text: String) {
        val target = parseOrReport(text) ?: return
        PlaybackController.clearError()
        check = CheckState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { InfoClient.fetch(Net.infoHttp, target) }
            when (result) {
                is InfoResult.Ok -> {
                    val i = result.info
                    Prefs.lastLink = target.toLink()
                    AppSettings.rememberHost(target, i.pc, i.host)
                    check = CheckState.Found(i.pc, i.rate)
                    join(target, i.pc, i.pin)
                }
                else -> check = CheckState.Problem(Errors.from(result, context, target)?.message ?: "Couldn't reach the host.")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            linkText = contents.trim()
            checkAndJoin(linkText)
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
            check = CheckState.Problem("The camera is only used to read the QR code. You can paste the link instead.")
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        EarbudsMonitor.refresh()
        setupTick++
    }

    fun launchScanner() {
        if (Permissions.has(context, Manifest.permission.CAMERA)) {
            try {
                scanLauncher.launch(scanOptions())
            } catch (_: Throwable) {
                check = CheckState.Problem("Couldn't open the camera. Paste the link instead.")
            }
        } else {
            Prefs.askedCamera = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun pasteLink() {
        val pasted = clipboard.getText()?.text?.trim().orEmpty()
        if (pasted.isEmpty()) {
            check = CheckState.Problem("The clipboard is empty. Copy the link on the host first.")
        } else {
            linkText = pasted
            checkAndJoin(pasted)
        }
    }

    val live = player.status == PlayerStatus.PLAYING && !player.userMuted
    val readout = when {
        player.running && player.status != PlayerStatus.CONNECTING ->
            ScopeMath.readout(player.rate, player.playoutMs, player.transport, player.synced, player.syncErrorMs, player.targetMs)
        player.running -> "connecting"
        else -> "idle"
    }

    Scaffold(
        containerColor = AppTheme.colors.bg,
        topBar = {
            TopBar(title = "Dual Earbuds", readout = readout, live = live) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings",
                        tint = AppTheme.colors.muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            val chip = setupChip
            if (chip != null) {
                StatusChip(Tone.WARN, chip, modifier = Modifier.padding(top = 12.dp), onClick = onOpenSetup)
            }

            if (player.status != PlayerStatus.IDLE) {
                NowPlayingSection(
                    player = player,
                    onStop = { PlaybackController.stop(context) },
                    onRetry = { checkAndJoin(linkText) },
                    onEnterPin = { player.target?.let { pinFor = PendingJoin(it.withoutPin(), player.pcName) } }
                )
            }

            JoinSection(
                enabled = !player.running,
                nearby = nearby,
                searching = searching,
                recent = recent,
                linkText = linkText,
                onLinkChange = {
                    linkText = it
                    check = CheckState.None
                    PlaybackController.clearError()
                },
                onPaste = { pasteLink() },
                onScan = { launchScanner() },
                onSubmitLink = { checkAndJoin(linkText) },
                onNearby = { host ->
                    val t = host.target()
                    if (t != null) join(t, host.name, host.pinRequired) else needLink = host
                },
                onRecent = { r: RecentHost ->
                    linkText = r.link
                    checkAndJoin(r.link)
                },
                checkLine = { CheckLine(check) }
            )

            EarbudsSection(
                earbuds = earbuds,
                calibratedMs = if (earbuds.connected) latencies[earbuds.name] else null,
                onAllowBluetooth = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Prefs.askedBluetooth = true
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onOpenBluetooth = { BackgroundSettings.openBluetoothSettings(context) },
                onCalibrate = onOpenCalibration
            )

            Section("Sound") {
                Panel {
                    PanelBody {
                        SliderRow(
                            label = "Volume",
                            valueText = "$volume%",
                            value = volume.toFloat(),
                            onValueChange = { PlaybackController.setVolume((it / 5f).roundToInt() * 5) },
                            valueRange = 0f..Prefs.MAX_VOLUME.toFloat(),
                            hint = if (volume > 100) "Above 100% can distort loud sounds." else null,
                            valueColor = if (volume > 100) AppTheme.colors.warn else androidx.compose.ui.graphics.Color.Unspecified
                        )
                    }
                    PanelDivider()
                    PanelBody {
                        Text("Equalizer", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text)
                        val options = listOf("auto" to "Auto") + EqPreset.entries.map { it.wire to it.label }
                        Segmented(
                            options = options,
                            selected = eqManual?.wire ?: "auto",
                            onSelect = { w -> SoundSettings.setEqManual(EqPreset.fromWire(w)) }
                        )
                        Hint(
                            if (eqManual == null) {
                                "Auto follows the host or Smart EQ" + if (player.running) ", now ${player.eqInUse.label}" else ""
                            } else {
                                "Your choice stays until you pick Auto."
                            }
                        )
                    }
                    PanelDivider()
                    PanelRow("Phone media volume", value = "system", onClick = { showSystemVolumePanel(context) })
                }
            }

            Section("This phone as host") {
                Panel {
                    PanelRow(
                        "Share this phone",
                        subtitle = "Send this phone's sound to other phones, in sync",
                        leading = painterResource(R.drawable.ic_share),
                        chevron = true,
                        onClick = onShareThisPhone
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    pinFor?.let { pending ->
        PinDialog(
            hostName = pending.name,
            wrongBefore = pinWasWrong,
            onSubmit = { pin ->
                pinFor = null
                start(pending.target.copy(pin = pin))
            },
            onDismiss = { pinFor = null }
        )
    }
    needLink?.let { host ->
        NeedLinkDialog(
            hostName = host.name,
            onScan = {
                needLink = null
                launchScanner()
            },
            onPaste = {
                needLink = null
                pasteLink()
            },
            onDismiss = { needLink = null }
        )
    }
    if (showBatteryOffer) {
        BatteryOfferDialog(
            onOpenGuide = {
                showBatteryOffer = false
                onOpenSetup()
            },
            onDismiss = { showBatteryOffer = false }
        )
    }
}

@Composable
private fun CheckLine(check: CheckState) {
    val c = AppTheme.colors
    when (check) {
        CheckState.None -> Unit
        CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.muted)
            Spacer(Modifier.width(8.dp))
            Text("Checking the link…", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        is CheckState.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Tone.GOOD)
            Spacer(Modifier.width(6.dp))
            val rateText = if (check.rate > 0) ScopeMath.kHz(check.rate) else "not sharing sound yet"
            Text("Found ${check.name}, $rateText", style = MaterialTheme.typography.bodySmall, color = c.text)
        }
        is CheckState.Problem -> Banner(text = check.message)
    }
}
