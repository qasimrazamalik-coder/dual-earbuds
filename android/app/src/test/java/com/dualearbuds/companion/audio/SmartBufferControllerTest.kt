package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SmartBufferControllerTest {

    /** Feeds [packets] arrivals 10 ms apart with up to [jitterMs] of random extra delay. Returns the end time. */
    private fun feed(c: SmartBufferController, start: Long, packets: Int, jitterMs: Int, seed: Long = 7, seq0: Long = 0): Long {
        val r = Random(seed)
        var t = start
        for (i in 0 until packets) {
            val nominal = start + i * 10L
            val arrival = nominal + if (jitterMs > 0) r.nextInt(jitterMs + 1) else 0
            t = arrival
            c.onArrival(seq0 + i, arrival)
            c.tick(nominal)
        }
        return t
    }

    @Test
    fun startsAtSixtyDuringWarmup() {
        val c = SmartBufferController().also { it.reset(0) }
        feed(c, 0, 100, 0)
        assertEquals(60, c.currentMs)
    }

    @Test
    fun cleanNetworkShrinksToMinimumSlowly() {
        val c = SmartBufferController().also { it.reset(0) }
        feed(c, 0, 1_000, 0) // 10 s
        assertEquals(30, c.targetMs) // p99 0 + 10 margin -> clamped to 30
        assertEquals(55, c.currentMs) // only −5 per 5 s
        feed(c, 10_000, 3_000, 0, seq0 = 1_000)
        assertEquals(30, c.currentMs)
    }

    @Test
    fun jitteryNetworkRaisesTargetImmediately() {
        val c = SmartBufferController().also { it.reset(0) }
        feed(c, 0, 3_000, 80)
        assertTrue("target ${c.targetMs}", c.targetMs in 80..100) // p99 of 0..80 ≈ 79, + 10
        assertTrue(c.currentMs >= c.targetMs)
        assertTrue(c.jitterP99Ms > 70)
    }

    @Test
    fun underrunAddsTwentyAtOnceAndIsCapped() {
        val c = SmartBufferController().also { it.reset(0) }
        assertEquals(80, c.onUnderrun(0))
        repeat(20) { c.onUnderrun(0) }
        assertEquals(300, c.currentMs)
    }

    @Test
    fun streamRestartRelearns() {
        val c = SmartBufferController().also { it.reset(0) }
        feed(c, 0, 3_000, 80)
        val high = c.targetMs
        // Host restarted: sequence numbers jump back; a calm network follows for 90 s.
        feed(c, 40_000, 9_000, 0, seq0 = 5)
        assertTrue(c.targetMs < high)
        assertEquals(30, c.targetMs)
    }
}
