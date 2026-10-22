package com.dualearbuds.companion.net

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** How a web socket session ended. [httpCode] is the HTTP status of a failed upgrade (e.g. 403), else 0. */
data class WsEnd(val opened: Boolean, val httpCode: Int, val reason: String)

/**
 * One web socket session to a host (docs/protocol.md v3). Callbacks run on OkHttp's reader thread and
 * must not block for long.
 */
class StreamConnection(
    private val http: OkHttpClient,
    private val target: LinkTarget,
    private val deviceName: String,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onOpened()
        /** v3 `hello`. [host] and [party] are UNKNOWN/false from v2 hosts. */
        fun onHello(pc: String, rate: Int, host: HostKind, party: Boolean)
        fun onMirroring(mirroring: Boolean, rate: Int)
        /** v3 `party`: Party mode switched on the host. */
        fun onParty(on: Boolean)
        /** v3 `control`: the host set this listener's volume (0–150 %). */
        fun onControlVolume(volume: Int)
        /** v3 `kick`: the host is about to close this listener; it must not auto-reconnect. */
        fun onKick(reason: String)
        /** [samples] is interleaved stereo s16 (L,R,L,R…). [seq] is the unsigned 32-bit sequence number. */
        fun onPacket(rate: Int, seq: Long, samples: ShortArray)

        /** v3.1 `hello` extras: target delay, remote control allowed, sound source ("all"/"app") and app name. */
        fun onHelloExtras(playoutMs: Int, remote: Boolean, source: String, app: String) = Unit

        /** v3.1 `sync` reply: our [c] echoed with host time [h]. */
        fun onSyncReply(c: Double, h: Double) = Unit

        /** v3.1 `clock`: packet [seq] was captured at host time [h]. */
        fun onClock(seq: Long, h: Double) = Unit

        /** v3.1 `talk`: the host is speaking over the music. */
        fun onTalk(on: Boolean) = Unit

        /** v3.1 `control.eq`: the host suggests an EQ preset (flat|bass|voice|movie). */
        fun onSuggestEq(preset: String) = Unit

        /** Source changed (in `state`, if the host sends it). */
        fun onSource(source: String, app: String) = Unit

        /** v3.2 `playout`: the host's common deadline P changed (the host ramps it; we just follow). */
        fun onPlayout(ms: Int) = Unit
    }

    private val active = AtomicBoolean(true)
    private val opened = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastMessageAt = 0L

    val isOpen: Boolean
        get() = active.get() && opened.get()

    /** Sends a text frame. Thread-safe. Returns false when not connected. */
    fun send(text: String): Boolean {
        if (!active.get()) return false
        return try {
            socket?.send(text) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** Closes the session from any thread. [runUntilClosed] returns shortly after. */
    fun close() {
        active.set(false)
        try {
            socket?.close(1000, "stop")
        } catch (_: Throwable) {
        }
    }

    /** Connects and suspends until the session ends (closed, failed, timed out or cancelled). */
    suspend fun runUntilClosed(): WsEnd = coroutineScope {
        val done = CompletableDeferred<WsEnd>()
        val request = Request.Builder().url(target.wsUrl(deviceName)).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!active.get()) return
                opened.set(true)
                lastMessageAt = SystemClock.elapsedRealtime()
                safe { callbacks.onOpened() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active.get()) return
                lastMessageAt = SystemClock.elapsedRealtime()
                safe { handleText(webSocket, text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!active.get()) return
                lastMessageAt = SystemClock.elapsedRealtime()
                safe { handleBinary(bytes) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(1000, null)
                } catch (_: Throwable) {
                }
                done.complete(WsEnd(opened.get(), 0, "the host closed the link ($code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(WsEnd(opened.get(), 0, "link closed ($code)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                done.complete(WsEnd(opened.get(), code, t.message ?: t.javaClass.simpleName))
            }
        }

        val ws = http.newWebSocket(request, listener)
        socket = ws
        if (!active.get()) {
            ws.cancel()
        }

        // Watchdog: the host pings every 2 s, so 8 s of silence means the link is dead (e.g. Wi-Fi dropped).
        val watchdog = launch {
            while (isActive) {
                delay(1000)
                if (!active.get()) {
                    done.complete(WsEnd(opened.get(), 0, "stopped"))
                    break
                }
                val last = lastMessageAt
                if (opened.get() && last > 0 && SystemClock.elapsedRealtime() - last > 8000) {
                    try {
                        ws.cancel()
                    } catch (_: Throwable) {
                    }
                    done.complete(WsEnd(true, 0, "no data from the host for 8 s"))
                    break
                }
            }
        }

        try {
            done.await()
        } finally {
            active.set(false)
            watchdog.cancel()
            try {
                if (!ws.close(1000, "bye")) ws.cancel()
            } catch (_: Throwable) {
                try {
                    ws.cancel()
                } catch (_: Throwable) {
                }
            }
            socket = null
        }
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        val o = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return
        }
        when (o.optString("type")) {
            "ping" -> {
                val reply = JSONObject()
                reply.put("type", "pong")
                reply.put("t", o.opt("t") ?: 0)
                webSocket.send(reply.toString())
            }
            "hello" -> {
                callbacks.onHello(
                    o.optString("pc"),
                    o.optInt("rate", 0),
                    HostKind.fromWire(o.optString("host")),
                    o.optBoolean("party", false)
                )
                callbacks.onHelloExtras(
                    o.optInt("playoutMs", 120).coerceIn(20, 2_000),
                    o.optBoolean("remote", false),
                    o.optString("source", "all").ifBlank { "all" },
                    o.optString("sourceApp", "").ifBlank { o.optString("app", "") }
                )
            }
            "state" -> {
                callbacks.onMirroring(o.optBoolean("mirroring", true), o.optInt("rate", 0))
                if (o.has("source")) {
                    callbacks.onSource(o.optString("source", "all"), o.optString("sourceApp", "").ifBlank { o.optString("app", "") })
                }
            }
            "sync" -> {
                val c = o.optDouble("c", Double.NaN)
                val h = o.optDouble("h", Double.NaN)
                if (!c.isNaN() && !h.isNaN()) callbacks.onSyncReply(c, h)
            }
            "clock" -> {
                val seq = o.optLong("seq", -1L)
                val h = o.optDouble("h", Double.NaN)
                if (seq >= 0 && !h.isNaN()) callbacks.onClock(seq and 0xFFFFFFFFL, h)
            }
            "playout" -> {
                val ms = o.optDouble("ms", Double.NaN)
                if (!ms.isNaN()) callbacks.onPlayout(ms.toInt().coerceIn(20, 2_000))
            }
            "talk" -> callbacks.onTalk(o.optBoolean("on", false))
            "party" -> callbacks.onParty(o.optBoolean("on", false))
            "control" -> {
                if (o.has("volume")) {
                    val v = o.optDouble("volume", Double.NaN)
                    if (!v.isNaN()) callbacks.onControlVolume(v.toInt().coerceIn(0, 150))
                }
                if (o.has("eq")) callbacks.onSuggestEq(o.optString("eq"))
            }
            "kick" -> callbacks.onKick(o.optString("reason"))
            else -> Unit // Unknown types are ignored (docs/protocol.md).
        }
    }

    /** Binary packet: int32 LE rate, uint32 LE seq, then s16le stereo interleaved PCM. */
    private fun handleBinary(bytes: ByteString) {
        val size = bytes.size
        if (size < 8) return
        val raw = bytes.toByteArray()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(0)
        val seq = bb.getInt(4).toLong() and 0xFFFFFFFFL
        var sampleCount = (size - 8) / 2
        sampleCount -= sampleCount % 2 // whole stereo frames only
        if (sampleCount <= 0) return
        val samples = ShortArray(sampleCount)
        bb.position(8)
        bb.asShortBuffer().get(samples, 0, sampleCount)
        callbacks.onPacket(rate, seq, samples)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
