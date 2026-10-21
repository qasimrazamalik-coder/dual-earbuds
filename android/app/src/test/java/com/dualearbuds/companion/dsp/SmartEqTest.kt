package com.dualearbuds.companion.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class SmartEqTest {

    @Test
    fun appNamesMapToPresets() {
        assertEquals(EqPreset.MOVIE, AppEqMapper.presetFor("Netflix"))
        assertEquals(EqPreset.MOVIE, AppEqMapper.presetFor("com.google.android.youtube"))
        assertEquals(EqPreset.BASS, AppEqMapper.presetFor("YouTube Music"))
        assertEquals(EqPreset.BASS, AppEqMapper.presetFor("com.google.android.apps.youtube.music"))
        assertEquals(EqPreset.BASS, AppEqMapper.presetFor("Spotify.exe"))
        assertEquals(EqPreset.VOICE, AppEqMapper.presetFor("Zoom"))
        assertEquals(EqPreset.MOVIE, AppEqMapper.presetFor("HBO Max"))
        assertNull(AppEqMapper.presetFor("maximizer"))
        assertNull(AppEqMapper.presetFor("Notepad"))
        assertNull(AppEqMapper.presetFor(""))
    }

    @Test
    fun manualChoiceAlwaysWins() {
        assertEquals(EqPreset.VOICE, EqResolver.resolve(EqPreset.VOICE, SmartEqMode.BOTH, EqPreset.MOVIE, EqPreset.BASS, EqPreset.MOVIE))
    }

    @Test
    fun autoUsesSmartThenHostThenFlat() {
        assertEquals(EqPreset.MOVIE, EqResolver.resolve(null, SmartEqMode.BY_APP, EqPreset.MOVIE, EqPreset.BASS, null))
        assertEquals(EqPreset.BASS, EqResolver.resolve(null, SmartEqMode.BY_SOUND, EqPreset.MOVIE, EqPreset.BASS, null))
        assertEquals(EqPreset.BASS, EqResolver.resolve(null, SmartEqMode.BOTH, null, EqPreset.BASS, EqPreset.VOICE))
        assertEquals(EqPreset.VOICE, EqResolver.resolve(null, SmartEqMode.OFF, EqPreset.MOVIE, null, EqPreset.VOICE))
        assertEquals(EqPreset.FLAT, EqResolver.resolve(null, SmartEqMode.BY_APP, null, null, null))
    }

    private fun features(rate: Int = 48_000, signal: (Int) -> Double): SoundFeatures {
        val fx = FeatureExtractor(rate)
        var out: SoundFeatures? = null
        var t = 0
        while (out == null) out = fx.feed(signal(t++))
        return out
    }

    @Test
    fun classifierTellsSpeechMusicMovieSilence() {
        val r = Random(5)
        val rate = 48_000.0
        // Speech-like: band noise around 1 kHz, syllables at 4 Hz.
        var lp = 0.0
        val speech = features { t ->
            val env = if (((t / rate) * 4) % 1.0 < 0.5) 1.0 else 0.05
            val n = r.nextGaussian()
            lp += 0.3 * (n - lp)
            0.2 * env * (sin(2 * PI * 900 * t / rate) * 0.6 + lp * 0.4)
        }
        // Music-like: steady chord.
        val music = features { t ->
            0.1 * (sin(2 * PI * 220 * t / rate) + sin(2 * PI * 277 * t / rate) + sin(2 * PI * 330 * t / rate) + 0.5 * sin(2 * PI * 440 * t / rate))
        }
        // Movie-like: speech rhythm over a rumble.
        var lp2 = 0.0
        val movie = features { t ->
            val env = if (((t / rate) * 4) % 1.0 < 0.5) 1.0 else 0.05
            val n = r.nextGaussian()
            lp2 += 0.3 * (n - lp2)
            0.12 * env * (sin(2 * PI * 900 * t / rate) * 0.6 + lp2 * 0.4) + 0.25 * sin(2 * PI * 50 * t / rate)
        }
        val silence = features { 0.0 }
        assertEquals("speech $speech", SoundClass.SPEECH, SoundModel.classify(speech))
        assertEquals("music $music", SoundClass.MUSIC, SoundModel.classify(music))
        assertEquals("movie $movie", SoundClass.MOVIE, SoundModel.classify(movie))
        assertEquals(SoundClass.SILENCE, SoundModel.classify(silence))
    }

    @Test
    fun hysteresisNeedsFiveWindowsInARow() {
        val h = ClassHysteresis(5)
        repeat(4) { assertNull(h.update(SoundClass.MUSIC)) }
        assertEquals(SoundClass.MUSIC, h.update(SoundClass.MUSIC))
        repeat(4) { assertEquals(SoundClass.MUSIC, h.update(SoundClass.SPEECH)) }
        assertEquals(SoundClass.MUSIC, h.update(SoundClass.MUSIC)) // streak broken
        repeat(4) { h.update(SoundClass.SPEECH) }
        assertEquals(SoundClass.MUSIC, h.update(SoundClass.SILENCE)) // silence changes nothing
        assertEquals(SoundClass.SPEECH, h.update(SoundClass.SPEECH))
    }

    @Test
    fun fftFindsAToneBin() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val mags = DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, mags.indices.maxByOrNull { mags[it] })
    }
}
