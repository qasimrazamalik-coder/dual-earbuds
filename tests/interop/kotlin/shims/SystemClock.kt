// Test shim: android.os.SystemClock on a plain JVM (monotonic, like the real one).
package android.os

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
