package com.dualearbuds.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PLACEHOLDER. The real "Share this phone" screen lives in the feat/host branch and replaces this file
 * (and the whole android/host module) on merge. Same package and signature so the app compiles either way.
 */
@Composable
fun HostScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Share this phone", style = MaterialTheme.typography.headlineSmall)
            Text("Coming soon", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
