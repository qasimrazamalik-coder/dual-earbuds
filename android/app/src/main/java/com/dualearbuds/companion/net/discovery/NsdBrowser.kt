package com.dualearbuds.companion.net.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.dualearbuds.companion.diag.AppLog
import com.dualearbuds.companion.net.LinkTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Direct-join target of a discovered host (null when the host didn't publish its key). */
fun DiscoveredHost.target(): LinkTarget? = key?.let { LinkTarget(address, port, it) }

/**
 * Browses `_dualearbuds._tcp` with [NsdManager] while the Home screen is visible (docs/protocol.md v3.1).
 * NsdManager resolves one service at a time, so found services go through a [ResolveQueue]; every callback is
 * moved to the main thread. Needs no runtime permission.
 */
object NsdBrowser {
    private const val SERVICE_TYPE = "_dualearbuds._tcp."

    private val main = Handler(Looper.getMainLooper())
    private val queue = ResolveQueue()
    private val found = LinkedHashMap<String, DiscoveredHost>()
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var users = 0

    /** Starts browsing (reference-counted; pair with [stop]). */
    fun start(context: Context) {
        main.post {
            users++
            if (listener != null) return@post
            val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@post
            nsd = manager
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    main.post { _searching.value = true }
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { queue.offer(info.serviceName)?.let { resolve(it) } }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        queue.remove(info.serviceName)
                        if (found.remove(info.serviceName) != null) publish()
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    main.post { _searching.value = false }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLog.w("NSD discovery failed ($errorCode)")
                    main.post {
                        _searching.value = false
                        listener = null
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            try {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
                listener = l
            } catch (t: Throwable) {
                AppLog.w("NSD unavailable", t)
            }
        }
    }

    fun stop() {
        main.post {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return@post
            val l = listener ?: return@post
            try {
                nsd?.stopServiceDiscovery(l)
            } catch (_: Throwable) {
            }
            listener = null
            queue.clear()
            found.clear()
            publish()
            _searching.value = false
        }
    }

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34 but is the only API down to 26.
    private fun resolve(serviceName: String) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
        }
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        val next = queue.done()
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            // Someone else's resolve was running: try this one again later.
                            main.postDelayed({ queue.offer(serviceName)?.let { resolve(it) } }, 400)
                        }
                        next?.let { resolve(it) }
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val address = serviceInfo.host?.hostAddress ?: ""
                    val txt = serviceInfo.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
                    val host = DiscoveredHost.fromTxt(serviceInfo.serviceName, address, serviceInfo.port, txt)
                    main.post {
                        if (host != null) {
                            found[host.serviceName] = host
                            publish()
                        }
                        queue.done()?.let { resolve(it) }
                    }
                }
            })
        } catch (t: Throwable) {
            AppLog.w("NSD resolve failed", t)
            main.post { queue.done()?.let { resolve(it) } }
        }
    }

    private fun publish() {
        _hosts.value = found.values.sortedBy { it.name.lowercase() }
    }
}
