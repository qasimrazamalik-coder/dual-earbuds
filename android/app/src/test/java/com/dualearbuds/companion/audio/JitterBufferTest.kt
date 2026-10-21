package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    /** 10 ms of 48 kHz stereo = 480 frames = 960 samples. */
    private fun packet() = AudioPacket(48_000, ShortArray(960))

    @Test
    fun waitsForTargetBeforePlaying() {
        val jb = JitterBuffer().also { it.reset(0) }
        assertEquals(60, jb.targetMs())
        repeat(5) { jb.push(packet(), 0) }
        assertNull(jb.poll(0))
        jb.push(packet(), 0)
        assertNotNull(jb.poll(0))
    }

    @Test
    fun manualDelayAddsOnTopInBothModes() {
        val jb = JitterBuffer().also { it.reset(0) }
        jb.delayMs = 40
        assertEquals(100, jb.targetMs())
        jb.mode = BufferMode.MANUAL
        jb.manualMs = 200
        assertEquals(240, jb.targetMs())
    }

    @Test
    fun underrunCountsAndRaisesAdaptiveBuffer() {
        val jb = JitterBuffer().also { it.reset(0) }
        repeat(6) { jb.push(packet(), 0) }
        repeat(6) { assertNotNull(jb.poll(10)) }
        assertNull(jb.poll(70)) // ran dry
        jb.push(packet(), 100) // stream still flowing -> real underrun
        assertEquals(1, jb.underruns)
        assertEquals(80, jb.targetMs())
    }

    @Test
    fun hostPauseIsNotAnUnderrun() {
        val jb = JitterBuffer().also { it.reset(0) }
        repeat(6) { jb.push(packet(), 0) }
        repeat(6) { jb.poll(10) }
        assertNull(jb.poll(70))
        jb.push(packet(), 5_000) // sound resumed long after: a pause
        assertEquals(0, jb.underruns)
        assertEquals(60, jb.targetMs())
    }

    @Test
    fun flushIsNotAnUnderrun() {
        val jb = JitterBuffer().also { it.reset(0) }
        repeat(6) { jb.push(packet(), 0) }
        repeat(6) { jb.poll(10) }
        jb.poll(70)
        jb.flush()
        jb.push(packet(), 80)
        assertEquals(0, jb.underruns)
    }

    @Test
    fun manualModeDoesNotAdapt() {
        val jb = JitterBuffer().also { it.reset(0) }
        jb.mode = BufferMode.MANUAL
        jb.manualMs = 50
        repeat(5) { jb.push(packet(), 0) }
        repeat(5) { jb.poll(10) }
        jb.poll(60)
        jb.push(packet(), 70)
        assertEquals(1, jb.underruns)
        assertEquals(50, jb.targetMs())
        assertEquals(60, jb.adaptiveMs)
    }

    @Test
    fun trimsBackToTargetWhenFarAhead() {
        val jb = JitterBuffer().also { it.reset(0) }
        // Target 60, slack 60: the 13th packet (130 ms) triggers a trim back to 60 ms.
        repeat(12) { jb.push(packet(), 0) }
        assertEquals(0, jb.dropped)
        jb.push(packet(), 0)
        assertTrue(jb.queuedMs <= 60.0 + 1e-6)
        assertEquals(7, jb.dropped)
    }

    @Test
    fun resetCountersKeepsAdaptiveValue() {
        val jb = JitterBuffer().also { it.reset(0) }
        repeat(6) { jb.push(packet(), 0) }
        repeat(6) { jb.poll(10) }
        jb.poll(70)
        jb.push(packet(), 80)
        jb.resetCounters()
        assertEquals(0, jb.underruns)
        assertEquals(80, jb.targetMs())
    }
}
