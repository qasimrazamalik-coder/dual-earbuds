package com.dualearbuds.host

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.dualearbuds.host.server.AudioPacket
import com.dualearbuds.host.server.HostIdentity
import com.dualearbuds.host.server.HostServer

/**
 * Foreground service (type mediaProjection) for "Share this phone": captures other apps' sound with
 * AudioPlaybackCapture (Android 10+) and serves it to listeners with [HostServer] (protocol v3).
 * The phone keeps playing its own sound normally; capture only copies it.
 */
class HostService : Service() {

    companion object {
        const val ACTION_START = "com.dualearbuds.host.action.START"
        const val ACTION_STOP = "com.dualearbuds.host.action.STOP"
        const val EXTRA_RESULT_CODE = "com.dualearbuds.host.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.dualearbuds.host.extra.RESULT_DATA"
        const val CHANNEL_ID = "host_sharing"
        const val NOTIFICATION_ID = 4310
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_PORT = 8790
        private const val SILENCE_HINT_MS = 5_000L
        private const val SILENCE_LEVEL = 4 // |sample| at or below this counts as silence (about -78 dBFS)
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var server: HostServer? = null
    private var inForeground = false

    @Volatile
    private var capturing = false

    @Volatile
    private var lastListenerCount = -1

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockHighPerf: WifiManager.WifiLock? = null

    private val page: ByteArray? by lazy {
        try {
            assets.open("web-player.html").use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService() must be answered with startForeground() first, and on
                // Android 14+ the mediaProjection type must be active before getMediaProjection().
                if (!startForegroundSafely()) {
                    stopEverything("Android did not allow sharing to start. Open the app and try again.")
                    return START_NOT_STICKY
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    stopEverything("Sharing this phone's sound needs Android 10 or newer.")
                    return START_NOT_STICKY
                }
                if (server != null) return START_NOT_STICKY // already sharing
                val start = requireNotNull(intent)
                val code = start.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(start, EXTRA_RESULT_DATA, Intent::class.java)
                if (data == null) {
                    stopEverything("Sharing was not allowed.")
                    return START_NOT_STICKY
                }
                val error = startSharing(code, data)
                if (error != null) stopEverything(error)
            }
            ACTION_STOP -> stopEverything(null)
            else -> {
                // Restarted without a projection token: it cannot be resumed, so clean up.
                if (startForegroundSafely()) stopEverything(null) else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        HostController.reportStopped(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sharing

    /** Returns null on success, otherwise a message for the user. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSharing(resultCode: Int, data: Intent): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Allow the audio permission so this phone can share other apps' sound."
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
            ?: return "Sharing is not available on this phone."
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (_: Throwable) {
            null
        } ?: return "Sharing was not allowed. Tap Start and choose to share the entire screen."
        projection = proj
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // The user revoked sharing (status-bar chip / lock screen on newer Android).
                main.post { stopEverything(null) }
            }
        }
        projectionCallback = cb
        try {
            proj.registerCallback(cb, main)
        } catch (_: Throwable) {
        }

        val rec = createRecord(proj) ?: return "Could not capture sound on this phone."
        record = rec

        val srv = HostServer(
            identity = HostIdentity(pcName = deviceName(), version = "3.0.0", host = "android"),
            initialKey = HostController.linkKey(this),
            pageProvider = { page }
        )
        srv.setParty(HostController.party(this))
        srv.onChange = { onServerChanged(srv) }
        val port = try {
            srv.start(DEFAULT_PORT)
        } catch (t: Throwable) {
            return "Could not open a network port: ${t.message ?: "unknown error"}"
        }
        server = srv
        HostController.server = srv
        srv.setSharing(SAMPLE_RATE)

        try {
            rec.startRecording()
        } catch (_: Throwable) {
            return "Could not start capturing sound."
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return "Another app is using sound capture. Close it and try again."
        }
        capturing = true
        captureThread = Thread({ captureLoop(rec, srv) }, "host-capture").apply { start() }

        acquireLocks()
        HostController.refreshNetwork(this)
        HostController.reportSharing(port, srv.key)
        onServerChanged(srv)
        return null
    }

    @SuppressLint("MissingPermission") // checked in startSharing()
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createRecord(proj: MediaProjection): AudioRecord? {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val packet = AudioPacket.pcmBytes(SAMPLE_RATE)
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, packet * 8))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                null
            } else {
                rec
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureLoop(rec: AudioRecord, srv: HostServer) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val bytes = AudioPacket.pcmBytes(SAMPLE_RATE) // 10 ms = 1920 bytes
        val buf = ByteArray(bytes)
        var lastSound = SystemClock.elapsedRealtime()
        var silentShown = false
        try {
            while (capturing) {
                var off = 0
                while (off < bytes && capturing) {
                    val r = rec.read(buf, off, bytes - off, AudioRecord.READ_BLOCKING)
                    if (r < 0) throw IllegalStateException("AudioRecord.read error $r")
                    if (r == 0) SystemClock.sleep(2)
                    off += r
                }
                if (!capturing) break
                srv.sendAudio(buf, 0, bytes)

                val now = SystemClock.elapsedRealtime()
                if (isSilent(buf, bytes)) {
                    if (!silentShown && now - lastSound >= SILENCE_HINT_MS) {
                        silentShown = true
                        HostController.reportSilent(true)
                    }
                } else {
                    lastSound = now
                    if (silentShown) {
                        silentShown = false
                        HostController.reportSilent(false)
                    }
                }
            }
        } catch (_: Throwable) {
            if (capturing) main.post { stopEverything("Sound capture stopped unexpectedly.") }
        }
    }

    private fun isSilent(buf: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            if (s > SILENCE_LEVEL || s < -SILENCE_LEVEL) return false
            i += 2
        }
        return true
    }

    private fun onServerChanged(srv: HostServer) {
        val list = srv.listeners()
        HostController.reportListeners(list)
        if (list.size != lastListenerCount) {
            lastListenerCount = list.size
            main.post { postNotification() }
        }
    }

    /** Stops capture and the server and removes the service. Safe to call more than once. */
    private fun stopEverything(error: String?) {
        teardown()
        HostController.reportStopped(error)
        if (inForeground) {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            inForeground = false
        }
        stopSelf()
    }

    private fun teardown() {
        capturing = false
        val rec = record
        record = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
        }
        try {
            captureThread?.join(500)
        } catch (_: InterruptedException) {
        }
        captureThread = null
        if (rec != null) {
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }
        val srv = server
        server = null
        if (srv != null) {
            if (HostController.server === srv) HostController.server = null
            srv.onChange = null
            try {
                srv.setSharing(0)
                srv.stop()
            } catch (_: Throwable) {
            }
        }
        val proj = projection
        projection = null
        if (proj != null) {
            try {
                projectionCallback?.let { proj.unregisterCallback(it) }
            } catch (_: Throwable) {
            }
            try {
                proj.stop()
            } catch (_: Throwable) {
            }
        }
        projectionCallback = null
        lastListenerCount = -1
        releaseLocks()
    }

    private fun deviceName(): String {
        val fromSettings = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Throwable) {
            null
        }
        if (!fromSettings.isNullOrBlank()) return fromSettings
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.host_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.host_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundSafely(): Boolean {
        if (inForeground) return true
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            inForeground = true
            true
        } catch (_: Throwable) {
            inForeground = false
            false
        }
    }

    private fun postNotification() {
        if (!inForeground || server == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val count = server?.listenerCount() ?: 0
        val text = when (count) {
            0 -> "Waiting for listeners to join"
            1 -> "1 listener"
            else -> "$count listeners"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.host_ic_stat_share)
            .setContentTitle("Sharing this phone's sound")
            .setContentText(text)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(0xFF14E0C2.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.host_ic_stop, "Stop", stopIntent)
            .build()
    }

    // ---------------------------------------------------------------- locks

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualEarbuds:host").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "DualEarbuds:host").apply { setReferenceCounted(false) }
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (wifiLockHighPerf == null) {
                    wifiLockHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DualEarbuds:host-hp")
                        .apply { setReferenceCounted(false) }
                }
                wifiLockHighPerf?.let { if (!it.isHeld) it.acquire() }
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLockHighPerf?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
    }
}
