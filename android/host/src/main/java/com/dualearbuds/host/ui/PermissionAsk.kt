package com.dualearbuds.host.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** A permission about to be asked, with its one-line reason. */
internal data class PermissionAsk(val permission: String, val title: String, val reason: String, val then: (Boolean) -> Unit)

/** Shows the one-line reason before Android's own prompt; "Not now" carries on without it. */
@Composable
internal fun PermissionReasonDialog(ask: PermissionAsk, onContinue: () -> Unit, onSkip: () -> Unit) {
    val t = tokens
    AlertDialog(
        onDismissRequest = onSkip,
        shape = RoundedCornerShape(8.dp),
        containerColor = t.surface,
        titleContentColor = t.text,
        textContentColor = t.muted,
        title = { Text(ask.title, style = Type.page) },
        text = { Text(ask.reason, style = Type.body) },
        confirmButton = { RowAction("Continue", onClick = onContinue) },
        dismissButton = { RowAction("Not now", onClick = onSkip) }
    )
}
