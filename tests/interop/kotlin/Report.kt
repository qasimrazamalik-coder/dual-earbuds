// Interop harness: check reporting and small helpers shared by the Kotlin drivers.
package interop

import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class Report(private val who: String) {
    private var failed = 0

    fun check(ok: Boolean, name: String, detail: String = "") {
        if (!ok) failed++
        println((if (ok) "CHECK PASS " else "CHECK FAIL ") + "[$who] $name" + (if (detail.isEmpty()) "" else " :: $detail"))
        System.out.flush()
    }

    fun info(text: String) {
        println("INFO [$who] $text")
        System.out.flush()
    }

    fun exit(): Nothing {
        System.out.flush()
        Runtime.getRuntime().halt(if (failed == 0) 0 else 1)
        throw IllegalStateException()
    }
}

/** --name value arguments. */
class Args(argv: Array<String>) {
    private val m = HashMap<String, String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) {
                if (i + 1 < argv.size && !argv[i + 1].startsWith("--")) {
                    m[a.substring(2)] = argv[i + 1]; i += 2
                } else {
                    m[a.substring(2)] = "true"; i++
                }
            } else i++
        }
    }

    fun str(k: String, d: String? = null): String? = m[k] ?: d
    fun int(k: String, d: Int): Int = m[k]?.toInt() ?: d
    fun dbl(k: String, d: Double): Double = m[k]?.toDouble() ?: d
    fun flag(k: String): Boolean = m[k] == "true"
}

/** 1 kHz test tone, s16le stereo, continuous across packets. */
class Tone(private val rate: Int = 48_000, private val hz: Double = 1000.0, private val amp: Double = 0.3) {
    private var n = 0L
    fun next10ms(dst: ByteArray) {
        val frames = rate / 100
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * (n + i) / rate) * amp * 32767).toInt()
            val o = i * 4
            dst[o] = v.toByte(); dst[o + 1] = (v shr 8).toByte()
            dst[o + 2] = v.toByte(); dst[o + 3] = (v shr 8).toByte()
        }
        n += frames
    }
}

/** Sign changes of the left channel in one packet: 20 for 10 ms of a 1 kHz tone. */
fun zeroCrossings(samples: ShortArray): Int {
    var z = 0
    var prev = samples.getOrElse(0) { 0 }.toInt()
    var i = 2
    while (i < samples.size) {
        val v = samples[i].toInt()
        if ((prev < 0 && v >= 0) || (prev >= 0 && v < 0)) z++
        prev = v
        i += 2
    }
    return z
}

/** The host driver writes `epoch ms at host time 0` here; listeners compare their clock sync against it. */
fun writeEpoch(file: String?, epochAtHostZero: Double) {
    if (file != null) File(file).writeText(String.format(java.util.Locale.US, "%.3f", epochAtHostZero))
}

fun readEpoch(file: String?): Double? = try {
    file?.let { File(it).readText().trim().toDouble() }
} catch (_: Exception) {
    null
}

/** Wall clock in ms with sub-ms resolution, shared by all processes on this machine. */
fun epochMs(): Double {
    val i = java.time.Instant.now()
    return i.epochSecond * 1000.0 + i.nano / 1_000_000.0
}
