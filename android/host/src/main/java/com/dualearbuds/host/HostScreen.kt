package com.dualearbuds.host

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.host.ui.HostCard
import com.dualearbuds.host.ui.Hint
import com.dualearbuds.host.ui.LinkSection
import com.dualearbuds.host.ui.ListenersSection
import com.dualearbuds.host.ui.NetworkSection
import com.dualearbuds.host.ui.Note
import com.dualearbuds.host.ui.PartySection
import com.dualearbuds.host.ui.PermissionAsk
import com.dualearbuds.host.ui.PermissionReasonDialog
import com.dualearbuds.host.ui.SourceSection
import com.dualearbuds.host.ui.TalkSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "Share this phone": this phone becomes a host (protocol v3 + v3.1) and other phones, laptops or
 * browsers listen. Call it inside the app's MaterialTheme; every colour comes from that theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") localOutputLatencyMs: Int? = null,
    @Suppress("UNUSED_PARAMETER") onCalibrate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val state by HostController.state.collectAsStateWithLifecycle()
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    LaunchedEffect(Unit) {
        HostController.init(context)
        while (true) {
            delay(3_000)
            withContext(Dispatchers.IO) { HostController.refreshNetwork(context) }
        }
    }

    var info by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<PermissionAsk?>(null) }
    var pending by remember { mutableStateOf<PermissionAsk?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        p?.then?.invoke(granted)
    }

    /** Asks for [permission] with a one-line reason unless already granted; then runs [then]. */
    fun withPermission(permission: String, title: String, reason: String, then: (Boolean) -> Unit) {
        if (hasPermission(context, permission)) then(true) else asking = PermissionAsk(permission, title, reason, then)
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HostController.startSharing(context, result.resultCode, data)
        } else {
            info = "Sharing needs your OK on Android's screen. Tap Start sharing to try again."
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
        HostController.clearError()
        HostController.refreshNetwork(context)
        val askAudio = {
            withPermission(
                Manifest.permission.RECORD_AUDIO,
                "Audio access",
                "Android counts copying other apps' sound as recording; the mic stays off unless you use Talk-over."
            ) { ok ->
                if (ok) {
                    askProjection()
                } else {
                    info = "Without audio access this phone can't share other apps' sound."
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                "Notifications",
                "Shows that sharing is on, with a Stop button."
            ) { askAudio() } // optional: carry on either way
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
            }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Share this phone") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!supported) {
                    Note(
                        "Sharing this phone's sound needs Android 10 or newer. This phone runs Android ${Build.VERSION.RELEASE}. " +
                            "It can still listen to a laptop or another phone.",
                        error = true
                    )
                    return@Column
                }
                StatusCard(state, onStart = { begin() }, onStop = { HostController.stopSharing(context) })
                state.error?.let { Note(it, error = true) }
                info?.let { Note(it) }
                if (state.status == HostStatus.SHARING) {
                    if (state.silent) {
                        Note(
                            "No sound is coming through. If something is playing, that app may block sharing (Netflix and other DRM apps do). " +
                                "Try YouTube, Spotify or another music app."
                        )
                    }
                    LinkSection(state)
                    ListenersSection(state)
                    TalkSection(state, onTalk = { toggleTalk(it) })
                }
                SourceSection(state)
                PartySection(state)
                NetworkSection(state)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(state: HostUiState, onStart: () -> Unit, onStop: () -> Unit) {
    HostCard(title = null) {
        Text(
            when (state.status) {
                HostStatus.SHARING -> if (state.listeners.isEmpty()) "Sharing · waiting for listeners" else "Sharing with ${state.listeners.size} of ${state.maxClients}"
                HostStatus.STARTING -> "Starting…"
                HostStatus.IDLE -> "Play this phone's sound on other phones and their earbuds"
            },
            style = MaterialTheme.typography.titleMedium
        )
        if (state.status == HostStatus.IDLE) {
            Hint("Through the Dual Earbuds app or any browser on the same Wi-Fi or this phone's hotspot. No internet or account needed.")
        }
        when (state.status) {
            HostStatus.SHARING -> Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("Stop sharing") }
            HostStatus.STARTING -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Starting…") }
            HostStatus.IDLE -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start sharing") }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
