package com.dualearbuds.companion.data

import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.net.LinkTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable app-level settings (theme, automatic update checks, recent hosts). Backed by [Prefs]. */
object AppSettings {
    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _autoUpdate = MutableStateFlow(true)
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    private val _recent = MutableStateFlow<List<RecentHost>>(emptyList())

    /** Last 5 hosts that answered /info, newest first (one-tap "Recent" chips on Home). */
    val recent: StateFlow<List<RecentHost>> = _recent.asStateFlow()

    fun init() {
        _theme.value = Prefs.theme
        _autoUpdate.value = Prefs.autoUpdate
        _recent.value = RecentHosts.decode(Prefs.recentHosts)
    }

    /** Thread-safe; called from the UI (Check link) and the playback service (successful /info). */
    @Synchronized
    fun rememberHost(target: LinkTarget, name: String, kind: HostKind) {
        val list = RecentHosts.add(_recent.value, RecentHost(target.toLink(), name.ifBlank { target.display }, kind))
        _recent.value = list
        Prefs.recentHosts = RecentHosts.encode(list)
    }

    @Synchronized
    fun forgetHost(link: String) {
        val list = RecentHosts.remove(_recent.value, link)
        _recent.value = list
        Prefs.recentHosts = RecentHosts.encode(list)
    }

    fun setTheme(mode: ThemeMode) {
        Prefs.theme = mode
        _theme.value = mode
    }

    fun setAutoUpdate(enabled: Boolean) {
        Prefs.autoUpdate = enabled
        _autoUpdate.value = enabled
    }
}
