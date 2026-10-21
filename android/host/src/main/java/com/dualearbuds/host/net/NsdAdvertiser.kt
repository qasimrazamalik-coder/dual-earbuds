package com.dualearbuds.host.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Advertises the host as `_dualearbuds._tcp` with NsdManager (protocol v3.1 "Discovery").
 * TXT changes (key shown/hidden, PIN on/off) re-register the service. All work runs on the main
 * thread; NsdManager callbacks are serialised through [main].
 */
internal class NsdAdvertiser(context: Context) {
    data class Ad(val instance: String, val port: Int, val txt: Map<String, String>)

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registered: NsdManager.RegistrationListener? = null
    private var current: Ad? = null
    private var wanted: Ad? = null
    private var busy = false

    /** Advertise [ad] (null = stop advertising). Safe to call from any thread, any number of times. */
    fun update(ad: Ad?) {
        main.post {
            wanted = ad
            pump()
        }
    }

    private fun pump() {
        val manager = nsd ?: return
        if (busy) return
        val reg = registered
        if (reg != null) {
            if (wanted == current) return
            busy = true
            try {
                manager.unregisterService(reg)
            } catch (_: Throwable) {
                registered = null
                current = null
                busy = false
                pump()
            }
            return
        }
        val ad = wanted ?: return
        val info = NsdServiceInfo().apply {
            serviceName = ad.instance
            serviceType = Discovery.SERVICE_TYPE
            port = ad.port
            for ((k, v) in ad.txt) {
                try {
                    setAttribute(k, v)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                main.post {
                    busy = false
                    pump()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (registered === this) {
                        registered = null
                        current = null
                    }
                    busy = false
                    // No retry loop: discovery is optional; the QR code and link still work.
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (registered === this) {
                        registered = null
                        current = null
                    }
                    busy = false
                    pump()
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (registered === this) {
                        registered = null
                        current = null
                    }
                    busy = false
                    pump()
                }
            }
        }
        busy = true
        registered = listener
        current = ad
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            registered = null
            current = null
            busy = false
        }
    }
}
