package com.dualearbuds.host.server

/** What `/info` and `hello` report about this host at one moment. */
data class HostFacts(
    val identity: HostIdentity,
    val rate: Int,
    val party: Boolean,
    val maxClients: Int,
    val pin: Boolean,
    val remote: Boolean,
    /** "all" or "app". */
    val source: String,
    /** App name when [source] is "app". */
    val app: String?,
    val playoutMs: Int
)

/** A listener → host `remote` action (v3.1). */
enum class RemoteAction(val wire: String) {
    PLAY_PAUSE("playpause"), NEXT("next"), PREV("prev"), VOL_UP("volup"), VOL_DOWN("voldown");

    companion object {
        fun parse(s: String?): RemoteAction? = entries.firstOrNull { it.wire == s }
    }
}

/** The fields of a listener's `status` message; null = not present (keep the old value). */
data class ListenerStatus(
    val device: String?,
    val earbuds: String?,
    val battery: Int?,
    val volume: Int?,
    val bufferMs: Int?,
    val underruns: Int?,
    val playing: Boolean?,
    val synced: Boolean?
)

/** JSON messages of protocol v3 + v3.1, host side. */
object HostMessages {
    const val PROTOCOL = 3
    const val DEFAULT_PLAYOUT_MS = 120

    /**
     * `/info`. Protocol v3.1 says the app name rides in `"app"`, but in `/info` that key already
     * identifies the service (`"app":"DualEarbuds"`, which listeners check), so here the shared app's
     * name goes in `"sourceApp"`; `hello` uses `"app"` as written.
     */
    fun info(f: HostFacts): String {
        val pairs = arrayListOf<Pair<String, Any?>>(
            "app" to "DualEarbuds",
            "protocol" to PROTOCOL,
            "version" to f.identity.version,
            "host" to f.identity.host,
            "pc" to f.identity.pcName,
            "rate" to f.rate,
            "channels" to 2,
            "format" to "s16le",
            "party" to f.party,
            "maxClients" to f.maxClients,
            "pin" to f.pin,
            "remote" to f.remote,
            "source" to f.source
        )
        if (f.source == "app" && !f.app.isNullOrBlank()) pairs += "sourceApp" to f.app
        return MiniJson.obj(*pairs.toTypedArray())
    }

    fun hello(f: HostFacts): String {
        val pairs = arrayListOf<Pair<String, Any?>>(
            "type" to "hello",
            "protocol" to PROTOCOL,
            "pc" to f.identity.pcName,
            "version" to f.identity.version,
            "host" to f.identity.host,
            "rate" to f.rate,
            "party" to f.party,
            "playoutMs" to f.playoutMs,
            "remote" to f.remote,
            "source" to f.source
        )
        if (f.source == "app" && !f.app.isNullOrBlank()) pairs += "app" to f.app
        return MiniJson.obj(*pairs.toTypedArray())
    }

    fun ping(t: Long) = MiniJson.obj("type" to "ping", "t" to t)
    fun state(mirroring: Boolean, rate: Int) = MiniJson.obj("type" to "state", "mirroring" to mirroring, "rate" to rate)
    fun party(on: Boolean) = MiniJson.obj("type" to "party", "on" to on)
    fun volume(volume: Int) = MiniJson.obj("type" to "control", "volume" to volume)
    fun kick(reason: String) = MiniJson.obj("type" to "kick", "reason" to reason)
    fun talk(on: Boolean) = MiniJson.obj("type" to "talk", "on" to on)
    fun clock(seq: Long, hostMs: Long) = MiniJson.obj("type" to "clock", "seq" to seq, "h" to hostMs)
    fun syncReply(c: Double, hostMs: Long) = MiniJson.obj("type" to "sync", "c" to c, "h" to hostMs)

    /** Reads a `status` message, clamping every number to its protocol range. */
    fun parseStatus(m: Map<String, Any?>): ListenerStatus = ListenerStatus(
        device = m.str("device"),
        earbuds = m.str("earbuds"),
        battery = m.num("battery")?.toInt()?.coerceIn(-1, 100),
        volume = m.num("volume")?.toInt()?.coerceIn(0, 150),
        bufferMs = m.num("bufferMs")?.toInt()?.coerceIn(0, 60_000),
        underruns = m.num("underruns")?.toInt()?.coerceAtLeast(0),
        playing = m.bool("playing"),
        synced = m.bool("synced")
    )
}
