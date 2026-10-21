package com.dualearbuds.companion.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dualearbuds.companion.BuildConfig
import com.dualearbuds.companion.data.AppSettings
import com.dualearbuds.companion.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** [progress] 0..1, or -1 when the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    /** Android blocks installs from this app until the user allows "Install unknown apps" for it. */
    data class NeedsInstallPermission(val release: ReleaseInfo, val file: File) : UpdateState
    /** A calm, user-facing message; [release] is kept so the user can retry the download. */
    data class Problem(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * In-app updates from the latest GitHub release (docs/protocol.md "Updates").
 *
 * - Checks `releases/latest`, compares tags with [SemVer]; automatic checks run at most once per 24 h.
 * - Downloads `DualEarbuds.apk` only after the user taps Download, only over HTTPS and only from
 *   [UpdatePolicy.ALLOWED_DOWNLOAD_HOSTS] — every redirect hop is checked by a network interceptor.
 * - Saves into the app cache and hands it to the system installer via a FileProvider + ACTION_VIEW. Android
 *   itself refuses an APK signed with a different key, so a tampered file can't replace the app.
 */
object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String
        get() = BuildConfig.VERSION_NAME

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val apiHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    private val downloadHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false) // never https -> http
            .addNetworkInterceptor(AllowedHostInterceptor)
            .build()
    }

    /** Runs for the first request and every redirect: refuses anything not on the allow list. */
    private object AllowedHostInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url.toString()
            if (!UpdatePolicy.isAllowedDownloadUrl(url)) {
                throw IOException("Refused download from ${chain.request().url.host}")
            }
            return chain.proceed(chain.request())
        }
    }

    /** Called on app start: checks quietly when enabled and 24 h have passed. Never shows errors. */
    fun autoCheckIfDue() {
        if (!UpdatePolicy.isAutoCheckDue(System.currentTimeMillis(), Prefs.lastUpdateCheck, AppSettings.autoUpdate.value)) return
        if (job?.isActive == true) return
        job = scope.launch { check(manual = false) }
    }

    /** "Check for updates" in Settings. */
    fun checkNow() {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch { check(manual = true) }
    }

    private fun check(manual: Boolean) {
        val request = Request.Builder()
            .url(UpdatePolicy.LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "DualEarbuds-Android/${currentVersion}")
            .build()
        val next: UpdateState = try {
            apiHttp.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        Prefs.lastUpdateCheck = System.currentTimeMillis()
                        when (val parsed = ReleaseParser.parse(response.body?.string().orEmpty())) {
                            is ReleaseParse.Invalid -> UpdateState.Problem("The update information looked wrong. Try again later.")
                            is ReleaseParse.Ok -> {
                                val r = parsed.release
                                if (SemVer.isNewer(r.tag, currentVersion)) {
                                    UpdateState.Available(r)
                                } else {
                                    UpdateState.UpToDate(r.version.toString())
                                }
                            }
                        }
                    }
                    404 -> {
                        Prefs.lastUpdateCheck = System.currentTimeMillis()
                        UpdateState.Problem("No releases are published yet. You have the latest version.")
                    }
                    403, 429 -> UpdateState.Problem("GitHub is busy right now. Try again in a little while.")
                    else -> UpdateState.Problem("Couldn't check for updates (HTTP ${response.code}). Try again later.")
                }
            }
        } catch (_: IOException) {
            UpdateState.Problem("You're offline or GitHub can't be reached. Connect to the internet and try again.")
        } catch (t: Throwable) {
            UpdateState.Problem("Couldn't check for updates. Try again later.")
        }
        if (manual) {
            _state.value = next
        } else if (next is UpdateState.Available) {
            // Quiet checks only ever surface good news.
            _state.value = next
        }
    }

    /** Downloads the APK of [release] (the user agreed by tapping Download). */
    fun download(context: Context, release: ReleaseInfo) {
        val apk = release.apk
        if (apk == null) {
            _state.value = UpdateState.Problem("This release has no Android app attached yet. Try again later.", release)
            return
        }
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(release, if (apk.size > 0) 0f else -1f)
        job = scope.launch {
            val dir = File(app.cacheDir, "updates")
            val part = File(dir, "${UpdatePolicy.APK_ASSET_NAME}.part")
            val dest = File(dir, UpdatePolicy.APK_ASSET_NAME)
            try {
                dir.mkdirs()
                part.delete()
                dest.delete()
                val request = Request.Builder()
                    .url(apk.url)
                    .header("User-Agent", "DualEarbuds-Android/${currentVersion}")
                    .header("Accept", "application/octet-stream")
                    .build()
                downloadHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpProblem(response.code)
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: apk.size
                    if (total > MAX_APK_BYTES) throw IOException("Download too large")
                    var read = 0L
                    var lastReported = -1
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (read > MAX_APK_BYTES) throw IOException("Download too large")
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt()
                                    if (pct != lastReported) {
                                        lastReported = pct
                                        _state.value = UpdateState.Downloading(release, (read.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }
                    if (apk.size > 0 && read != apk.size) throw IOException("Incomplete download")
                }
                if (!part.renameTo(dest)) throw IOException("Couldn't save the update")
                if (!looksLikeThisApp(app, dest)) {
                    dest.delete()
                    _state.value = UpdateState.Problem("The downloaded file isn't a Dual Earbuds update, so it was deleted.", release)
                    return@launch
                }
                _state.value = UpdateState.ReadyToInstall(release, dest)
                install(app, release, dest)
            } catch (e: HttpProblem) {
                part.delete()
                _state.value = UpdateState.Problem(
                    if (e.code == 404) "The update file isn't on GitHub (yet). Try again later." else "Download failed (HTTP ${e.code}). Try again later.",
                    release
                )
            } catch (_: IOException) {
                part.delete()
                _state.value = UpdateState.Problem("Download stopped — check your internet connection and try again.", release)
            } catch (t: Throwable) {
                part.delete()
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = UpdateState.Problem("Download failed. Try again later.", release)
            }
        }
    }

    /**
     * Opens the system installer. If this app may not install packages yet, switches to
     * [UpdateState.NeedsInstallPermission] so the UI can explain and open that settings page.
     */
    fun install(context: Context, release: ReleaseInfo, file: File) {
        val app = context.applicationContext
        if (!file.exists()) {
            _state.value = UpdateState.Problem("The downloaded update is gone. Download it again.", release)
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) { // API 26+, minSdk is 26
            _state.value = UpdateState.NeedsInstallPermission(release, file)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            _state.value = UpdateState.ReadyToInstall(release, file)
        } catch (_: ActivityNotFoundException) {
            _state.value = UpdateState.Problem("No installer found on this phone. Download the APK from the GitHub page instead.", release)
        } catch (_: Throwable) {
            _state.value = UpdateState.Problem("Couldn't open the installer. Try again.", release)
        }
    }

    /** Opens Settings → "Install unknown apps" for this app. Returns false if no such screen exists. */
    fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Throwable) {
        false
    }

    /** After returning from the permission page: continue straight to the installer if it is now allowed. */
    fun onResume(context: Context) {
        val s = _state.value
        if (s is UpdateState.NeedsInstallPermission && context.packageManager.canRequestPackageInstalls()) {
            install(context, s.release, s.file)
        }
    }

    fun dismissProblem() {
        val s = _state.value
        if (s is UpdateState.Problem) _state.value = s.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    /** Same package name as this app (the installer checks the signature itself). */
    private fun looksLikeThisApp(context: Context, apk: File): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        info?.packageName == context.packageName
    } catch (_: Throwable) {
        false
    }

    private class HttpProblem(val code: Int) : IOException("HTTP $code")
}

/** Own subclass so the manifest entry can't clash with a FileProvider declared by a library module. */
class UpdateFileProvider : FileProvider()
