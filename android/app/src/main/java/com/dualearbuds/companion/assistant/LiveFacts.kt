package com.dualearbuds.companion.assistant

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One short line about the session right now; [keywords] help the scorer match it to questions. */
data class LiveFact(val text: String, val keywords: String)

/** What the listener side knows (built from the app's player state; plain values so this stays testable). */
data class ListenInputs(
    /** "Playing", "Connecting", "Not connected"… */
    val status: String,
    val connected: Boolean,
    val hostName: String = "",
    /** "laptop", "phone" or "host". */
    val hostNoun: String = "host",
    /** "lipsync" / "together", null when the host didn't say. */
    val priority: String? = null,
    val playoutMs: Int = 0,
    val needMs: Int? = null,
    val synced: Boolean = false,
    val syncErrMs: Double? = null,
    /** "UDP", "WS" or "". */
    val transport: String = "",
    val earbudsName: String? = null,
    val calibratedMs: Double? = null,
    val powerSave: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val onWifi: Boolean? = null,
    /** Wi-Fi frequency in MHz; ≤ 0 unknown. */
    val wifiMhz: Int = -1,
    val lastError: String? = null,
    val bufferMs: Int = 0,
    val underruns: Long = 0,
    val lost: Long = 0,
    val packets: Long = 0
)

/** One listener as the sharing phone sees it. */
data class ShareListener(
    val name: String,
    val needMs: Int?,
    val syncErrMs: Int?,
    /** "UDP", "WS" or "Browser". */
    val transport: String,
    val battery: Int = -1
)

/** What the "Share this phone" side knows while sharing. */
data class ShareInputs(
    val sharing: Boolean,
    val playoutMs: Int = 0,
    val priority: String? = null,
    val listeners: List<ShareListener> = emptyList(),
    val capturedPerSec: Int = 0,
    val peakDb: Int? = null,
    val stalled: Boolean = false,
    val sentPerSec: Int = 0,
    val localOutputName: String? = null,
    val localNeedMs: Int? = null,
    val error: String? = null
)

/** Builds the 4–10 "Right now" lines from the app's (and when sharing, the host module's) state. */
object LiveFactsBuilder {
    const val MAX_FACTS = 10

    fun build(listen: ListenInputs?, share: ShareInputs?): List<LiveFact> {
        val out = ArrayList<LiveFact>()
        if (share != null && share.sharing) shareFacts(share, out)
        if (listen != null) listenFacts(listen, out, sharing = share?.sharing == true)
        return out.take(MAX_FACTS)
    }

    private fun listenFacts(l: ListenInputs, out: MutableList<LiveFact>, sharing: Boolean) {
        if (l.connected) {
            val host = l.hostName.ifBlank { "the ${l.hostNoun}" }
            val how = when (l.transport) {
                "UDP" -> " over UDP"
                "WS" -> " over WebSocket (UDP is not getting through)"
                else -> ""
            }
            out += LiveFact(
                "${l.status} from $host (${l.hostNoun})$how.",
                "connect connected listen host network udp websocket firewall status playing"
            )
            priorityName(l.priority)?.let {
                out += LiveFact("Sync priority on the host: $it.", "priority lipsync lip sync together video late delay behind mode")
            }
            if (l.playoutMs > 0) {
                val why = l.needMs?.let { need ->
                    val parts = ArrayList<String>()
                    l.calibratedMs?.let { parts += "earbuds ${it.roundToInt()} ms" }
                    if (need >= l.playoutMs - 2) " because this phone needs $need ms" + parts.joinToString(prefix = " (", postfix = ")").takeIf { parts.isNotEmpty() }.orEmpty()
                    else "; this phone needs $need ms"
                }.orEmpty()
                out += LiveFact(
                    "P is ${l.playoutMs} ms$why.",
                    "late delay latency slow behind lag sync playout deadline need"
                )
            }
            val err = l.syncErrMs
            if (l.synced && err != null) {
                val ms = err.roundToInt()
                val text = when {
                    abs(ms) <= 5 -> "This phone is in sync (${signed(ms)} from the common deadline)."
                    ms > 0 -> "This phone is $ms ms late against the common deadline."
                    else -> "This phone is ${-ms} ms early against the common deadline."
                }
                out += LiveFact(text, "sync late early behind ahead echo off offset deadline")
            } else if (l.connected) {
                out += LiveFact("Clock sync is not running yet, so the sync offset is unknown.", "sync clock offset unknown echo late")
            }
            if (l.underruns > 0 || l.lost > 0) {
                out += LiveFact(
                    "Since connecting: ${l.lost} packets lost, ${l.underruns} buffer underruns; buffer ${l.bufferMs} ms.",
                    "choppy crackle cut drop dropout stutter gap lost underrun buffer"
                )
            }
        } else {
            out += LiveFact("${l.status}: not connected to a host.", "connect not connected join status")
        }
        val buds = l.earbudsName
        if (buds != null) {
            val cal = l.calibratedMs?.let { "calibrated at ${it.roundToInt()} ms" } ?: "not calibrated (run Calibrate)"
            out += LiveFact("Earbuds: $buds, $cal.", "earbuds bluetooth calibrate calibration latency late delay")
        } else if (!sharing) {
            out += LiveFact("No Bluetooth earbuds are connected to this phone.", "earbuds bluetooth connected pair no sound")
        }
        if (l.powerSave) out += LiveFact("Battery saver is on.", "battery saver power stop stops cut background screen off")
        if (l.backgroundRestricted) out += LiveFact(
            "Android restricts this app in the background.",
            "background restricted battery stop stops cut screen off killed"
        )
        if (l.onWifi == false) {
            out += LiveFact("This phone is not on Wi-Fi.", "wifi network hotspot connect band")
        } else if (l.wifiMhz > 0) {
            val band = if (l.wifiMhz < 3000) "2.4 GHz" else if (l.wifiMhz < 5925) "5 GHz" else "6 GHz"
            val tail = if (l.wifiMhz < 3000) " (crowded; shares the air with Bluetooth)" else ""
            out += LiveFact("Wi-Fi band: $band$tail.", "wifi band network choppy crackle dropout stutter 2 4 5 ghz hotspot")
        }
        l.lastError?.takeIf { it.isNotBlank() }?.let {
            out += LiveFact("Last error: $it", "error fail failed problem connect not working")
        }
    }

