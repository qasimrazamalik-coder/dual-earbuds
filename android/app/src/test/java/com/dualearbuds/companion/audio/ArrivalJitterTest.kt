package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ArrivalJitterTest {

    private fun feed(j: ArrivalJitter, start: Long, packets: Int, jitterMs: Int, seq0: Long = 0, seed: Long = 3): Long {
        val r = Random(seed)
        var t = start
        for (i in 0 until packets) {
            t = start + i * 10L + if (jitterMs > 0) r.nextInt(jitterMs + 1) else 0
            j.onArrival(seq0 + i, t)
        }
        return t
    }

    @Test
    fun nanUntilEnoughPackets() {
        val j = ArrivalJitter().also { it.reset(0) }
        feed(j, 0, 20, 0)
        assertTrue(j.p99Ms.isNaN())
        feed(j, 200, 100, 0, seq0 = 20)
        assertEquals(0.0, j.p99Ms, 0.5)
    }

    @Test
    fun measuresRandomJitter() {
        val j = ArrivalJitter().also { it.reset(0) }
        feed(j, 0, 2_000, 40)
        assertTrue("p99 ${j.p99Ms}", j.p99Ms in 33.0..41.0)
    }

    @Test
    fun powerSaveBurstsShowAsTheirGap() {
        // Wi-Fi power save: nothing for 200 ms, then 20 packets at once.
        val j = ArrivalJitter().also { it.reset(0) }
        for (i in 0 until 1_000) {
            val burstEnd = ((i / 20) + 1) * 200L
            j.onArrival(i.toLong(), burstEnd)
        }
        assertTrue("p99 ${j.p99Ms}", j.p99Ms in 180.0..200.0)
    }

    @Test
    fun forgetsOldJitterAfterThirtySeconds() {
        val j = ArrivalJitter().also { it.reset(0) }
        feed(j, 0, 1_000, 120) // 10 s of a bad network
        assertTrue(j.p99Ms > 90)
        feed(j, 10_000, 4_000, 0, seq0 = 1_000) // 40 s calm
        assertTrue("p99 ${j.p99Ms}", j.p99Ms < 5)
    }

    @Test
    fun duplicatesAndLateCopiesAreIgnored() {
        val j = ArrivalJitter().also { it.reset(0) }
        feed(j, 0, 100, 0)
        val n = j.count
        j.onArrival(99, 5_000) // a duplicate arriving very late must not count as jitter
        j.onArrival(50, 5_000)
        assertEquals(n, j.count)
        assertEquals(0.0, j.p99Ms, 0.5)
    }
}
