package com.dualearbuds.host.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAndDiscoveryTest {
    @Test
    fun linkHasKeyButNeverAPin() {
        assertEquals("http://192.168.43.1:8790/?k=ABCD2345", LinkBuilder.link("192.168.43.1", 8790, "abcd2345"))
        assertNull(LinkBuilder.link(null, 8790, "ABCD2345"))
        assertNull(LinkBuilder.link("10.0.0.2", 0, "ABCD2345"))
        assertFalse(LinkBuilder.link("10.0.0.2", 8790, "ABCD2345")!!.contains("p="))
    }

    @Test
    fun portParsing() {
        assertEquals(8790, LinkBuilder.parsePort(" 8790 "))
        assertNull(LinkBuilder.parsePort("80"))
        assertNull(LinkBuilder.parsePort("70000"))
        assertNull(LinkBuilder.parsePort("abc"))
    }

    @Test
    fun hotspotInterfaceIsPreferredAndMobileDataSkipped() {
        val c = listOf("rmnet_data0" to "10.1.2.3", "wlan1" to "192.168.1.5", "swlan0" to "192.168.43.1")
        assertEquals("192.168.43.1", LinkBuilder.pickHotspotAddress(c))
        assertEquals("192.168.1.5", LinkBuilder.pickHotspotAddress(c.dropLast(1)))
        assertNull(LinkBuilder.pickHotspotAddress(listOf("rmnet0" to "10.0.0.1")))
    }

    @Test
    fun showOnNetworkDefaults() {
        assertTrue(Discovery.showOnNetwork(NetKind.HOTSPOT, null))
        assertFalse(Discovery.showOnNetwork(NetKind.WIFI, null))
        assertTrue(Discovery.showOnNetwork(NetKind.WIFI, true))
        assertFalse(Discovery.showOnNetwork(NetKind.HOTSPOT, false))
    }

    @Test
    fun txtCarriesKeyOnlyWhenShown() {
        val shown = Discovery.txt("Pixel 8", "abcd2345", pin = true, show = true)
        assertEquals(mapOf("v" to "3", "name" to "Pixel 8", "host" to "android", "pin" to "1", "k" to "ABCD2345"), shown)
        val hidden = Discovery.txt("Pixel 8", "abcd2345", pin = false, show = false)
        assertFalse(hidden.containsKey("k"))
        assertEquals("0", hidden["pin"])
    }

    @Test
    fun utf8LimitsDoNotSplitCharacters() {
        assertEquals("ab", Discovery.utf8Prefix("abc", 2))
        assertEquals("a", Discovery.utf8Prefix("aé", 2)) // é is 2 bytes
        assertEquals("a🎧", Discovery.utf8Prefix("a🎧b", 5)) // headphone emoji is 4 bytes
        assertTrue(Discovery.instanceName("x".repeat(100)).toByteArray().size <= 63)
    }
}
