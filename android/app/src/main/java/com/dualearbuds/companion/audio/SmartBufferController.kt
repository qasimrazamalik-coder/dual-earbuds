package com.dualearbuds.companion.audio

import kotlin.math.max
import kotlin.math.min

/** Common shape of the listener's buffer controllers (all times in ms on one monotonic clock). */
interface BufferController {
    val currentMs: Int
    fun reset(now: Long)
    fun onUnderrun(now: Long): Int
    fun tick(now: Long): Int

    /** A packet with sequence number [seq] arrived at [now]. Only the smart controller uses it. */
    fun onArrival(seq: Long, now: Long) = Unit
}

/**
 * Smart buffer (docs/smart-features.md #4): learns this network's jitter and keeps the delay as low as is safe.
 *
 * - Each arrival gives a delay sample d = arrival − seq × 10 ms (any constant offset cancels out).
 *   Jitter = d − min(d) over the window; its 99th percentile comes from a [P2Quantile].
 * - "Last 60 s": two estimators run staggered by 30 s; the older one (30–60 s of data) is used and is replaced
 *   by a fresh one every 30 s.
 * - Target = p99 jitter + [marginMs], clamped to the protocol's [minMs]..[maxMs]. A higher target is applied at
 *   once; a lower one is approached by −[decayStepMs] per [decayEveryMs].
 * - An underrun adds [underrunStepMs] immediately (and restarts the slow shrink timer).
 * - Until [warmupPackets] arrivals the start value is kept.
 */
class SmartBufferController(
    val startMs: Int = 60,
    val minMs: Int = 30,
    val maxMs: Int = 300,
    val marginMs: Int = 10,
    val underrunStepMs: Int = 20,
    val decayStepMs: Int = 5,
    val decayEveryMs: Long = 5_000,
    private val windowMs: Long = 60_000,
    private val warmupPackets: Int = 200,
    private val packetMs: Double = 10.0
) : BufferController {

    private class Window(var startedAt: Long) {
        val p99 = P2Quantile(0.99)
        var minD = Double.POSITIVE_INFINITY

        fun add(d: Double) {
            p99.add(d)
            if (d < minD) minD = d
        }

        fun jitterP99(): Double = if (p99.count == 0) Double.NaN else max(0.0, p99.value() - minD)
    }

    override var currentMs: Int = startMs
        private set

    private var old = Window(0)
    private var young = Window(0)
    private var nextDecayAt = 0L
    private var firstSeq = -1L
    private var seqUnwrap = 0L
    private var lastSeq = -1L
    private var arrivals = 0

    /** Latest p99 jitter estimate (ms), NaN before data. For diagnostics. */
    var jitterP99Ms: Double = Double.NaN
        private set

    /** The learned target (p99 + margin, clamped), or [startMs] during warm-up. */
    var targetMs: Int = startMs
        private set

    override fun reset(now: Long) {
        currentMs = startMs
        targetMs = startMs
        old = Window(now)
        young = Window(now + windowMs / 2)
        nextDecayAt = now + decayEveryMs
        firstSeq = -1L
        lastSeq = -1L
        seqUnwrap = 0L
        arrivals = 0
        jitterP99Ms = Double.NaN
    }

    override fun onArrival(seq: Long, now: Long) {
        val s = seq and 0xFFFFFFFFL
        if (lastSeq >= 0) {
            val delta = ((s - lastSeq) and 0xFFFFFFFFL)
            if (delta in 1L..100_000L) {
                seqUnwrap += delta
            } else {
                // Stream restarted (host stopped/started, reconnect): start learning again from here.
                old = Window(now)
                young = Window(now + windowMs / 2)
                seqUnwrap = 0
                firstSeq = s
                arrivals = 0
            }
        } else {
            firstSeq = s
        }
        lastSeq = s
        val d = now - seqUnwrap * packetMs
        rotate(now)
        old.add(d)
        if (now >= young.startedAt) young.add(d)
        arrivals++
        if (arrivals >= warmupPackets) {
            val j = old.jitterP99()
            if (!j.isNaN()) {
                jitterP99Ms = j
                targetMs = (j + marginMs).toInt().coerceIn(minMs, maxMs)
                if (targetMs > currentMs) currentMs = targetMs
            }
        }
    }

    private fun rotate(now: Long) {
        if (now - old.startedAt >= windowMs && young.p99.count > 0) {
            old = young
            young = Window(now)
        }
    }

    override fun onUnderrun(now: Long): Int {
        currentMs = min(maxMs, currentMs + underrunStepMs)
        nextDecayAt = now + decayEveryMs * 2
        return currentMs
    }

    override fun tick(now: Long): Int {
        while (now >= nextDecayAt) {
            if (currentMs > targetMs) currentMs = max(targetMs, currentMs - decayStepMs)
            nextDecayAt += decayEveryMs
            if (now - nextDecayAt > decayEveryMs * 4) nextDecayAt = now + decayEveryMs // long pause: don't spin
        }
        currentMs = currentMs.coerceIn(minMs, maxMs)
        return currentMs
    }
}
