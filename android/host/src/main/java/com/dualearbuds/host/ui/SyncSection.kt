package com.dualearbuds.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostUiState
import com.dualearbuds.host.server.SyncPriority

/** v3.3 "Sync priority": lip-sync with this phone's screen (default) or one deadline for every ear. */
@Composable
internal fun SyncSection(state: HostUiState) {
    val context = LocalContext.current
    val p = state.prefs.syncPriority
    SectionLabel("Sync priority", if (p == SyncPriority.LIPSYNC) "lip-sync" else "together")
    Panel {
        PanelRow(
            "Priority",
            if (p == SyncPriority.LIPSYNC) {
                "Sound matches video on this phone. Listeners keep less delay; a rare tiny gap is possible."
            } else {
                "Every ear hears the same moment, with a little more delay for safety."
            },
            trailing = {
                Segmented(
                    options = listOf(SyncPriority.LIPSYNC to "Lip-sync", SyncPriority.TOGETHER to "Together"),
                    selected = p,
                    onSelect = { HostController.setSyncPriority(context, it) }
                )
            }
        )
    }
}
