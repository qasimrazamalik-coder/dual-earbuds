package com.dualearbuds.companion.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.net.ErrorKind
import com.dualearbuds.companion.net.LinkTarget
import com.dualearbuds.companion.net.UserError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlayerStatus { IDLE, CONNECTING, BUFFERING, PLAYING, LAPTOP_PAUSED, RECONNECTING, ERROR }

data class PlayerState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val target: LinkTarget? = null,
    val pcName: String = "",
    val message: String = "",
    val error: UserError? = null,
    val packets: Long = 0,
    val lost: Long = 0,
    val dropped: Long = 0,
    val underruns: Long = 0,
    val bufferMs: Int = 0,
    val latencyMs: Int = 0,
    val rate: Int = 0,
    val userMuted: Boolean = false
) {
    /** True while the service is (trying to be) playing — i.e. between Start and Stop. */
    val running: Boolean
        get() = status != PlayerStatus.IDLE && status != PlayerStatus.ERROR
}

/** Single source of truth shared by the UI and [PlaybackService]. */
object PlaybackController {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(Prefs.DEFAULT_VOLUME)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _bufferMs = MutableStateFlow(Prefs.DEFAULT_BUFFER_MS)
    val bufferMs: StateFlow<Int> = _bufferMs.asStateFlow()

    fun init() {
        _volume.value = Prefs.volume
        _bufferMs.value = Prefs.bufferMs
    }

    internal fun update(transform: (PlayerState) -> PlayerState) = _state.update(transform)

    internal fun set(state: PlayerState) {
        _state.value = state
    }

    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, Prefs.MAX_VOLUME)
        if (_volume.value != v) {
            _volume.value = v
            Prefs.volume = v
        }
    }

    fun changeVolume(delta: Int) = setVolume(_volume.value + delta)

    fun setBufferMs(ms: Int) {
        val v = ms.coerceIn(Prefs.MIN_BUFFER_MS, Prefs.MAX_BUFFER_MS)
        if (_bufferMs.value != v) {
            _bufferMs.value = v
            Prefs.bufferMs = v
        }
    }

    /** Starts (or switches) playback from the given laptop. Call from the foreground UI. */
    fun start(context: Context, target: LinkTarget) {
        Prefs.lastLink = target.toLink()
        Prefs.wantPlaying = true
        _state.update {
            it.copy(
                status = PlayerStatus.CONNECTING,
                target = target,
                message = "Looking for the laptop at ${target.display}…",
                error = null
            )
        }
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_START)
            .putExtra(PlaybackService.EXTRA_LINK, target.toLink())
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            Prefs.wantPlaying = false
            _state.value = PlayerState(
                status = PlayerStatus.ERROR,
                target = target,
                error = UserError(ErrorKind.OTHER, "Android didn't allow the player to start: ${t.message ?: t.javaClass.simpleName}"),
                message = "Couldn't start the player"
            )
        }
    }

    fun stop(context: Context) {
        Prefs.wantPlaying = false
        val intent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)
        try {
            context.startService(intent)
        } catch (_: Throwable) {
            _state.update { PlayerState(target = it.target) }
        }
    }

    /** Clears a shown error (e.g. when the user edits the link). */
    fun clearError() {
        _state.update { if (it.status == PlayerStatus.ERROR) PlayerState(target = it.target) else it }
    }
}
