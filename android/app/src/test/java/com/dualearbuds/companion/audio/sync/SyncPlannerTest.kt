package com.dualearbuds.companion.audio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    @Test
    fun withinDeadbandPlaysAtNormalRate() {
        val p = SyncPlanner()
        assertEquals(SyncAction.Play(1.0), p.plan(1.5, 48_000))
    }

    @Test
    fun trimIsAtMostHalfPercent() {
        val p = SyncPlanner()
        assertEquals(0.995, p.ratioFor(30.0), 1e-12) // late -> consume faster
        assertEquals(1.005, p.ratioFor(-30.0), 1e-12) // early -> slower
        val mid = p.ratioFor(3.5)
        assertTrue(mid < 1.0 && mid > 0.995)
    }

    @Test
    fun beyondFortyMsSkipsOrInserts() {
        val p = SyncPlanner()
        assertEquals(SyncAction.Skip(2_400), p.plan(50.0, 48_000)) // 50 ms late
        assertEquals(SyncAction.Insert(4_800), p.plan(-100.0, 48_000)) // 100 ms early
        assertTrue(p.plan(40.0, 48_000) is SyncAction.Play)
    }

    @Test
    fun smoothsTheErrorForTrimming() {
        val p = SyncPlanner(smoothing = 0.5)
        p.plan(0.0, 48_000)
        val a = p.plan(20.0, 48_000) as SyncAction.Play // smoothed 10
        assertEquals(0.995, a.ratio, 1e-12)
        assertEquals(10.0, p.smoothedErrorMs, 1e-9)
    }

    @Test
    fun trimmerAtRatioOneIsAnExactCopy() {
        val t = RateTrimmer()
        val input = ShortArray(960) { (it * 3).toShort() }
        val out = ShortArray(t.maxOutputSamples(960, 1.0))
        assertEquals(960, t.process(input, 960, 1.0, out))
        for (i in 0 until 960) assertEquals(input[i], out[i])
    }

    @Test
    fun trimmerChangesLengthByTheRatioOverManyPackets() {
        val t = RateTrimmer()
        val out = ShortArray(2_000)
        var total = 0L
        val packets = 1_000
        for (p in 0 until packets) {
            val input = ShortArray(960) { 1000 }
            total += t.process(input, 960, 1.005, out)
        }
        val expectedFrames = packets * 480 * 1.005
        assertEquals(expectedFrames, total / 2.0, 3.0)
    }

    @Test
    fun trimmerInterpolatesWithoutJumps() {
        val t = RateTrimmer()
        val out = ShortArray(2_000)
        var prev: Int? = null
        var maxStep = 0
        var x = 0
        repeat(20) {
            val input = ShortArray(960)
            for (f in 0 until 480) {
                val v = (x++ % 2000).toShort() // slow ramp: steps of 1 per frame
                input[f * 2] = v
                input[f * 2 + 1] = v
            }
            val n = t.process(input, 960, 0.995, out)
            var i = 0
            while (i < n) {
                val v = out[i].toInt()
                if (prev != null && v > prev!!) maxStep = maxOf(maxStep, v - prev!!)
                prev = v
                i += 2
            }
        }
        assertTrue("step $maxStep", maxStep <= 2)
    }
}
