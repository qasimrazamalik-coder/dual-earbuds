package com.dualearbuds.companion.diag

import android.content.Context
import android.os.Build
import com.dualearbuds.companion.BuildConfig
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.power.PowerState
import com.dualearbuds.companion.service.PlaybackController

/** Gathers "Copy diagnostics" text. The link key and PIN are never included (see [Redact]). */
object Diagnostics {
    fun collect(context: Context? = null): String {
        val p = PlaybackController.state.value
        val b = PlaybackController.buffer.value
        val e = EarbudsMonitor.state.value
        return DiagnosticsReport.build(
            DiagnosticsInput(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = Build.VERSION.RELEASE ?: "?",
                sdk = Build.VERSION.SDK_INT,
                model = Build.MODEL ?: "?",
                brand = Build.BRAND ?: (Build.MANUFACTURER ?: "?"),
                status = p.status.name + (p.error?.let { " (${it.kind})" } ?: ""),
                hostKind = p.hostKind.label,
                hostVersion = p.hostVersion,
                protocol = p.protocol,
                bufferMode = b.mode.name + (if (b.delayMs > 0) " +${b.delayMs} ms delay" else ""),
                bufferTargetMs = p.targetMs,
                bufferQueuedMs = p.bufferMs,
                jitterP99Ms = p.jitterP99Ms,
                underruns = p.underruns,
                lostPackets = p.lost,
                concealedPackets = p.concealed,
                trimmedPackets = p.dropped,
                synced = p.synced,
                syncOffsetMs = p.syncOffsetMs,
                syncRttMs = p.syncRttMs,
                syncErrorMs = if (p.synced) p.syncErrorMs else null,
                outputLatencyMs = p.outputLatencyMs ?: if (e.connected) SoundSettings.latencyFor(e.name) else null,
                earbuds = if (e.connected) e.name + (if (e.battery in 0..100) " ${e.battery}%" else "") else "",
                eq = (SoundSettings.eqManual.value?.label ?: "Auto") + " → " + p.eqInUse.label +
                    " · Smart EQ ${SoundSettings.smartEq.value.label}",
                dialogue = SoundSettings.dialogue.value.label,
                logLines = AppLog.ring.snapshot(),
                transport = p.transport,
                playoutMs = p.playoutMs,
                needMs = p.needMs,
                usedLatency = p.usedLatencyMs?.let { String.format(java.util.Locale.US, "%.0f ms (%s)", it, p.latencySource?.label ?: "?") } ?: "",
                power = context?.let { PowerState.read(it).summary() } ?: ""
            )
        )
    }
}
