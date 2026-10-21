package com.dualearbuds.companion.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private val rate = 48_000

    private fun stereoTone(freq: Double, amp: Float, frames: Int, phase0: Int = 0, side: Boolean = false) = FloatArray(frames * 2) { i ->
        val f = phase0 + i / 2
        val v = (amp * sin(2 * PI * freq * f / rate)).toFloat()
        if (side && i % 2 == 1) -v else v
    }

    private fun rmsDb(x: FloatArray, from: Int = 0) =
        10 * log10(x.drop(from).sumOf { (it * it).toDouble() } / (x.size - from) + 1e-15)

    @Test
    fun peakingFilterHasItsGainAtCentre() {
        val b = Biquad.peaking(1000.0, 1.0, 6.0, rate)
        assertEquals(6.0, b.magnitudeDb(1000.0, rate), 0.05)
        assertEquals(0.0, b.magnitudeDb(20.0, rate), 0.2)
    }

    @Test
    fun shelvesBoostTheirEnd() {
        assertEquals(6.0, Biquad.lowShelf(120.0, 6.0, rate).magnitudeDb(20.0, rate), 0.3)
        assertEquals(3.0, Biquad.highShelf(7000.0, 3.0, rate).magnitudeDb(20_000.0, rate), 0.3)
    }

    @Test
    fun eqCurveInterpolatesOnLogFrequency() {
        val c = EqCurve(doubleArrayOf(100.0, 1000.0), doubleArrayOf(0.0, 10.0))
        assertEquals(5.0, c.gainAt(sqrt(100.0 * 1000.0)), 1e-9)
        assertEquals(0.0, c.gainAt(20.0), 0.0)
        assertEquals(10.0, c.gainAt(20_000.0), 0.0)
    }

    @Test
    fun presetsParseFromTheWire() {
        assertEquals(EqPreset.MOVIE, EqPreset.fromWire("movie"))
        assertEquals(EqPreset.BASS, EqPreset.fromWire(" BASS "))
        assertEquals(null, EqPreset.fromWire("loud"))
    }

    @Test
    fun softwareEqFlatIsBypassedAndBassBoostsLows() {
        val eq = SoftwareEq()
        eq.setSpec(EqSpec(EqPreset.FLAT), rate)
        assertTrue(eq.isBypassed)
        eq.setSpec(EqSpec(EqPreset.BASS), rate)
        val lowIn = stereoTone(50.0, 0.1f, rate)
        val low = lowIn.copyOf()
        eq.process(low, rate) // 1 s: crossfade (300 ms) finished
        val low2 = stereoTone(50.0, 0.1f, rate, phase0 = rate)
        val ref = low2.copyOf()
        eq.process(low2, rate)
        assertTrue(rmsDb(low2) - rmsDb(ref) > 1.5) // +6 dB shelf minus the 3 dB pre-gain
    }

    @Test
    fun eqCrossfadeHasNoJump() {
        val eq = SoftwareEq()
        eq.setSpec(EqSpec(EqPreset.FLAT), rate)
        val x = stereoTone(440.0, 0.3f, 4_800)
        eq.setSpec(EqSpec(EqPreset.VOICE), rate)
        eq.process(x, 4_800)
        var maxStep = 0f
        for (f in 1 until 4_800) maxStep = maxOf(maxStep, abs(x[f * 2] - x[(f - 1) * 2]))
        // A 440 Hz sine at 0.3 moves at most ~0.018 per sample; the fade must not add a step.
        assertTrue("step $maxStep", maxStep < 0.03f)
    }

    @Test
    fun hearingGainsArePerEar() {
        val eq = SoftwareEq()
        val gains = listOf(0.0, 0.0, 0.0, 0.0, 9.0, 0.0, 0.0)
        eq.setSpec(EqSpec(EqPreset.FLAT, hearingLeftDb = gains, hearingRightDb = List(7) { 0.0 }), rate)
        val x = stereoTone(4000.0, 0.05f, rate)
        eq.process(x, rate)
        var le = 0.0
        var re = 0.0
        for (f in rate / 2 until rate) {
            le += x[f * 2] * x[f * 2]
            re += x[f * 2 + 1] * x[f * 2 + 1]
        }
        assertTrue(10 * log10(le / re) > 6.0) // left ear boosted at 4 kHz relative to right
    }

    @Test
    fun limiterNeverExceedsCeiling() {
        val lim = Limiter(ceiling = 0.9f)
        val x = stereoTone(100.0, 1.8f, 9_600)
        lim.process(x, 9_600)
        assertTrue(x.all { abs(it) <= 0.9f + 1e-6f })
    }

    @Test
    fun compressorCurve() {
        val c = Compressor(thresholdDb = -24.0, ratio = 3.0)
        assertEquals(0.0, c.staticGainDb(-30.0), 1e-9)
        assertEquals(-8.0, c.staticGainDb(-12.0), 1e-9) // 12 dB over -> 4 dB over
    }

    @Test
    fun vadHearsCentreSpeechBandButNotBassOrWideSides() {
        fun run(signal: (Int) -> Pair<Float, Float>): Boolean {
            val d = DialogueBoost(rate)
            d.mode = DialogueMode.LIGHT
            val buf = FloatArray(960)
            var t = 0
            repeat(100) {
                for (f in 0 until 480) {
                    val (l, r) = signal(t++)
                    buf[f * 2] = l
                    buf[f * 2 + 1] = r
                }
                d.process(buf, 480)
            }
            return d.speechDetected
        }
        val voice = run { t -> val v = (0.2 * sin(2 * PI * 800.0 * t / rate)).toFloat(); v to v }
        val bass = run { t -> val v = (0.2 * sin(2 * PI * 60.0 * t / rate)).toFloat(); v to v }
        val wide = run { t -> val v = (0.2 * sin(2 * PI * 800.0 * t / rate)).toFloat(); v to -v }
        val silence = run { _ -> 0f to 0f }
        assertTrue(voice)
        assertFalse(bass)
        assertFalse(wide)
        assertFalse(silence)
    }

    @Test
    fun dialogueBoostRaisesCentreVoiceAndNeverClips() {
        val d = DialogueBoost(rate)
        d.mode = DialogueMode.STRONG
        val frames = rate
        val x = stereoTone(1000.0, 0.9f, frames)
        d.process(x, frames)
        assertTrue(x.all { abs(it) <= 0.97f + 1e-6f })
        val quiet = stereoTone(1000.0, 0.05f, frames)
        val qRef = quiet.copyOf()
        val d2 = DialogueBoost(rate).also { it.mode = DialogueMode.STRONG }
        d2.process(quiet, frames)
        assertTrue(rmsDb(quiet, frames) - rmsDb(qRef, frames) > 3.0)
    }

    @Test
    fun dialogueOffIsTransparent() {
        val d = DialogueBoost(rate)
        val x = stereoTone(1000.0, 0.3f, 4_800, side = true)
        val ref = x.copyOf()
        d.process(x, 4_800)
        for (i in x.indices) assertEquals(ref[i], x[i], 1e-5f)
        assertTrue(d.isIdle)
    }
}
