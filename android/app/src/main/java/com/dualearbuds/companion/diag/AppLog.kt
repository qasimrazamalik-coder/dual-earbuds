package com.dualearbuds.companion.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App log: Logcat plus the last 200 lines (keys and PINs removed) for "Copy diagnostics". */
object AppLog {
    private const val TAG = "DualEarbuds"
    val ring = LogRing(200)
    private val time = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun i(msg: String) {
        val safe = Redact.secrets(msg)
        try {
            Log.i(TAG, safe)
        } catch (_: Throwable) {
        }
        ring.add("${time.get()?.format(Date()) ?: ""} $safe")
    }

    fun w(msg: String, t: Throwable? = null) =
        i("WARN $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
}
