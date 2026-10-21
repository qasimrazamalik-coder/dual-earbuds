package com.dualearbuds.companion.data

import com.dualearbuds.companion.hearing.HearingProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecsTest {

    @Test
    fun doublesRoundTrip() {
        val m = mapOf("Evo Z-Buds" to 182.5, "Galaxy Buds2\tPro" to -3.0)
        val back = Codecs.decodeDoubles(Codecs.encodeDoubles(m))
        assertEquals(182.5, back["Evo Z-Buds"]!!, 1e-9)
        assertEquals(-3.0, back["Galaxy Buds2 Pro"]!!, 1e-9)
    }

    @Test
    fun stringsRoundTrip() {
        val m = mapOf("Buds" to "p1", "AirPods" to "p2")
        assertEquals(m, Codecs.decodeStrings(Codecs.encodeStrings(m)))
    }

    @Test
    fun profilesRoundTripAndSkipBroken() {
        val p = HearingProfile("id1", "Qasim", List(7) { -50.0 - it }, List(7) { -40.0 }, 123L)
        val text = Codecs.encodeProfiles(listOf(p)) + "\nbroken\tline"
        val back = Codecs.decodeProfiles(text)
        assertEquals(1, back.size)
        assertEquals(p, back[0])
    }
}
