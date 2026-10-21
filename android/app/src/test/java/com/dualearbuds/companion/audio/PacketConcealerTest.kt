package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PacketConcealerTest {
    private val rate = 48_000

    private fun tone(start: Int = 0, amp: Int = 10_000): ShortArray = ShortArray(960) { i ->
        val f = start + i / 2
        (amp * kotlin.math.sin(2 * Math.PI * 1000 * f / rate)).toInt().toShort()
    }

    @Test
    fun nothingToRepeatBeforeTheFirstPacket() {
        val c = PacketConcealer()
        assertTrue(c.conceal(1, rate).isEmpty())
    }

    @Test
    fun repeatsLastPacketWithFadeToSilenceWithin30ms() {
        val c = PacketConcealer()
        val last = tone()
        c.onReal(last.copyOf(), rate)
        val fill = c.conceal(5, rate)
        assertEquals(3, fill.size) // at most 30 ms
        // The first sample repeats the last packet at full gain.
        assertEquals(last[0].toInt(), fill[0][0].toInt())
        // Envelope falls monotonically: compare peak of each packet.
        val peaks = fill.map { p -> p.maxOf { abs(it.toInt()) } }
        assertTrue(peaks[0] > peaks[1] && peaks[1] > peaks[2])
        // Last frame of the 30 ms fill is (almost) silent.
        assertTrue(abs(fill[2][958].toInt()) < 50)
    }

    @Test
    fun oneLostPacketIsPartlyFaded() {
        val c = PacketConcealer()
        c.onReal(ShortArray(960) { 9_000 }, rate)
        val fill = c.conceal(1, rate)
        assertEquals(1, fill.size)
        val end = fill[0][958].toInt()
        assertTrue(end in 5_900..6_100) // 2/3 of the way after 10 of 30 ms
    }

    @Test
    fun crossfadesBackWithoutAJump() {
        val c = PacketConcealer()
        c.onReal(ShortArray(960) { 9_000 }, rate)
        val fill = c.conceal(1, rate)
        val endOfFill = fill[0][958].toInt()
        val real = ShortArray(960) { -9_000 }
        c.onReal(real, rate)
        // First sample of the real packet starts near where the fill ended, not at -9000.
        assertTrue(abs(real[0] - (endOfFill * 0.97)) < 600)
        // After the 5 ms crossfade the real sound is untouched.
        assertEquals(-9_000, real[2 * 300].toInt())
        var maxStep = abs(real[0] - endOfFill)
        for (f in 1 until 240) maxStep = maxOf(maxStep, abs(real[f * 2] - real[(f - 1) * 2]))
        assertTrue("step $maxStep", maxStep < 400)
    }

    @Test
    fun rateChangeIsNotConcealed() {
        val c = PacketConcealer()
        c.onReal(tone(), rate)
        assertTrue(c.conceal(1, 44_100).isEmpty())
    }
}
