package com.dualearbuds.companion.diag

import java.util.Locale

/** Everything "Copy diagnostics" includes. Never holds the link key or a PIN. */
data class DiagnosticsInput(
    val appVersion: String,
    val androidVersion: String,
    val sdk: Int,
    val model: String,
    val brand: String,
    val status: String,
    val hostKind: String,
    val hostVersion: String,
    val protocol: Int,
    val bufferMode: String,
    val bufferTargetMs: Int,
    val bufferQueuedMs: Int,
    val jitterP99Ms: Double,
    val underruns: Long,
    val lostPackets: Long,
    val concealedPackets: Long,
    val trimmedPackets: Long,
    val synced: Boolean,
    val syncOffsetMs: Double?,
    val syncRttMs: Double?,
    val syncErrorMs: Double?,
    val outputLatencyMs: Double?,
    val earbuds: String,
    val eq: String,
    val dialogue: String,
    val logLines: List<String>,
    /** v3.2 */
    val transport: String = "",
    val playoutMs: Int = 0,
    val needMs: Int? = null,
    val usedLatency: String = "",
    val power: String = ""
)

object DiagnosticsReport {
    fun build(d: DiagnosticsInput): String {
        fun num(v: Double?, unit: String = " ms") = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.1f%s", v, unit)
        val sb = StringBuilder()
        sb.appendLine("Dual Earbuds diagnostics")
        sb.appendLine("App ${d.appVersion} · Android ${d.androidVersion} (API ${d.sdk}) · ${d.brand} ${d.model}")
        sb.appendLine("Status: ${d.status} · host ${d.hostKind} ${d.hostVersion.ifBlank { "?" }} · protocol ${d.protocol}")
        sb.appendLine("Buffer: ${d.bufferMode} · target ${d.bufferTargetMs} ms · queued ${d.bufferQueuedMs} ms · p99 jitter ${num(d.jitterP99Ms)}")
        sb.appendLine("Underruns ${d.underruns} · lost ${d.lostPackets} · concealed ${d.concealedPackets} · trimmed ${d.trimmedPackets}")
        sb.appendLine(
            "Clock sync: " + if (d.synced) {
                "on · offset ${num(d.syncOffsetMs)} · rtt ${num(d.syncRttMs)} · error ${num(d.syncErrorMs)}"
            } else {
                "off (adaptive buffer)"
            }
        )
        sb.appendLine("Output latency (calibrated): ${num(d.outputLatencyMs)} · earbuds: ${d.earbuds.ifBlank { "none" }}")
        sb.appendLine(
            "Deadline: P ${if (d.playoutMs > 0) "${d.playoutMs} ms" else "—"} · need ${d.needMs?.let { "$it ms" } ?: "—"}" +
                " · output ${d.usedLatency.ifBlank { "—" }} · audio over ${d.transport.ifBlank { "—" }}"
        )
        if (d.power.isNotBlank()) sb.appendLine("Power: ${d.power}")
        sb.appendLine("EQ: ${d.eq} · dialogue boost: ${d.dialogue}")
        sb.appendLine("--- last ${d.logLines.size} log lines ---")
        d.logLines.forEach { sb.appendLine(Redact.secrets(it)) }
        return Redact.secrets(sb.toString().trimEnd())
    }
}
