package com.dualearbuds.companion.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.power.Brand
import com.dualearbuds.companion.power.GuideStep
import com.dualearbuds.companion.power.OpenResult
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.SectionCard
import com.dualearbuds.companion.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val detected = remember { BackgroundSettings.detectBrand() }
    var brandName by rememberSaveable { mutableStateOf(detected.name) }
    val brand = Brand.entries.firstOrNull { it.name == brandName } ?: detected

    var ignoringOptimizations by remember { mutableStateOf(BackgroundSettings.isIgnoringBatteryOptimizations(context)) }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    var hint by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoringOptimizations = BackgroundSettings.isIgnoringBatteryOptimizations(context)
        notificationsOk = notificationsAllowed(context)
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOk = granted || notificationsAllowed(context)
    }

    fun report(result: OpenResult) {
        hint = when (result) {
            OpenResult.EXACT -> null
            OpenResult.FALLBACK -> "Your phone didn't open that screen directly, so App info opened instead. Look for Battery, Autostart or Background activity there."
            OpenResult.FAILED -> "Couldn't open settings on this phone. Open Settings → Apps → Dual Earbuds manually."
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Keep running in background") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Phones like ${brand.label} close background apps to save battery. A few switches keep the laptop sound playing with the screen off.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---------------------------------------------- step 1: battery optimisation (all brands)
            SectionCard(
                title = "1 · Ignore battery optimization",
                trailing = { StatusDot(ok = ignoringOptimizations) }
            ) {
                Text(
                    if (ignoringOptimizations) {
                        "Done — Android won't put Dual Earbuds to sleep."
                    } else {
                        "Lets the player keep running while the screen is off. Tap Allow and confirm."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!ignoringOptimizations) {
                    Button(
                        onClick = { report(BackgroundSettings.requestIgnoreBatteryOptimizations(context)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) { Text("Allow") }
                }
            }

            // ---------------------------------------------- step 2: notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SectionCard(
                    title = "2 · Allow the playing notification",
                    trailing = { StatusDot(ok = notificationsOk) }
                ) {
                    Text(
                        "The notification shows what's playing and gives you Stop and volume buttons on the lock screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!notificationsOk) {
                        FilledTonalButton(onClick = {
                            try {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } catch (_: Throwable) {
                                report(BackgroundSettings.openNotificationSettings(context))
                            }
                        }) { Text("Allow notifications") }
                    }
                }
            }

            // ---------------------------------------------- brand picker
            Text("Your phone", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Brand.entries.forEach { b ->
                    FilterChip(
                        selected = b == brand,
                        onClick = {
                            brandName = b.name
                            hint = null
                        },
                        label = { Text(if (b == detected) "${b.label} (this phone)" else b.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // ---------------------------------------------- brand steps
            BackgroundSettings.steps(brand).forEachIndexed { index, step ->
                StepCard(number = index + 3, step = step, onAction = { action -> report(action) })
            }

            val currentHint = hint
            if (currentHint != null) {
                Banner(text = currentHint, color = Palette.Amber)
            }

            SectionCard(title = "Good to know") {
                Text(
                    "• Keep the Dual Earbuds notification — removing it lets Android close the player.\n" +
                        "• Don't use \"Clear all\" / \"Boost\" cleaners on this app.\n" +
                        "• If sound stops with the screen off, raise the buffer to 250–300 ms and check the steps above again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("Done") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun notificationsAllowed(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusDot(ok: Boolean) {
    if (ok) {
        Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = Palette.Teal, modifier = Modifier.size(26.dp))
    } else {
        Icon(Icons.Filled.Warning, contentDescription = "Not done", tint = Palette.Amber, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun StepCard(number: Int, step: GuideStep, onAction: (OpenResult) -> Unit) {
    val context = LocalContext.current
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val action = step.action
                if (action != null) {
                    FilledTonalButton(onClick = { onAction(action.open(context)) }) { Text(action.label) }
                }
            }
        }
    }
}
