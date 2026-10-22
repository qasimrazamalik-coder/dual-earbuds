package com.dualearbuds.companion.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {
    /** Shared client. Read timeout 0 because the web socket is a long-lived stream; we run our own watchdog. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Quick rejoin after a Wi-Fi blip: same host, so fail fast and try again rather than wait 4 s. */
    val quickHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Short timeouts for the one-shot GET /info check. Shares the connection pool with [http]. */
    val infoHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Best-effort check whether the phone is on Wi-Fi. Returns true when unsure, so we never
     * block a working setup (for example the laptop joined this phone's hotspot).
     */
    fun isWifiLikelyOff(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            if (onWifi) return false
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled != true
        } catch (t: Throwable) {
            false
        }
    }
}
