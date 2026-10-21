package com.dualearbuds.companion.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.dualearbuds.companion.MainActivity
import com.dualearbuds.companion.R
import com.dualearbuds.companion.audio.AudioEngine
import com.dualearbuds.companion.audio.AudioPacket
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.bt.EarbudsState
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.net.ErrorKind
import com.dualearbuds.companion.net.Errors
import com.dualearbuds.companion.net.InfoClient
import com.dualearbuds.companion.net.InfoResult
import com.dualearbuds.companion.net.LinkParser
import com.dualearbuds.companion.net.LinkTarget
import com.dualearbuds.companion.net.Net
import com.dualearbuds.companion.net.ParseResult
import com.dualearbuds.companion.net.StreamConnection
import com.dualearbuds.companion.net.UserError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Foreground media-playback service: receives the laptop stream over a web socket, plays it through
 * [AudioEngine], keeps wake/Wi-Fi locks while playing and shows a media notification with controls.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.dualearbuds.companion.action.START"
        const val ACTION_STOP = "com.dualearbuds.companion.action.STOP"
        const val ACTION_VOLUME_UP = "com.dualearbuds.companion.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.dualearbuds.companion.action.VOLUME_DOWN"
        const val EXTRA_LINK = "link"
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 4210

        private const val CUSTOM_VOL_DOWN = "vol_down"
        private const val CUSTOM_VOL_UP = "vol_up"
        private const val CUSTOM_STOP = "stop"
        private const val VOLUME_STEP = 10
        private const val MAX_BACKOFF_MS = 10_000L
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
    )
    private val engine = AudioEngine()

    private var sessionJob: Job? = null
    private var currentTarget: LinkTarget? = null
    private var inForeground = false

    @Volatile
    private var connection: StreamConnection? = null

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockHighPerf: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequestCompat? = null

    @Volatile
    private var focusMuted = false

    @Volatile
    private var userMuted = false

    @Volatile
    private var mirroring = true

    @Volatile
    private var pcName = ""

    private val packets = AtomicLong()
    private val lost = AtomicLong()

    @Volatile
    private var lastSeq = -1L

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        engine.gain = PlaybackController.volume.value / 100f
        engine.targetMs = PlaybackController.bufferMs.value
        createMediaSession()
        startObservers()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            shutdown(forget = true, finalState = null)
            return START_NOT_STICKY
        }
        if (action == ACTION_VOLUME_UP || action == ACTION_VOLUME_DOWN) {
            PlaybackController.changeVolume(if (action == ACTION_VOLUME_UP) VOLUME_STEP else -VOLUME_STEP)
            if (sessionJob?.isActive != true) {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        // ACTION_START, or a null intent when Android restarts a sticky service.
        val link: String? = intent?.getStringExtra(EXTRA_LINK)
            ?: if (Prefs.wantPlaying) Prefs.lastLink else null
        val parsed = if (link != null) LinkParser.parse(link) else null
        val target: LinkTarget? = (parsed as? ParseResult.Ok)?.target
        if (target == null) {
            // startForegroundService() must still be answered with startForeground() before stopping.
            if (intent != null) startForegroundSafely()
            shutdown(forget = intent != null, finalState = null)
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            shutdown(forget = false, finalState = null)
            return START_NOT_STICKY
        }
        startSession(target)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away from Recents (stopWithTask="false").
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        sessionJob = null
        connection?.close()
        connection = null
        engine.stop()
        releaseLocks()
        abandonFocus()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {
        }
        mediaSession = null
        scope.cancel()
        val s = PlaybackController.state.value
        if (s.running) PlaybackController.set(PlayerState(target = s.target))
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private fun startSession(target: LinkTarget) {
        if (sessionJob?.isActive == true && currentTarget == target) return
        sessionJob?.cancel()
        connection?.close()
        currentTarget = target
        Prefs.wantPlaying = true
        Prefs.lastLink = target.toLink()

        packets.set(0)
        lost.set(0)
        lastSeq = -1L
        mirroring = true
        pcName = ""
        userMuted = false
        applyMute()

        acquireLocks()
        requestFocus()
        mediaSession?.isActive = true
        updateMediaSession()

        engine.targetMs = PlaybackController.bufferMs.value
        engine.gain = PlaybackController.volume.value / 100f
        engine.start()

        PlaybackController.update {
            it.copy(
                status = PlayerStatus.CONNECTING,
                target = target,
                error = null,
                message = "Looking for the laptop at ${target.display}…",
                packets = 0, lost = 0, dropped = 0, underruns = 0, userMuted = false
            )
        }
        sessionJob = scope.launch {
            try {
                runSession(target)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    shutdown(
                        forget = true,
                        finalState = PlayerState(
                            status = PlayerStatus.ERROR,
                            target = target,
                            error = Errors.other(t.message ?: t.javaClass.simpleName),
                            message = "Playback stopped because of an error"
                        )
                    )
                }
            }
        }
    }

    private suspend fun runSession(target: LinkTarget) {
        var backoff = 1000L
        var fatal: UserError? = null
        while (true) {
            PlaybackController.update {
                it.copy(
                    status = if (it.status == PlayerStatus.RECONNECTING) PlayerStatus.RECONNECTING else PlayerStatus.CONNECTING,
                    message = "Looking for the laptop at ${target.display}…"
                )
            }
            val info = withContext(Dispatchers.IO) { InfoClient.fetch(Net.infoHttp, target) }
            if (info is InfoResult.Ok) {
                pcName = info.info.pc
                mirroring = info.info.rate > 0
                PlaybackController.update { it.copy(pcName = pcName, error = null) }
            } else {
                val err = Errors.from(info, applicationContext, target) ?: Errors.other("unknown")
                if (info == InfoResult.WrongKey || info == InfoResult.NotDualEarbuds) {
                    fatal = err
                    break
                }
                waitAndRetry(backoff, err)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }

            val conn = StreamConnection(Net.http, target, Build.MODEL ?: "Android", connectionCallbacks)
            connection = conn
            val end = conn.runUntilClosed()
            if (connection === conn) connection = null
            engine.flush()

            if (end.httpCode == 403) {
                fatal = Errors.wrongKey()
                break
            }
            if (end.opened) backoff = 1000L
            val err = when (end.httpCode) {
                503 -> Errors.serverFull()
                0 -> if (end.opened) {
                    UserError(ErrorKind.OTHER, "Lost the link to ${pcName.ifBlank { "the laptop" }} (${end.reason}).")
                } else {
                    Errors.notFound(applicationContext, target)
                }
                else -> Errors.other("HTTP ${end.httpCode}")
            }
            waitAndRetry(backoff, err)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        val error = fatal
        withContext(Dispatchers.Main) {
            shutdown(
                forget = true,
                finalState = PlayerState(
                    status = PlayerStatus.ERROR,
                    target = target,
                    pcName = pcName,
                    error = error,
                    message = error?.message ?: ""
                )
            )
        }
    }

    private suspend fun waitAndRetry(backoffMs: Long, error: UserError) {
        var remaining = backoffMs
        while (remaining > 0) {
            val seconds = ((remaining + 999) / 1000)
            PlaybackController.update {
                it.copy(
                    status = PlayerStatus.RECONNECTING,
                    error = error,
                    message = "Retrying in ${seconds}s…"
                )
            }
            val step = remaining.coerceAtMost(1000L)
            delay(step)
            remaining -= step
        }
    }

    private val connectionCallbacks = object : StreamConnection.Callbacks {
        override fun onOpened() {
            lastSeq = -1L
            PlaybackController.update {
                it.copy(
                    status = if (mirroring) PlayerStatus.BUFFERING else PlayerStatus.LAPTOP_PAUSED,
                    error = null,
                    message = "Connected"
                )
            }
            sendStatus()
        }

        override fun onHello(pc: String, rate: Int) {
            if (pc.isNotBlank()) pcName = pc
            mirroring = rate > 0
            PlaybackController.update { it.copy(pcName = pcName) }
        }

        override fun onMirroring(mirroring: Boolean, rate: Int) {
            this@PlaybackService.mirroring = mirroring
            if (!mirroring) engine.flush()
        }

        override fun onPacket(rate: Int, seq: Long, samples: ShortArray) {
            if (rate !in 8000..192000) return
            val prev = lastSeq
            if (prev >= 0) {
                val expected = (prev + 1) and 0xFFFFFFFFL
                if (seq != expected) {
                    val gap = (seq - expected) and 0xFFFFFFFFL
                    if (gap in 1L..5000L) lost.addAndGet(gap)
                }
            }
            lastSeq = seq
            packets.incrementAndGet()
            mirroring = true
            engine.push(AudioPacket(rate, samples))
        }
    }

    // ---------------------------------------------------------------- observers

    private fun startObservers() {
        // Settings -> engine
        scope.launch {
            PlaybackController.volume.collect { v -> engine.gain = v / 100f }
        }
        scope.launch {
            PlaybackController.bufferMs.collect { ms -> engine.targetMs = ms }
        }

        // Live stats + derived playing state, twice a second.
        scope.launch {
            while (isActive) {
                delay(500)
                val conn = connection
                if (sessionJob?.isActive != true) continue
                val s = engine.stats()
                val open = conn?.isOpen == true
                PlaybackController.update { st ->
                    val status = when {
                        !open -> st.status
                        !mirroring -> PlayerStatus.LAPTOP_PAUSED
                        s.playing -> PlayerStatus.PLAYING
                        else -> PlayerStatus.BUFFERING
                    }
                    st.copy(
                        status = status,
                        packets = packets.get(),
                        lost = lost.get(),
                        dropped = s.dropped,
                        underruns = s.underruns,
                        bufferMs = s.bufferMs,
                        latencyMs = if (s.rate > 0) s.bufferMs + s.trackMs else 0,
                        rate = s.rate,
                        message = if (open) "Connected" else st.message,
                        userMuted = userMuted
                    )
                }
            }
        }

        // Status to the laptop every 5 s…
        scope.launch {
            while (isActive) {
                delay(5000)
                sendStatus()
            }
        }
        // …and whenever something in it changes.
        scope.launch {
            combine(
                EarbudsMonitor.state,
                PlaybackController.volume,
                PlaybackController.bufferMs,
                PlaybackController.state.map { it.status == PlayerStatus.PLAYING && !it.userMuted }.distinctUntilChanged()
            ) { earbuds, volume, buffer, playing ->
                StatusKey(earbuds.name, earbuds.battery, volume, buffer, playing)
            }.distinctUntilChanged().collect { sendStatus() }
        }

        // Notification + lock-screen metadata.
        scope.launch {
            combine(
                PlaybackController.state.map { NotifKey(it.status, it.pcName, it.userMuted) }.distinctUntilChanged(),
                EarbudsMonitor.state,
                PlaybackController.volume,
                PlaybackController.bufferMs
            ) { key, earbuds, volume, buffer ->
                NotifContent(key, earbuds, volume, buffer)
            }.distinctUntilChanged().collect {
                withContext(Dispatchers.Main) {
                    if (inForeground) {
                        updateMediaSession()
                        postNotification()
                    }
                }
            }
        }
    }

    private data class StatusKey(val name: String, val battery: Int, val volume: Int, val buffer: Int, val playing: Boolean)
    private data class NotifKey(val status: PlayerStatus, val pc: String, val muted: Boolean)
    private data class NotifContent(val key: NotifKey, val earbuds: EarbudsState, val volume: Int, val buffer: Int)

    private fun sendStatus() {
        val conn = connection ?: return
        if (!conn.isOpen) return
        try {
            val earbuds = EarbudsMonitor.state.value
            val st = PlaybackController.state.value
            val json = JSONObject()
                .put("type", "status")
                .put("device", Build.MODEL ?: "Android")
                .put("earbuds", if (earbuds.connected) earbuds.name else "")
                .put("battery", if (earbuds.connected) earbuds.battery else -1)
                .put("volume", PlaybackController.volume.value)
                .put("bufferMs", PlaybackController.bufferMs.value)
                .put("playing", st.status == PlayerStatus.PLAYING && !userMuted)
            conn.send(json.toString())
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- stop

    /** Stops everything. [forget] = don't auto-restart (user pressed Stop or a fatal error). */
    private fun shutdown(forget: Boolean, finalState: PlayerState?) {
        if (forget) Prefs.wantPlaying = false
        sessionJob?.cancel()
        sessionJob = null
        connection?.close()
        connection = null
        currentTarget = null
        engine.stop()
        releaseLocks()
        abandonFocus()
        try {
            mediaSession?.isActive = false
        } catch (_: Throwable) {
        }
        val target = PlaybackController.state.value.target
        PlaybackController.set(finalState ?: PlayerState(target = target))
        if (inForeground) {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            inForeground = false
        }
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try {
            stopSelf()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- foreground + notification

    private fun startForegroundSafely(): Boolean {
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            inForeground = true
            true
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException when Android restarts us from the background.
            inForeground = false
            false
        }
    }

    private fun postNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun notificationTitle(): String {
        val st = PlaybackController.state.value
        val pc = st.pcName.ifBlank { "laptop" }
        val base = when (st.status) {
            PlayerStatus.CONNECTING -> "Connecting to $pc…"
            PlayerStatus.RECONNECTING -> "Reconnecting to $pc…"
            PlayerStatus.LAPTOP_PAUSED -> "Connected to $pc (no sound shared)"
            PlayerStatus.BUFFERING, PlayerStatus.PLAYING -> if (st.userMuted) "Muted · $pc" else "Playing from $pc"
            PlayerStatus.IDLE, PlayerStatus.ERROR -> "Dual Earbuds"
        }
        return base + earbudsSuffix(EarbudsMonitor.state.value)
    }

    private fun earbudsSuffix(e: EarbudsState): String {
        if (!e.connected) return ""
        return if (e.battery in 0..100) " · ${e.name} ${e.battery}%" else " · ${e.name}"
    }

    private fun notificationText(): String {
        val v = PlaybackController.volume.value
        val b = PlaybackController.bufferMs.value
        val e = EarbudsMonitor.state.value
        val buds = if (e.connected) "" else "No Bluetooth earbuds on this phone · "
        return "${buds}Volume $v% · buffer $b ms"
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = MediaNotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.let { style.setMediaSession(it.sessionToken) }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_earbuds)
            .setContentTitle(notificationTitle())
            .setContentText(notificationText())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(0xFF14E0C2.toInt())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_volume_down, "Volume -", servicePendingIntent(ACTION_VOLUME_DOWN, 1))
            .addAction(R.drawable.ic_stop, "Stop", servicePendingIntent(ACTION_STOP, 2))
            .addAction(R.drawable.ic_volume_up, "Volume +", servicePendingIntent(ACTION_VOLUME_UP, 3))
            .setStyle(style)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ---------------------------------------------------------------- media session

    private fun createMediaSession() {
        try {
            val session = MediaSessionCompat(this, "DualEarbuds")
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    userMuted = false
                    applyMute()
                    PlaybackController.update { it.copy(userMuted = false) }
                    updateMediaSession()
                }

                override fun onPause() {
                    // Earbud / lock-screen pause = mute; the link stays up so Play resumes instantly.
                    userMuted = true
                    applyMute()
                    PlaybackController.update { it.copy(userMuted = true) }
                    updateMediaSession()
                }

                override fun onStop() {
                    shutdown(forget = true, finalState = null)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        CUSTOM_VOL_DOWN -> PlaybackController.changeVolume(-VOLUME_STEP)
                        CUSTOM_VOL_UP -> PlaybackController.changeVolume(VOLUME_STEP)
                        CUSTOM_STOP -> shutdown(forget = true, finalState = null)
                    }
                }
            })
            val activityIntent = PendingIntent.getActivity(
                this,
                10,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.setSessionActivity(activityIntent)
            mediaSession = session
            updateMediaSession()
        } catch (_: Throwable) {
            mediaSession = null
        }
    }

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        try {
            val st = PlaybackController.state.value
            val state = if (userMuted) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
            val playback = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(CUSTOM_VOL_DOWN, "Volume -", R.drawable.ic_volume_down).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(CUSTOM_VOL_UP, "Volume +", R.drawable.ic_volume_up).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.ic_stop).build()
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
            session.setPlaybackState(playback)

            val e = EarbudsMonitor.state.value
            val subtitle = if (e.connected) {
                if (e.battery in 0..100) "${e.name} · ${e.battery}%" else e.name
            } else {
                "Dual Earbuds"
            }
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Laptop sound · ${st.pcName.ifBlank { "laptop" }}")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Volume ${PlaybackController.volume.value}%")
                .build()
            session.setMetadata(metadata)
        } catch (_: Throwable) {
        }
    }

    private fun applyMute() {
        engine.muted = userMuted || focusMuted
    }

    // ---------------------------------------------------------------- audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call / navigation prompt: go quiet until it's over.
                focusMuted = true
                applyMute()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusMuted = false
                applyMute()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another media app started. The user explicitly chose to mirror, so keep playing.
                focusMuted = false
                applyMute()
            }
            else -> Unit // LOSS_TRANSIENT_CAN_DUCK: the system ducks for us.
        }
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            AudioManagerCompat.requestAudioFocus(am, request)
            focusRequest = request
            focusMuted = false
            applyMute()
        } catch (_: Throwable) {
        }
    }

    private fun abandonFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(am, it) }
        } catch (_: Throwable) {
        }
        focusRequest = null
        focusMuted = false
    }

    // ---------------------------------------------------------------- locks

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualEarbuds:playback").apply {
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
                wifiLock = wm.createWifiLock(mode, "DualEarbuds:stream").apply { setReferenceCounted(false) }
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
            // LOW_LATENCY only applies while the screen is on; on many OEM builds HIGH_PERF still
            // keeps Wi-Fi power-save off with the screen off, so hold it as well on API 29+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (wifiLockHighPerf == null) {
                    wifiLockHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DualEarbuds:stream-hp")
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
