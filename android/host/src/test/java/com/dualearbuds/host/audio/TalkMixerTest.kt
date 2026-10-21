package com.dualearbuds.host.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TalkMixerTest {
    private fun pcm(frames: Int, value: Int): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames * 2) {
            b[2 * i] = value.toByte(); b[2 * i + 1] = (value shr 8).toByte()
        }
        return b
    }

    private fun sample(b: ByteArray, idx: Int): Int = (b[2 * idx].toInt() and 0xFF) or (b[2 * idx + 1].toInt() shl 8)

    @Test
    fun dbMath() {
        assertEquals(0.2512f, TalkMixer.dbToGain(-12f), 1e-3f)
        assertEquals(1f, TalkMixer.dbToGain(0f), 1e-6f)
    }

    @Test
    fun untouchedWhenNotTalking() {
        val m = TalkMixer()
        val b = pcm(480, 10_000)
        m.process(b, 480, FloatArray(480) { 0.5f }, talking = false)
        assertEquals(10_000, sample(b, 0))
        assertEquals(10_000, sample(b, 959))
    }

    @Test
    fun ducksMusicBy12DbAndMixesMicThenRecovers() {
        val m = TalkMixer()
        val silentMic = FloatArray(480)
        repeat(20) { m.process(pcm(480, 10_000), 480, silentMic, talking = true) } // 200 ms
        val b = pcm(480, 10_000)
        m.process(b, 480, silentMic, talking = true)
        assertEquals(2512.0, sample(b, 0).toDouble(), 30.0)
        val withMic = pcm(480, 0)
        m.process(withMic, 480, FloatArray(480) { 0.25f }, talking = true)
        assertEquals(0.25 * 32767, sample(withMic, 958).toDouble(), 100.0)
        repeat(300) { m.process(pcm(480, 10_000), 480, silentMic, talking = false) } // 3 s
        assertEquals(1f, m.musicGain, 0f)
        assertEquals(0f, m.micGain, 0f)
    }

    @Test
    fun softClipNeverExceedsFullScale() {
        assertEquals(0.5f, TalkMixer.softClip(0.5f), 0f)
        for (x in listOf(0.81f, 1f, 2f, 10f, 1000f)) {
            val y = TalkMixer.softClip(x)
            assertTrue(y < 1f && y > 0.8f)
            assertEquals(-y, TalkMixer.softClip(-x), 0f)
        }
        assertTrue(TalkMixer.softClip(2f) > TalkMixer.softClip(1f))
    }

    @Test
    fun gainsGlideWithoutJumps() {
        val m = TalkMixer()
        val b = pcm(480, 20_000)
        m.process(b, 480, FloatArray(480), talking = true)
        for (i in 1 until 480) assertTrue(abs(sample(b, 2 * i) - sample(b, 2 * i - 2)) < 600)
    }
}
