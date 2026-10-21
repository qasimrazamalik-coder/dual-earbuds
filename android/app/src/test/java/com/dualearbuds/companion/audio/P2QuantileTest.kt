package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class P2QuantileTest {

    @Test
    fun estimatesUniformP99() {
        val q = P2Quantile(0.99)
        val r = Random(1)
        repeat(20_000) { q.add(r.nextDouble() * 100) }
        assertEquals(99.0, q.value(), 1.0)
    }

    @Test
    fun estimatesMedianOfNormal() {
        val q = P2Quantile(0.5)
        val r = Random(2)
        repeat(10_000) { q.add(50 + r.nextGaussian() * 10) }
        assertEquals(50.0, q.value(), 0.6)
    }

    @Test
    fun fewSamplesUseNearestRank() {
        val q = P2Quantile(0.99)
        assertTrue(q.value().isNaN())
        q.add(3.0); q.add(1.0); q.add(2.0)
        assertEquals(3.0, q.value(), 0.0)
    }

    @Test
    fun catchesRareSpikes() {
        val q = P2Quantile(0.99)
        val r = Random(3)
        repeat(30_000) { i -> q.add(if (i % 50 == 0) 40.0 + r.nextDouble() else r.nextDouble() * 2) }
        // 2 % of samples are ~40 ms spikes, so p99 sits inside the spikes.
        assertTrue(q.value() > 30.0)
    }
}
