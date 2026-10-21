package com.dualearbuds.host.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MicRingTest {
    @Test
    fun readsInOrderZeroFillsAndDropsOldestOnOverflow() {
        val r = MicRing(8)
        r.write(shortArrayOf(1, 2, 3), 0, 3)
        val out = FloatArray(5)
        assertEquals(3, r.read(out, 5))
        assertEquals(1f / 32768f, out[0])
        assertEquals(0f, out[4])
        r.write(ShortArray(12) { (it + 1).toShort() }, 0, 12) // only the newest 8 stay: 5..12
        assertEquals(8, r.available())
        val o2 = FloatArray(8)
        r.read(o2, 8)
        assertEquals(5f / 32768f, o2[0])
        assertEquals(12f / 32768f, o2[7])
        r.write(shortArrayOf(1, 2, 3, 4, 5, 6), 0, 6)
        r.write(shortArrayOf(7, 8, 9, 10), 0, 4) // overflow by 2 -> 3..10
        val o3 = FloatArray(8)
        r.read(o3, 8)
        assertEquals(3f / 32768f, o3[0])
        assertEquals(10f / 32768f, o3[7])
    }
}
