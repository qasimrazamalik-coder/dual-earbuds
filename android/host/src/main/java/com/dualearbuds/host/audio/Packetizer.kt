package com.dualearbuds.host.audio

/**
 * Cuts a stream of s16le stereo bytes (as AudioRecord hands them over, any chunk size) into exact
 * 10 ms packets and works out when each packet was captured. Allocation-free after construction.
 */
class Packetizer(val rate: Int, private val sink: Sink) {

    fun interface Sink {
        /** [packet] holds exactly [packetBytes] bytes; valid only during the call. */
        fun onPacket(packet: ByteArray, capturedAtMs: Long)
    }

    /** Bytes in one 10 ms packet (stereo, 16-bit). */
    val packetBytes: Int = rate / 100 * 4
    private val buf = ByteArray(packetBytes)
    private var fill = 0

    /**
     * Adds [len] bytes read at [readEndMs] (the time the read returned). A packet's capture time is
     * [readEndMs] minus the duration of the bytes that came after it in this chunk.
     */
    fun write(src: ByteArray, off: Int, len: Int, readEndMs: Long) {
        var i = off
        var left = len
        while (left > 0) {
            val n = minOf(left, packetBytes - fill)
            System.arraycopy(src, i, buf, fill, n)
            fill += n
            i += n
            left -= n
            if (fill == packetBytes) {
                fill = 0
                sink.onPacket(buf, readEndMs - bytesToMs(left))
            }
        }
    }

    fun reset() {
        fill = 0
    }

    private fun bytesToMs(bytes: Int): Long = bytes.toLong() * 1000L / (rate.toLong() * 4L)
}
