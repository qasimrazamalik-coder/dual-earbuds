package com.dualearbuds.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Wi-Fi power save on phones: the access point holds packets until the phone wakes for a beacon (every ~100–300 ms),
 * then delivers them in one burst. Once the buffer has adapted, this must not cause dropouts.
 */
class BurstyArrivalTest {

    private class Result(val underrunsAfterAdapt: Long, val totalUnderruns: Long, val targetMs: Int, val maxQueuedMs: Double, val played: Int)

    /**
     * Simulates [seconds] of a 10 ms packet stream whose arrivals are held for [gapMs] and released in bursts
     * (plus up to [extraJitterMs] random delay), against an audio thread that takes one packet every 10 ms.
     */
    private fun simulate(mode: BufferMode, gapMs: Long, seconds: Int, adaptAfterMs: Long, extraJitterMs: Int = 0, seed: Long = 5): Result {
        val jb = JitterBuffer().also { it.reset(0); it.mode = mode }
        val r = Random(seed)
        val total = seconds * 100
        val arrivals = LongArray(total) { i ->
            val sent = i * 10L
            val held = ((sent / gapMs) + 1) * gapMs // released at the next wake-up
            held + if (extraJitterMs > 0) r.nextInt(extraJitterMs + 1) else 0
        }
        // Delivery is in order (TCP or the UDP gate), so an arrival can't be earlier than the previous one.
        for (i in 1 until total) if (arrivals[i] < arrivals[i - 1]) arrivals[i] = arrivals[i - 1]
        var next = 0
        var underrunsAtAdapt = -1L
        var maxQueued = 0.0
        var played = 0
        // The audio thread wants a packet every 10 ms; it starts as soon as the buffer lets it.
        var t = 0L
        while (t < total * 10L + 1_000) {
            while (next < total && arrivals[next] <= t) {
                jb.push(AudioPacket(48_000, ShortArray(960), next.toLong()), arrivals[next])
                next++
            }
            if (t >= adaptAfterMs && underrunsAtAdapt < 0) underrunsAtAdapt = jb.underruns
            if (jb.poll(t) != null) played++
            if (t >= adaptAfterMs) maxQueued = maxOf(maxQueued, jb.queuedMs)
            t += 10
            if (next >= total && jb.queuedMs <= 0.0) break
        }
        return Result(jb.underruns - underrunsAtAdapt, jb.underruns, jb.targetMs(), maxQueued, played)
    }

    @Test
    fun smartBufferAbsorbs200msPowerSaveBursts() {
        val res = simulate(BufferMode.SMART, gapMs = 200, seconds = 60, adaptAfterMs = 8_000)
        assertEquals("underruns after adapting (target ${res.targetMs} ms)", 0L, res.underrunsAfterAdapt)
        assertTrue("target ${res.targetMs}", res.targetMs in 180..260)
        assertTrue("dropped too much: played ${res.played}", res.played >= 5_900)
    }

    @Test
    fun smartBufferAbsorbsBurstsWithExtraJitter() {
        val res = simulate(BufferMode.SMART, gapMs = 200, seconds = 60, adaptAfterMs = 8_000, extraJitterMs = 30)
        assertEquals("underruns after adapting (target ${res.targetMs} ms)", 0L, res.underrunsAfterAdapt)
        assertTrue("target ${res.targetMs}", res.targetMs <= 300)
    }

    @Test
    fun shorterGapsGiveLowerDelay() {
        val res = simulate(BufferMode.SMART, gapMs = 100, seconds = 60, adaptAfterMs = 8_000)
        assertEquals(0L, res.underrunsAfterAdapt)
        assertTrue("target ${res.targetMs}", res.targetMs in 90..150)
    }

    @Test
    fun adaptsWithinTheFirstSecondsWithFewDropouts() {
        val res = simulate(BufferMode.SMART, gapMs = 200, seconds = 20, adaptAfterMs = 2_500)
        assertEquals(0L, res.underrunsAfterAdapt)
        assertTrue("underruns while learning: ${res.totalUnderruns}", res.totalUnderruns <= 5)
    }
}
