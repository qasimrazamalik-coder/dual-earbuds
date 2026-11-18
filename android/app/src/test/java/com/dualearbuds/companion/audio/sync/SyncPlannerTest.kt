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
    fun beyondTwentyMsSkipsOrInserts() {
        val p = SyncPlanner()
        assertEquals(SyncAction.Skip(2_400), p.plan(50.0, 48_000)) // 50 ms late
        assertEquals(SyncAction.Insert(4_800), p.plan(-100.0, 48_000)) // 100 ms early
        assertEquals(SyncAction.Skip(1_008), p.plan(21.0, 48_000))
        assertEquals(SyncAction.Insert(1_200), p.plan(-25.0, 48_000))
        assertTrue(p.plan(20.0, 48_000) is SyncAction.Play)
        assertTrue(p.plan(-19.0, 48_000) is SyncAction.Play)
    }

    /**
     * Closed loop: 10 ms packets at 48 kHz; Play(r) moves the error by 10 ms × (1 − r), Skip/Insert by their
     * frames. Returns the time (ms) until |error| ≤ 5 ms and stays there, or −1.
     */
    private fun convergeMs(p: SyncPlanner, startErrorMs: Double, snap: Boolean, maxMs: Int = 20_000): Int {
        var e = startErrorMs
        if (snap) p.snapNext()
        var settledAt = -1
        var t = 0
        while (t < maxMs) {
            when (val a = p.plan(e, 48_000)) {
                is SyncAction.Play -> e -= 10.0 * (1.0 - a.ratio)
                is SyncAction.Skip -> e -= a.frames / 48.0
                is SyncAction.Insert -> e += a.frames / 48.0
            }
            t += 10
            if (kotlin.math.abs(e) <= 5.0) {
                if (settledAt < 0) settledAt = t
            } else {
                settledAt = -1
            }
        }
        return settledAt
    }

    @Test
    fun playoutIncreaseReconvergesWithinTwoSeconds() {
        // P +40 ms: this ear is suddenly 40 ms early. The old 40 ms limit trimmed it at 0.5 % for ~7 s.
        val big = convergeMs(SyncPlanner(), -40.0, snap = true)
        assertTrue("took $big ms", big in 0..2_000)
        assertTrue(convergeMs(SyncPlanner(), -40.0, snap = false) in 0..2_000)
        assertTrue(convergeMs(SyncPlanner(hardLimitMs = 40.0), -39.0, snap = false) > 6_000)
        // P +15 ms: under the hard limit; the snap after a P change handles it at once.
        val small = convergeMs(SyncPlanner(), -15.0, snap = true)
        assertTrue("took $small ms", small in 0..2_000)
        // Without a P change, a drift of 15 ms is trimmed smoothly (no skip/insert).
        val p = SyncPlanner()
        assertTrue(p.plan(-15.0, 48_000) is SyncAction.Play)
    }

    @Test
    fun snapAppliesToOnePlanOnlyAndResetClearsIt() {
        val p = SyncPlanner()
        p.snapNext()
        assertEquals(SyncAction.Insert(480), p.plan(-10.0, 48_000))
        assertTrue(p.plan(-10.0, 48_000) is SyncAction.Play)
        p.snapNext()
        assertTrue(p.plan(-4.0, 48_000) is SyncAction.Play) // within 5 ms: nothing to snap
        assertTrue(p.plan(-10.0, 48_000) is SyncAction.Play)
        p.snapNext()
        p.reset()
        assertTrue(p.plan(-10.0, 48_000) is SyncAction.Play)
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
