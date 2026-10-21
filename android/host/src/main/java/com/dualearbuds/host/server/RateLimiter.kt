package com.dualearbuds.host.server

/**
 * Token bucket: at most [perSecond] events per second on average, bursts up to [burst].
 * Used for listener remote-control presses (5/s). Thread-safe, no allocation.
 */
class RateLimiter(
    private val perSecond: Double,
    private val burst: Double = perSecond,
    private val nowMs: () -> Long
) {
    private var tokens = burst
    private var last = nowMs()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMs()
        val elapsed = (now - last).coerceAtLeast(0L)
        last = now
        tokens = minOf(burst, tokens + elapsed * perSecond / 1000.0)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
