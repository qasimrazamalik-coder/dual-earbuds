package com.dualearbuds.companion.audio

import com.dualearbuds.companion.dsp.DialogueMode
import com.dualearbuds.companion.dsp.EqPreset
import com.dualearbuds.companion.dsp.EqSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspChainTest {
    private fun tone(n: Int, amp: Int) = ShortArray(n) { (amp * sin(2 * PI * 1000 * (it / 2) / 48_000.0)).toInt().toShort() }

    @Test
    fun allOffIsPlainGain() {
        val c = DspChain()
        val src = tone(960, 20_000)
        val dst = ShortArray(960)
        c.process(src, dst, 960, 48_000, 0.5f)
        for (i in 0 until 960) assertEquals((src[i] * 0.5f).toInt(), dst[i].toInt())
    }

    @Test
    fun boostedPathNeverClipsEvenAtHighVolume() {
        val c = DspChain()
        c.eqSpec = EqSpec(EqPreset.BASS)
        c.dialogue = DialogueMode.NIGHT
        val dst = ShortArray(960)
        var maxAbs = 0
        repeat(100) {
            c.process(tone(960, 30_000), dst, 960, 48_000, 1f)
            maxAbs = maxOf(maxAbs, dst.maxOf { abs(it.toInt()) })
        }
        assertTrue("max $maxAbs", maxAbs <= 32_000)
    }

    @Test
    fun turningFeaturesOffReturnsToPlainPath() {
        val c = DspChain()
        c.dialogue = DialogueMode.LIGHT
        val dst = ShortArray(960)
        repeat(10) { c.process(tone(960, 1_000), dst, 960, 48_000, 1f) }
        c.dialogue = DialogueMode.OFF
        repeat(200) { c.process(tone(960, 1_000), dst, 960, 48_000, 1f) } // boost fades out
        val src = tone(960, 1_000)
        c.process(src, dst, 960, 48_000, 1f)
        for (i in 0 until 960) assertEquals(src[i], dst[i])
    }
}
