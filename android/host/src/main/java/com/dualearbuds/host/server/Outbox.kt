package com.dualearbuds.host.server

/**
 * Per-listener send queue. Control/text frames always go first and are never dropped; audio frames
 * sit in a fixed ring of [audioCapacity] slots (≈600 ms) and the oldest is dropped when it is full.
 * No allocation per audio packet: the ring only stores references to pooled frame buffers.
 */
class Outbox(private val audioCapacity: Int, private val maxControl: Int = 256) {

    class Item(val bytes: ByteArray, val closeAfter: Boolean)

    private val lock = Object()
    private val ring = arrayOfNulls<ByteArray>(audioCapacity)
    private var head = 0
    private var size = 0
    private val control = ArrayDeque<Item>()
    private var closed = false

    /** Queues an audio frame; returns true when an old frame had to be dropped. */
    fun offerAudio(frame: ByteArray): Boolean {
        synchronized(lock) {
            if (closed) return false
            var dropped = false
            if (size == audioCapacity) {
                ring[head] = null
                head = (head + 1) % audioCapacity
                size--
                dropped = true
            }
            ring[(head + size) % audioCapacity] = frame
            size++
            lock.notify()
            return dropped
        }
    }

    /** Queues a text/control frame. Returns false when closed or the control queue overflows. */
    fun offerControl(frame: ByteArray, closeAfter: Boolean = false): Boolean {
        synchronized(lock) {
            if (closed || control.size >= maxControl) return false
            control.addLast(Item(frame, closeAfter))
            lock.notify()
            return true
        }
    }

    /**
     * Next frame to send: control first, then audio. Returns an [Item] for control frames, a
     * ByteArray for audio, or null on timeout / close.
     */
    fun take(timeoutMs: Long): Any? {
        synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!closed && control.isEmpty() && size == 0) {
                val waitMs = (deadline - System.nanoTime()) / 1_000_000
                if (waitMs <= 0) return null
                lock.wait(waitMs)
            }
            if (closed) return null
            if (control.isNotEmpty()) return control.removeFirst()
            val f = ring[head]
            ring[head] = null
            head = (head + 1) % audioCapacity
            size--
            return f
        }
    }

    fun audioQueued(): Int = synchronized(lock) { size }

    fun close() {
        synchronized(lock) {
            closed = true
            control.clear()
            for (i in ring.indices) ring[i] = null
            size = 0
            lock.notifyAll()
        }
    }
}
