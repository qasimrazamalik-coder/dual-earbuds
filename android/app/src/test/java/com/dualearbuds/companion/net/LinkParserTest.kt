package com.dualearbuds.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    private fun ok(raw: String): LinkTarget {
        val r = LinkParser.parse(raw)
        assertTrue("Expected Ok for \"$raw\", got $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).target
    }

    private fun error(raw: String): String {
        val r = LinkParser.parse(raw)
        assertTrue("Expected Error for \"$raw\", got $r", r is ParseResult.Error)
        return (r as ParseResult.Error).message
    }

    @Test
    fun parsesQrLink() {
        assertEquals(LinkTarget("192.168.1.20", 8790, "ABCD2345"), ok("http://192.168.1.20:8790/?k=ABCD2345"))
    }

    @Test
    fun keyIsCaseInsensitiveAndUppercased() {
        assertEquals("ABCD2345", ok("http://10.0.0.5:8790/?k=abcd2345").key)
    }

    @Test
    fun defaultPortWhenMissing() {
        assertEquals(LinkParser.DEFAULT_PORT, ok("192.168.43.1/?k=ABCD2345").port)
        assertEquals(LinkParser.DEFAULT_PORT, ok("http://192.168.43.1/?k=ABCD2345").port)
    }

    @Test
    fun customPortAndNoScheme() {
        val t = ok("192.168.1.20:9000/?k=ABCD2345")
        assertEquals("192.168.1.20", t.host)
        assertEquals(9000, t.port)
    }

    @Test
    fun wsSchemeAndWhitespaceAreTolerated() {
        val t = ok("  ws://192.168.1.20:8790/ws?k=ABCD 2345 \n")
        assertEquals(LinkTarget("192.168.1.20", 8790, "ABCD2345"), t)
    }

    @Test
    fun ipv6Host() {
        val t = ok("http://[fe80::1]:8790/?k=ABCD2345")
        assertEquals(8790, t.port)
        assertEquals("[fe80::1]:8790", t.display)
    }

    @Test
    fun canonicalLinkRoundTrips() {
        val t = ok("192.168.1.20:8790/?k=abcd2345")
        assertEquals("http://192.168.1.20:8790/?k=ABCD2345", t.toLink())
        assertEquals(t, ok(t.toLink()))
    }

    @Test
    fun infoAndWsUrls() {
        val t = LinkTarget("192.168.1.20", 8790, "ABCD2345")
        assertEquals("http://192.168.1.20:8790/info?k=ABCD2345", t.infoUrl().toString())
        assertEquals(
            "http://192.168.1.20:8790/ws?k=ABCD2345&client=android&name=Redmi%20Note%2012",
            t.wsUrl("Redmi Note 12").toString()
        )
    }

    @Test
    fun addressAndKeyTypedSeparately() {
        assertEquals(LinkTarget("192.168.1.20", 8790, "ABCD2345"), ok("192.168.1.20 ABCD2345"))
        assertEquals(LinkTarget("192.168.1.20", 9000, "ABCD2345"), ok("192.168.1.20:9000 abcd2345"))
        assertEquals(LinkTarget("192.168.1.20", 8790, "ABCD2345"), ok(" 192.168.1.20:8790  k=ABCD 2345 "))
        assertEquals(LinkTarget("10.0.0.5", 8790, "ABCD2345"), ok("http://10.0.0.5:8790/, ABCD2345"))
        assertEquals(8790, ok("[fe80::1]:8790 ABCD2345").port)
    }

    @Test
    fun addressAndKeyStillValidatesTheKey() {
        assertTrue(error("192.168.1.20 ABCD2341").contains("A–Z and 2–7"))
        error("192.168.1.20:99999 ABCD2345")
    }

    @Test
    fun errors() {
        error("")
        error("   ")
        error("http://192.168.1.20:8790/")
        error("http://192.168.1.20:8790/?k=ABC")
        error("http://192.168.1.20:8790/?k=ABCD2341") // 0 and 1 are not in the key alphabet
        error("http://192.168.1.20:8790/?k=ABCD23456")
    }
}
