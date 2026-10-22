package com.dualearbuds.companion.audio

import kotlin.math.max

/**
 * One decoded audio packet: interleaved stereo s16 at [rate] Hz. [seq] is the uint32 sequence number (−1 when
 * unknown); [concealed] marks a packet made up by [PacketConcealer].
 */
class AudioPacket(val rate: Int, val samples: ShortArray, val seq: Long = -1L, val concealed: Boolean = false) {
    val durationMs: Double = if (rate > 0) (samples.size / 2) * 1000.0 / rate else 0.0
}

/**
 * SMART = learns the network's jitter (smart-features.md #4, the default); STEADY = the protocol v3 adaptive
 * rules unchanged; MANUAL = a fixed buffer the user picks.
 */
enum class BufferMode { SMART, STEADY, MANUAL }

/**
 * The listener's jitter queue (pure Kotlin, no Android types, not thread-safe — [AudioEngine] guards it with
 * its lock).
 *
 * - [push] adds a packet; if the queue grows well past the target (clock drift, a burst after a Wi-Fi stall)
 *   the oldest packets are dropped back down to the target, keeping latency low.
 * - [poll] hands the next packet to the audio thread. After a start or an underrun it first waits until the
 *   queue holds [targetMs] of sound.
 * - An *underrun* is counted only when the queue ran dry while the stream was still flowing, i.e. the next
 *   packet arrived within [underrunWindowMs]. A host that stops sharing (no more packets) is a pause, not an
 *   underrun, so it never inflates the adaptive buffer.
 */
class JitterBuffer(
    private val steady: AdaptiveBufferController = AdaptiveBufferController(),
    private val smart: SmartBufferController = SmartBufferController(),
    private val underrunWindowMs: Long = 1_000
) {
    var mode: BufferMode = BufferMode.STEADY

    /**
     * Synced playback (clock sync): the audio thread schedules packets itself, so [poll]'s fill-to-target gate
     * is bypassed ([pollSynced]) and the queue may hold up to [syncedHoldMs] before old packets are trimmed.
     */
    var synced: Boolean = false
    var syncedHoldMs: Int = 400
    var manualMs: Int = 180
    var delayMs: Int = 0

    private val queue = ArrayDeque<AudioPacket>()

    /** Arrival jitter over the last 30 s, for v3.2 `needMs` (independent of the buffer mode). */
    private val arrivals = ArrivalJitter()

    /** Milliseconds of sound waiting in the queue. */
    var queuedMs: Double = 0.0
        private set

    /** True while (re)filling up to the target before playing. */
    var buffering: Boolean = true
        private set

    /** Underruns since the last [resetCounters]. */
    var underruns: Long = 0
        private set

    /** Packets dropped to keep latency down since the last [resetCounters]. */
    var dropped: Long = 0
        private set

    private var dryAt = -1L

    private val controller: BufferController?
        get() = when (mode) {
            BufferMode.SMART -> smart
            BufferMode.STEADY -> steady
            BufferMode.MANUAL -> null
        }

    /** Current automatic value (without delay) of the active controller, or the manual value. */
    val adaptiveMs: Int
        get() = controller?.currentMs ?: manualMs

    /** Smart mode's p99 jitter estimate (NaN before data), for diagnostics. */
    val jitterP99Ms: Double
        get() = smart.jitterP99Ms

    /** p99 arrival jitter over the last 30 s (NaN until ~0.5 s of packets), for the v3.2 common deadline. */
    val jitterP99Over30sMs: Double
        get() = arrivals.p99Ms

    /** The buffer we fill to: smart/steady/manual, plus the manual delay. */
    fun targetMs(): Int = adaptiveMs + max(0, delayMs)

    /** New playback session: empty queue, buffers back to their start value, counters zeroed. */
    fun reset(now: Long) {
        clearQueue()
        steady.reset(now)
        smart.reset(now)
        arrivals.reset(now)
        resetCounters()
    }

    fun resetCounters() {
        underruns = 0
        dropped = 0
    }

    /** Drops queued sound (host paused or link dropped). Not an underrun. */
    fun flush() = clearQueue()

    fun push(packet: AudioPacket, now: Long) {
        if (dryAt >= 0) {
            if (now - dryAt <= underrunWindowMs) {
                underruns++
                controller?.onUnderrun(now)
            }
            dryAt = -1
        }
        if (packet.seq >= 0 && !packet.concealed) {
            smart.onArrival(packet.seq, now)
            arrivals.onArrival(packet.seq, now)
        }
        queue.addLast(packet)
        queuedMs += packet.durationMs
        val target = if (synced) syncedHoldMs else targetMs()
        if (queuedMs > target + trimSlackMs(target)) {
            while (queue.size > 1 && queuedMs > target) {
                val old = queue.removeFirst()
                queuedMs -= old.durationMs
                dropped++
            }
            if (queuedMs < 0.0) queuedMs = 0.0
        }
    }

    /** Next packet to play, or null (still buffering, or just ran dry). */
    fun poll(now: Long): AudioPacket? {
        controller?.tick(now)
        if (buffering) {
            if (queue.isNotEmpty() && queuedMs >= targetMs()) {
                buffering = false
            } else {
                return null
            }
        }
        val p = queue.removeFirstOrNull()
        if (p == null) {
            buffering = true
            dryAt = now
            return null
        }
        queuedMs -= p.durationMs
        if (queue.isEmpty() || queuedMs < 0.0) queuedMs = 0.0
        return p
    }

    /** Synced mode: the oldest packet without the fill gate; an empty queue still counts towards underruns. */
    fun pollSynced(now: Long): AudioPacket? {
        controller?.tick(now)
        val p = queue.removeFirstOrNull()
        if (p == null) {
            if (!buffering) dryAt = now
            buffering = true
            return null
        }
        buffering = false
        queuedMs -= p.durationMs
        if (queue.isEmpty() || queuedMs < 0.0) queuedMs = 0.0
        return p
    }

    /** The oldest queued packet, without removing it. */
    fun peek(): AudioPacket? = queue.firstOrNull()

    private fun clearQueue() {
        queue.clear()
        queuedMs = 0.0
        buffering = true
        dryAt = -1
    }

    companion object {
        /** How far above the target the queue may grow before trimming: half the target, at least 60 ms. */
        fun trimSlackMs(targetMs: Int): Int = max(60, targetMs / 2)
    }
}
