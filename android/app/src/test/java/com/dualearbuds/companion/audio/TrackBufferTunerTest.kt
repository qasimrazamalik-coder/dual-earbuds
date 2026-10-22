package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackBufferTunerTest {

    @Test
    fun startsAtTwoBursts() {
        val t = TrackBufferTuner(burstFrames = 240, capacityFrames = 1_920, rate = 48_000)
        assertEquals(480, t.sizeFrames) // 10 ms, not the 40 ms allocated
    }

    @Test
    fun tinyBurstsKeepAFloorOfFourMs() {
        val t = TrackBufferTuner(burstFrames = 48, capacityFrames = 1_920, rate = 48_000)
        assertEquals(192, t.sizeFrames)
    }

    @Test
    fun unknownBurstFallsBackToTenMs() {
        val t = TrackBufferTuner(burstFrames = 0, capacityFrames = 1_920, rate = 48_000)
        assertEquals(480, t.burstFrames)
        assertEquals(960, t.sizeFrames)
    }

    @Test
    fun growsOneBurstPerUnderrunUpToCapacity() {
        val t = TrackBufferTuner(burstFrames = 192, capacityFrames = 1_000, rate = 48_000)
        assertNull(t.onUnderrunCount(0)) // first reading is the baseline
        assertNull(t.onUnderrunCount(0))
        assertEquals(576, t.onUnderrunCount(1))
        assertEquals(768, t.onUnderrunCount(4)) // several underruns between checks = one step
        assertEquals(960, t.onUnderrunCount(5))
        assertEquals(1_000, t.onUnderrunCount(6))
        assertNull(t.onUnderrunCount(7))
        assertEquals(4, t.raises)
    }

    @Test
    fun followsWhatThePlatformApplied() {
        val t = TrackBufferTuner(burstFrames = 192, capacityFrames = 4_000, rate = 48_000)
        t.onApplied(512)
        assertEquals(512, t.sizeFrames)
        t.onUnderrunCount(0)
        assertEquals(704, t.onUnderrunCount(1))
    }
}