    private fun shareFacts(s: ShareInputs, out: MutableList<LiveFact>) {
        val n = s.listeners.size
        val head = when (n) {
            0 -> "Sharing this phone; no listeners yet."
            1 -> "Sharing this phone to 1 listener."
            else -> "Sharing this phone to $n listeners."
        }
        out += LiveFact(head, "share sharing listener listeners phone host connect join")
        priorityName(s.priority)?.let {
            out += LiveFact("Sync priority: $it.", "priority lipsync lip sync together video late delay mode")
        }
        if (s.playoutMs > 0) {
            val worst = s.listeners.filter { it.needMs != null }.maxByOrNull { it.needMs!! }
            val localNeed = s.localNeedMs
            val why = when {
                worst != null && (localNeed == null || worst.needMs!! >= localNeed) ->
                    " because ${worst.name} needs ${worst.needMs} ms"
                localNeed != null -> " because this phone's own ${s.localOutputName?.takeIf { it.isNotBlank() } ?: "output"} needs $localNeed ms"
                else -> ""
            }
            out += LiveFact("P is ${s.playoutMs} ms$why.", "late delay latency slow behind lag sync playout deadline need")
        }
        if (n > 0) {
            val rows = s.listeners.take(4).joinToString("; ") { l ->
                val parts = ArrayList<String>()
                l.needMs?.let { parts += "needs $it ms" }
                l.syncErrMs?.let { parts += signed(it) }
                parts += l.transport
                if (l.battery in 0..100) parts += "battery ${l.battery} %"
                "${l.name} (${parts.joinToString(", ")})"
            }
            out += LiveFact("Listeners: $rows.", "listener listeners phone late behind sync udp websocket battery echo")
        }
        val peak = s.peakDb?.let { "peak $it dB" } ?: "no level"
        out += LiveFact(
            "Capture: ${s.capturedPerSec} packets/s, $peak, sent ${s.sentPerSec}/s.",
            "capture sound audio silent silence no sound share sharing level sent"
        )
        if (s.stalled) out += LiveFact(
            "Capture has stalled: Android is not passing the playing sound to Dual Earbuds.",
            "stalled capture no sound silent silence share sharing app blocks"
        )
        s.error?.takeIf { it.isNotBlank() }?.let { out += LiveFact("Sharing error: $it", "error fail failed share sharing problem") }
    }

    private fun priorityName(wire: String?): String? = when (wire?.lowercase(Locale.US)) {
        "lipsync" -> "Lip-sync (video first)"
        "together" -> "Together (every ear in step)"
        else -> null
    }

    private fun signed(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "−${-ms} ms"
        else -> "0 ms"
    }
}
