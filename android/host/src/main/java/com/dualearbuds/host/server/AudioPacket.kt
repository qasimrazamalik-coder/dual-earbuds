package com.dualearbuds.host.server

/** Binary audio packet: int32 rate, uint32 sequence (both little-endian), then s16le stereo PCM. */
object AudioPacket {
    const val HEADER_SIZE = 8
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4
    const val PACKET_MS = 10

    /** PCM bytes in one 10 ms packet at [rate]. */
    fun pcmBytes(rate: Int): Int = rate / 100 * BYTES_PER_FRAME

    fun writeHeader(dst: ByteArray, off: Int, rate: Int, seq: Int) {
        putIntLe(dst, off, rate)
        putIntLe(dst, off + 4, seq)
    }

    fun readRate(src: ByteArray, off: Int): Int = getIntLe(src, off)

    /** The sequence number as an unsigned value. */
    fun readSeq(src: ByteArray, off: Int): Long = getIntLe(src, off + 4).toLong() and 0xFFFFFFFFL

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
