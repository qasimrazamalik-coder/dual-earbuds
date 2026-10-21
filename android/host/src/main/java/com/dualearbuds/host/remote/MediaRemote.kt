package com.dualearbuds.host.remote

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.dualearbuds.host.server.RemoteAction

/** Presses media keys on this phone for listeners (setting "Let listeners control playback"). */
internal class MediaRemote(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun perform(action: RemoteAction) {
        val am = audio ?: return
        try {
            when (action) {
                RemoteAction.PLAY_PAUSE -> press(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                RemoteAction.NEXT -> press(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                RemoteAction.PREV -> press(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                // The host phone's own media volume (whether the captured copy follows it depends on the phone).
                RemoteAction.VOL_UP -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                RemoteAction.VOL_DOWN -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun press(am: AudioManager, code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
