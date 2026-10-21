package com.dualearbuds.companion.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.BuildConfig
import com.dualearbuds.companion.R
import com.dualearbuds.companion.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            SectionCard(title = "Dual Earbuds companion", icon = painterResource(R.drawable.ic_earbuds)) {
                Text("Version ${BuildConfig.VERSION_NAME} · phone link protocol v2", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Your laptop can only drive one pair of Bluetooth earbuds well. The Dual Earbuds Windows app streams the laptop's sound over Wi-Fi to this phone, and the phone plays it on a second pair — so two people hear the same thing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard(title = "How to use") {
                Text(
                    "1. On the laptop, open Dual Earbuds and turn on Phone link.\n" +
                        "2. Pair your second earbuds with this phone.\n" +
                        "3. Scan the QR code (or paste the link) and press Start.\n" +
                        "4. Set up \"Keep running in background\" once so it plays with the screen off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard(title = "Privacy") {
                Text(
                    "Everything stays on your own network: the phone talks only to your laptop. No account, no cloud, no analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard(title = "This device") {
                Text(
                    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard(title = "Contact") {
                Text("Made by Qasim Raza Malik", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:iamqasimrazamalik@gmail.com"))
                                .putExtra(Intent.EXTRA_SUBJECT, "Dual Earbuds (Android)")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                    }
                }) { Text("iamqasimrazamalik@gmail.com") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
