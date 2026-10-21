package com.dualearbuds.companion.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HearingTestTest {

    /** Simulated listener who hears everything at or above [thresholdDb]. */
    private fun run(stair: Staircase, thresholdDb: Double) {
        var guard = 0
        while (!stair.isDone && guard++ < 100) stair.answer(stair.levelDb >= thresholdDb)
    }

    @Test
    fun staircaseConvergesNearTheThreshold() {
        val s = Staircase(startDb = -30.0)
        run(s, -50.0)
        assertTrue(s.isDone)
        assertEquals(-50.0, s.thresholdDb, 4.0)
    }

    @Test
    fun cantHearAtMaxStops() {
        val s = Staircase(startDb = -30.0, maxDb = -12.0)
        run(s, 0.0) // hears nothing
        assertTrue(s.isDone)
        assertEquals(-12.0, s.thresholdDb, 0.01)
    }

    @Test
    fun gainsUseHalfOfTheLossCappedAtNine() {
        val p = HearingProfile(
            "a", "Me",
            leftDb = listOf(-60.0, -60.0, -60.0, -60.0, -50.0, -40.0, -20.0),
            rightDb = listOf(-60.0, -60.0, -60.0, -60.0, -60.0, -60.0, -60.0),
            createdAt = 0
        )
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 5.0, 9.0, 9.0), HearingEq.gains(p, Ear.LEFT))
        assertTrue(HearingEq.gains(p, Ear.RIGHT).all { it == 0.0 })
    }

    @Test
    fun sessionCoversBothEarsAndAllFrequencies() {
        val session = HearingTestSession()
        assertEquals(14, session.totalSteps)
        var guard = 0
        while (!session.isDone && guard++ < 1000) {
            val (ear, freq, level) = session.current!!
            val threshold = if (ear == Ear.RIGHT && freq >= 4000) -35.0 else -55.0
            session.answer(level >= threshold)
        }
        val p = session.result("id", "Test", 1L)
        assertNotNull(p)
        val right = HearingEq.gains(p!!, Ear.RIGHT)
        assertTrue(right[4] > 5.0 && right[0] == 0.0)
    }
}
