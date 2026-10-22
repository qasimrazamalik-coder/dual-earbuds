package com.dualearbuds.host

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.host.device.DeviceProbe
import com.dualearbuds.host.device.SetupFixes
import com.dualearbuds.host.model.Readout
import com.dualearbuds.host.setup.CheckItem
import com.dualearbuds.host.setup.DeviceFacts
import com.dualearbuds.host.setup.Fix
import com.dualearbuds.host.setup.SetupCheck
import com.dualearbuds.host.ui.GuideSection
import com.dualearbuds.host.ui.Hint
import com.dualearbuds.host.ui.LinkSection
import com.dualearbuds.host.ui.ListenersSection
import com.dualearbuds.host.ui.Mono
import com.dualearbuds.host.ui.NetworkSection
import com.dualearbuds.host.ui.Notice
import com.dualearbuds.host.ui.PartySection
import com.dualearbuds.host.ui.PermissionAsk
import com.dualearbuds.host.ui.PermissionReasonDialog
import com.dualearbuds.host.ui.PrimaryButton
import com.dualearbuds.host.ui.QuietButton
import com.dualearbuds.host.ui.SetupSection
import com.dualearbuds.host.ui.SourceSection
import com.dualearbuds.host.ui.TalkSection
import com.dualearbuds.host.ui.Type
import com.dualearbuds.host.ui.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "Share this phone": this phone becomes a host (protocol v3.2) and other phones, laptops or
 * browsers listen. Call it inside the app's MaterialTheme; every colour comes from that theme
 * (see ui/Instrument.kt for which role is used where).
 *
 * @param onBack leaves the screen.
 * @param localOutputLatencyMs optional: calibrated latency (ms) of this phone's own output by device
 *   name, for the common deadline; see [HostConfig.localOutputLatencyMs].
 * @param onCalibrate optional: opens the app's calibration screen; the Setup check then offers it
 *   for uncalibrated earbuds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onBack: () -> Unit,
    localOutputLatencyMs: ((deviceName: String) -> Double?)? = null,
    onCalibrate: (() -> Unit)? = null
) {
    if (localOutputLatencyMs != null) {
        SideEffect { HostConfig.localOutputLatencyMs = localOutputLatencyMs }
    }
    val context = LocalContext.current
    val state by HostController.state.collectAsStateWithLifecycle()
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val t = tokens

    LaunchedEffect(Unit) {
        HostController.init(context)
        while (true) {
            delay(3_000)
            withContext(Dispatchers.IO) { HostController.refreshNetwork(context) }
        }
    }

    // Setup check: re-read the phone every 3 s (cheap), so a fix done in Settings shows on return.
    val facts by produceState<DeviceFacts?>(null, state.addresses, onCalibrate != null) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                try {
                    DeviceProbe.facts(context, state.addresses, onCalibrate != null)
                } catch (_: Throwable) {
                    null
                }
            }
            delay(3_000)
        }
    }
    val checks: List<CheckItem> = facts?.let { SetupCheck.evaluate(it, state.listeners, state.playoutMs) } ?: emptyList()

    var info by remember { mutableStateOf<String?>(null) }
    var settingsHint by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<PermissionAsk?>(null) }
    var pending by remember { mutableStateOf<PermissionAsk?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (p != null && !granted) {
            val activity = context.findActivity()
            // Denied and Android won't ask again: only App info can turn it on now.
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p.permission)) {
                HostController.markPermanentlyDenied(p.permission)
            }
        }
        p?.then?.invoke(granted)
    }

    /** Asks for [permission] with a one-line reason unless granted; then runs [then] either way. */
    fun withPermission(permission: String, title: String, reason: String, then: (Boolean) -> Unit) {
        when {
            hasPermission(context, permission) -> then(true)
            HostController.isPermanentlyDenied(permission) -> then(false)
            else -> asking = PermissionAsk(permission, title, reason, then)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HostController.startSharing(context, result.resultCode, data)
        } else {
            info = "Android's screen-sharing prompt was cancelled. Tap Start sharing and choose the entire screen."
        }
    }

    fun askProjection() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            info = "Sharing is not available on this phone."
            return
        }
        val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole-screen consent: every app's sound can then be captured.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            projectionLauncher.launch(intent)
        } catch (_: Throwable) {
            info = "Sharing is not available on this phone."
        }
    }

    fun begin() {
        info = null
        settingsHint = false
        HostController.clearError()
        HostController.refreshNetwork(context)
        val askAudio = {
            withPermission(
                Manifest.permission.RECORD_AUDIO,
                "Audio access",
                "Android counts copying other apps' sound as recording. The mic stays off unless you use Talk-over."
            ) { ok ->
                if (ok) {
                    askProjection()
                } else {
                    info = "Without audio access this phone can't share other apps' sound."
                    settingsHint = HostController.isPermanentlyDenied(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        // Optional, asked once: the notification carries the Stop button.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !HostController.askedNotifications) {
            HostController.askedNotifications = true
            withPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                "Notifications",
                "Shows that sharing is on, with a Stop button, while the screen is off."
            ) { askAudio() }
        } else {
            askAudio()
        }
    }

    fun toggleTalk(on: Boolean) {
        if (!on) {
            HostController.setTalking(false)
            return
        }
        withPermission(Manifest.permission.RECORD_AUDIO, "Microphone", "So your voice can be mixed into what listeners hear.") { ok ->
            if (ok) {
                HostController.setTalking(true)
            } else {
                info = "Talk-over needs the microphone."
                settingsHint = HostController.isPermanentlyDenied(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun fix(f: Fix) {
        when (f) {
            Fix.CALIBRATE -> onCalibrate?.invoke()
            Fix.NOTIFICATIONS -> {
                val perm = Manifest.permission.POST_NOTIFICATIONS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(context, perm) &&
                    !HostController.isPermanentlyDenied(perm)
                ) {
                    pending = PermissionAsk(perm, "", "") { }
                    try {
                        permissionLauncher.launch(perm)
                    } catch (_: Throwable) {
                        pending = null
                        SetupFixes.open(context, f)
                    }
                } else {
                    SetupFixes.open(context, f)
                }
            }
            else -> if (!SetupFixes.open(context, f)) info = "This phone has no shortcut for that. Look for it in Settings."
        }
    }

    asking?.let { ask ->
        PermissionReasonDialog(
            ask,
            onContinue = {
                asking = null
                pending = ask
                try {
                    permissionLauncher.launch(ask.permission)
                } catch (_: Throwable) {
                    pending = null
                    ask.then(false)
                }
            },
            onSkip = {
                asking = null
                ask.then(false)
            }
        )
    }

    val sharing = state.status == HostStatus.SHARING
    Scaffold(
        containerColor = t.bg,
        contentColor = t.text,
        topBar = { TopBar(onBack, live = sharing, readout = Readout.header(sharing, SAMPLE_RATE, state.playoutMs, state.listeners)) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                if (!supported) {
                    Spacer(Modifier.height(16.dp))
                    Notice(
                        "Sharing this phone's sound needs Android 10 or newer; this phone has Android ${Build.VERSION.RELEASE}. " +
                            "It can still listen to a laptop or another phone.",
                        error = true
                    )
                    return@Column
                }
                StateBlock(state, onStart = { begin() }, onStop = { HostController.stopSharing(context) })
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Notice(it, error = true)
                }
                info?.let {
                    Spacer(Modifier.height(8.dp))
                    Notice(
                        it,
                        action = if (settingsHint) "App info" else null,
                        onAction = if (settingsHint) {
                            {
                                SetupFixes.open(context, Fix.APP_SETTINGS)
                                Unit
                            }
                        } else {
                            null
                        }
                    )
                }
                if (sharing && state.silent) {
                    Spacer(Modifier.height(8.dp))
                    Notice(
                        "No sound for 5 s. If something is playing, that app blocks sharing (Netflix and other protected apps). " +
                            "Try YouTube or a music app."
                    )
                }
                if (sharing) {
                    LinkSection(state)
                    ListenersSection(state)
                }
                SetupSection(checks, onFix = { fix(it) })
                if (sharing) TalkSection(state, onTalk = { toggleTalk(it) })
                PartySection(state)
                SourceSection(state)
                NetworkSection(state)
                GuideSection()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private const val SAMPLE_RATE = 48_000

/** 48 dp bar: page name left, live mono readout right, hairline under it. No subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onBack: () -> Unit, live: Boolean, readout: String) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.bg)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = t.text, modifier = Modifier.size(20.dp))
            }
            Text("Share this phone", style = Type.page, color = t.text)
            Spacer(Modifier.weight(1f))
            if (live) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(t.accent, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
            }
            Mono(readout, color = t.muted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(t.line)
        )
    }
}

/** One state headline, the numbers behind it in mono, and the view's one primary action. */
@Composable
private fun StateBlock(state: HostUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val t = tokens
    val sharing = state.status == HostStatus.SHARING
    val starting = state.status == HostStatus.STARTING
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(Readout.headline(sharing, starting, state.listeners), style = Type.headline, color = t.text)
        when {
            sharing -> {
                val own = state.localOutput?.needMs
                Mono(
                    buildString {
                        append("P ").append(state.playoutMs).append(" ms")
                        if (own != null) append("  ·  this phone ").append(own).append(" ms")
                        append("  ·  ").append(state.listeners.size).append(" of ").append(state.maxClients)
                    },
                    color = t.muted
                )
                Spacer(Modifier.height(6.dp))
                QuietButton("Stop sharing", onClick = onStop, danger = true)
            }
            starting -> {
                Hint("Waiting for Android to allow sharing.")
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Starting", onClick = {}, enabled = false)
            }
            else -> {
                Hint("Other phones, laptops and browsers on this Wi-Fi or hotspot hear what this phone plays.")
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Start sharing", onClick = onStart)
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
