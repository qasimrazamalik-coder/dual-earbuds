package com.dualearbuds.companion.audio

import kotlin.math.sign

/**
 * Online quantile estimator — the P² algorithm (Jain & Chlamtac, 1985). Constant memory (5 markers), no
 * allocation per sample. Pure Kotlin, not thread-safe.
 */
class P2Quantile(private val p: Double) {
    init {
        require(p > 0.0 && p < 1.0) { "p must be in (0, 1)" }
    }

    private val q = DoubleArray(5) // marker heights
    private val n = IntArray(5) // marker positions (1-based)
    private val np = DoubleArray(5) // desired positions
    private val dn = doubleArrayOf(0.0, p / 2, p, (1 + p) / 2, 1.0)

    var count: Int = 0
        private set

    fun reset() {
        count = 0
    }

    fun add(x: Double) {
        if (count < 5) {
            q[count] = x
            count++
            if (count == 5) {
                q.sort()
                for (i in 0 until 5) n[i] = i + 1
                np[0] = 1.0
                np[1] = 1 + 2 * p
                np[2] = 1 + 4 * p
                np[3] = 3 + 2 * p
                np[4] = 5.0
            }
            return
        }
        count++
        val k: Int = when {
            x < q[0] -> {
                q[0] = x
                0
            }
            x < q[1] -> 0
            x < q[2] -> 1
            x < q[3] -> 2
            x <= q[4] -> 3
            else -> {
                q[4] = x
                3
            }
        }
        for (i in k + 1 until 5) n[i]++
        for (i in 0 until 5) np[i] += dn[i]
        for (i in 1..3) {
            val d = np[i] - n[i]
            if ((d >= 1 && n[i + 1] - n[i] > 1) || (d <= -1 && n[i - 1] - n[i] < -1)) {
                val s = sign(d).toInt()
                val candidate = parabolic(i, s)
                q[i] = if (q[i - 1] < candidate && candidate < q[i + 1]) candidate else linear(i, s)
                n[i] += s
            }
        }
    }

    /** Current estimate; with fewer than 5 samples, the nearest-rank value of what was seen (NaN when empty). */
    fun value(): Double {
        if (count == 0) return Double.NaN
        if (count < 5) {
            val sorted = q.copyOf(count).also { it.sort() }
            val idx = ((count - 1) * p + 0.5).toInt().coerceIn(0, count - 1)
            return sorted[idx]
        }
        return q[2]
    }

    private fun parabolic(i: Int, d: Int): Double {
        val ni = n[i].toDouble()
        val nPrev = n[i - 1].toDouble()
        val nNext = n[i + 1].toDouble()
        return q[i] + d / (nNext - nPrev) * (
            (ni - nPrev + d) * (q[i + 1] - q[i]) / (nNext - ni) +
                (nNext - ni - d) * (q[i] - q[i - 1]) / (ni - nPrev)
            )
    }

    private fun linear(i: Int, d: Int): Double = q[i] + d * (q[i + d] - q[i]) / (n[i + d] - n[i])
}
