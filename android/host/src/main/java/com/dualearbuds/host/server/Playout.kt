package com.dualearbuds.host.server

/**
 * The common deadline P of protocol v3.2: every ear hears the sample captured at host time t at t + P.
 *
 * P = clamp(max(needMs of synced listeners, host local need), [minMs], [maxMs]).
 * Increases apply at once; decreases are at most [stepDownMs] per [stepDownEveryMs] (5 ms per second),
 * so nobody hears a jump. With nobody synced there is no one to disturb, so P follows the target at once.
 * Not thread-safe: the server calls it from its housekeeping thread only.
 */
class Playout(
    initialMs: Int = HostMessages.DEFAULT_PLAYOUT_MS,
    private val minMs: Int = MIN_MS,
    private val maxMs: Int = MAX_MS,
    private val stepDownMs: Int = 5,
    private val stepDownEveryMs: Long = 1_000
) {
    /** The P currently announced. */
    var currentMs: Int = initialMs.coerceIn(minMs, maxMs)
        private set

    private var lastChangeAt = Long.MIN_VALUE

    /**
     * Moves P towards [targetMs] at time [nowMs]. [anyoneSynced] = at least one listener plays to the
     * deadline. Returns true when [currentMs] changed (the caller announces `playout`).
     */
    fun step(targetMs: Int, nowMs: Long, anyoneSynced: Boolean): Boolean {
        val target = targetMs.coerceIn(minMs, maxMs)
        val before = currentMs
        if (lastChangeAt == Long.MIN_VALUE) lastChangeAt = nowMs
        when {
            // Nobody plays to the deadline, or P must grow: apply at once.
            !anyoneSynced || target >= currentMs -> {
                currentMs = target
                lastChangeAt = nowMs
            }
            // Shrinking: one small step per interval.
            nowMs - lastChangeAt >= stepDownEveryMs -> {
                currentMs = maxOf(target, currentMs - stepDownMs)
                lastChangeAt = nowMs
            }
        }
        return currentMs != before
    }

    companion object {
        const val MIN_MS = 40
        const val MAX_MS = 400

        /**
         * P for these needs: the slowest synced listener or the host's own ear, clamped to 40–400 ms.
         * A synced listener that does not report `needMs` (v3.1) counts as the old fixed 120 ms.
         */
        fun target(listenerNeedsMs: Iterable<Int>, localNeedMs: Int, minMs: Int = MIN_MS, maxMs: Int = MAX_MS): Int {
            var m = localNeedMs
            for (n in listenerNeedsMs) if (n > m) m = n
            return m.coerceIn(minMs, maxMs)
        }
    }
}

/** What the host phone's own output is, for its local need (protocol v3.2, Android host). */
enum class LocalOutputKind { BLUETOOTH, WIRED, SPEAKER }

/** The host phone's own ear: which output plays the captured app and how late it is. */
data class LocalOutput(
    val kind: LocalOutputKind,
    /** Device name as Android reports it (Bluetooth: the earbuds' name), or "" for the speaker. */
    val name: String = "",
    /** Calibrated output latency in ms, or null when not calibrated. */
    val calibratedMs: Double? = null
) {
    /** The captured app plays here immediately and can't be delayed, so this is the host's local need. */
    val needMs: Int
        get() {
            val c = calibratedMs
            if (isCalibrated && c != null) return Math.round(c).toInt()
            return when (kind) {
                LocalOutputKind.BLUETOOTH -> DEFAULT_BLUETOOTH_MS
                LocalOutputKind.WIRED, LocalOutputKind.SPEAKER -> DEFAULT_WIRED_MS
            }
        }

    /** A usable calibration (0–400 ms) is present. */
    val isCalibrated: Boolean
        get() {
            val c = calibratedMs ?: return false
            return !c.isNaN() && c >= 0.0 && c <= Playout.MAX_MS
        }

    companion object {
        /** v3.2: uncalibrated Bluetooth A2DP earbuds are assumed 180 ms late, wired/speaker 20 ms. */
        const val DEFAULT_BLUETOOTH_MS = 180
        const val DEFAULT_WIRED_MS = 20

        /**
         * Looks up [name] in a calibration table: exact name first, then ignoring case and spaces
         * at the ends (Android and the Bluetooth stack sometimes differ in case/trailing spaces).
         */
        fun lookup(table: Map<String, Double>, name: String): Double? {
            if (name.isBlank()) return null
            table[name]?.let { return it }
            val n = name.trim()
            return table.entries.firstOrNull { it.key.trim().equals(n, ignoreCase = true) }?.value
        }

        /**
         * Reads the app's stored calibration text: one `name<TAB>ms` per line (the listener app's
         * `output_latency` preference). Bad lines are skipped.
         */
        fun decodeCalibration(text: String?): Map<String, Double> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, Double>()
            for (line in text.lineSequence()) {
                val i = line.lastIndexOf('\t')
                if (i <= 0) continue
                val v = line.substring(i + 1).trim().toDoubleOrNull() ?: continue
                if (v.isNaN() || v.isInfinite()) continue
                out[line.substring(0, i)] = v
            }
            return out
        }
    }
}
