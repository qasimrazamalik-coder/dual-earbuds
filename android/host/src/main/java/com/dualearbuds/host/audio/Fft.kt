package com.dualearbuds.host.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of size [n] (a power of two). Tables precomputed, no allocation per call. */
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(2.0 * PI * i / n).toFloat()
            sinT[i] = sin(2.0 * PI * i / n).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) if ((i shr b) and 1 != 0) r = r or (1 shl (bits - 1 - b))
            rev[i] = r
        }
    }

    /** Forward ([inverse] false) or inverse transform; the inverse is scaled by 1/n. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}
