package com.dualearbuds.host.setup

import kotlin.math.roundToInt

/** v3.3 "Low-latency earbuds hint" (docs/protocol.md): shown when calibrated earbuds add more than 150 ms. */
object LowLatencyHint {
    const val THRESHOLD_MS = 150.0

    /** The hint for a calibrated latency of [latencyMs], or null when it is fine (or not measured). */
    fun text(latencyMs: Double?): String? {
        if (latencyMs == null || latencyMs.isNaN() || latencyMs <= THRESHOLD_MS) return null
        return "These earbuds add ${latencyMs.roundToInt()} ms. If they have a game or low-latency mode (often in the " +
            "earbuds' own app or a triple tap), turn it on and calibrate again. " +
            "Prefer AAC or SBC over LDAC (Developer options → Bluetooth audio codec)."
    }
}
