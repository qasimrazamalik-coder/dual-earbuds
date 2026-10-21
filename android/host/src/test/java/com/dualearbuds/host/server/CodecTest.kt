package com.dualearbuds.host.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

class LinkKeyTest {
    @Test
    fun generatedKeysUseTheBase32AlphabetAndLength() {
        val rnd = SecureRandom()
        val seen = HashSet<String>()
        repeat(500) {
            val k = LinkKey.generate(rnd)
            assertEquals(8, k.length)
            assertTrue(k, k.all { it in 'A'..'Z' || it in '2'..'7' })
            assertTrue(LinkKey.isWellFormed(k))
            seen += k
        }
        assertTrue("keys should be random", seen.size > 490)
    }

    @Test
    fun matchingIsCaseInsensitiveAndRejectsWrongKeys() {
        assertTrue(LinkKey.matches("ABCD2345", "abcd2345"))
        assertTrue(LinkKey.matches("ABCD2345", " ABCD2345 "))
        assertFalse(LinkKey.matches("ABCD2345", "ABCD2346"))
        assertFalse(LinkKey.matches("ABCD2345", "ABCD234"))
        assertFalse(LinkKey.matches("ABCD2345", ""))
        assertFalse(LinkKey.matches("ABCD2345", null))
        assertFalse(LinkKey.isWellFormed("ABCD2341")) // 1 is not in the alphabet
    }
}

class WsCodecTest {
    @Test
    fun acceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertTrue(WsCodec.isValidClientKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertFalse(WsCodec.isValidClientKey("short"))
        assertFalse(WsCodec.isValidClientKey(null))
    }

    @Test
    fun serverFrameHeaders() {
        val small = WsCodec.textFrame("hi")
        assertEquals(0x81.toByte(), small[0])
        assertEquals(2.toByte(), small[1])
        val mid = WsCodec.frame(WsCodec.OP_BINARY, ByteArray(1928))
        assertEquals(0x82.toByte(), mid[0])
        assertEquals(126.toByte(), mid[1])
        assertEquals(1928, ((mid[2].toInt() and 0xFF) shl 8) or (mid[3].toInt() and 0xFF))
        assertEquals(4 + 1928, mid.size)
        val big = WsCodec.frame(WsCodec.OP_BINARY, ByteArray(70_000))
        assertEquals(127.toByte(), big[1])
        assertEquals(10 + 70_000, big.size)
        val close = WsCodec.closeFrame(1000, "bye")
        assertEquals(0x88.toByte(), close[0])
        assertEquals(5.toByte(), close[1])
    }

    @Test
    fun maskedFramesRoundTrip() {
        val sizes = intArrayOf(0, 5, 125, 126, 300, 4096)
        for (n in sizes) {
            val payload = ByteArray(n) { (it * 7).toByte() }
            val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            val bytes = WsCodec.maskedFrame(WsCodec.OP_BINARY, payload, mask)
            if (n > 0) assertNotEquals("payload must be masked on the wire", payload[n - 1], bytes[bytes.size - 1])
            val r = WsFrameReader(ByteArrayInputStream(bytes), 4096)
            assertTrue(r.next())
            assertEquals(WsCodec.OP_BINARY, r.opcode)
            assertTrue(r.fin)
            assertEquals(n, r.length)
            assertArrayEquals(payload, r.payload.copyOf(r.length))
            assertFalse(r.next())
        }
    }

    @Test
    fun unmaskedClientFrameIsAProtocolError() {
        val unmasked = WsCodec.textFrame("hello")
        expectClose(unmasked, 4096, WsCodec.CLOSE_PROTOCOL)
    }

    @Test
    fun controlFramesLimitedTo125BytesAndMustBeFinal() {
        val mask = byteArrayOf(1, 2, 3, 4)
        expectClose(WsCodec.maskedFrame(WsCodec.OP_PING, ByteArray(126), mask), 4096, WsCodec.CLOSE_PROTOCOL)
        expectClose(WsCodec.maskedFrame(WsCodec.OP_PING, ByteArray(5), mask, fin = false), 4096, WsCodec.CLOSE_PROTOCOL)
        val ok = WsFrameReader(ByteArrayInputStream(WsCodec.maskedFrame(WsCodec.OP_PING, ByteArray(125), mask)), 4096)
        assertTrue(ok.next())
        assertEquals(125, ok.length)
    }

    @Test
    fun oversizedMessageIsRejected() {
        val bytes = WsCodec.maskedFrame(WsCodec.OP_TEXT, ByteArray(4097), byteArrayOf(9, 9, 9, 9))
        expectClose(bytes, 4096, WsCodec.CLOSE_TOO_BIG)
    }

    @Test
    fun rsvBitsAndUnknownOpcodesAreRejected() {
        val f = WsCodec.maskedFrame(WsCodec.OP_TEXT, ByteArray(1), byteArrayOf(1, 1, 1, 1))
        f[0] = (f[0].toInt() or 0x40).toByte()
        expectClose(f, 4096, WsCodec.CLOSE_PROTOCOL)
        expectClose(WsCodec.maskedFrame(0x3, ByteArray(1), byteArrayOf(1, 1, 1, 1)), 4096, WsCodec.CLOSE_PROTOCOL)
    }

    private fun expectClose(bytes: ByteArray, max: Int, code: Int) {
        try {
            WsFrameReader(ByteArrayInputStream(bytes), max).next()
            fail("expected WsProtocolException")
        } catch (e: WsProtocolException) {
            assertEquals(code, e.closeCode)
        }
    }
}

