package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBufferControllerTest {

    private fun controller(now: Long = 0L) = AdaptiveBufferController().also { it.reset(now) }

    @Test
    fun startsAt60() {
        assertEquals(60, controller().currentMs)
        assertEquals(60, controller().tick(19_999))
    }

    @Test
    fun underrunAdds20() {
        val c = controller()
        assertEquals(80, c.onUnderrun(1_000))
    }

    @Test
    fun underrunsInsideHoldWindowAddOnlyOnce() {
        val c = controller()
        c.onUnderrun(1_000)
        assertEquals(80, c.onUnderrun(5_000))
        assertEquals(80, c.onUnderrun(10_999))
        // Hold ended (10 s after the step at 1 000 ms): the next underrun steps again.
        assertEquals(100, c.onUnderrun(11_000))
    }

    @Test
    fun neverAbove300() {
        val c = controller()
        var t = 0L
        repeat(30) {
            t += 10_000
            c.onUnderrun(t)
        }
        assertEquals(300, c.currentMs)
    }

    @Test
    fun noDecreaseBefore20sClean() {
        val c = controller()
        c.onUnderrun(0) // 80
        assertEquals(80, c.tick(19_999))
    }

    @Test
    fun decreases5Per5sAfter20sClean() {
        val c = controller()
        c.onUnderrun(0) // 80
        assertEquals(75, c.tick(20_000))
        assertEquals(75, c.tick(24_999))
        assertEquals(70, c.tick(25_000))
        assertEquals(65, c.tick(30_000))
    }

    @Test
    fun underrunDuringHoldRestartsCleanTimer() {
        val c = controller()
        c.onUnderrun(0) // 80, held until 10 000
        c.onUnderrun(9_000) // ignored for the step, but the clean period restarts
        assertEquals(80, c.tick(20_000))
        assertEquals(80, c.tick(28_999))
        assertEquals(75, c.tick(29_000))
    }

    @Test
    fun decaysDownToMinimum30AndStays() {
        val c = controller()
        // 60 -> 30 takes 6 steps: at 20 s, 25 s, ... 45 s.
        assertEquals(30, c.tick(45_000))
        assertEquals(30, c.tick(10_000_000))
    }

    @Test
    fun catchesUpSkippedTicks() {
        val c = controller()
        c.onUnderrun(0) // 80
        // Not ticked for 32 s: steps at 20, 25 and 30 s are all applied.
        assertEquals(65, c.tick(32_000))
    }

    @Test
    fun resetGoesBackToStart() {
        val c = controller()
        c.onUnderrun(0)
        c.onUnderrun(10_000)
        c.reset(50_000)
        assertEquals(60, c.currentMs)
        assertEquals(60, c.tick(69_999))
        assertEquals(55, c.tick(70_000))
    }

    @Test
    fun tickWithoutResetStartsCleanPeriodLazily() {
        val c = AdaptiveBufferController()
        assertEquals(60, c.tick(1_000_000))
        assertEquals(60, c.tick(1_019_999))
        assertEquals(55, c.tick(1_020_000))
    }
}
