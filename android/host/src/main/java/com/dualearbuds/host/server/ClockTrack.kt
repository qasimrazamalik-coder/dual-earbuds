package com.dualearbuds.host.server

/**
 * Host side of clock sync (protocol v3.1). Remembers the capture time of the newest packet so the
 * host can send `{"type":"clock","seq":S,"h":H}` once a second, and answers listeners' `sync`.
 * [record] is called by the capture thread for every packet: synchronized, allocation-free.
 */
class ClockTrack {
    private var seq = 0L
    private var hostMs = 0L
    private var valid = false

    @Synchronized
    fun record(seq: Int, hostMs: Long) {
        this.seq = seq.toLong() and 0xFFFFFFFFL
        this.hostMs = hostMs
        valid = true
    }

    @Synchronized
    fun reset() {
        valid = false
    }

    /** The `clock` message for the newest packet, or null before the first packet. */
    @Synchronized
    fun clockMessage(): String? = if (!valid) null else HostMessages.clock(seq, hostMs)

    companion object {
        /**
         * Reply to a listener's `{"type":"sync","c":C}`: echoes C exactly and adds host time.
         * Returns null when `c` is missing or not a finite number.
         */
        fun syncReply(c: Double?, hostNowMs: Long): String? {
            if (c == null || c.isNaN() || c.isInfinite()) return null
            return HostMessages.syncReply(c, hostNowMs)
        }
    }
}
