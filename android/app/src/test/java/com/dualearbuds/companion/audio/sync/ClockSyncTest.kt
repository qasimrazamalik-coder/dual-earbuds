package com.dualearbuds.companion.audio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {

    @Test
    fun offsetIsMidpointOfRoundTrip() {
        val cs = ClockSync()
        // Host clock = local + 1000. Request sent at local 100, host answered at host 1105, reply at local 110.
        cs.onSyncReply(c = 100.0, h = 1105.0, nowLocal = 110.0)
        assertEquals(1000.0, cs.offsetMs!!, 1e-9)
        assertEquals(10.0, cs.bestRttMs!!, 1e-9)
    }

    @Test
    fun keepsLowestRoundTripOfLastEight() {
        val cs = ClockSync(window = 8)
        // Asymmetric slow sample: big RTT, biased offset.
        cs.onSyncReply(0.0, 1090.0, 100.0) // offset 1040, rtt 100
        cs.onSyncReply(200.0, 1202.0, 204.0) // offset 1000, rtt 4  <- best
        cs.onSyncReply(300.0, 1330.0, 340.0) // offset 1010, rtt 40
        assertEquals(1000.0, cs.offsetMs!!, 1e-9)
        // Push the best one out of the window with 8 worse samples.
        repeat(8) { i -> cs.onSyncReply(1000.0 + i * 100, 2000.0 + i * 100 + 30, 1000.0 + i * 100 + 50) }
        assertEquals(8, cs.sampleCount)
        assertEquals(1005.0, cs.offsetMs!!, 1e-9) // 2030 - (1000+1050)/2
    }

    @Test
    fun ignoresNegativeRoundTrip() {
        val cs = ClockSync()
        cs.onSyncReply(c = 500.0, h = 0.0, nowLocal = 400.0)
        assertNull(cs.offsetMs)
    }

    @Test
    fun hostTimeOfSeqIsTenMsPerPacketAndWrapSafe() {
        val cs = ClockSync()
        cs.onClock(seq = 100, hostMs = 5_000.0, nowLocal = 0.0)
        assertEquals(5_000.0, cs.hostTimeOf(100)!!, 1e-9)
        assertEquals(5_250.0, cs.hostTimeOf(125)!!, 1e-9)
        assertEquals(4_990.0, cs.hostTimeOf(99)!!, 1e-9)
        cs.onClock(seq = 0xFFFFFFFFL, hostMs = 1_000.0, nowLocal = 0.0)
        assertEquals(1_020.0, cs.hostTimeOf(1)!!, 1e-9) // wrapped: 0xFFFFFFFF -> 0 -> 1
    }

    @Test
    fun targetFollowsProtocolFormula() {
        val cs = ClockSync()
        cs.onSyncReply(100.0, 1105.0, 110.0) // offset +1000
        cs.onClock(seq = 10, hostMs = 2_000.0, nowLocal = 120.0)
        cs.playoutMs = 120.0
        // h(12) = 2020; + 120 playout - 30 output latency - 15 delay = 2095 host = 1095 local
        assertEquals(1_095.0, cs.targetLocalMs(12, outputLatencyMs = 30.0, delayMs = 15.0)!!, 1e-9)
    }

    @Test
    fun readyNeedsOffsetAndFreshClock() {
        val cs = ClockSync()
        assertFalse(cs.isReady(0.0))
        cs.onClock(1, 0.0, nowLocal = 0.0)
        assertFalse(cs.isReady(0.0))
        cs.onSyncReply(0.0, 0.0, 2.0)
        assertTrue(cs.isReady(100.0))
        assertFalse(cs.isReady(10_000.0)) // no clock message for 10 s: fall back to the buffer
    }

    @Test
    fun seqDeltaIsSigned() {
        assertEquals(1L, ClockSync.seqDelta(0, 0xFFFFFFFFL))
        assertEquals(-1L, ClockSync.seqDelta(0xFFFFFFFFL, 0))
        assertEquals(-5L, ClockSync.seqDelta(5, 10))
    }
}
