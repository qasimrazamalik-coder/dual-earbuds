package com.dualearbuds.host.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketizerTest {
    @Test
    fun cutsAnyChunkSizeIntoExactTenMsPackets() {
        val packets = ArrayList<ByteArray>()
        val times = ArrayList<Long>()
        val p = Packetizer(48_000) { pkt, t -> packets += pkt.copyOf(); times += t }
        assertEquals(1920, p.packetBytes)
        val stream = ByteArray(1920 * 3 + 100) { it.toByte() }
        // 700 + 3000 + rest
        p.write(stream, 0, 700, readEndMs = 1000)
        assertEquals(0, packets.size)
        p.write(stream, 700, 3000, readEndMs = 1020)
        assertEquals(1, packets.size)
        p.write(stream, 3700, stream.size - 3700, readEndMs = 1040)
        assertEquals(3, packets.size)
        for (i in 0 until 3) assertArrayEquals(stream.copyOfRange(i * 1920, (i + 1) * 1920), packets[i])
        // Packet 0 ended 1780 bytes (≈9 ms) before the end of the second read.
        assertEquals(1020L - 1780L * 1000 / 192_000, times[0])
        // Packet 2 ended 100 bytes before the end of the third read: 0 ms (integer ms).
        assertEquals(1040L, times[2])
    }
}
