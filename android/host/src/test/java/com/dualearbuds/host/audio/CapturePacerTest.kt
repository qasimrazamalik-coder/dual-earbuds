package com.dualearbuds.host.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePacerTest {

    /** Runs the capture thread's empty-read polling every [stepMs] from [from] to [to]; returns silent packets sent. */
    private fun poll(p: CapturePacer, from: Long, to: Long, stepMs: Long = 4): Int {
        var n = 0
        var t = from
        while (t <= to) {
            n += p.silenceDue(t)
            t += stepMs
        }
        return n
    }

    @Test
    fun noRealDataFillsSilenceOnTheTenMsClock() {
        val p = CapturePacer(0)
        assertEquals(0, poll(p, 0, 30)) // quiet for 30 ms: nothing yet
        val sent = poll(p, 31, 1_000)
        assertTrue("about 100 packets per second, got $sent", sent in 99..100)
    }

    @Test
    fun steadyCaptureNeverGetsSilence() {
        val p = CapturePacer(0)
        var sent = 0
        for (t in 0L..5_000L step 4) {
            if (t % 20 == 0L) p.onReal(t) // 20 ms periods
            sent += p.silenceDue(t)
        }
        assertEquals(0, sent)
        assertFalse(p.stalled(5_000))
    }

    @Test
    fun learnsLargerCaptureBursts() {
        val p = CapturePacer(0)
        var sent = 0
        for (t in 0L..5_000L step 2) {
            if (t % 40 == 0L) p.onReal(t) // a phone that hands over 40 ms at a time
            sent += p.silenceDue(t)
        }
        assertTrue("only the first gap is filled, got $sent", sent in 1..4)
        assertEquals(50, p.gapLimitMs)
    }

    @Test
    fun longStallDoesNotRaiseTheLimit() {
        val p = CapturePacer(0)
        poll(p, 0, 3_000)
        p.onReal(3_000)
        assertEquals(30, p.gapLimitMs)
    }

    @Test
    fun lateWakeUpSendsOnePacketNotAFlood() {
        val p = CapturePacer(0)
        assertEquals(3, p.silenceDue(35))
        assertEquals(1, p.silenceDue(800)) // thread slept 765 ms
        assertEquals(1, p.silenceDue(810))
        assertEquals(0, p.silenceDue(812))
    }

    @Test
    fun stalledAfterTwoSecondsWithoutRealData() {
        val p = CapturePacer(0)
        assertFalse(p.stalled(1_999))
        assertTrue(p.stalled(2_000))
        p.onReal(2_500)
        assertFalse(p.stalled(2_600))
        assertEquals(0, p.silenceDue(2_520))
        assertTrue(p.silenceDue(2_531) > 0)
    }

    /**
     * A capture path that stalls for [gapMs], then hands over the whole backlog at once (bursty phones, or a
     * re-routed track). Runs [cycles] of steady 10 ms data + one gap; returns (packets on the wire − wall-clock
     * slots) at the end, with or without [CapturePacer.admitReal].
     */
    private fun offsetAfterBurstyGaps(cycles: Int, gapMs: Long, useAdmit: Boolean, backlog: Boolean = true): Pair<Long, Long> {
        val p = CapturePacer(0)
        var sent = 0L
        var t = 0L
        var lastData = 0L
        repeat(cycles) {
            // 200 ms of steady capture, one packet every 10 ms.
            repeat(20) {
                t += 10
                p.onReal(t)
                if (!useAdmit || p.admitReal(t)) sent++
                lastData = t
                sent += p.silenceDue(t + 2)
            }
            // The gap: polled every 4 ms, filler goes out.
            val end = t + gapMs
            var u = t + 4
            while (u < end) {
                sent += p.silenceDue(u)
                u += 4
            }
            t = end
            p.onReal(t)
            // Backlog: everything captured during the gap arrives now (or nothing, for a true stall).
            val n = if (backlog) (t - lastData) / 10 else 1
            for (i in 0 until n) if (!useAdmit || p.admitReal(t)) sent++
            lastData = t
        }
        return (sent - t / 10) to p.drained
    }

    @Test
    fun fillerDoesNotAddLatencyOnceRealDataFlowsAgain() {
        // Gaps of 150 ms are too long to be learned as a burst pattern, so every one of them gets filler.
        // Without draining, each gap leaves its filler on the wire: the offset grows by ~12 packets per gap.
        val (grown, _) = offsetAfterBurstyGaps(cycles = 50, gapMs = 150, useAdmit = false)
        assertTrue("old behaviour should drift, got $grown", grown > 500)
        // With draining the stream stays on the clock (±1 packet of rounding), however many gaps there were.
        for (cycles in listOf(1, 10, 50)) {
            for (gap in listOf(60L, 150L)) {
                val (offset, drained) = offsetAfterBurstyGaps(cycles, gapMs = gap, useAdmit = true)
                assertTrue("$cycles gaps of $gap ms: offset $offset packets", offset in -1L..1L)
                assertTrue(drained > 0)
            }
        }
    }

    @Test
    fun trueStallWithoutBacklogDropsNoRealData() {
        val (offset, drained) = offsetAfterBurstyGaps(cycles = 20, gapMs = 500, useAdmit = true, backlog = false)
        assertEquals(0L, drained)
        assertTrue("offset $offset", offset in -1L..1L)
    }

    @Test
    fun steadyCaptureIsAlwaysAdmitted() {
        val p = CapturePacer(0)
        for (t in 10L..5_000L step 10) {
            p.onReal(t)
            assertTrue(p.admitReal(t))
            assertEquals(0, p.silenceDue(t + 3))
        }
        assertEquals(0L, p.drained)
    }

    @Test
    fun drainingStopsAfterTheBacklogWindow() {
        val p = CapturePacer(0)
        p.onReal(0)
        assertEquals(4, p.silenceDue(45)) // 4 filler packets for 0..40 ms
        p.onReal(50)
        // Backlog of 5 packets at 50 ms: 4 filler + 5 real for 5 slots → the oldest 4 go, the newest plays.
        val admitted = (0 until 5).count { p.admitReal(50) }
        assertEquals(1, admitted)
        assertEquals(4L, p.drained)
        // Long after the window, nothing is dropped any more even if the count looks ahead.
        p.onReal(400)
        assertTrue(p.admitReal(400))
    }
}
