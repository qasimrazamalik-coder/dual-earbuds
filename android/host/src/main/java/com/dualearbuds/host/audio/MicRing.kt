package com.dualearbuds.host.audio

/**
 * Single-producer/single-consumer ring of mono 16-bit mic samples between the mic thread and the
 * capture thread. Keeps at most [capacity] samples: when the consumer falls behind, the oldest are
 * dropped so talk-over never lags. No allocation after construction.
 */
class MicRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var head = 0
    private var size = 0
    private val lock = Any()

    fun write(src: ShortArray, off: Int, len: Int) {
        synchronized(lock) {
            var start = off
            var n = len
            if (n > capacity) {
                start += n - capacity
                n = capacity
            }
            val overflow = size + n - capacity
            if (overflow > 0) {
                head = (head + overflow) % capacity
                size -= overflow
            }
            var tail = (head + size) % capacity
            for (i in 0 until n) {
                data[tail] = src[start + i]
                tail++
                if (tail == capacity) tail = 0
            }
            size += n
        }
    }

    /** Reads up to [len] samples as floats in −1..1; the rest of [dst] is zero-filled. Returns samples read. */
    fun read(dst: FloatArray, len: Int): Int {
        synchronized(lock) {
            val n = minOf(len, size)
            for (i in 0 until n) {
                dst[i] = data[head] / 32768f
                head++
                if (head == capacity) head = 0
            }
            size -= n
            for (i in n until len) dst[i] = 0f
            return n
        }
    }

    fun available(): Int = synchronized(lock) { size }

    fun clear() {
        synchronized(lock) {
            head = 0
            size = 0
        }
    }
}
