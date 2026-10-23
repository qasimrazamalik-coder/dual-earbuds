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
}
