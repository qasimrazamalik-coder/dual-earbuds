package com.dualearbuds.host.audio

/**
 * Keeps the 10 ms packet clock going when playback capture delivers nothing.
 *
 * On some phones AudioPlaybackCapture hands over no data at all (a track that was already playing is not
 * re-routed to the capture mix, or the capture path stalls). A blocking read then waits forever: no packets,
 * listeners hear nothing and the silence check never runs. The capture thread therefore reads without blocking
 * and asks [silenceDue] how many silent packets to send so listeners stay connected and in sync.
 *
 * - Silence starts once capture has been quiet for more than [gapLimitMs] (30 ms), then follows the 10 ms clock.
 * - A device that delivers capture in larger bursts (say every 40 ms) would get silence between bursts; when real
 *   data comes back within [maxGapMs] after a fill, the limit grows to that gap (+10 ms, at most [maxGapMs]).
 * - After a late wake-up only one packet goes out, not a flood.
 * - [stalled]: no real data for [stallMs] (2 s).
 *
 * Pure Kotlin, times in ms on any monotonic clock. Not thread-safe: the capture thread owns it.
 */
class CapturePacer(
    now: Long,
    private val packetMs: Long = 10,
    gapMs: Long = 30,
    private val maxGapMs: Long = 100,
    private val stallMs: Long = 2_000,
    private val maxBurst: Int = 10
) {
    private var lastRealAt = now
    private var filled = 0L

    /** Quiet time after which silence is sent (learned, [gapMs]..[maxGapMs]). */
    var gapLimitMs: Long = gapMs
        private set

    /** Real capture data arrived at [now]. */
    fun onReal(now: Long) {
        if (filled > 0) {
            val gap = now - lastRealAt
            if (gap < maxGapMs) gapLimitMs = minOf(maxGapMs, maxOf(gapLimitMs, gap + packetMs))
        }
        lastRealAt = now
        filled = 0
    }

    /** Silent packets to send now (0 most of the time). */
    fun silenceDue(now: Long): Int {
        val quiet = now - lastRealAt
        if (quiet <= gapLimitMs) return 0
        val due = quiet / packetMs - filled
        if (due <= 0) return 0
        if (due > maxBurst) {
            filled += due // woke up late: skip the missed slots, resume the clock with one packet
            return 1
        }
        filled += due
        return due.toInt()
    }

    /** No real capture data for [stallMs] or longer. */
    fun stalled(now: Long): Boolean = now - lastRealAt >= stallMs
}
