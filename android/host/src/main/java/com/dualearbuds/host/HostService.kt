package com.dualearbuds.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.dualearbuds.host.audio.MicRing
import com.dualearbuds.host.audio.NoiseCancel
import com.dualearbuds.host.audio.Packetizer
import com.dualearbuds.host.audio.SpectralDenoiser
import com.dualearbuds.host.audio.TalkMixer
import com.dualearbuds.host.capture.AppCatalog
import com.dualearbuds.host.capture.MicCapture
import com.dualearbuds.host.capture.PlaybackCapture
import com.dualearbuds.host.net.Discovery
import com.dualearbuds.host.net.NsdAdvertiser
import com.dualearbuds.host.remote.MediaRemote
import com.dualearbuds.host.server.HostIdentity
import com.dualearbuds.host.server.HostServer

/**
 * Foreground service (type mediaProjection, plus microphone for talk-over) for "Share this phone".
 * Owns the capture ([PlaybackCapture], Android 10+), the talk-over mic, the [HostServer]
 * (protocol v3 + v3.1), the mDNS advertisement and media-key remote control.
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
        private const val VERSION = "3.1.0"
        private const val SILENCE_HINT_MS = 5_000L
        private const val SILENCE_LEVEL = 4 // |sample| at or below this counts as silence (about -78 dBFS)
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var server: HostServer? = null
    private var nsd: NsdAdvertiser? = null
    private var remote: MediaRemote? = null
    private var inForeground = false
    private val locks by lazy { HostLocks(this) }
    private val name by lazy { deviceName() }

    // Talk-over: mic → ring → denoiser → mixer (on the capture thread).
    private val micRing = MicRing(PlaybackCapture.SAMPLE_RATE / 10) // 100 ms
    private val mic = MicCapture(micRing)
    private val mixer = TalkMixer(PlaybackCapture.SAMPLE_RATE)
    private val denoiser = SpectralDenoiser()

    @Volatile private var talking = false
    @Volatile private var noiseCancel = NoiseCancel.DEFAULT
    @Volatile private var resetDenoiser = false
    @Volatile private var capturing = false
    @Volatile private var lastListenerCount = -1

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

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Returns null on success, otherwise a message for the user. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSharing(resultCode: Int, data: Intent): String? {
        if (!hasMicPermission()) return "Allow the audio permission so this phone can share other apps' sound."
        val prefs = HostController.prefs(this)
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

        val srv = HostServer(
            identity = HostIdentity(pcName = name, version = VERSION, host = "android"),
            initialKey = prefs.key,
            pageProvider = { page }
        )
        val rem = MediaRemote(this)
        remote = rem
        srv.onRemote = { action -> rem.perform(action) }
        srv.onChange = { onServerChanged(srv) }
        applyToServer(srv, prefs)
        val port = try {
            srv.start(prefs.port)
        } catch (t: Throwable) {
            return "Could not open a network port: ${t.message ?: "unknown error"}"
        }
        server = srv
        noiseCancel = prefs.noiseCancel

        startCapture(proj, prefs.app)?.let { return it }

        locks.acquire()
        nsd = NsdAdvertiser(this)
        HostController.engine = engine
        HostController.refreshNetwork(this)
        HostController.reportSharing(port)
        engine.applyDiscovery()
        onServerChanged(srv)
        return null
    }

    private fun applyToServer(srv: HostServer, prefs: HostPrefs) {
        srv.setParty(prefs.party)
        srv.setPin(prefs.activePin)
        srv.setRemoteAllowed(prefs.remote)
    }

    /** Creates the AudioRecord for [app] (null = all apps) and starts the capture thread. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture(proj: MediaProjection, app: AppChoice?): String? {
        val srv = server ?: return "Not sharing."
        var uid: Int? = null
        var label: String? = null
        if (app != null) {
            uid = AppCatalog.uidOf(this, app.packageName)
            if (uid == null) {
                HostController.setSource(this, null) // the app was uninstalled: share all apps
                HostController.reportError("${app.label} is not installed any more, so all apps are shared.")
            } else {
                label = app.label
            }
        }
        val rec = PlaybackCapture.create(proj, uid) ?: return "Could not capture sound on this phone."
        try {
            rec.startRecording()
        } catch (_: Throwable) {
            rec.release()
            return "Could not start capturing sound."
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return "Another app is using sound capture. Close it and try again."
        }
        record = rec
        srv.setSource(label)
        srv.setSharing(PlaybackCapture.SAMPLE_RATE)
        capturing = true
        captureThread = Thread({ captureLoop(rec, srv) }, "host-capture").apply { start() }
        return null
    }

    private fun stopCapture() {
        capturing = false
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        try {
            captureThread?.join(500)
        } catch (_: InterruptedException) {
        }
        captureThread = null
        try {
            rec?.release()
        } catch (_: Throwable) {
        }
    }

    private fun captureLoop(rec: AudioRecord, srv: HostServer) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val micBuf = FloatArray(SpectralDenoiser.HOP)
        var lastSound = SystemClock.elapsedRealtime()
        var silentShown = false
        val packetizer = Packetizer(PlaybackCapture.SAMPLE_RATE) { pkt, capturedAt ->
            val frames = pkt.size / 4
            val talk = talking
            if (talk || mixer.active) {
                if (talk) {
                    if (resetDenoiser) {
                        resetDenoiser = false
                        denoiser.reset()
                    }
                    micRing.read(micBuf, frames)
                    denoiser.process(micBuf, noiseCancel)
                } else {
                    micBuf.fill(0f)
                }
                mixer.process(pkt, frames, micBuf, talk)
            }
            srv.sendAudio(pkt, 0, pkt.size, capturedAt)

            val now = SystemClock.elapsedRealtime()
            if (isSilent(pkt)) {
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
        val buf = ByteArray(packetizer.packetBytes)
        try {
            while (capturing) {
                val r = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (r < 0) throw IllegalStateException("AudioRecord.read error $r")
                if (r == 0) {
                    SystemClock.sleep(2)
                    continue
                }
                if (!capturing) break
                packetizer.write(buf, 0, r, srv.hostMs())
            }
        } catch (_: Throwable) {
            if (capturing) main.post { stopEverything("Sound capture stopped unexpectedly.") }
        }
    }

    private fun isSilent(buf: ByteArray): Boolean {
        var i = 0
        while (i + 1 < buf.size) {
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

    // ---------------------------------------------------------------- HostEngine (main thread)

    /** Live setting changes from [HostController]; kept private so the service's public API stays small. */
    private val engine = object : HostEngine {
        override fun applyPrefs(prefs: HostPrefs, sourceChanged: Boolean) {
            main.post {
                val srv = server ?: return@post
                applyToServer(srv, prefs)
                noiseCancel = prefs.noiseCancel
                if (sourceChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val proj = projection ?: return@post
                    stopCapture()
                    startCapture(proj, prefs.app)?.let { stopEverything(it) }
                }
                applyDiscovery()
            }
        }

        override fun applyTalk(on: Boolean) {
            main.post {
                val srv = server ?: return@post
                if (on) {
                    if (!hasMicPermission()) {
                        HostController.reportTalking(false)
                        HostController.reportError("Allow the microphone to talk to your listeners.")
                        return@post
                    }
                    if (!mic.start()) {
                        HostController.reportTalking(false)
                        HostController.reportError("The microphone is busy (a call or another app?). Try again later.")
                        return@post
                    }
                    resetDenoiser = true
                    talking = true
                    srv.setTalking(true)
                } else {
                    talking = false
                    mic.stop()
                    srv.setTalking(false)
                }
                postNotification()
            }
        }

        override fun applyDiscovery() {
            main.post {
                val srv = server ?: return@post
                val advertiser = nsd ?: return@post
                val st = HostController.state.value
                val txt = Discovery.txt(name, srv.key, pin = srv.pin != null, show = st.showOnNetwork)
                advertiser.update(NsdAdvertiser.Ad(Discovery.instanceName(name), srv.port, txt))
            }
        }

        override fun newKey(key: String) {
            main.post {
                server?.setKey(key)
                applyDiscovery()
            }
        }

        override fun setVolume(id: Int, volume: Int) {
            server?.setVolume(id, volume)
        }

        override fun kick(id: Int) {
            server?.kick(id)
        }
    }

    // ---------------------------------------------------------------- stop

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
        if (HostController.engine === engine) HostController.engine = null
        talking = false
        mic.stop()
        stopCapture()
        nsd?.update(null)
        nsd = null
        val srv = server
        server = null
        if (srv != null) {
            srv.onChange = null
            srv.onRemote = null
            try {
                srv.setSharing(0)
                srv.stop()
            } catch (_: Throwable) {
            }
        }
        remote = null
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
        locks.release()
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
        val projectionType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        // Also the microphone type, so talk-over keeps working with the screen off. It needs
        // RECORD_AUDIO (which capture needs anyway); if Android refuses it, start without it.
        val withMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission()) {
            projectionType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            projectionType
        }
        for (type in if (withMic != projectionType) listOf(withMic, projectionType) else listOf(projectionType)) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
                inForeground = true
                return true
            } catch (_: Throwable) {
            }
        }
        inForeground = false
        return false
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
        var text = when (count) {
            0 -> "Waiting for listeners to join"
            1 -> "1 listener"
            else -> "$count listeners"
        }
        if (talking) text += " · Talk-over on"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.host_ic_stat_share)
            .setContentTitle("Sharing this phone's sound")
            .setContentText(text)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.host_ic_stop, "Stop", stopIntent)
            .build()
    }
}
