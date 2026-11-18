package com.dualearbuds.companion.assistant

import android.content.Context
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.service.PlayerStatus
import com.dualearbuds.companion.setup.SetupFactsReader
import com.dualearbuds.host.HostController
import com.dualearbuds.host.HostStatus
import com.dualearbuds.host.model.Readout

/** Loads the bundled help (assets/help, copied from docs/help at build time). Offline; cached per process. */
object HelpLibrary {
    @Volatile
    private var cached: Assistant? = null

    fun get(context: Context): Assistant {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = load(context)
            cached = loaded
            return loaded
        }
    }

    private fun load(context: Context): Assistant {
        val assets = context.applicationContext.assets
        fun read(name: String): String? = try {
            assets.open("help/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        }
        val names = read("index.txt")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: try { assets.list("help")?.toList() } catch (_: Throwable) { null }
            ?: emptyList()
        val files = LinkedHashMap<String, String>()
        names.filter { it.endsWith(".md") }.forEach { n -> read(n)?.let { files[n] = it } }
        return Assistant.fromFiles(files, read("_synonyms.txt"))
    }
}

/** Reads the live facts for the Ask screen from the player, the device and (while sharing) the host module. */
object AskFacts {
    fun read(context: Context): List<LiveFact> {
        val p = PlaybackController.state.value
        val setup = try { SetupFactsReader.read(context) } catch (_: Throwable) { null }
        val buds = setup?.earbudsName
        val connected = p.status == PlayerStatus.PLAYING || p.status == PlayerStatus.BUFFERING ||
            p.status == PlayerStatus.LAPTOP_PAUSED
        val status = when (p.status) {
            PlayerStatus.IDLE -> "Not connected"
            PlayerStatus.CONNECTING -> "Connecting"
            PlayerStatus.BUFFERING -> "Buffering"
            PlayerStatus.PLAYING -> "Playing"
            PlayerStatus.LAPTOP_PAUSED -> "Connected, no sound shared"
            PlayerStatus.RECONNECTING -> "Reconnecting"
            PlayerStatus.ERROR -> "Stopped after an error"
        }
        val listen = ListenInputs(
            status = status,
            connected = connected,
            hostName = p.pcName,
            hostNoun = p.hostKind.noun,
            priority = p.priority?.wire,
            playoutMs = p.playoutMs,
            needMs = p.needMs,
            synced = p.synced,
            syncErrMs = p.syncErrorMs,
            transport = p.transport,
            earbudsName = buds,
            calibratedMs = buds?.let { SoundSettings.latencyFor(it) },
            powerSave = setup?.powerSave ?: p.powerSave,
            backgroundRestricted = setup?.backgroundRestricted ?: p.backgroundRestricted,
            onWifi = setup?.onWifi,
            wifiMhz = setup?.wifiFrequencyMhz ?: -1,
            lastError = p.error?.message,
            bufferMs = p.bufferMs,
            underruns = p.underruns,
            lost = p.lost,
            packets = p.packets
        )
        val h = HostController.state.value
        val share = if (h.status == HostStatus.SHARING) {
            ShareInputs(
                sharing = true,
                playoutMs = h.playoutMs,
                priority = h.prefs.syncPriority.wire,
                listeners = h.listeners.map {
                    ShareListener(it.displayName, it.needMs, it.syncErrMs, Readout.transport(it), it.battery)
                },
                capturedPerSec = h.stats.capturedPerSec,
                peakDb = h.stats.peakDb,
                stalled = h.stalled || h.stats.stalled,
                sentPerSec = h.stats.sentPerSec,
                localOutputName = h.localOutput?.name,
                localNeedMs = h.localOutput?.needMs,
                error = h.error
            )
        } else null
        return LiveFactsBuilder.build(listen, share)
    }
}
