package com.dualearbuds.companion.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR section (RBJ Audio EQ Cookbook), transposed direct form II, one channel of state.
 * Coefficients are normalised (a0 = 1). No allocation in [process].
 */
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    /** Magnitude response in dB at [freq] (for tests and the EQ preview). */
    fun magnitudeDb(freq: Double, rate: Int): Double {
        val w = 2 * PI * freq / rate
        val cw = cos(w)
        val c2w = cos(2 * w)
        val sw = sin(w)
        val s2w = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag)
    }

    fun copy(): Biquad = Biquad(b0, b1, b2, a1, a2)

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        private fun safeFreq(freq: Double, rate: Int) = freq.coerceIn(10.0, rate * 0.45)

        fun peaking(freq: Double, q: Double, gainDb: Double, rate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * safeFreq(freq, rate) / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowShelf(freq: Double, gainDb: Double, rate: Int, slope: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * safeFreq(freq, rate) / rate
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) - (a - 1) * c + sa),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - sa
            )
        }

        fun highShelf(freq: Double, gainDb: Double, rate: Int, slope: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * safeFreq(freq, rate) / rate
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) + (a - 1) * c + sa),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - sa
            )
        }

        fun highPass(freq: Double, rate: Int, q: Double = 0.7071): Biquad {
            val w = 2 * PI * safeFreq(freq, rate) / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(freq: Double, rate: Int, q: Double = 0.7071): Biquad {
            val w = 2 * PI * safeFreq(freq, rate) / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}
