package com.dualearbuds.companion.audio

/**
 * Packet-loss concealment (docs/protocol.md v3.1): a missing packet is filled by repeating the last 10 ms
 * with a fade to silence over at most [fadeMs] (30 ms), and the next real packet is crossfaded back in over
 * [crossfadeMs] so there is never a click.
 *
 * Pure Kotlin, used on the network thread before packets enter the jitter queue. Not thread-safe.
 */
class PacketConcealer(
    private val fadeMs: Int = 30,
    private val crossfadeMs: Int = 5,
    /** Gaps longer than this are not filled (the host dropped old packets for a slow listener). */
    val maxConcealPackets: Int = 3
) {
    private var last: ShortArray? = null
    private var lastRate = 0

    /** Gain reached at the end of the concealment (0 = fully faded), or 1 when not concealing. */
    private var concealGain = 1.0

    /** Samples of the repeat continuation used for the crossfade back in (the last repeat, unfaded). */
    private var concealing = false

    fun reset() {
        last = null
        lastRate = 0
        concealGain = 1.0
        concealing = false
    }

    /**
     * Builds the fill for [missing] lost packets just before a real packet. Returns at most [maxConcealPackets]
     * packets (fewer when there is no previous packet to repeat, or the rate changed).
     */
    fun conceal(missing: Int, rate: Int): List<ShortArray> {
        val src = last ?: return emptyList()
        if (missing <= 0 || rate != lastRate || rate <= 0) return emptyList()
        val count = minOf(missing, maxConcealPackets)
        val frames = src.size / 2
        val fadeFrames = (rate.toLong() * fadeMs / 1000).toInt().coerceAtLeast(1)
        val out = ArrayList<ShortArray>(count)
        var faded = 0 // frames into the fade so far
        for (p in 0 until count) {
            val pkt = ShortArray(src.size)
            for (f in 0 until frames) {
                val g = 1.0 - (faded + f).toDouble() / fadeFrames
                val gain = if (g <= 0.0) 0.0 else g
                pkt[f * 2] = (src[f * 2] * gain).toInt().toShort()
                pkt[f * 2 + 1] = (src[f * 2 + 1] * gain).toInt().toShort()
            }
            faded += frames
            out += pkt
        }
        val end = 1.0 - faded.toDouble() / fadeFrames
        concealGain = if (end <= 0.0) 0.0 else end
        concealing = true
        return out
    }

    /**
     * Passes a real packet through. Right after a concealment its start is crossfaded from the (faded) repeat
     * continuation into the real sound. Modifies [samples] in place and remembers it for the next loss.
     */
    fun onReal(samples: ShortArray, rate: Int) {
        val src = last
        if (concealing && src != null && rate == lastRate && src.size >= 2) {
            val frames = samples.size / 2
            val xf = (rate.toLong() * crossfadeMs / 1000).toInt().coerceIn(1, frames)
            val srcFrames = src.size / 2
            for (f in 0 until xf) {
                val t = (f + 1).toDouble() / (xf + 1)
                val sf = f % srcFrames
                for (ch in 0..1) {
                    val repeat = src[sf * 2 + ch] * concealGain
                    val real = samples[f * 2 + ch].toDouble()
                    samples[f * 2 + ch] = clamp(repeat * (1 - t) + real * t)
                }
            }
        }
        concealing = false
        concealGain = 1.0
        last = samples.copyOf()
        lastRate = rate
    }

    private fun clamp(v: Double): Short {
        val i = v.toInt()
        return (if (i > 32767) 32767 else if (i < -32768) -32768 else i).toShort()
    }
}
