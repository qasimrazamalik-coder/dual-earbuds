package com.dualearbuds.companion.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** What is playing, as far as Smart EQ "By sound" cares (smart-features #2). */
enum class SoundClass { SPEECH, MUSIC, MOVIE, SILENCE }

/** Hand-computed features of one 1 s window. */
data class SoundFeatures(
    val levelDb: Double,
    /** Spectral flatness 0..1 (1 = noise-like, 0 = tonal). */
    val flatness: Double,
    /** Spectral centroid in Hz. */
    val centroidHz: Double,
    /** Speech-band (300 Hz–4 kHz) loudness modulation at 2–8 Hz relative to its mean (syllables ≈ 4 Hz). */
    val modulation4Hz: Double,
    /** Zero-crossing rate per sample (0..1). */
    val zcr: Double,
    /** Share of spectral energy below 250 Hz. */
    val lowRatio: Double
)

/**
 * Tiny fixed decision model over [SoundFeatures]. Not trained ML: explicit, readable rules with thresholds
 * picked from how speech, music and film mixes typically look.
 */
object SoundModel {
    fun classify(f: SoundFeatures): SoundClass {
        if (f.levelDb < -50.0) return SoundClass.SILENCE
        val speechy = f.modulation4Hz > 0.25
        return when {
            speechy && f.lowRatio < 0.25 -> SoundClass.SPEECH
            speechy -> SoundClass.MOVIE // speech rhythm over strong low-frequency effects/score
            f.flatness > 0.35 && f.lowRatio > 0.25 -> SoundClass.MOVIE // noisy, bass-heavy effects
            else -> SoundClass.MUSIC
        }
    }
}

/**
 * Computes [SoundFeatures] over 1 s windows from a mono feed, one 512-point FFT every [hopFrames], plus the
 * loudness envelope at 100 Hz for the 4 Hz modulation. All buffers are preallocated; [feed] never allocates.
 */
class FeatureExtractor(private var rate: Int = 48_000) {
    private val fftSize = 512
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val frame = DoubleArray(fftSize)
    private var framePos = 0

    private val envelope = DoubleArray(100) // 10 ms loudness blocks of the speech band (300 Hz–4 kHz) over 1 s
    private var bandHp = Biquad.highPass(300.0, rate)
    private var bandLp = Biquad.lowPass(4000.0, rate)
    private var envPos = 0
    private var blockEnergy = 0.0
    private var blockN = 0
    private var blockFrames = rate / 100

    private var sumFlat = 0.0
    private var sumCentroid = 0.0
    private var sumLow = 0.0
    private var spectra = 0
    private var zc = 0
    private var samples = 0L
    private var prev = 0.0
    private var energy = 0.0

    fun setRate(newRate: Int) {
        if (newRate == rate || newRate <= 0) return
        rate = newRate
        blockFrames = newRate / 100
        bandHp = Biquad.highPass(300.0, newRate)
        bandLp = Biquad.lowPass(4000.0, newRate)
        reset()
    }

    fun reset() {
        framePos = 0; envPos = 0; blockEnergy = 0.0; blockN = 0
        sumFlat = 0.0; sumCentroid = 0.0; sumLow = 0.0; spectra = 0
        zc = 0; samples = 0; prev = 0.0; energy = 0.0
    }

    /** Feeds one mono sample. Returns a feature set when a 1 s window completes, else null. */
    fun feed(x: Double): SoundFeatures? {
        if ((x >= 0) != (prev >= 0)) zc++
        prev = x
        samples++
        energy += x * x
        val b = bandLp.process(bandHp.process(x.toFloat())).toDouble()
        blockEnergy += b * b
        if (++blockN >= blockFrames) {
            envelope[envPos++] = sqrt(blockEnergy / blockN)
            blockEnergy = 0.0
            blockN = 0
        }
        frame[framePos++] = x
        if (framePos == fftSize) {
            framePos = 0
            analyseSpectrum()
        }
        if (envPos >= envelope.size) {
            val out = finishWindow()
            return out
        }
        return null
    }

    private fun analyseSpectrum() {
        for (i in 0 until fftSize) {
            re[i] = frame[i] * window[i]
            im[i] = 0.0
        }
        Fft.transform(re, im)
        val half = fftSize / 2
        val binHz = rate.toDouble() / fftSize
        var sum = 0.0
        var logSum = 0.0
        var weighted = 0.0
        var low = 0.0
        for (k in 1 until half) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12
            sum += p
            logSum += ln(p)
            weighted += p * k * binHz
            if (k * binHz < 250.0) low += p
        }
        val n = half - 1
        if (sum < 1e-9) return
        sumFlat += kotlin.math.exp(logSum / n) / (sum / n)
        sumCentroid += weighted / sum
        sumLow += low / sum
        spectra++
    }

    private fun finishWindow(): SoundFeatures {
        val mean = envelope.average()
        // Modulation energy at 2–8 Hz of the envelope (DFT bins 2..8 of a 1 s, 100-point series).
        var mod = 0.0
        for (k in 2..8) {
            var cr = 0.0
            var ci = 0.0
            for (i in envelope.indices) {
                val a = 2 * PI * k * i / envelope.size
                val v = envelope[i] - mean
                cr += v * cos(a)
                ci -= v * sin(a)
            }
            mod += (cr * cr + ci * ci)
        }
        val modNorm = if (mean > 1e-9) sqrt(mod) / (envelope.size * mean) else 0.0
        val level = 10 * log10(energy / samples.coerceAtLeast(1) + 1e-12)
        val f = SoundFeatures(
            levelDb = level,
            flatness = if (spectra > 0) sumFlat / spectra else 1.0,
            centroidHz = if (spectra > 0) sumCentroid / spectra else 0.0,
            modulation4Hz = modNorm,
            zcr = zc.toDouble() / samples.coerceAtLeast(1),
            lowRatio = if (spectra > 0) sumLow / spectra else 0.0
        )
        envPos = 0
        sumFlat = 0.0; sumCentroid = 0.0; sumLow = 0.0; spectra = 0
        zc = 0; samples = 0; energy = 0.0
        return f
    }
}

/**
 * Hysteresis for Smart EQ "By sound": the decision changes only after the same class wins [holdWindows]
 * consecutive 1 s windows (5 s). Silence never changes the decision.
 */
class ClassHysteresis(private val holdWindows: Int = 5) {
    var current: SoundClass? = null
        private set
    private var candidate: SoundClass? = null
    private var streak = 0

    fun reset() {
        current = null
        candidate = null
        streak = 0
    }

    /** Returns the (possibly unchanged) decided class. */
    fun update(c: SoundClass): SoundClass? {
        if (c == SoundClass.SILENCE) return current
        if (c == candidate) streak++ else {
            candidate = c
            streak = 1
        }
        if (streak >= holdWindows && c != current) current = c
        return current
    }
}

/** In-place iterative radix-2 FFT. [re]/[im] length must be a power of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
