package com.dualearbuds.companion.dsp

import kotlin.math.pow

/** Dialogue boost choices (smart-features #3). */
enum class DialogueMode(val label: String) {
    OFF("Off"),
    LIGHT("Light"),
    STRONG("Strong"),
    NIGHT("Night");

    /** Extra level of the 300 Hz–4 kHz centre band while speech is detected. */
    val boostDb: Double
        get() = when (this) {
            OFF -> 0.0
            LIGHT -> 4.0
            STRONG, NIGHT -> 7.0
        }

    /** Side (wide stereo: music, effects) level; Strong/Night cut it slightly. */
    val sideGain: Float
        get() = when (this) {
            OFF, LIGHT -> 1f
            STRONG, NIGHT -> 0.8f
        }
}

/**
 * Lightweight voice-activity detector on 10 ms blocks of the centre (mid) channel. Speech is assumed when the
 * 300 Hz–4 kHz band carries most of the centre's energy, the centre dominates the sides, and the band is well
 * above its slowly tracked noise floor. A short hangover avoids chopping between syllables.
 * Pure Kotlin, no allocation.
 */
class VoiceActivityDetector(
    private val bandRatioMin: Double = 0.55,
    private val centreDominance: Double = 1.5,
    private val floorMarginDb: Double = 9.0,
    private val hangoverBlocks: Int = 20,
    private val minLevelDb: Double = -55.0
) {
    private var floorDb = -70.0
    private var hang = 0

    var isSpeech: Boolean = false
        private set

    fun reset() {
        floorDb = -70.0
        hang = 0
        isSpeech = false
    }

    /** Mean-square energies of one block: speech band of the centre, whole centre, side. */
    fun update(bandEnergy: Double, midEnergy: Double, sideEnergy: Double): Boolean {
        val bandDb = 10 * kotlin.math.log10(bandEnergy + 1e-12)
        // Floor follows quiet blocks quickly and loud blocks very slowly.
        floorDb = if (bandDb < floorDb) floorDb + (bandDb - floorDb) * 0.2 else floorDb + (bandDb - floorDb) * 0.002
        val ratioOk = midEnergy > 1e-12 && bandEnergy / midEnergy >= bandRatioMin
        val centreOk = midEnergy >= sideEnergy * centreDominance
        val levelOk = bandDb > minLevelDb && bandDb > floorDb + floorMarginDb
        val now = ratioOk && centreOk && levelOk
        if (now) hang = hangoverBlocks else if (hang > 0) hang--
        isSpeech = now || hang > 0
        return isSpeech
    }
}

/**
 * Dialogue boost (smart-features #3): mid/side split; the centre's 300 Hz–4 kHz band is raised only while the
 * [VoiceActivityDetector] hears speech; Strong/Night also lower the sides a little; Night adds a gentle
 * compressor. A [Limiter] at the end makes clipping impossible. Works on interleaved stereo floats in place,
 * in blocks of any size (VAD decisions are made per [blockFrames]).
 */
class DialogueBoost(rate: Int = 48_000) {
    private var rate = rate
    private var hp = Biquad.highPass(300.0, rate)
    private var lp = Biquad.lowPass(4000.0, rate)
    private val vad = VoiceActivityDetector()
    private val compressor = Compressor(rate = rate)
    private val limiter = Limiter(rate = rate)

    var mode: DialogueMode = DialogueMode.OFF

    private var gain = 0f // current added band gain (linear, 0 = none)
    private var side = 1f
    private var attack = Limiter.coef(20.0, rate)
    private var release = Limiter.coef(250.0, rate)

    private var blockFrames = rate / 100
    private var accBand = 0.0
    private var accMid = 0.0
    private var accSide = 0.0
    private var accN = 0

    val speechDetected: Boolean
        get() = vad.isSpeech

    fun setRate(newRate: Int) {
        if (newRate == rate || newRate <= 0) return
        rate = newRate
        hp = Biquad.highPass(300.0, newRate)
        lp = Biquad.lowPass(4000.0, newRate)
        compressor.setRate(newRate)
        limiter.setRate(newRate)
        attack = Limiter.coef(20.0, newRate)
        release = Limiter.coef(250.0, newRate)
        blockFrames = newRate / 100
    }

    fun reset() {
        hp.reset(); lp.reset(); vad.reset(); compressor.reset(); limiter.reset()
        gain = 0f
        side = 1f
        accBand = 0.0; accMid = 0.0; accSide = 0.0; accN = 0
    }

    /** True when the stage is fully idle (Off and the boost has faded out), so the caller may skip it. */
    val isIdle: Boolean
        get() = mode == DialogueMode.OFF && gain < 1e-4f && side > 0.9999f

    fun process(buf: FloatArray, frames: Int) {
        val m = mode
        val target = if (m == DialogueMode.OFF) 0f else (10.0.pow(m.boostDb / 20) - 1).toFloat()
        val sideTarget = m.sideGain
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            val mid = (l + r) * 0.5f
            val sd = (l - r) * 0.5f
            val band = lp.process(hp.process(mid))
            accBand += band * band
            accMid += mid * mid
            accSide += sd * sd
            if (++accN >= blockFrames) {
                vad.update(accBand / accN, accMid / accN, accSide / accN)
                accBand = 0.0; accMid = 0.0; accSide = 0.0; accN = 0
            }
            val want = if (vad.isSpeech) target else 0f
            gain = if (want > gain) want + (gain - want) * attack else want + (gain - want) * release
            side = sideTarget + (side - sideTarget) * release
            val outMid = mid + band * gain
            val outSide = sd * side
            buf[f * 2] = outMid + outSide
            buf[f * 2 + 1] = outMid - outSide
        }
        if (m == DialogueMode.NIGHT) compressor.process(buf, frames)
        if (m != DialogueMode.OFF || gain > 0f) limiter.process(buf, frames)
    }
}
