package com.dualearbuds.companion.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive low-latency playback buffer, exactly as docs/protocol.md (v3, "Low-latency playback") specifies:
 *
 * - start at [startMs] (60 ms), never below [minMs] (30 ms), at most [maxMs] (300 ms);
 * - on an underrun: +[underrunStepMs] (20 ms) and stay there for [holdMs] (10 s) — further underruns inside
 *   that window do not add again (one burst of dropouts is one step), but they do restart the clean timer;
 * - after [cleanMs] (20 s) with no underruns: −[decayStepMs] (5 ms) per [decayEveryMs] (5 s) down to the minimum.
 *
 * The manual *Delay* setting is not part of this class: it is added on top by [JitterBuffer.targetMs] and is
 * never changed automatically.
 *
 * Pure Kotlin, no Android types. Times are milliseconds on any monotonic clock. Not thread-safe: the caller
 * serialises access (the [JitterBuffer] lock).
 */
class AdaptiveBufferController(
    val startMs: Int = 60,
    val minMs: Int = 30,
    val maxMs: Int = 300,
    val underrunStepMs: Int = 20,
    val holdMs: Long = 10_000,
    val cleanMs: Long = 20_000,
    val decayStepMs: Int = 5,
    val decayEveryMs: Long = 5_000
) : BufferController {
    /** Current adaptive buffer in ms (without the manual delay). */
    override var currentMs: Int = startMs
        private set

    private var holdUntil = Long.MIN_VALUE
    private var nextDecayAt = Long.MAX_VALUE

    /** Back to the starting value; the clean period starts at [now]. Call when playback starts. */
    override fun reset(now: Long) {
        currentMs = startMs
        holdUntil = Long.MIN_VALUE
        nextDecayAt = now + cleanMs
    }

    /** Playback ran dry. Returns the new value. */
    override fun onUnderrun(now: Long): Int {
        // Any underrun restarts the 20 s clean period.
        nextDecayAt = now + cleanMs
        if (now < holdUntil) return currentMs
        currentMs = min(maxMs, currentMs + underrunStepMs)
        holdUntil = now + holdMs
        return currentMs
    }

    /** Applies the slow decrease. Call regularly (any rate); skipped time is caught up. Returns the value. */
    override fun tick(now: Long): Int {
        if (nextDecayAt == Long.MAX_VALUE) {
            // Never reset: start the clean period now.
            nextDecayAt = now + cleanMs
            return currentMs
        }
        while (now >= nextDecayAt) {
            if (currentMs <= minMs) {
                currentMs = minMs
                nextDecayAt = now + decayEveryMs
                break
            }
            currentMs = max(minMs, currentMs - decayStepMs)
            nextDecayAt += decayEveryMs
        }
        return currentMs
    }
}
