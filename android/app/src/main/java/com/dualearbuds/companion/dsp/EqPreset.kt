package com.dualearbuds.companion.dsp

import kotlin.math.ln

/**
 * Listener EQ presets (docs/protocol.md v3.1 "Equalizer": `flat|bass|voice|movie`). Gains are in dB at
 * [EqCurve.PRESET_FREQS]: a low shelf at 60 Hz, peaks at 230 / 910 / 3.6 k and a high shelf at 14 kHz.
 */
enum class EqPreset(val wire: String, val label: String, val gainsDb: DoubleArray) {
    FLAT("flat", "Flat", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)),
    BASS("bass", "Bass", doubleArrayOf(6.0, 3.0, 0.0, 0.0, 1.0)),
    VOICE("voice", "Voice", doubleArrayOf(-3.0, -1.0, 3.0, 4.0, 0.0)),
    MOVIE("movie", "Movie", doubleArrayOf(4.0, 1.0, -1.0, 2.5, 3.0));

    val isFlat: Boolean
        get() = gainsDb.all { it == 0.0 }

    companion object {
        fun fromWire(value: String?): EqPreset? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/** A gain curve over frequency, defined at a few points and interpolated on a log-frequency axis. */
class EqCurve(val freqs: DoubleArray, val gainsDb: DoubleArray) {
    init {
        require(freqs.size == gainsDb.size && freqs.isNotEmpty())
    }

    /** Gain at [freq]: linear in log-frequency between points, held flat outside them. */
    fun gainAt(freq: Double): Double {
        if (freq <= freqs.first()) return gainsDb.first()
        if (freq >= freqs.last()) return gainsDb.last()
        for (i in 0 until freqs.size - 1) {
            val f0 = freqs[i]
            val f1 = freqs[i + 1]
            if (freq <= f1) {
                val t = (ln(freq) - ln(f0)) / (ln(f1) - ln(f0))
                return gainsDb[i] + t * (gainsDb[i + 1] - gainsDb[i])
            }
        }
        return gainsDb.last()
    }

    companion object {
        val PRESET_FREQS = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

        fun of(preset: EqPreset) = EqCurve(PRESET_FREQS, preset.gainsDb)
    }
}
