package com.dualearbuds.host.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** A permission about to be asked, with its one-line reason. */
internal data class PermissionAsk(val permission: String, val title: String, val reason: String, val then: (Boolean) -> Unit)

/** Shows the one-line reason before Android's own prompt. */
@Composable
internal fun PermissionReasonDialog(ask: PermissionAsk, onContinue: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(ask.title) },
        text = { Text(ask.reason) },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Not now") } }
    )
}
