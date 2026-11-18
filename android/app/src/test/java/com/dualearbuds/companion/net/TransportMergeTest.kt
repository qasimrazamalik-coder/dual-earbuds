package com.dualearbuds.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportMergeTest {
    private val out = ArrayList<Long>()
    private val m = TransportMerge<String>()
    private fun ws(seq: Long, t: Long) = m.offer(seq, fromUdp = false, nowMs = t, payload = "w") { s, _ -> out += s }

    /** One datagram: the redundant previous packet first, then the new one (as UdpAudioReceiver hands them over). */
    private fun udp(seq: Long, t: Long) {
        m.offer((seq - 1) and 0xFFFFFFFFL, true, t, "u") { s, _ -> out += s }
        m.offer(seq, true, t, "u") { s, _ -> out += s }
    }

    @Test
    fun webSocketToUdpSwitchLosesNothing() {
        for (i in 0L until 100L) ws(i, i * 10)
        // The host switches: UDP overtakes TCP. Datagrams 107, 108… arrive while WS 100…105 are still in flight.
        udp(107, 1000)
        udp(108, 1010)
        assertTrue("UDP must wait for the WebSocket packets", out.last() == 99L)
        for (i in 100L..105L) ws(i, 1012 + (i - 100))
        udp(109, 1020)
        udp(110, 1030)
        assertEquals((0L..110L).toList(), out)
        assertEquals(0, m.heldCount)
    }

    @Test
    fun steadyUdpWithLossIsNeverHeld() {
        udp(1, 0)
        udp(2, 10)
        udp(5, 40) // 3 lost entirely (both copies), 4 via redundancy
        assertEquals(listOf(0L, 1L, 2L, 4L, 5L), out)
        assertEquals(0, m.heldCount)
    }

    @Test
    fun heldPacketsAreReleasedWhenTheWebSocketNeverCatchesUp() {
        for (i in 0L until 10L) ws(i, i * 10)
        udp(20, 100)
        assertEquals(9L, out.last())
        udp(21, 200) // still within 250 ms of the last WS packet? 200 − 90 = 110 → held
        assertEquals(9L, out.last())
        udp(22, 400) // the WebSocket went quiet: release in order, gap counted as loss by the caller
        assertEquals((0L..9L).toList() + listOf(19L, 20L, 21L, 22L), out)
        assertEquals(0, m.heldCount)
    }

    @Test
    fun duplicatesAcrossTransportsAreDropped() {
        ws(0, 0)
        ws(1, 10)
        udp(2, 15) // 1 dup, 2 new
        ws(2, 16)
        ws(3, 26)
        udp(3, 27)
        assertEquals(listOf(0L, 1L, 2L, 3L), out)
    }

    @Test
    fun worksAcrossTheSequenceWrap() {
        val top = 0xFFFFFFFFL
        ws(top - 3, 0)
        ws(top - 2, 10)
        udp(1, 15) // 0 and 1 overtake top−1, top
        ws(top - 1, 17)
        ws(top, 18)
        udp(2, 25)
        assertEquals(listOf(top - 3, top - 2, top - 1, top, 0L, 1L, 2L), out)
    }

    @Test
    fun resetForgetsEverything() {
        ws(5, 0)
        udp(9, 5)
        m.reset()
        out.clear()
        udp(1, 1000)
        assertEquals(listOf(0L, 1L), out)
    }
}
