package com.dualearbuds.companion.audio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineNeedTest {

    @Test
    fun needIsJitterPlusLatencyPlusTen() {
        assertEquals(232, DeadlineNeed.needMs(41.2, 180.0))
        assertEquals(30, DeadlineNeed.needMs(Double.NaN, 20.0))
    }

    @Test
    fun needIsClamped() {
        assertEquals(DeadlineNeed.MAX_NEED_MS, DeadlineNeed.needMs(5_000.0, 180.0))
        assertEquals(10, DeadlineNeed.needMs(-3.0, 0.0))
    }

    @Test
    fun calibratedWinsAndAddsWhatTheTimestampReports() {
        val o = DeadlineNeed.outputLatency(calibratedMs = 150.0, timestampMs = 40.0, bluetooth = true)
        assertEquals(LatencySource.CALIBRATED, o.source)
        assertEquals(190.0, o.ms, 1e-9)
        val noTs = DeadlineNeed.outputLatency(calibratedMs = 150.0, timestampMs = null, bluetooth = true)
        assertEquals(150.0, noTs.ms, 1e-9)
    }

    @Test
    fun timestampThenDefaults() {
        assertEquals(LatencySource.TIMESTAMP, DeadlineNeed.outputLatency(null, 95.0, true).source)
        assertEquals(95.0, DeadlineNeed.outputLatency(null, 95.0, true).ms, 1e-9)
        assertEquals(180.0, DeadlineNeed.outputLatency(null, null, true).ms, 1e-9)
        assertEquals(LatencySource.DEFAULT_WIRED, DeadlineNeed.outputLatency(null, null, false).source)
        assertEquals(20.0, DeadlineNeed.outputLatency(null, Double.NaN, false).ms, 1e-9)
    }

    @Test
    fun reportsRisesQuicklyAndFallsLazily() {
        assertTrue(DeadlineNeed.worthReporting(null, 100))
        assertTrue(DeadlineNeed.worthReporting(100, 103))
        assertFalse(DeadlineNeed.worthReporting(100, 101))
        assertFalse(DeadlineNeed.worthReporting(100, 92))
        assertTrue(DeadlineNeed.worthReporting(100, 90))
    }

    @Test
    fun followingPlayoutMovesTheTarget() {
        val c = ClockSync()
        c.onSyncReply(c = 0.0, h = 1_000.0, nowLocal = 10.0) // offset 995
        c.onClock(seq = 100, hostMs = 2_000.0, nowLocal = 1_010.0)
        val before = c.targetLocalMs(100, outputLatencyMs = 0.0, delayMs = 0.0)!!
        c.playoutMs = 230.0 // host sent {"type":"playout","ms":230}
        val after = c.targetLocalMs(100, outputLatencyMs = 0.0, delayMs = 0.0)!!
        assertEquals(110.0, after - before, 1e-9)
    }

    @Test
    fun smootherIgnoresNonsense() {
        val s = LatencySmoother(alpha = 0.5)
        s.add(-5.0)
        assertTrue(s.valueMs.isNaN())
        s.add(100.0)
        s.add(200.0)
        assertEquals(150.0, s.valueMs, 1e-9)
        s.add(9_999.0)
        assertEquals(150.0, s.valueMs, 1e-9)
    }
}
