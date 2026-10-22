package com.dualearbuds.companion.audio.sync

import kotlin.math.ceil
import kotlin.math.max

/** Where the output latency used for `needMs` came from. */
enum class LatencySource(val label: String) {
    /** Calibrated for these earbuds (plus what the output timestamp already reports). */
    CALIBRATED("calibrated"),

    /** From AudioTrack.getTimestamp: how long a written frame takes to be played. */
    TIMESTAMP("measured"),
    DEFAULT_BLUETOOTH("estimate"),
    DEFAULT_WIRED("estimate")
}

data class OutputLatency(val ms: Double, val source: LatencySource)

/**
 * Listener side of the v3.2 common deadline (docs/protocol.md "Common deadline"): what this listener needs the
 * host's playout P to be at least, reported as `"needMs"` in `status`.
 *
 *   needMs = jitterP99 (last 30 s) + outputLatency + 10
 *
 * outputLatency, in order of preference:
 * 1. the calibrated value for the current Bluetooth device. Calibration measures the delay the output timestamps
 *    do *not* report (see CalibrationRunner), so the timestamp part is added to it when known;
 * 2. the AudioTrack.getTimestamp-derived latency (written frame → played);
 * 3. 180 ms for Bluetooth, 20 ms for wired/speaker.
 */
object DeadlineNeed {
    const val MARGIN_MS = 10.0
    const val DEFAULT_BLUETOOTH_MS = 180.0
    const val DEFAULT_WIRED_MS = 20.0
    const val MIN_NEED_MS = 10
    const val MAX_NEED_MS = 2_000

    fun outputLatency(calibratedMs: Double?, timestampMs: Double?, bluetooth: Boolean): OutputLatency {
        val ts = timestampMs?.takeIf { !it.isNaN() && it >= 0.0 && it < 2_000.0 }
        val cal = calibratedMs?.takeIf { !it.isNaN() && it >= 0.0 && it < 2_000.0 }
        return when {
            cal != null -> OutputLatency(cal + (ts ?: 0.0), LatencySource.CALIBRATED)
            ts != null -> OutputLatency(ts, LatencySource.TIMESTAMP)
            bluetooth -> OutputLatency(DEFAULT_BLUETOOTH_MS, LatencySource.DEFAULT_BLUETOOTH)
            else -> OutputLatency(DEFAULT_WIRED_MS, LatencySource.DEFAULT_WIRED)
        }
    }

    /** needMs, rounded up to whole ms. A jitter of NaN (no data yet) counts as 0. */
    fun needMs(jitterP99Ms: Double, outputLatencyMs: Double): Int {
        val j = if (jitterP99Ms.isNaN()) 0.0 else max(0.0, jitterP99Ms)
        val out = if (outputLatencyMs.isNaN()) 0.0 else max(0.0, outputLatencyMs)
        return ceil(j + out + MARGIN_MS).toInt().coerceIn(MIN_NEED_MS, MAX_NEED_MS)
    }

    /**
     * True when [newMs] differs enough from what the host last heard ([sentMs]) to send a status now: any rise of
     * 3 ms or more (the host applies increases at once), or a fall of 10 ms or more (it lowers P slowly anyway).
     */
    fun worthReporting(sentMs: Int?, newMs: Int): Boolean {
        if (sentMs == null) return true
        return newMs - sentMs >= 3 || sentMs - newMs >= 10
    }
}

/** Smooths a noisy latency reading (exponential moving average) and ignores absurd values. Not thread-safe. */
class LatencySmoother(private val alpha: Double = 0.05) {
    var valueMs: Double = Double.NaN
        private set

    fun reset() {
        valueMs = Double.NaN
    }

    fun add(ms: Double) {
        if (ms.isNaN() || ms < 0.0 || ms > 2_000.0) return
        valueMs = if (valueMs.isNaN()) ms else valueMs + alpha * (ms - valueMs)
    }
}
