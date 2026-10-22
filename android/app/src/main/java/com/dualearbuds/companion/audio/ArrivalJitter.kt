package com.dualearbuds.companion.audio

import kotlin.math.max

/**
 * Packet arrival jitter over roughly the last [windowMs] (protocol v3.2 `needMs`). Pure Kotlin, not thread-safe.
 *
 * Every arrival gives a delay sample d = arrival − seq × [packetMs] (the constant one-way delay and the clock
 * offset cancel out). Jitter = d − min(d) inside the window; its 99th percentile comes from a [P2Quantile].
 * This is "how late can a packet be compared with the earliest one", which is exactly what a playout deadline must
 * absorb (inter-arrival gaps of a burst after a Wi-Fi power-save pause show up here as ~the pause length).
 *
 * "Last 30 s": two estimators run staggered by half a window; the older one (15–30 s of data) is reported and is
 * replaced every half window. Before the older one has [minSamples] the younger one is used.
 */
class ArrivalJitter(
    private val windowMs: Long = 30_000,
    private val packetMs: Double = 10.0,
    private val minSamples: Int = 50
) {
    private class Window(val startedAt: Long) {
        val p99 = P2Quantile(0.99)
        var minD = Double.POSITIVE_INFINITY

        fun add(d: Double) {
            p99.add(d)
            if (d < minD) minD = d
        }

        fun jitter(): Double = if (p99.count == 0) Double.NaN else max(0.0, p99.value() - minD)
    }

    private var old = Window(0)
    private var young = Window(windowMs / 2)
    private var lastSeq = -1L
    private var unwrapped = 0L

    /** Arrivals since the last [reset] or stream restart. */
    var count: Int = 0
        private set

    fun reset(now: Long) {
        old = Window(now)
        young = Window(now + windowMs / 2)
        lastSeq = -1L
        unwrapped = 0L
        count = 0
    }

    fun onArrival(seq: Long, now: Long) {
        val s = seq and 0xFFFFFFFFL
        if (lastSeq >= 0) {
            val delta = (s - lastSeq) and 0xFFFFFFFFL
            when {
                delta == 0L || delta >= 0x80000000L -> return // duplicate or late/reordered: not a new arrival
                delta <= 100_000L -> unwrapped += delta
                else -> reset(now) // stream restarted
            }
        }
        lastSeq = s
        val d = now - unwrapped * packetMs
        if (now - old.startedAt >= windowMs && young.p99.count > 0) {
            old = young
            young = Window(now)
        }
        old.add(d)
        if (now >= young.startedAt) young.add(d)
        count++
    }

    /** p99 jitter in ms, NaN before [minSamples] arrivals. */
    val p99Ms: Double
        get() {
            if (count < minSamples) return Double.NaN
            val w = if (old.p99.count >= minSamples) old else young
            return if (w.p99.count >= minSamples) w.jitter() else old.jitter()
        }
}
