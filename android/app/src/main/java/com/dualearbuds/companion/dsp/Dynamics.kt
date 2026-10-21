package com.dualearbuds.companion.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Stereo-linked peak limiter: instant attack (the gain drops on the sample that would exceed [ceiling]),
 * smooth release. Output never exceeds the ceiling. No allocation.
 */
class Limiter(private val ceiling: Float = 0.97f, releaseMs: Double = 80.0, rate: Int = 48_000) {
    private var gain = 1f
    private var releaseCoef = coef(releaseMs, rate)

    fun setRate(rate: Int, releaseMs: Double = 80.0) {
        releaseCoef = coef(releaseMs, rate)
    }

    fun reset() {
        gain = 1f
    }

    fun process(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            val peak = max(abs(l), abs(r))
            val needed = if (peak * gain > ceiling) ceiling / peak else 1f
            gain = if (needed < gain) needed else gain + (min(1f, needed) - gain) * (1f - releaseCoef)
            if (peak * gain > ceiling) gain = ceiling / peak
            buf[f * 2] = l * gain
            buf[f * 2 + 1] = r * gain
        }
    }

    companion object {
        internal fun coef(ms: Double, rate: Int): Float = exp(-1.0 / (ms / 1000.0 * rate)).toFloat()
    }
}

/**
 * Gentle downward compressor for Night mode (smart-features #3): RMS detector, [thresholdDb], [ratio],
 * [makeupDb]. Stereo-linked. Followed by a [Limiter] in the chain.
 */
class Compressor(
    private val thresholdDb: Double = -24.0,
    private val ratio: Double = 3.0,
    private val makeupDb: Double = 5.0,
    attackMs: Double = 10.0,
    releaseMs: Double = 250.0,
    rate: Int = 48_000
) {
    private var env = 0.0
    private var attack = Limiter.coef(attackMs, rate).toDouble()
    private var release = Limiter.coef(releaseMs, rate).toDouble()
    private val makeup = 10.0.pow(makeupDb / 20)

    fun setRate(rate: Int) {
        attack = Limiter.coef(10.0, rate).toDouble()
        release = Limiter.coef(250.0, rate).toDouble()
    }

    fun reset() {
        env = 0.0
    }

    /** Gain (linear) for a signal at [levelDb] after the static curve, without makeup. For tests. */
    fun staticGainDb(levelDb: Double): Double =
        if (levelDb <= thresholdDb) 0.0 else (thresholdDb + (levelDb - thresholdDb) / ratio) - levelDb

    fun process(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            val p = (l * l + r * r) / 2.0
            val c = if (p > env) attack else release
            env = p + (env - p) * c
            val levelDb = 10 * log10(env + 1e-12)
            val g = (10.0.pow(staticGainDb(levelDb) / 20) * makeup).toFloat()
            buf[f * 2] = l * g
            buf[f * 2 + 1] = r * g
        }
    }

    companion object {
        fun rmsDb(x: FloatArray, frames: Int): Double {
            var s = 0.0
            for (i in 0 until frames * 2) s += x[i] * x[i]
            return 10 * log10(s / max(1, frames * 2) + 1e-12)
        }
    }
}
