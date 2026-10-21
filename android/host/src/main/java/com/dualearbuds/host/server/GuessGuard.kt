package com.dualearbuds.host.server

/**
 * Guessing protection: an address that sends [maxDistinct] *different* wrong keys within
 * [windowMs] is ignored for [lockMs]. Repeating one wrong key (a stale tab after "New link")
 * never triggers it. Memory is bounded to [maxTracked] addresses.
 */
class GuessGuard(
    private val nowMs: () -> Long,
    private val maxDistinct: Int = 10,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 5 * 60_000,
    private val maxTracked: Int = 256
) {
    private class Entry {
        val wrong = LinkedHashMap<String, Long>() // distinct key -> last seen
        var lockedUntil = 0L
    }

    private val entries = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxTracked
    }

    @Synchronized
    fun isLocked(address: String): Boolean {
        val e = entries[address] ?: return false
        return e.lockedUntil != 0L && nowMs() < e.lockedUntil
    }

    /** Records a wrong key from [address]; returns true when the address is (now) locked. */
    @Synchronized
    fun recordWrong(address: String, key: String): Boolean {
        val now = nowMs()
        val e = entries.getOrPut(address) { Entry() }
        if (e.lockedUntil != 0L) {
            if (now < e.lockedUntil) return true
            e.lockedUntil = 0L
        }
        val it = e.wrong.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > windowMs) it.remove()
        val k = LinkKey.normalize(key).take(32)
        e.wrong.remove(k)
        e.wrong[k] = now
        if (e.wrong.size >= maxDistinct) {
            e.wrong.clear()
            e.lockedUntil = now + lockMs
            return true
        }
        return false
    }
}
