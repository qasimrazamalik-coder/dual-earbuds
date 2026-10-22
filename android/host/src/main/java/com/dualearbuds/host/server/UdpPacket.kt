package com.dualearbuds.host.server

import java.security.SecureRandom

/**
 * UDP datagrams of protocol v3.2 (all integers little-endian).
 *
 * Listener → host keepalive (21 bytes): `DE3U`, 1 byte type (1), 16-byte session token (`hello.token`).
 *
 * Host → listener audio: `DE3A`, then one or two blocks, newest first:
 * `uint32 seq, int32 rate, PCM (s16le stereo, 10 ms)`. The second block, when present, is the previous
 * packet again (seq − 1) so a single lost datagram costs nothing. Both blocks have the same PCM length,
 * so a listener can tell one block from two by the datagram length. Listeners dedupe by seq.
 */
object UdpPacket {
    const val TOKEN_BYTES = 16
    const val KEEPALIVE_TYPE: Byte = 1
    const val KEEPALIVE_SIZE = 4 + 1 + TOKEN_BYTES
    const val BLOCK_HEADER = 8
    private val KEEPALIVE_MAGIC = byteArrayOf('D'.code.toByte(), 'E'.code.toByte(), '3'.code.toByte(), 'U'.code.toByte())
    private val AUDIO_MAGIC = byteArrayOf('D'.code.toByte(), 'E'.code.toByte(), '3'.code.toByte(), 'A'.code.toByte())

    private val random = SecureRandom()

    fun newToken(): ByteArray = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }

    /** Token as it appears in `hello.token`: 32 lowercase hex digits. */
    fun tokenHex(token: ByteArray): String {
        val sb = StringBuilder(token.size * 2)
        for (b in token) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun parseTokenHex(hex: String): ByteArray? {
        if (hex.length != TOKEN_BYTES * 2) return null
        val out = ByteArray(TOKEN_BYTES)
        for (i in 0 until TOKEN_BYTES) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun keepalive(token: ByteArray): ByteArray {
        require(token.size == TOKEN_BYTES)
        val out = ByteArray(KEEPALIVE_SIZE)
        System.arraycopy(KEEPALIVE_MAGIC, 0, out, 0, 4)
        out[4] = KEEPALIVE_TYPE
        System.arraycopy(token, 0, out, 5, TOKEN_BYTES)
        return out
    }

    /** True when [buf] (first [len] bytes) is a well-formed keepalive; its token starts at [TOKEN_OFFSET]. */
    fun isKeepalive(buf: ByteArray, len: Int): Boolean {
        if (len != KEEPALIVE_SIZE) return false
        for (i in 0 until 4) if (buf[i] != KEEPALIVE_MAGIC[i]) return false
        return buf[4] == KEEPALIVE_TYPE
    }

    const val TOKEN_OFFSET = 5

    /** Bytes of an audio datagram with [blocks] blocks of [pcmLen] PCM bytes. */
    fun audioSize(pcmLen: Int, blocks: Int): Int = 4 + blocks * (BLOCK_HEADER + pcmLen)

    fun writeMagic(dst: ByteArray) {
        System.arraycopy(AUDIO_MAGIC, 0, dst, 0, 4)
    }

    /** Writes one block at [off]; returns the offset after it. */
    fun writeBlock(dst: ByteArray, off: Int, seq: Int, rate: Int, pcm: ByteArray, pcmOff: Int, pcmLen: Int): Int {
        putIntLe(dst, off, seq)
        putIntLe(dst, off + 4, rate)
        System.arraycopy(pcm, pcmOff, dst, off + BLOCK_HEADER, pcmLen)
        return off + BLOCK_HEADER + pcmLen
    }

    /** One decoded block (tests and tools). */
    class Block(val seq: Long, val rate: Int, val pcm: ByteArray)

    /**
     * Decodes an audio datagram whose blocks carry [pcmLen] PCM bytes each. Returns null when it is
     * not an audio datagram of that shape.
     */
    fun decodeAudio(buf: ByteArray, len: Int, pcmLen: Int): List<Block>? {
        if (len < 4) return null
        for (i in 0 until 4) if (buf[i] != AUDIO_MAGIC[i]) return null
        val block = BLOCK_HEADER + pcmLen
        if ((len - 4) % block != 0) return null
        val n = (len - 4) / block
        if (n !in 1..2) return null
        val out = ArrayList<Block>(n)
        var off = 4
        repeat(n) {
            val seq = getIntLe(buf, off).toLong() and 0xFFFFFFFFL
            val rate = getIntLe(buf, off + 4)
            out += Block(seq, rate, buf.copyOfRange(off + BLOCK_HEADER, off + block))
            off += block
        }
        return out
    }

    private const val HEX = "0123456789abcdef"

    private fun putIntLe(dst: ByteArray, off: Int, v: Int) {
        dst[off] = v.toByte()
        dst[off + 1] = (v ushr 8).toByte()
        dst[off + 2] = (v ushr 16).toByte()
        dst[off + 3] = (v ushr 24).toByte()
    }

    private fun getIntLe(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8) or
            ((src[off + 2].toInt() and 0xFF) shl 16) or
            ((src[off + 3].toInt() and 0xFF) shl 24)
}
