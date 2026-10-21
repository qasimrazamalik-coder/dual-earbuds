package com.dualearbuds.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.AppSettings
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlaybackService

class DualEarbudsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppSettings.init()
        PlaybackController.init()
        createNotificationChannel()
        EarbudsMonitor.init(this)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            PlaybackService.CHANNEL_ID,
            getString(R.string.channel_playback_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_playback_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}
