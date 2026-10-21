package com.dualearbuds.host.server

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Thrown when a peer breaks RFC 6455; [closeCode] is sent back in the close frame. */
class WsProtocolException(val closeCode: Int, message: String) : IOException(message)

/** RFC 6455 framing helpers (server side: frames we send are never masked). */
object WsCodec {
    const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    const val OP_CONT = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    const val CLOSE_NORMAL = 1000
    const val CLOSE_GOING_AWAY = 1001
    const val CLOSE_PROTOCOL = 1002
    const val CLOSE_TOO_BIG = 1009

    const val MAX_CONTROL_PAYLOAD = 125

    /** `Sec-WebSocket-Accept` for a client's `Sec-WebSocket-Key`. */
    fun acceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((clientKey.trim() + GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** A valid client key is base64 of exactly 16 bytes. */
    fun isValidClientKey(key: String?): Boolean {
        if (key == null) return false
        return try {
            Base64.getDecoder().decode(key.trim()).size == 16
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun headerSize(payloadLen: Int): Int = when {
        payloadLen <= 125 -> 2
        payloadLen <= 0xFFFF -> 4
        else -> 10
    }

    /** Writes an unmasked FIN frame header into [dst] at [off]; returns its size. */
    fun writeHeader(dst: ByteArray, off: Int, opcode: Int, payloadLen: Int): Int {
        dst[off] = (0x80 or (opcode and 0x0F)).toByte()
        return when {
            payloadLen <= 125 -> {
                dst[off + 1] = payloadLen.toByte()
                2
            }
            payloadLen <= 0xFFFF -> {
                dst[off + 1] = 126.toByte()
                dst[off + 2] = (payloadLen ushr 8).toByte()
                dst[off + 3] = payloadLen.toByte()
                4
            }
            else -> {
                dst[off + 1] = 127.toByte()
                val l = payloadLen.toLong()
                for (i in 0 until 8) dst[off + 2 + i] = (l ushr (56 - 8 * i)).toByte()
                10
            }
        }
    }

    /** A complete unmasked server frame. */
    fun frame(opcode: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        val h = headerSize(len)
        val out = ByteArray(h + len)
        writeHeader(out, 0, opcode, len)
        System.arraycopy(payload, off, out, h, len)
        return out
    }

    fun textFrame(text: String): ByteArray = frame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun closeFrame(code: Int, reason: String = ""): ByteArray {
        var r = reason.toByteArray(Charsets.UTF_8)
        if (r.size > MAX_CONTROL_PAYLOAD - 2) r = r.copyOf(MAX_CONTROL_PAYLOAD - 2)
        val p = ByteArray(2 + r.size)
        p[0] = (code ushr 8).toByte()
        p[1] = code.toByte()
        System.arraycopy(r, 0, p, 2, r.size)
        return frame(OP_CLOSE, p)
    }

    /** A masked frame as a client would send it (used by tests and tools). */
    fun maskedFrame(opcode: Int, payload: ByteArray, mask: ByteArray, fin: Boolean = true): ByteArray {
        require(mask.size == 4)
        val len = payload.size
        val ext = when {
            len <= 125 -> 0
            len <= 0xFFFF -> 2
            else -> 8
        }
        val out = ByteArray(2 + ext + 4 + len)
        out[0] = ((if (fin) 0x80 else 0) or (opcode and 0x0F)).toByte()
        when (ext) {
            0 -> out[1] = (0x80 or len).toByte()
            2 -> {
                out[1] = (0x80 or 126).toByte()
                out[2] = (len ushr 8).toByte()
                out[3] = len.toByte()
            }
            else -> {
                out[1] = (0x80 or 127).toByte()
                val l = len.toLong()
                for (i in 0 until 8) out[2 + i] = (l ushr (56 - 8 * i)).toByte()
            }
        }
        val mp = 2 + ext
        System.arraycopy(mask, 0, out, mp, 4)
        for (i in 0 until len) out[mp + 4 + i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return out
    }
}

/**
 * Reads client frames from [input] into one reusable buffer. Enforces: masking required,
 * RSV bits zero, known opcodes, control frames FIN and ≤125 bytes, data frames ≤ [maxPayload].
 */
class WsFrameReader(
    private val input: InputStream,
    private val maxPayload: Int,
    private val requireMask: Boolean = true
) {
    val payload = ByteArray(maxOf(maxPayload, WsCodec.MAX_CONTROL_PAYLOAD))
    var opcode = 0
        private set
    var fin = false
        private set
    var length = 0
        private set
    private val mask = ByteArray(4)

    /** Reads one frame. Returns false on a clean EOF before the first byte. */
    fun next(): Boolean {
        val b0 = input.read()
        if (b0 < 0) return false
        val b1 = readByte()
        fin = (b0 and 0x80) != 0
        if ((b0 and 0x70) != 0) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "RSV bits set")
        opcode = b0 and 0x0F
        val control = opcode >= 0x8
        when (opcode) {
            WsCodec.OP_CONT, WsCodec.OP_TEXT, WsCodec.OP_BINARY,
            WsCodec.OP_CLOSE, WsCodec.OP_PING, WsCodec.OP_PONG -> Unit
            else -> throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Unknown opcode $opcode")
        }
        val masked = (b1 and 0x80) != 0
        if (requireMask && !masked) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Client frames must be masked")
        var len: Long = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((readByte() shl 8) or readByte()).toLong()
        } else if (len == 127L) {
            var l = 0L
            repeat(8) { l = (l shl 8) or readByte().toLong() }
            len = l
        }
        if (control) {
            if (!fin) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Fragmented control frame")
            if (len > WsCodec.MAX_CONTROL_PAYLOAD) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Control frame too long")
        } else if (len < 0 || len > maxPayload) {
            throw WsProtocolException(WsCodec.CLOSE_TOO_BIG, "Message too big")
        }
        if (masked) readFully(mask, 4)
        val n = len.toInt()
        readFully(payload, n)
        if (masked) for (i in 0 until n) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        length = n
        return true
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(dst: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(dst, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }
}
