package com.dualearbuds.companion.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeMathTest {

    @Test
    fun positionsOnTheScale() {
        assertEquals(0.5f, ScopeMath.position(0.0), 1e-6f)
        assertEquals(1.0f, ScopeMath.position(50.0), 1e-6f)
        assertEquals(0.0f, ScopeMath.position(-80.0), 1e-6f)
        assertEquals(0.53f, ScopeMath.position(3.0), 1e-6f)
        assertTrue(ScopeMath.outOfRange(-51.0))
        assertFalse(ScopeMath.outOfRange(Double.NaN))
    }

    @Test
    fun grades() {
        assertEquals(SyncGrade.IN_SYNC, ScopeMath.grade(-4.9))
        assertEquals(SyncGrade.CLOSE, ScopeMath.grade(12.0))
        assertEquals(SyncGrade.OFF, ScopeMath.grade(-35.0))
        assertEquals(SyncGrade.UNKNOWN, ScopeMath.grade(null))
    }

    @Test
    fun formatting() {
        assertEquals("+3 ms", ScopeMath.signedMs(3.2))
        assertEquals("−12 ms", ScopeMath.signedMs(-12.4))
        assertEquals("0 ms", ScopeMath.signedMs(0.3))
        assertEquals("—", ScopeMath.signedMs(null))
        assertEquals("late", ScopeMath.direction(9.0))
        assertEquals("on time", ScopeMath.direction(1.0))
        assertEquals("48 kHz", ScopeMath.kHz(48_000))
        assertEquals("44.1 kHz", ScopeMath.kHz(44_100))
    }

    @Test
    fun readoutLeavesOutWhatIsUnknown() {
        assertEquals("48 kHz  ·  P 120 ms  ·  UDP  ·  sync +3 ms", ScopeMath.readout(48_000, 120, "UDP", true, 3.0, 60))
        assertEquals("48 kHz  ·  buf 60 ms  ·  WS", ScopeMath.readout(48_000, 120, "WS", false, null, 60))
        assertEquals("", ScopeMath.readout(0, 0, "", false, null, 0))
    }
}
