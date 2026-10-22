package com.dualearbuds.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.power.Brand
import com.dualearbuds.companion.power.GuideStep
import com.dualearbuds.companion.power.OpenResult
import com.dualearbuds.companion.ui.components.Banner
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.StatusDot
import com.dualearbuds.companion.ui.components.Tone
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

/** The phone maker's own battery steps (Setup check → "Steps"). The generic items live in Setup check. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val detected = remember { BackgroundSettings.detectBrand() }
    var brandName by rememberSaveable { mutableStateOf(detected.name) }
    val brand = Brand.entries.firstOrNull { it.name == brandName } ?: detected
    var hint by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(Prefs.brandStepsDone) }

    fun report(result: OpenResult) {
        hint = when (result) {
            OpenResult.EXACT -> null
            OpenResult.FALLBACK -> "That screen didn't open directly, so App info opened. Look for Battery, Autostart or Background activity there."
            OpenResult.FAILED -> "Couldn't open settings. Open Settings → Apps → Dual Earbuds yourself."
        }
    }

    ScreenScaffold(title = "Battery steps", onBack = onBack, readout = brand.label) {
        Section("Phone maker", count = "${Brand.entries.size}") {
            Panel {
                Brand.entries.forEachIndexed { i, b ->
                    if (i > 0) PanelDivider()
                    PanelRow(
                        b.label,
                        value = if (b == detected) "this phone" else null,
                        trailing = { if (b == brand) StatusDot(Tone.LIVE) },
                        onClick = {
                            brandName = b.name
                            hint = null
                        }
                    )
                }
            }
        }

        val steps = BackgroundSettings.steps(brand)
        Section("Steps", count = "${steps.size}") {
            Panel {
                steps.forEachIndexed { i, step ->
                    if (i > 0) PanelDivider()
                    StepRow(i + 1, step) { report(it) }
                }
            }
        }

        hint?.let { Banner(text = it, color = AppTheme.colors.warn, modifier = Modifier.padding(top = 16.dp)) }

        Section("When you're done") {
            Panel {
                PanelBody {
                    Hint("Android can't tell apps whether these switches are set, so mark them done yourself.")
                    PrimaryButton(
                        if (done) "Marked done" else "Mark done",
                        onClick = {
                            Prefs.brandStepsDone = true
                            done = true
                            onBack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: GuideStep, onResult: (OpenResult) -> Unit) {
    val context = LocalContext.current
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("$number", style = Mono.value, color = c.muted, modifier = Modifier.width(20.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        val action = step.action
        if (action != null) QuietButton(action.label, onClick = { onResult(action.open(context)) })
    }
}
