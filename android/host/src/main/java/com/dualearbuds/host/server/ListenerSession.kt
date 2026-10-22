package com.dualearbuds.host.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A listener as shown in the host's UI. */
data class ListenerInfo(
    val id: Int,
    val name: String,
    val client: String,
    val address: String,
    val device: String,
    val earbuds: String,
    val battery: Int,
    val volume: Int,
    val bufferMs: Int,
    val underruns: Int,
    val playing: Boolean,
    val rttMs: Int,
    val dropped: Long,
    /** The listener is doing clock sync (v3.1): it sent `sync` recently or said so in `status`. */
    val synced: Boolean,
    /** v3.2 `needMs` from its status, or null when it doesn't report one. */
    val needMs: Int? = null,
    /** Offset from the common deadline (+ = late), or null when unknown. */
    val syncErrMs: Int? = null,
    /** How audio reaches it: "udp" or "ws". */
    val transport: String = "ws"
) {
    val displayName: String get() = device.ifBlank { name.ifBlank { "Listener" } }
}

/** What a [ListenerSession] needs from its server. */
internal interface SessionOwner {
    val limits: HostLimits
    fun nowMs(): Long
    fun onText(session: ListenerSession, text: String)
    fun onClosed(session: ListenerSession)
}

/**
 * One connected WebSocket listener: a writer thread draining its [Outbox] and the reader loop
 * (run on the handshake thread). Frames are size-checked by [WsFrameReader].
 */
internal class ListenerSession(
    val id: Int,
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val client: String,
    val name: String,
    val address: String,
    private val owner: SessionOwner
) {
    private val limits = owner.limits
    val outbox = Outbox(maxOf(1, limits.queueMs / AudioPacket.PACKET_MS))
    private val closed = AtomicBoolean(false)

    @Volatile var writeStartedAt = 0L
    @Volatile var lastHeard = 0L
    @Volatile var lastSyncAt = 0L
    @Volatile var device = ""
    @Volatile var earbuds = ""
    @Volatile var battery = -1
    @Volatile var volume = 100
    @Volatile var bufferMs = 0
    @Volatile var underruns = 0
    @Volatile var playing = false
    @Volatile var syncedHint: Boolean? = null
    @Volatile var rttMs = -1
    @Volatile var needMs: Int? = null
    @Volatile var syncErrMs: Int? = null
    @Volatile var dropped = 0L

    val isClosed: Boolean get() = closed.get()

    fun info(now: Long): ListenerInfo {
        val recentSync = lastSyncAt != 0L && now - lastSyncAt <= SYNC_FRESH_MS
        return ListenerInfo(
            id, name, client, address, device, earbuds, battery, volume, bufferMs, underruns, playing, rttMs, dropped,
            synced = syncedHint ?: recentSync,
            needMs = needMs,
            syncErrMs = syncErrMs,
            transport = "ws"
        )
    }

    fun send(json: String) = sendFrame(WsCodec.textFrame(json))

    fun sendFrame(frame: ByteArray, closeAfter: Boolean = false) {
        // Control queue overflow means the listener is not reading at all.
        if (!outbox.offerControl(frame, closeAfter) && !closed.get()) close()
    }

    fun offerAudio(frame: ByteArray) {
        if (outbox.offerAudio(frame)) dropped++
    }

    fun startWriter() {
        Thread({ writerLoop() }, "host-ws-w$id").apply {
            isDaemon = true
            start()
        }
    }

    private fun writerLoop() {
        try {
            while (!closed.get()) {
                val item = outbox.take(1000) ?: continue
                val bytes: ByteArray
                var closeAfter = false
                if (item is Outbox.Item) {
                    bytes = item.bytes
                    closeAfter = item.closeAfter
                } else {
                    bytes = item as ByteArray
                }
                writeStartedAt = owner.nowMs().coerceAtLeast(1L)
                output.write(bytes)
                writeStartedAt = 0L
                if (closeAfter) {
                    output.flush()
                    close()
                    return
                }
            }
        } catch (_: IOException) {
            close()
        } catch (_: RuntimeException) {
            close()
        } finally {
            writeStartedAt = 0L
        }
    }

    /** Runs on the handshake thread after the upgrade, until the listener goes away. */
    fun runReader() {
        val reader = WsFrameReader(input, limits.maxMessageBytes)
        val message = ByteArray(limits.maxMessageBytes)
        var msgLen = 0
        var msgOpcode = -1
        try {
            while (!closed.get()) {
                val more = try {
                    reader.next()
                } catch (_: SocketTimeoutException) {
                    // Nothing from this listener for 30 s.
                    sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_GOING_AWAY, "idle"), closeAfter = true)
                    return
                }
                if (!more) break
                lastHeard = owner.nowMs()
                when (reader.opcode) {
                    WsCodec.OP_CLOSE -> {
                        sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_NORMAL), closeAfter = true)
                        return
                    }
                    WsCodec.OP_PING -> sendFrame(WsCodec.frame(WsCodec.OP_PONG, reader.payload, 0, reader.length))
                    WsCodec.OP_PONG -> Unit
                    WsCodec.OP_TEXT, WsCodec.OP_BINARY -> {
                        if (msgOpcode != -1) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Expected continuation")
                        System.arraycopy(reader.payload, 0, message, 0, reader.length)
                        msgLen = reader.length
                        if (reader.fin) {
                            if (reader.opcode == WsCodec.OP_TEXT) owner.onText(this, String(message, 0, msgLen, Charsets.UTF_8))
                            msgLen = 0
                        } else {
                            msgOpcode = reader.opcode
                        }
                    }
                    WsCodec.OP_CONT -> {
                        if (msgOpcode == -1) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Unexpected continuation")
                        if (msgLen + reader.length > message.size) throw WsProtocolException(WsCodec.CLOSE_TOO_BIG, "Message too big")
                        System.arraycopy(reader.payload, 0, message, msgLen, reader.length)
                        msgLen += reader.length
                        if (reader.fin) {
                            if (msgOpcode == WsCodec.OP_TEXT) owner.onText(this, String(message, 0, msgLen, Charsets.UTF_8))
                            msgOpcode = -1
                            msgLen = 0
                        }
                    }
                }
            }
            close()
        } catch (e: WsProtocolException) {
            sendFrame(WsCodec.closeFrame(e.closeCode, e.message ?: ""), closeAfter = true)
        } catch (_: IOException) {
            close()
        } catch (_: RuntimeException) {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbox.close()
        try {
            socket.close()
        } catch (_: IOException) {
        }
        owner.onClosed(this)
    }

    companion object {
        /** Listeners send `sync` every 2 s; 6 s without one means they stopped syncing. */
        const val SYNC_FRESH_MS = 6_000L
    }
}
