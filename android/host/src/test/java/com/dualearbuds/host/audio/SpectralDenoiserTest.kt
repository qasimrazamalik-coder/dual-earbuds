package com.dualearbuds.host.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralDenoiserTest {
    private val hop = SpectralDenoiser.HOP

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { (it * it).toDouble() } / x.size)

    /** Runs [seconds] of signal through and returns the output of the last second. */
    private fun run(level: NoiseCancel, seconds: Int, gen: (Int) -> Float): FloatArray {
        val d = SpectralDenoiser()
        val total = seconds * 100
        val tail = FloatArray(100 * hop)
        val buf = FloatArray(hop)
        var n = 0
        for (f in 0 until total) {
            for (i in 0 until hop) buf[i] = gen(n++)
            d.process(buf, level)
            if (f >= total - 100) System.arraycopy(buf, 0, tail, (f - (total - 100)) * hop, hop)
        }
        return tail
    }

    @Test
    fun fftRoundTrip() {
        val fft = Fft(1024)
        val re = FloatArray(1024) { sin(it * 0.1f) + 0.3f }
        val orig = re.copyOf()
        val im = FloatArray(1024)
        fft.transform(re, im, false)
        // DC bin = sum
        assertEquals(orig.sum().toDouble(), re[0].toDouble(), 1e-2)
        fft.transform(re, im, true)
        for (i in 0 until 1024) assertEquals(orig[i], re[i], 1e-4f)
    }

    @Test
    fun gainMath() {
        assertEquals(1f, SpectralDenoiser.binGain(100f, 0f, 2f, 0.08f), 0f)
        assertEquals(0.08f, SpectralDenoiser.binGain(1f, 1f, 2f, 0.08f), 0f)
        assertEquals(sqrt(0.8f), SpectralDenoiser.binGain(10f, 1f, 2f, 0.08f), 1e-6f)
        assertEquals(0.08f, SpectralDenoiser.binGain(0f, 1f, 2f, 0.08f), 0f)
    }

    @Test
    fun offIsIdentity() {
        val d = SpectralDenoiser()
        val buf = FloatArray(hop) { it / 1000f }
        val copy = buf.copyOf()
        d.process(buf, NoiseCancel.OFF)
        for (i in 0 until hop) assertEquals(copy[i], buf[i], 0f)
    }

    @Test
    fun steadyNoiseIsStronglyReducedAndLightIsInBetween() {
        val noise = { seed: Long -> val r = Random(seed); { _: Int -> (r.nextGaussian() * 0.05).toFloat() } }
        val inRms = rms(run(NoiseCancel.OFF, 3, noise(1)))
        val strong = rms(run(NoiseCancel.STRONG, 3, noise(1)))
        val light = rms(run(NoiseCancel.LIGHT, 3, noise(1)))
        val strongDb = 20 * log10(strong / inRms)
        val lightDb = 20 * log10(light / inRms)
        assertTrue("strong should cut steady noise by >= 15 dB, got $strongDb", strongDb < -15)
        assertTrue("light sits between: $lightDb", lightDb < -4 && lightDb > strongDb)
    }

    @Test
    fun speechLikeBurstsOverNoiseSurvive() {
        // A 1 kHz tone switched on/off every 300 ms (like syllables), 20 dB above the noise.
        val r = Random(7)
        val gen = { n: Int ->
            val on = (n / 14_400) % 2 == 0
            val tone = if (on) 0.5f * sin(2 * PI * 1000 * n / 48_000.0).toFloat() else 0f
            tone + (r.nextGaussian() * 0.01).toFloat()
        }
        val out = run(NoiseCancel.STRONG, 3, gen)
        val r2 = Random(7)
        var n0 = 0
        val ref = FloatArray(3 * 48_000) { val n = n0++; val on = (n / 14_400) % 2 == 0; (if (on) 0.5f * sin(2 * PI * 1000 * n / 48_000.0).toFloat() else 0f) + (r2.nextGaussian() * 0.01).toFloat() }
        val refTail = ref.copyOfRange(2 * 48_000, 3 * 48_000)
        val db = 20 * log10(rms(out) / rms(refTail))
        assertTrue("speech-like signal kept within 3 dB, got $db", db > -3 && db < 1)
    }

    @Test
    fun unityGainPathReconstructsWithOneHopDelay() {
        // With no subtraction every gain is 1: the windowed overlap-add alone must give back the
        // input delayed by exactly one hop.
        val d = SpectralDenoiser(overSubtract = 0f)
        val frames = 30
        val input = FloatArray(frames * hop) { n -> (sin(n * 0.05) * 0.3 + sin(n * 0.31) * 0.1).toFloat() }
        val output = FloatArray(frames * hop)
        val buf = FloatArray(hop)
        for (f in 0 until frames) {
            System.arraycopy(input, f * hop, buf, 0, hop)
            d.process(buf, NoiseCancel.STRONG)
            System.arraycopy(buf, 0, output, f * hop, hop)
        }
        for (n in 2 * hop until frames * hop) assertEquals(input[n - hop], output[n], 1e-4f)
    }
}
