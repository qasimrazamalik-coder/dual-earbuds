package com.dualearbuds.companion.calib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ClickCalibrationTest {
    private val rate = 48_000

    /** Simulates the mic: the playback delayed by [delayMs], attenuated, with noise and an optional echo. */
    private fun record(delayMs: Double, gain: Float = 0.3f, noise: Double = 0.01, seed: Long = 1, echo: Boolean = false): FloatArray {
        val play = ClickCalibration.playbackSignal(rate)
        val d = (delayMs * rate / 1000).toInt()
        val r = Random(seed)
        val rec = FloatArray(play.size + d + rate) { (r.nextGaussian() * noise).toFloat() }
        for (i in play.indices) {
            rec[i + d] += play[i] * gain
            if (echo && i + d + 480 < rec.size) rec[i + d + 480] += play[i] * gain * 0.3f
        }
        return rec
    }

    private fun expected() = ClickCalibration.clickStarts(rate)

    @Test
    fun findsASyntheticDelay() {
        val res = ClickCalibration.analyse(record(187.0), rate, expected())
        assertTrue(res.toString(), res is CalibrationResult.Ok)
        res as CalibrationResult.Ok
        assertEquals(187.0, res.latencyMs, 0.1)
        assertEquals(8, res.found)
    }

    @Test
    fun robustToNoiseAndEcho() {
        val res = ClickCalibration.analyse(record(243.5, gain = 0.15f, noise = 0.03, seed = 9, echo = true), rate, expected())
        assertTrue(res.toString(), res is CalibrationResult.Ok)
        assertEquals(243.5, (res as CalibrationResult.Ok).latencyMs, 0.2)
    }

    @Test
    fun handlesSlightlyNegativeResidual() {
        // The timestamps over-report latency by 20 ms: clicks arrive "before" the expected position.
        val rec = record(0.0)
        val shifted = expected().map { it + rate * 20 / 1000 }.toIntArray()
        val res = ClickCalibration.analyse(rec, rate, shifted) as CalibrationResult.Ok
        assertEquals(-20.0, res.latencyMs, 0.1)
    }

    @Test
    fun silenceFails() {
        val r = Random(3)
        val rec = FloatArray(rate * 6) { (r.nextGaussian() * 0.01).toFloat() }
        val res = ClickCalibration.analyse(rec, rate, expected())
        assertTrue(res.toString(), res is CalibrationResult.Failed)
    }
}
