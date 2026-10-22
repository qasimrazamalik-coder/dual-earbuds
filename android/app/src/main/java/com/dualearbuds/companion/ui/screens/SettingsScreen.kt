package com.dualearbuds.companion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualearbuds.companion.R
import com.dualearbuds.companion.data.AppSettings
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.ThemeMode
import com.dualearbuds.companion.diag.Diagnostics
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.components.ActionRow
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.SecondaryButton
import com.dualearbuds.companion.ui.components.Segmented
import com.dualearbuds.companion.ui.components.SliderRow
import com.dualearbuds.companion.ui.components.SwitchRow
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.update.AppUpdater
import com.dualearbuds.companion.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSmart: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenHearing: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenBestSound: () -> Unit
) {
    val context = LocalContext.current
    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val autoUpdate by AppSettings.autoUpdate.collectAsStateWithLifecycle()
    val buffer by PlaybackController.buffer.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Settings", onBack = onBack, readout = "v${AppUpdater.currentVersion}") {
        Section("Sound") {
            Panel {
                PanelRow("Smart features", subtitle = "Smart EQ, dialogue boost, smart buffer", leading = painterResource(R.drawable.ic_tune), chevron = true, onClick = onOpenSmart)
                PanelDivider()
                PanelRow("Calibrate earbuds", subtitle = "Measure their delay so every ear hears the same moment", leading = painterResource(R.drawable.ic_mic), chevron = true, onClick = onOpenCalibration)
                PanelDivider()
                PanelRow("Hearing profiles", subtitle = "A short tone test that tunes the sound to you", leading = painterResource(R.drawable.ic_earbuds), chevron = true, onClick = onOpenHearing)
                PanelDivider()
                PanelBody {
                    SliderRow(
                        label = "Extra delay",
                        valueText = "${buffer.delayMs} ms",
                        value = buffer.delayMs.toFloat(),
                        onValueChange = { PlaybackController.setDelayMs((it / 5f).roundToInt() * 5) },
                        valueRange = 0f..Prefs.MAX_DELAY_MS.toFloat(),
                        hint = "Makes this phone later by hand, e.g. to match a speaker. Never changed automatically."
                    )
                }
            }
        }

        Section("Playback") {
            Panel {
                PanelRow("Setup check", subtitle = "Battery, Bluetooth and Wi-Fi items that affect playback", chevron = true, onClick = onOpenSetup)
                PanelDivider()
                PanelRow("For the best sound", subtitle = "Five habits that cut delay and dropouts", chevron = true, onClick = onOpenBestSound)
                PanelDivider()
                PanelRow("Phone maker battery steps", subtitle = "Samsung, Xiaomi, Realme, OPPO, Vivo", chevron = true, onClick = onOpenGuide)
            }
        }

        Section("Appearance") {
            Panel {
                PanelBody {
                    Segmented(
                        options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                        selected = theme,
                        onSelect = { AppSettings.setTheme(it) }
                    )
                }
            }
        }

        UpdatesSection(autoUpdate)

        Section("Help") {
            Panel {
                PanelRow(
                    "Copy diagnostics",
                    subtitle = "Versions, buffer and sync numbers, last 200 log lines. Never the link key or PIN.",
                    onClick = { copyDiagnostics(context) }
                )
                PanelDivider()
                PanelRow("About", chevron = true, onClick = onOpenAbout)
            }
        }
    }
}

private fun copyDiagnostics(context: Context) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dual Earbuds diagnostics", Diagnostics.collect(context)))
        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(context, "Couldn't copy diagnostics", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun UpdatesSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val c = AppTheme.colors
    Section("Updates", count = "v${AppUpdater.currentVersion}") {
        Panel {
            PanelBody {
                SwitchRow(
                    title = "Check once a day",
                    subtitle = "Only asks GitHub. Nothing downloads until you tap Download.",
                    checked = autoUpdate,
                    onCheckedChange = { AppSettings.setAutoUpdate(it) }
                )
            }
            PanelDivider()
            PanelBody {
                when (val s = state) {
                    UpdateState.Idle -> SecondaryButton("Check for updates", onClick = { AppUpdater.checkNow() })
                    UpdateState.Checking -> {
                        Text("Checking…", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                    }
                    is UpdateState.UpToDate -> {
                        Text("Up to date (${s.latest}).", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        QuietButton("Check again", onClick = { AppUpdater.checkNow() })
                    }
                    is UpdateState.Available -> {
                        Text("Version ${s.release.version} is available.", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        if (s.release.notes.isNotBlank()) Hint(s.release.notes.take(600))
                        ActionRow {
                            PrimaryButton("Download", onClick = { AppUpdater.download(context, s.release) }, enabled = s.release.apk != null)
                            if (s.release.pageUrl.startsWith("https://github.com/")) {
                                QuietButton("What's new", onClick = { openUrl(context, s.release.pageUrl) })
                            }
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text("Downloading ${s.release.version}…", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                        }
                    }
                    is UpdateState.ReadyToInstall -> {
                        Text("Ready to install ${s.release.version}.", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        PrimaryButton("Install", onClick = { AppUpdater.install(context, s.release, s.file) })
                    }
                    is UpdateState.NeedsInstallPermission -> {
                        Text(
                            "Android needs your OK to install updates from this app. Turn on Allow from this source, then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text
                        )
                        PrimaryButton("Open setting", onClick = { AppUpdater.openInstallPermissionSettings(context) })
                    }
                    is UpdateState.Problem -> {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = c.warn)
                        QuietButton("OK", onClick = { AppUpdater.dismissProblem() })
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}