class AudioPacketTest {
    @Test
    fun headerIsLittleEndianRateThenSequence() {
        val b = ByteArray(8)
        AudioPacket.writeHeader(b, 0, 48_000, 0x01020304)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 4, 3, 2, 1), b)
        assertEquals(48_000, AudioPacket.readRate(b, 0))
        assertEquals(0x01020304L, AudioPacket.readSeq(b, 0))
    }

    @Test
    fun sequenceIsUnsignedAndWraps() {
        val b = ByteArray(8)
        AudioPacket.writeHeader(b, 0, 48_000, -1)
        assertEquals(0xFFFFFFFFL, AudioPacket.readSeq(b, 0))
        AudioPacket.writeHeader(b, 0, 48_000, (-1) + 1)
        assertEquals(0L, AudioPacket.readSeq(b, 0))
    }

    @Test
    fun tenMillisecondPacketSize() {
        assertEquals(1920, AudioPacket.pcmBytes(48_000))
        assertEquals(1764, AudioPacket.pcmBytes(44_100))
    }
}

class MiniJsonTest {
    @Test
    fun writesAndParsesProtocolMessages() {
        val s = MiniJson.obj("type" to "hello", "protocol" to 3, "pc" to "Qasim's \"Pixel\"\n", "party" to false, "x" to null)
        assertEquals("{\"type\":\"hello\",\"protocol\":3,\"pc\":\"Qasim's \\\"Pixel\\\"\\n\",\"party\":false,\"x\":null}", s)
        val m = MiniJson.parseObject(s)!!
        assertEquals("Qasim's \"Pixel\"\n", m["pc"])
        assertEquals(3.0, m["protocol"])
        assertEquals(false, m["party"])
        assertTrue(m.containsKey("x"))
    }

    @Test
    fun parsesStatusWithUnknownFieldsAndRejectsGarbage() {
        val m = MiniJson.parseObject(
            """{"type":"status","device":"Redmi \u00e9","earbuds":"","battery":-1,"volume":100,"bufferMs":60,""" +
                """"underruns":0,"playing":true,"extra":{"a":[1,2,{"b":null}]}}"""
        )!!
        assertEquals("Redmi é", m["device"])
        assertEquals(-1.0, m["battery"])
        assertEquals(true, m["playing"])
        assertEquals(null, MiniJson.parseObject("{\"a\":"))
        assertEquals(null, MiniJson.parseObject("[1,2]"))
        assertEquals(null, MiniJson.parseObject("{} trailing"))
    }
}

class OutboxTest {
    @Test
    fun dropsOldestAudioAndKeepsControlFirst() {
        val box = Outbox(3)
        val frames = Array(5) { byteArrayOf(it.toByte()) }
        assertFalse(box.offerAudio(frames[0]))
        assertFalse(box.offerAudio(frames[1]))
        assertFalse(box.offerAudio(frames[2]))
        assertTrue(box.offerAudio(frames[3])) // drops 0
        assertTrue(box.offerAudio(frames[4])) // drops 1
        box.offerControl(byteArrayOf(42))
        val first = box.take(10)
        assertTrue(first is Outbox.Item)
        assertEquals(42.toByte(), (first as Outbox.Item).bytes[0])
        assertEquals(2.toByte(), (box.take(10) as ByteArray)[0])
        assertEquals(3.toByte(), (box.take(10) as ByteArray)[0])
        assertEquals(4.toByte(), (box.take(10) as ByteArray)[0])
        assertEquals(null, box.take(10))
        box.close()
        assertFalse(box.offerControl(byteArrayOf(1)))
    }
}

class GuessGuardTest {
    private var now = 1_000_000L
    private val guard = GuessGuard({ now })

    @Test
    fun repeatingOneStaleKeyNeverLocks() {
        repeat(100) { assertFalse(guard.recordWrong("10.0.0.5", "OLDKEY22")) }
        assertFalse(guard.isLocked("10.0.0.5"))
    }

    @Test
    fun tenDistinctWrongKeysWithinAMinuteLockForFiveMinutes() {
        for (i in 0 until 9) assertFalse(guard.recordWrong("10.0.0.6", "KEY$i"))
        assertTrue(guard.recordWrong("10.0.0.6", "KEY9"))
        assertTrue(guard.isLocked("10.0.0.6"))
        assertFalse("other devices are not affected", guard.isLocked("10.0.0.7"))
        now += 4 * 60_000
        assertTrue(guard.isLocked("10.0.0.6"))
        now += 61_000
        assertFalse(guard.isLocked("10.0.0.6"))
    }

    @Test
    fun keysOlderThanTheWindowDoNotCount() {
        for (i in 0 until 9) {
            assertFalse(guard.recordWrong("10.0.0.8", "KEY$i"))
            now += 7_000 // 9 keys over 63 s
        }
        assertFalse(guard.recordWrong("10.0.0.8", "KEYX"))
        assertFalse(guard.isLocked("10.0.0.8"))
    }

    @Test
    fun caseDifferencesAreTheSameKey() {
        for (i in 0 until 20) assertFalse(guard.recordWrong("10.0.0.9", if (i % 2 == 0) "abcdefgh" else "ABCDEFGH"))
        assertFalse(guard.isLocked("10.0.0.9"))
    }
}
