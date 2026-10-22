// Test shim: okio.ByteString, as far as the listener's net code uses it.
@file:Suppress("unused")

package okio

class ByteString(private val data: ByteArray) {
    val size: Int get() = data.size
    fun toByteArray(): ByteArray = data.copyOf()
}
