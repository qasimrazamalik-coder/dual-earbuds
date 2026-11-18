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
 * - No added latency: when capture comes back with a backlog (data it held during the gap), the filler already
 *   covered that time. Sending both would put more packets on the wire than wall time — every listener's queue
 *   (and the seq → capture time extrapolation) would run that much later, a little more after every gap. So for
 *   [maxGapMs] after a fill, [admitReal] drops real packets that would put the stream ahead of the clock (the
 *   oldest of the backlog), at most as many as filler went out. A true stall (no backlog) drops nothing.
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

    // Filler accounting since the fill began: packets sent (filler + admitted real) from [anchorAt] on.
    private var anchorAt = now
    private var sentSinceAnchor = 0L
    private var debt = 0L
    private var debtUntil = Long.MIN_VALUE

    /** Real packets dropped so far because the filler had already covered their time. */
    var drained: Long = 0
        private set

    /** Quiet time after which silence is sent (learned, [gapMs]..[maxGapMs]). */
    var gapLimitMs: Long = gapMs
        private set

    /** Real capture data arrived at [now]. */
    fun onReal(now: Long) {
        if (filled > 0) {
            val gap = now - lastRealAt
            if (gap < maxGapMs) gapLimitMs = minOf(maxGapMs, maxOf(gapLimitMs, gap + packetMs))
            debtUntil = now + maxGapMs
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
        if (filled == 0L) {
            // A new fill: count from the last real data.
            anchorAt = lastRealAt
            sentSinceAnchor = 0
            debt = 0
        }
        if (due > maxBurst) {
            filled += due // woke up late: skip the missed slots, resume the clock with one packet
            sentSinceAnchor += 1
            debt += 1
            return 1
        }
        filled += due
        sentSinceAnchor += due
        debt += due
        return due.toInt()
    }

    /**
     * One real 10 ms packet is ready at [now] (call after [onReal], once per packet). False = drop it: it would
     * put the stream ahead of the clock because filler already went out for its time.
     */
    fun admitReal(now: Long): Boolean {
        if (debt <= 0) return true
        if (now > debtUntil) {
            debt = 0
            return true
        }
        val slots = (now - anchorAt) / packetMs
        if (sentSinceAnchor >= slots) {
            debt--
            drained++
            return false
        }
        sentSinceAnchor++
        return true
    }

    /** No real capture data for [stallMs] or longer. */
    fun stalled(now: Long): Boolean = now - lastRealAt >= stallMs
}
