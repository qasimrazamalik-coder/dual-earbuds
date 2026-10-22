package com.dualearbuds.companion.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dualearbuds.companion.BuildConfig
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.StepLine
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dualearbuds.companion.ui.theme.AppTheme

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ScreenScaffold(title = "About", onBack = onBack, readout = "v${BuildConfig.VERSION_NAME} · protocol 3.2") {
        Section("What it does") {
            Panel {
                PanelBody {
                    Text(
                        "A laptop or a phone shares its sound over Wi-Fi; this phone plays it on its own earbuds, " +
                            "at the same moment as every other listener.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.text
                    )
                }
            }
        }
        Section("How to use", count = "4") {
            Panel {
                PanelBody {
                    StepLine(1, "On the host, start sharing (Windows app, or Share this phone on Android).")
                    StepLine(2, "Connect your earbuds to this phone.")
                    StepLine(3, "Join from Nearby, scan the QR code or paste the link.")
                    StepLine(4, "Open Setup check once so it keeps playing with the screen off.")
                }
            }
        }
        Section("Privacy") {
            Panel {
                PanelBody {
                    Text(
                        "Everything stays on your own network. No account, no cloud, no analytics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.text
                    )
                }
            }
        }
        Section("This device") {
            Panel {
                PanelRow("Phone", value = "${Build.MANUFACTURER} ${Build.MODEL}")
                PanelDivider()
                PanelRow("Android", value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        }
        Section("Contact") {
            Panel {
                PanelRow(
                    "Qasim Raza Malik",
                    subtitle = "iamqasimrazamalik@gmail.com",
                    chevron = true,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:iamqasimrazamalik@gmail.com"))
                                    .putExtra(Intent.EXTRA_SUBJECT, "Dual Earbuds (Android)")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) {
                        }
                    }
                )
            }
        }
    }
}
