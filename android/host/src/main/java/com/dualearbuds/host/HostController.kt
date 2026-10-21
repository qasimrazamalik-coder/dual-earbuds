package com.dualearbuds.host

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.dualearbuds.host.server.HostServer
import com.dualearbuds.host.server.LinkKey
import com.dualearbuds.host.server.ListenerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HostStatus { IDLE, STARTING, SHARING }

data class HostUiState(
    val status: HostStatus = HostStatus.IDLE,
    val port: Int = 0,
    val key: String = "",
    val address: String? = null,
    val onWifi: Boolean = true,
    val party: Boolean = false,
    val maxClients: Int = 8,
    val listeners: List<ListenerInfo> = emptyList(),
    val silent: Boolean = false,
    val error: String? = null
) {
    /** The exact URL the QR code holds, or null while not sharing / no network. */
    val link: String?
        get() = if (status == HostStatus.SHARING && address != null && port > 0) "http://$address:$port/?k=$key" else null
}

/** Shared state between [HostService] and [HostScreen]. */
object HostController {
    private const val PREFS = "dualearbuds_host"
    private const val KEY_LINK = "link_key"
    private const val KEY_PARTY = "party"

    private val _state = MutableStateFlow(HostUiState())
    val state: StateFlow<HostUiState> = _state.asStateFlow()

    @Volatile
    internal var server: HostServer? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the saved key and Party setting (creates a key on first use). */
    fun init(context: Context) {
        val key = linkKey(context)
        val party = prefs(context).getBoolean(KEY_PARTY, false)
        _state.update { it.copy(key = key, party = party, maxClients = if (party) 16 else 8) }
        refreshNetwork(context)
    }

    internal fun linkKey(context: Context): String {
        val p = prefs(context)
        val saved = p.getString(KEY_LINK, null)
        if (saved != null && LinkKey.isWellFormed(saved)) return LinkKey.normalize(saved)
        val fresh = LinkKey.generate()
        p.edit().putString(KEY_LINK, fresh).apply()
        return fresh
    }

    internal fun party(context: Context): Boolean = prefs(context).getBoolean(KEY_PARTY, false)

    /** New random key; current listeners are disconnected and must scan the new QR code. */
    fun newLink(context: Context) {
        val fresh = LinkKey.generate()
        prefs(context).edit().putString(KEY_LINK, fresh).apply()
        server?.setKey(fresh)
        _state.update { it.copy(key = fresh) }
    }

    fun setParty(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PARTY, on).apply()
        server?.setParty(on)
        _state.update { it.copy(party = on, maxClients = if (on) 16 else 8) }
    }

    fun setVolume(id: Int, volume: Int) {
        server?.setVolume(id, volume)
    }

    fun kick(id: Int) {
        server?.kick(id)
    }

    fun refreshNetwork(context: Context) {
        val net = HostNetwork.current(context)
        _state.update { it.copy(address = net.address, onWifi = net.onWifi) }
    }

    /** Starts [HostService] with the MediaProjection consent result. */
    fun startSharing(context: Context, resultCode: Int, data: Intent) {
        _state.update { it.copy(status = HostStatus.STARTING, error = null, silent = false) }
        val intent = Intent(context, HostService::class.java)
            .setAction(HostService.ACTION_START)
            .putExtra(HostService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(HostService.EXTRA_RESULT_DATA, data)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            reportStopped("Could not start sharing: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stopSharing(context: Context) {
        val intent = Intent(context, HostService::class.java).setAction(HostService.ACTION_STOP)
        try {
            context.startService(intent)
        } catch (_: Throwable) {
            reportStopped(null)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    internal fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    internal fun reportSharing(port: Int, key: String) {
        _state.update {
            it.copy(status = HostStatus.SHARING, port = port, key = key, error = null, silent = false)
        }
    }

    internal fun reportStopped(error: String?) {
        server = null
        _state.update {
            it.copy(status = HostStatus.IDLE, port = 0, listeners = emptyList(), silent = false, error = error ?: it.error)
        }
    }

    internal fun reportListeners(list: List<ListenerInfo>) {
        _state.update { if (it.listeners == list) it else it.copy(listeners = list) }
    }

    internal fun reportSilent(silent: Boolean) {
        _state.update { if (it.silent == silent) it else it.copy(silent = silent) }
    }
}
