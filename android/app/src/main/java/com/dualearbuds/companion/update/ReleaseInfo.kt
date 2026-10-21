package com.dualearbuds.companion.update

import java.net.URI

/** The APK asset of a release. [size] is -1 when GitHub didn't report it. */
data class ApkAsset(val url: String, val size: Long)

data class ReleaseInfo(
    val version: SemVer,
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    /** Null when the release has no `DualEarbuds.apk` (or its URL isn't an allowed GitHub download). */
    val apk: ApkAsset?
)

sealed interface ReleaseParse {
    data class Ok(val release: ReleaseInfo) : ReleaseParse
    data class Invalid(val reason: String) : ReleaseParse
}

/** Update constants and the pure rules around them (no Android types — unit-tested). */
object UpdatePolicy {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/qasimrazamalik-coder/dual-earbuds/releases/latest"
    const val APK_ASSET_NAME = "DualEarbuds.apk"
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Hosts the updater may download from (docs/protocol.md, "Updates"). */
    val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    /** HTTPS, default port, no user info, host exactly one of [ALLOWED_DOWNLOAD_HOSTS]. */
    fun isAllowedDownloadUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_DOWNLOAD_HOSTS
    }

    /** Automatic checks run at most once per [intervalMs]; a clock that moved backwards counts as due. */
    fun isAutoCheckDue(nowMs: Long, lastCheckMs: Long, enabled: Boolean, intervalMs: Long = AUTO_CHECK_INTERVAL_MS): Boolean {
        if (!enabled) return false
        if (lastCheckMs <= 0L) return true
        if (nowMs < lastCheckMs) return true
        return nowMs - lastCheckMs >= intervalMs
    }
}

/** Parses `GET /repos/{owner}/{repo}/releases/latest`. Never throws. */
object ReleaseParser {
    fun parse(json: String): ReleaseParse {
        val root = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            return ReleaseParse.Invalid("Not JSON: ${e.message}")
        }
        val o = root as? Map<*, *> ?: return ReleaseParse.Invalid("Not a release object")
        val tag = (o["tag_name"] as? String)?.trim().orEmpty()
        val version = SemVer.parse(tag) ?: return ReleaseParse.Invalid("Tag \"$tag\" is not a version")
        if (o["draft"] == true) return ReleaseParse.Invalid("Draft release")
        val title = (o["name"] as? String)?.trim().orEmpty().ifEmpty { tag }
        val notes = (o["body"] as? String)?.replace("\r\n", "\n")?.trim().orEmpty()
        val page = (o["html_url"] as? String).orEmpty()
        val assets = o["assets"] as? List<*> ?: emptyList<Any?>()
        val apk = assets.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["name"] == UpdatePolicy.APK_ASSET_NAME }
            ?.let { a ->
                val url = a["browser_download_url"] as? String
                if (!UpdatePolicy.isAllowedDownloadUrl(url)) {
                    null
                } else {
                    val size = (a["size"] as? Number)?.toLong()?.takeIf { it > 0 } ?: -1L
                    ApkAsset(url!!, size)
                }
            }
        return ReleaseParse.Ok(ReleaseInfo(version, tag, title, notes, page, apk))
    }
}
