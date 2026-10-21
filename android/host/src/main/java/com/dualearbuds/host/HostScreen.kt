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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.host.server.ListenerInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Accent colours matching the app's dark theme (deep navy, electric teal). */
private object HostColors {
    val Teal = Color(0xFF14E0C2)
    val TealDeep = Color(0xFF0A8F7C)
    val TealInk = Color(0xFF02201B)
    val Amber = Color(0xFFFFC857)
    val Coral = Color(0xFFFF6B7A)
    val Sky = Color(0xFF7FA8FF)
}

/**
 * "Share this phone": this phone becomes a host (protocol v3) and other phones listen through the
 * Dual Earbuds app or any browser. Call it inside the app's theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by HostController.state.collectAsStateWithLifecycle()
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    LaunchedEffect(Unit) {
        HostController.init(context)
        while (true) {
            delay(3_000)
            HostController.refreshNetwork(context)
        }
    }

    var info by remember { mutableStateOf<String?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HostController.startSharing(context, result.resultCode, data)
        } else {
            info = "Sharing needs your OK on the next screen. Tap Start to try again."
        }
    }

    fun askProjection() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            info = "Sharing is not available on this phone."
            return
        }
        val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole-screen consent so every app's sound can be captured (not a single app).
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

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            askProjection()
        } else {
            info = "Without the audio permission, this phone can't share other apps' sound."
        }
    }

    fun askMicThenProjection() {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) askProjection()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        askMicThenProjection() // the notification is optional; carry on either way
    }

    fun begin() {
        info = null
        HostController.clearError()
        HostController.refreshNetwork(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askMicThenProjection()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Share this phone") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Play this phone's sound on other phones and their earbuds — through the Dual Earbuds app or any browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (!supported) {
                Notice(
                    text = "Sharing this phone's sound needs Android 10 or newer. This phone runs Android ${Build.VERSION.RELEASE}. " +
                        "It can still listen to a laptop or another phone.",
                    color = HostColors.Amber,
                    icon = Icons.Filled.Info
                )
            }

            val sharing = state.status == HostStatus.SHARING
            val starting = state.status == HostStatus.STARTING
            ShareButton(
                running = sharing,
                busy = starting,
                enabled = supported && !starting,
                onClick = { if (sharing) HostController.stopSharing(context) else begin() }
            )
            Text(
                when {
                    sharing -> if (state.listeners.isEmpty()) "Sharing · waiting for listeners" else "Sharing with ${state.listeners.size} of ${state.maxClients}"
                    starting -> "Starting…"
                    else -> "Not sharing"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (sharing) HostColors.Teal else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (supported && !sharing && !starting && !hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                Text(
                    "Android will ask for audio access: it's how Android lets an app copy other apps' sound. The microphone is never used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            (state.error ?: info)?.let { Notice(text = it, color = HostColors.Coral, icon = Icons.Filled.Warning) }

            AnimatedVisibility(visible = sharing && state.silent) {
                Notice(
                    text = "No sound is coming through. If something is playing, that app may block sharing its sound (Netflix does). Try YouTube, Spotify or another music app.",
                    color = HostColors.Amber,
                    icon = Icons.Filled.Warning
                )
            }

            if (!state.onWifi) {
                Notice(
                    text = if (state.address != null) {
                        "Not on Wi-Fi. Other phones must join this phone's hotspot, then scan the code."
                    } else {
                        "Not on Wi-Fi. Connect to Wi-Fi, or turn on this phone's hotspot and let the other phones join it."
                    },
                    color = HostColors.Sky,
                    icon = Icons.Filled.Info
                )
            }

            val link = state.link
            if (sharing && link != null) {
                LinkCard(link = link, onNewLink = { HostController.newLink(context) })
            } else if (sharing) {
                Notice(
                    text = "No network address yet. Join a Wi-Fi network or turn on the hotspot.",
                    color = HostColors.Amber,
                    icon = Icons.Filled.Warning
                )
            }

            HostCard(title = "Party mode") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Up to 16 listeners", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Normally 8. More listeners need a strong Wi-Fi signal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.party,
                        onCheckedChange = { HostController.setParty(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HostColors.TealInk,
                            checkedTrackColor = HostColors.Teal
                        )
                    )
                }
            }

            if (sharing) {
                HostCard(title = "Listeners · ${state.listeners.size}/${state.maxClients}") {
                    if (state.listeners.isEmpty()) {
                        Text(
                            "Nobody yet. On the other phone, scan the code with the Dual Earbuds app or the camera.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.listeners.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ListenerRow(l)
                        }
                    }
                }
            }

            HostCard(title = "Good to know") {
                Text(
                    "Some apps, like Netflix, block sharing their sound. YouTube, Spotify and most music apps work.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "This phone keeps playing its own sound as usual. Everything stays on your Wi-Fi or hotspot — no internet or account needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun LinkCard(link: String, onNewLink: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val qr = remember(link) { QrBitmap.create(link)?.asImageBitmap() }
    var copied by remember(link) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    HostCard(title = "Scan to listen") {
        if (qr != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = "QR code of the listening link",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .padding(10.dp)
                )
            }
        }
        Text(
            "Use the Dual Earbuds app or the phone's camera. Any browser on the same Wi-Fi or hotspot can open the link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                }
        ) {
            Text(
                link,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (copied) "Copied" else "Copy link") }
            OutlinedButton(onClick = onNewLink, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New link")
            }
        }
        Text(
            "New link disconnects everyone and makes the old code stop working.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ListenerRow(l: ListenerInfo) {
    var dragging by remember(l.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (l.playing) HostColors.Teal else HostColors.Amber)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    l.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listenerDetails(l),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { HostController.kick(l.id) }) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = HostColors.Coral)
                Spacer(Modifier.width(4.dp))
                Text("Remove", color = HostColors.Coral)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            val shown = dragging ?: l.volume.toFloat()
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { HostController.setVolume(l.id, it.roundToInt()) }
                    dragging = null
                },
                valueRange = 0f..150f,
                colors = SliderDefaults.colors(
                    thumbColor = HostColors.Teal,
                    activeTrackColor = HostColors.Teal
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${shown.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

private fun listenerDetails(l: ListenerInfo): String {
    val parts = ArrayList<String>(4)
    parts += if (l.client == "web") "Browser" else "App"
    if (l.earbuds.isNotBlank()) {
        parts += if (l.battery in 0..100) "${l.earbuds} ${l.battery}%" else l.earbuds
    } else if (l.battery in 0..100) {
        parts += "earbuds ${l.battery}%"
    }
    if (l.rttMs >= 0) parts += "${l.rttMs} ms round trip"
    if (!l.playing) parts += "not playing"
    return parts.joinToString(" · ")
}

/** Big round Share / Stop button with a soft pulse while sharing (same look as the app's Start). */
@Composable
private fun ShareButton(running: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "hostPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hostPulseScale"
    )
    val fill = if (running) {
        Brush.radialGradient(listOf(Color(0xFF203154), Color(0xFF141F38)))
    } else {
        Brush.radialGradient(listOf(HostColors.Teal, HostColors.TealDeep))
    }
    val textColor = if (running) HostColors.Teal else HostColors.TealInk
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(188.dp)
                .scale(if (running || busy) pulse else 1f)
                .border(2.dp, HostColors.Teal.copy(alpha = if (running) 0.55f else 0.18f), CircleShape)
        )
        Box(
            Modifier
                .size(164.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "STOP" else "SHARE",
                color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HostCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun Notice(text: String, color: Color, icon: ImageVector) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
