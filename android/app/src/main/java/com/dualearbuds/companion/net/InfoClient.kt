package com.dualearbuds.companion.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** GET /info (protocol v3). `host`, `party` and `maxClients` are absent on v2 hosts. */
data class LaptopInfo(
    val pc: String,
    val version: String,
    val protocol: Int,
    val rate: Int,
    val channels: Int,
    val format: String,
    val host: HostKind = HostKind.UNKNOWN,
    val party: Boolean = false,
    val maxClients: Int = 8,
    /** v3.1: the host asks for a room PIN on `/ws`. */
    val pin: Boolean = false,
    /** v3.1: listeners may send `remote` actions. */
    val remote: Boolean = false,
    /** v3.1: "all" or "app"; [app] names the app when only one app's sound is shared. */
    val source: String = "all",
    val app: String = ""
)

sealed interface InfoResult {
    data class Ok(val info: LaptopInfo) : InfoResult
    data object WrongKey : InfoResult
    data object ServerFull : InfoResult
    data object NotDualEarbuds : InfoResult
    data class Unreachable(val reason: String) : InfoResult
    data class Failed(val reason: String) : InfoResult
}

/** GET /info?k=KEY. Blocking: call it from Dispatchers.IO. Never throws. */
object InfoClient {
    fun fetch(http: OkHttpClient, target: LinkTarget): InfoResult {
        val request = try {
            Request.Builder()
                .url(target.infoUrl())
                .header("Accept", "application/json")
                .build()
        } catch (t: Throwable) {
            return InfoResult.Failed(t.message ?: "Invalid address")
        }
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parse(response.body?.string().orEmpty(), target)
                    403 -> InfoResult.WrongKey
                    503 -> InfoResult.ServerFull
                    404 -> InfoResult.NotDualEarbuds
                    else -> InfoResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            InfoResult.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (t: Throwable) {
            InfoResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun parse(body: String, target: LinkTarget): InfoResult {
        return try {
            val o = JSONObject(body)
            // "app" identifies the service. A host that (against v3.1) also put the shared app's name in "app"
            // would overwrite it, so a v3 shape (protocol + pc + format) is accepted too.
            val appField = o.optString("app")
            val looksV3 = o.optInt("protocol", 0) >= 3 && o.has("pc") && o.has("format")
            if (appField != "DualEarbuds" && !looksV3) return InfoResult.NotDualEarbuds
            val sourceApp = o.optString("sourceApp", "").ifBlank { if (appField != "DualEarbuds") appField else "" }
            InfoResult.Ok(
                LaptopInfo(
                    pc = o.optString("pc").ifBlank { target.host },
                    version = o.optString("version"),
                    protocol = o.optInt("protocol", 0),
                    rate = o.optInt("rate", 0),
                    channels = o.optInt("channels", 2),
                    format = o.optString("format", "s16le"),
                    host = HostKind.fromWire(o.optString("host")),
                    party = o.optBoolean("party", false),
                    maxClients = o.optInt("maxClients", 8),
                    pin = o.optBoolean("pin", false),
                    remote = o.optBoolean("remote", false),
                    source = o.optString("source", "all").ifBlank { "all" },
                    app = sourceApp
                )
            )
        } catch (t: Throwable) {
            InfoResult.NotDualEarbuds
        }
    }
}
