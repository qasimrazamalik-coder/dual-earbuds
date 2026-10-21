package com.dualearbuds.host.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Mic noise suppressor for talk-over (smart feature #1), 48 kHz mono, 10 ms (480-sample) frames.
 *
 * FALLBACK IMPLEMENTATION: smart-features.md asks for an RNNoise-style GRU port with its BSD
 * weights, or — until that port is finished and tested — a spectral-gating suppressor with the same
 * Off/Light/Strong options. This is the spectral-gating fallback; the RNNoise port is not included.
 *
 * Method: 960-sample sqrt-Hann windows with 50 % overlap (hop = one 10 ms frame, 10 ms latency),
 * 1024-point FFT, per-bin noise power = running mean of the frames that look like noise (power
 * below 3× the estimate); louder frames (speech) only let it creep up ≈1 dB/s, quieter ones pull it down;
 * Wiener-style gain with over-subtraction and a floor, smoothed over time (fast open, slow close)
 * and across neighbouring bins to avoid "musical noise". Light = 50 % dry + 50 % denoised.
 * Real-time safe: all buffers are allocated once.
 */
class SpectralDenoiser(private val overSubtract: Float = OVER_SUBTRACT) {
    private val fft = Fft(FFT_SIZE)
    private val window = FloatArray(WINDOW) { i -> sqrt(0.5 - 0.5 * cos(2.0 * PI * i / WINDOW)).toFloat() }
    private val frame = FloatArray(WINDOW)      // last two hops of input
    private val ola = FloatArray(HOP)          // overlap-add tail
    private val dryDelay = FloatArray(HOP)     // input delayed by one hop, for the Light mix
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val smoothPow = FloatArray(BINS)
    private val noise = FloatArray(BINS)
    private val gain = FloatArray(BINS)
    private val rawGain = FloatArray(BINS)
    private var frames = 0

    /** Clears all state (use after a pause so an old noise estimate is not reused). */
    fun reset() {
        frame.fill(0f); ola.fill(0f); dryDelay.fill(0f)
        smoothPow.fill(0f); noise.fill(0f); gain.fill(1f)
        frames = 0
    }

    init {
        reset()
    }

    /**
     * Denoises one hop of [HOP] samples in place (−1..1 floats). [OFF][NoiseCancel.OFF] leaves the
     * samples untouched (no delay). Other levels delay the signal by one hop (10 ms).
     */
    fun process(buf: FloatArray, level: NoiseCancel) {
        if (level == NoiseCancel.OFF) return
        // Slide the analysis frame and keep the matching dry hop.
        System.arraycopy(frame, HOP, frame, 0, HOP)
        System.arraycopy(buf, 0, frame, HOP, HOP)
        for (i in 0 until WINDOW) re[i] = frame[i] * window[i]
        for (i in WINDOW until FFT_SIZE) re[i] = 0f
        im.fill(0f)
        fft.transform(re, im, inverse = false)

        val warm = frames < WARMUP_FRAMES
        for (k in 0 until BINS) {
            val p = re[k] * re[k] + im[k] * im[k]
            val s = if (frames == 0) p else POW_SMOOTH * smoothPow[k] + (1f - POW_SMOOTH) * p
            smoothPow[k] = s
            val n = noise[k]
            noise[k] = when {
                frames == 0 -> p
                warm -> n + (p - n) * 0.1f                    // learn the room quickly at the start
                p < n -> n + (p - n) * NOISE_FALL             // quieter: follow down
                p < NOISE_GATE * n -> n + (p - n) * NOISE_MEAN // noise-like: running mean
                else -> n * NOISE_RISE                        // speech: creep up slowly
            }
            rawGain[k] = binGain(s, noise[k], overSubtract, GAIN_FLOOR)
        }
        for (k in 0 until BINS) {
            val a = rawGain[if (k > 0) k - 1 else k]
            val b = rawGain[k]
            val c = rawGain[if (k < BINS - 1) k + 1 else k]
            val g = 0.25f * a + 0.5f * b + 0.25f * c
            val prev = gain[k]
            gain[k] = if (g > prev) prev + (g - prev) * GAIN_OPEN else prev + (g - prev) * GAIN_CLOSE
        }
        for (k in 0 until BINS) {
            val g = gain[k]
            re[k] *= g; im[k] *= g
            if (k in 1 until FFT_SIZE / 2) {
                re[FFT_SIZE - k] *= g; im[FFT_SIZE - k] *= g
            }
        }
        fft.transform(re, im, inverse = true)
        val wet = level.wet
        val dry = 1f - wet
        for (i in 0 until HOP) {
            val y = ola[i] + re[i] * window[i]
            val x = dryDelay[i]
            dryDelay[i] = buf[i]
            buf[i] = wet * y + dry * x
            ola[i] = re[i + HOP] * window[i + HOP]
        }
        if (frames < Int.MAX_VALUE) frames++
    }

    companion object {
        const val HOP = 480
        const val WINDOW = 960
        const val FFT_SIZE = 1024
        const val BINS = FFT_SIZE / 2 + 1
        private const val WARMUP_FRAMES = 25
        private const val POW_SMOOTH = 0.8f
        private const val NOISE_FALL = 0.05f
        private const val NOISE_MEAN = 0.03f
        private const val NOISE_GATE = 3f
        private const val NOISE_RISE = 1.0023f  // ≈ +1 dB per second at 100 frames/s
        private const val OVER_SUBTRACT = 3.0f
        private const val GAIN_FLOOR = 0.08f    // ≈ −22 dB
        private const val GAIN_OPEN = 0.5f
        private const val GAIN_CLOSE = 0.25f

        /**
         * Per-bin suppression gain: sqrt(1 − α·N/S), never below [floor] and never above 1.
         * [signalPow] S and [noisePow] N are powers; α is [overSubtract].
         */
        fun binGain(signalPow: Float, noisePow: Float, overSubtract: Float, floor: Float): Float {
            if (signalPow <= 1e-20f) return floor
            val r = 1f - overSubtract * noisePow / signalPow
            if (r <= floor * floor) return floor
            return if (r >= 1f) 1f else sqrt(r)
        }
    }
}
