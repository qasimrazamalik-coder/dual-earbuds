package com.dualearbuds.companion.audio.sync

/**
 * Listener side of the v3.1 clock sync (docs/protocol.md, "Clock sync"). Pure Kotlin, not thread-safe
 * (the caller serialises access).
 *
 * - [onSyncReply]: the host echoed our `c` with its time `h`. Offset = h − (c + now)/2, round trip = now − c.
 *   The sample with the smallest round trip among the last [window] is used.
 * - [onClock]: the host said packet `seq` was captured at host time `h`. Later packets are 10 ms apart.
 * - [hostTimeOf] / [localTimeOfHost] / [targetLocalMs] turn a sequence number into the local moment it must
 *   reach the ear: h(seq) + playoutMs − outputLatency + delay (Delay always makes this listener later), converted to the local clock.
 *
 * All times are milliseconds (Double for sub-ms precision). "Local" is one monotonic clock chosen by the caller.
 */
class ClockSync(
    private val window: Int = 8,
    /** Duration of one packet in ms (protocol: 10 ms). */
    val packetMs: Double = 10.0,
    /** The clock reference goes stale when no `clock` message arrived for this long. */
    private val clockStaleMs: Double = 5_000.0
) {
    private class Sample(val offset: Double, val rtt: Double)

    private val samples = ArrayDeque<Sample>()

    private var refSeq = -1L
    private var refHostMs = 0.0
    private var refReceivedLocalMs = Double.NEGATIVE_INFINITY

    /** Host target delay from `hello` (`playoutMs`, default 120). */
    var playoutMs: Double = DEFAULT_PLAYOUT_MS

    /** host − local, from the best sample, or null before the first reply. */
    val offsetMs: Double?
        get() = samples.minByOrNull { it.rtt }?.offset

    /** Round trip of the sample in use. */
    val bestRttMs: Double?
        get() = samples.minByOrNull { it.rtt }?.rtt

    val sampleCount: Int
        get() = samples.size

    fun reset() {
        samples.clear()
        refSeq = -1L
        refHostMs = 0.0
        refReceivedLocalMs = Double.NEGATIVE_INFINITY
        playoutMs = DEFAULT_PLAYOUT_MS
    }

    /** Reply to our `{"type":"sync","c":c}`. Replies with a negative round trip (clock jump) are ignored. */
    fun onSyncReply(c: Double, h: Double, nowLocal: Double) {
        val rtt = nowLocal - c
        if (rtt < 0 || rtt > MAX_RTT_MS) return
        samples.addLast(Sample(h - (c + nowLocal) / 2.0, rtt))
        while (samples.size > window) samples.removeFirst()
    }

    fun onClock(seq: Long, hostMs: Double, nowLocal: Double) {
        refSeq = seq and 0xFFFFFFFFL
        refHostMs = hostMs
        refReceivedLocalMs = nowLocal
    }

    /** True when both an offset and a fresh clock reference are known: synced playback is possible. */
    fun isReady(nowLocal: Double): Boolean =
        samples.isNotEmpty() && refSeq >= 0 && nowLocal - refReceivedLocalMs <= clockStaleMs

    /** Host capture time of [seq] (uint32, wrap-safe), or null without a reference. */
    fun hostTimeOf(seq: Long): Double? {
        if (refSeq < 0) return null
        return refHostMs + seqDelta(seq, refSeq) * packetMs
    }

    fun localTimeOfHost(hostMs: Double): Double? = offsetMs?.let { hostMs - it }

    /**
     * Local time the first sample of [seq] must be heard at, so that every listener plays it at the same moment:
     * h(seq) + playoutMs − outputLatencyMs + delayMs, on the local clock.
     */
    fun targetLocalMs(seq: Long, outputLatencyMs: Double, delayMs: Double): Double? {
        val h = hostTimeOf(seq) ?: return null
        return localTimeOfHost(h + playoutMs - outputLatencyMs + delayMs)
    }

    companion object {
        const val DEFAULT_PLAYOUT_MS = 120.0
        const val MAX_RTT_MS = 2_000.0

        /** Signed distance a − b between two uint32 sequence numbers. */
        fun seqDelta(a: Long, b: Long): Long {
            val d = ((a and 0xFFFFFFFFL) - (b and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            return if (d >= 0x80000000L) d - 0x100000000L else d
        }
    }
}
