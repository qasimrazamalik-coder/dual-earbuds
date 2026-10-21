package com.dualearbuds.companion.audio

import kotlin.math.max

/** One decoded audio packet: interleaved stereo s16 at [rate] Hz. */
class AudioPacket(val rate: Int, val samples: ShortArray) {
    val durationMs: Double = if (rate > 0) (samples.size / 2) * 1000.0 / rate else 0.0
}

/** AUTO = protocol v3 adaptive buffer; MANUAL = a fixed buffer the user picks. */
enum class BufferMode { AUTO, MANUAL }

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
    private val adaptive: AdaptiveBufferController = AdaptiveBufferController(),
    private val underrunWindowMs: Long = 1_000
) {
    var mode: BufferMode = BufferMode.AUTO
    var manualMs: Int = 180
    var delayMs: Int = 0

    private val queue = ArrayDeque<AudioPacket>()

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

    /** Current adaptive value (without delay), whatever the mode. */
    val adaptiveMs: Int
        get() = adaptive.currentMs

    /** The buffer we fill to: adaptive (AUTO) or fixed (MANUAL), plus the manual delay. */
    fun targetMs(): Int = (if (mode == BufferMode.AUTO) adaptive.currentMs else manualMs) + max(0, delayMs)

    /** New playback session: empty queue, adaptive buffer back to its start value, counters zeroed. */
    fun reset(now: Long) {
        clearQueue()
        adaptive.reset(now)
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
                if (mode == BufferMode.AUTO) adaptive.onUnderrun(now)
            }
            dryAt = -1
        }
        queue.addLast(packet)
        queuedMs += packet.durationMs
        val target = targetMs()
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
        if (mode == BufferMode.AUTO) adaptive.tick(now)
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
