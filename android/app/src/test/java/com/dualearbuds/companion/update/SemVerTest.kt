package com.dualearbuds.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun parsesTagsAndPlainVersions() {
        assertEquals(SemVer(3, 0, 0), SemVer.parse("v3.0.0"))
        assertEquals(SemVer(3, 0, 0), SemVer.parse("3.0.0"))
        assertEquals(SemVer(3, 1, 0), SemVer.parse("V3.1"))
        assertEquals(SemVer(4, 0, 0), SemVer.parse(" 4 "))
        assertEquals(SemVer(3, 0, 1, listOf("beta", "2")), SemVer.parse("v3.0.1-beta.2+build.7"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(SemVer.parse(null))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("v"))
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("3.0.0.1"))
        assertNull(SemVer.parse("03.0.0"))
        assertNull(SemVer.parse("3.0.0-"))
        assertNull(SemVer.parse("3.0.0-be ta"))
    }

    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(SemVer.parse("3.10.0")!! > SemVer.parse("3.9.9")!!)
        assertTrue(SemVer.parse("10.0.0")!! > SemVer.parse("9.99.99")!!)
        assertTrue(SemVer.parse("3.0.10")!! > SemVer.parse("3.0.2")!!)
    }

    @Test
    fun preReleaseSortsBeforeRelease() {
        assertTrue(SemVer.parse("3.0.0-beta")!! < SemVer.parse("3.0.0")!!)
        assertTrue(SemVer.parse("3.0.0-alpha")!! < SemVer.parse("3.0.0-beta")!!)
        assertTrue(SemVer.parse("3.0.0-beta.2")!! < SemVer.parse("3.0.0-beta.10")!!)
        assertTrue(SemVer.parse("3.0.0-1")!! < SemVer.parse("3.0.0-alpha")!!)
        assertTrue(SemVer.parse("3.0.0-alpha")!! < SemVer.parse("3.0.0-alpha.1")!!)
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertEquals(0, SemVer.parse("3.0.0+a")!!.compareTo(SemVer.parse("3.0.0+b")!!))
    }

    @Test
    fun isNewer() {
        assertTrue(SemVer.isNewer("v3.0.1", "3.0.0"))
        assertTrue(SemVer.isNewer("v3.1.0", "3.0.9"))
        assertFalse(SemVer.isNewer("v3.0.0", "3.0.0"))
        assertFalse(SemVer.isNewer("v2.9.9", "3.0.0"))
        assertFalse(SemVer.isNewer("v3.0.0-beta", "3.0.0"))
        assertFalse(SemVer.isNewer("nightly", "3.0.0"))
        // An unparseable current version: any real release is an update.
        assertTrue(SemVer.isNewer("v3.0.0", "dev"))
    }

    @Test
    fun toStringRoundTrips() {
        assertEquals("3.0.0", SemVer.parse("v3")!!.toString())
        assertEquals("3.0.1-rc.1", SemVer.parse("3.0.1-rc.1")!!.toString())
    }
}
