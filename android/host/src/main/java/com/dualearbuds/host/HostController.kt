package com.dualearbuds.host

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dualearbuds.host.audio.NoiseCancel
import com.dualearbuds.host.net.Discovery
import com.dualearbuds.host.net.HostAddress
import com.dualearbuds.host.net.HostNetwork
import com.dualearbuds.host.net.LinkBuilder
import com.dualearbuds.host.net.NetKind
import com.dualearbuds.host.server.LinkKey
import com.dualearbuds.host.server.ListenerInfo
import com.dualearbuds.host.server.LocalOutput
import com.dualearbuds.host.server.RoomPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HostStatus { IDLE, STARTING, SHARING }

data class HostUiState(
    val status: HostStatus = HostStatus.IDLE,
    val prefs: HostPrefs = HostPrefs(key = ""),
    /** Port actually bound while sharing (may differ from the setting if it was busy). */
    val port: Int = 0,
    val addresses: List<HostAddress> = emptyList(),
    /** Address the link/QR uses; null = the first of [addresses]. */
    val chosenAddress: String? = null,
    /** "Show on this network" chosen by the user this session; null = automatic. */
    val showChoice: Boolean? = null,
    val talking: Boolean = false,
    val listeners: List<ListenerInfo> = emptyList(),
    val silent: Boolean = false,
    val error: String? = null,
    /** Common deadline P in ms (v3.2) while sharing, else 0. */
    val playoutMs: Int = 0,
    /** This phone's own output (its earbuds, wired or speaker) while sharing. */
    val localOutput: LocalOutput? = null
) {
    val address: HostAddress? get() = addresses.firstOrNull { it.address == chosenAddress } ?: addresses.firstOrNull()
    val netKind: NetKind get() = address?.kind ?: NetKind.NONE
    val showOnNetwork: Boolean get() = Discovery.showOnNetwork(netKind, showChoice)
    val maxClients: Int get() = if (prefs.party) 16 else 8

    /** The exact URL the QR code holds (never the PIN), or null while not sharing / no network. */
    val link: String? get() = if (status == HostStatus.SHARING) LinkBuilder.link(address?.address, port, prefs.key) else null
}

/** What the running [HostService] applies live. */
internal interface HostEngine {
    fun applyPrefs(prefs: HostPrefs, sourceChanged: Boolean)
    fun applyTalk(on: Boolean)
    fun applyDiscovery()
    fun newKey(key: String)
    fun setVolume(id: Int, volume: Int)
    fun kick(id: Int)
}

/** Shared state between [HostService] and [HostScreen]; every setting change goes through here. */
object HostController {
    private val _state = MutableStateFlow(HostUiState())
    val state: StateFlow<HostUiState> = _state.asStateFlow()

    @Volatile
    internal var engine: HostEngine? = null

    private var settings: HostSettings? = null

    private fun settings(context: Context): HostSettings =
        settings ?: HostSettings(context).also { settings = it }

    /** Loads saved settings (creates a link key on first use) and looks up the network. */
    fun init(context: Context) {
        if (_state.value.prefs.key.isEmpty()) {
            val prefs = settings(context).load()
            _state.update { it.copy(prefs = prefs) }
        }
        refreshNetwork(context)
    }

    internal fun prefs(context: Context): HostPrefs {
        init(context)
        return _state.value.prefs
    }

    private fun changePrefs(context: Context, sourceChanged: Boolean = false, change: (HostPrefs) -> HostPrefs) {
        init(context)
        val next = change(_state.value.prefs)
        settings(context).save(next)
        _state.update { it.copy(prefs = next) }
        engine?.applyPrefs(next, sourceChanged)
    }

    fun setParty(context: Context, on: Boolean) = changePrefs(context) { it.copy(party = on) }

    fun setPinOn(context: Context, on: Boolean) = changePrefs(context) { it.copy(pinOn = on) }

    /** Stores the typed PIN (digits only, max 6); it takes effect once it has 4–6 digits. */
    fun setPin(context: Context, raw: String) = changePrefs(context) { it.copy(pin = RoomPin.sanitizeInput(raw)) }

    fun setRemote(context: Context, on: Boolean) = changePrefs(context) { it.copy(remote = on) }

    fun setNoiseCancel(context: Context, level: NoiseCancel) = changePrefs(context) { it.copy(noiseCancel = level) }

    /** null = all apps. */
    fun setSource(context: Context, app: AppChoice?) = changePrefs(context, sourceChanged = true) { it.copy(app = app) }

    /** Port for the next start (1024–65535). Returns false when invalid. */
    fun setPort(context: Context, text: String): Boolean {
        val p = LinkBuilder.parsePort(text) ?: return false
        changePrefs(context) { it.copy(port = p) }
        return true
    }

    /** New random key; current listeners are disconnected and must scan the new QR code. */
    fun newLink(context: Context) {
        val fresh = LinkKey.generate()
        changePrefs(context) { it.copy(key = fresh) }
        engine?.newKey(fresh)
    }

    fun setShowOnNetwork(on: Boolean) {
        _state.update { it.copy(showChoice = on) }
        engine?.applyDiscovery()
    }

    fun chooseAddress(address: String) {
        _state.update { it.copy(chosenAddress = address) }
        engine?.applyDiscovery()
    }

    fun setTalking(on: Boolean) {
        if (_state.value.status != HostStatus.SHARING) return
        _state.update { it.copy(talking = on) }
        engine?.applyTalk(on)
    }

    fun setVolume(id: Int, volume: Int) {
        engine?.setVolume(id, volume)
    }

    fun kick(id: Int) {
        engine?.kick(id)
    }

    fun refreshNetwork(context: Context) {
        val list = HostNetwork.addresses(context)
        val before = _state.value
        _state.update { if (it.addresses == list) it else it.copy(addresses = list) }
        if (before.addresses != list && before.netKind != _state.value.netKind) engine?.applyDiscovery()
    }

    /** Starts [HostService] with the MediaProjection consent result. */
    fun startSharing(context: Context, resultCode: Int, data: Intent) {
        init(context)
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

    internal fun reportError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    internal fun reportSharing(port: Int) {
        _state.update { it.copy(status = HostStatus.SHARING, port = port, error = null, silent = false) }
    }

    internal fun reportStopped(error: String?) {
        engine = null
        _state.update {
            it.copy(
                status = HostStatus.IDLE, port = 0, listeners = emptyList(), silent = false, talking = false,
                showChoice = null, error = error ?: it.error, playoutMs = 0, localOutput = null
            )
        }
    }

    internal fun reportTalking(on: Boolean) {
        _state.update { it.copy(talking = on) }
    }

    internal fun reportListeners(list: List<ListenerInfo>) {
        _state.update { if (it.listeners == list) it else it.copy(listeners = list) }
    }

    internal fun reportPlayout(ms: Int) {
        _state.update { if (it.playoutMs == ms) it else it.copy(playoutMs = ms) }
    }

    internal fun reportLocalOutput(out: LocalOutput) {
        _state.update { if (it.localOutput == out) it else it.copy(localOutput = out) }
    }

    internal fun reportSilent(silent: Boolean) {
        _state.update { if (it.silent == silent) it else it.copy(silent = silent) }
    }
}
