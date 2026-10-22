package com.dualearbuds.companion.audio

/**
 * Keeps the AudioTrack's *effective* buffer small (protocol v3.2: about two hardware bursts) and grows it one
 * burst at a time only when the track itself underruns (its `underrunCount` rises) — the same idea as Oboe's
 * latency tuner. Network jitter is the jitter buffer's job; this only covers the playback thread's scheduling.
 * Pure Kotlin, not thread-safe (audio thread only).
 *
 * @param burstFrames frames per hardware burst (AudioManager PROPERTY_OUTPUT_FRAMES_PER_BUFFER), ≤ 0 = unknown
 * @param capacityFrames the track's allocated buffer; the effective size never exceeds it
 * @param rate sample rate, used for the floor and for the unknown-burst fallback (10 ms)
 */
class TrackBufferTuner(burstFrames: Int, private val capacityFrames: Int, rate: Int, startBursts: Int = 2) {
    val burstFrames: Int = if (burstFrames in 16..8_192) burstFrames else (rate / 100).coerceAtLeast(16)

    /** Never below 4 ms: some devices report tiny bursts their scheduler can't actually meet. */
    private val floorFrames = (rate * 4 / 1000).coerceAtLeast(64)

    var sizeFrames: Int = (this.burstFrames * startBursts).coerceAtLeast(floorFrames).coerceAtMost(capacityFrames.coerceAtLeast(1))
        private set

    /** Times the size was raised because of track underruns. */
    var raises: Int = 0
        private set

    private var lastUnderruns = -1

    /** The track's actual size after setBufferSizeInFrames (the platform may round it). */
    fun onApplied(actualFrames: Int) {
        if (actualFrames > 0) sizeFrames = actualFrames
    }

    /**
     * Feed the track's cumulative underrun count. Returns the new size to apply when it should grow, else null.
     */
    fun onUnderrunCount(count: Int): Int? {
        if (count < 0) return null
        if (lastUnderruns < 0 || count < lastUnderruns) {
            lastUnderruns = count
            return null
        }
        if (count == lastUnderruns) return null
        lastUnderruns = count
        val grown = (sizeFrames + burstFrames).coerceAtMost(capacityFrames)
        if (grown <= sizeFrames) return null
        sizeFrames = grown
        raises++
        return grown
    }
}
