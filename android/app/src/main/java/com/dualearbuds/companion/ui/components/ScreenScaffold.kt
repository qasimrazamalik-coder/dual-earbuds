package com.dualearbuds.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

/**
 * Header bar (48 dp): page name at 15 sp semibold on the left, a live mono [readout] on the right (muted, accent
 * dot only when [live]). No subtitle, no large title. A 1 dp hairline under it.
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    readout: String? = null,
    live: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!readout.isNullOrBlank()) {
                    if (live) {
                        StatusDot(Tone.LIVE)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        readout,
                        style = Mono.value,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
            actions()
            if (onBack != null || readout.isNullOrBlank()) Spacer(Modifier.width(4.dp)) else Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** Standard secondary screen: [TopBar] with back arrow, then a scrolling column with a 16 dp gutter. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    readout: String? = null,
    live: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        containerColor = AppTheme.colors.bg,
        topBar = { TopBar(title = title, onBack = onBack, readout = readout, live = live) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}
