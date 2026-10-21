package com.dualearbuds.companion.net

import android.content.Context

enum class ErrorKind { WRONG_KEY, NOT_FOUND, WIFI_OFF, SERVER_FULL, NOT_DUAL_EARBUDS, BAD_LINK, KICKED, OTHER }

data class UserError(val kind: ErrorKind, val message: String) {
    /** Errors where "Use a hotspot" is the likely fix (public Wi-Fi blocks devices from reaching each other). */
    val suggestsHotspot: Boolean
        get() = kind == ErrorKind.NOT_FOUND || kind == ErrorKind.WIFI_OFF
}

object Errors {
    fun wrongKey() = UserError(
        ErrorKind.WRONG_KEY,
        "Wrong link key. The host refused it — scan the QR code on the laptop (or the sharing phone) again; the key changes after New link."
    )

    fun notFound(context: Context, target: LinkTarget): UserError =
        if (Net.isWifiLikelyOff(context)) {
            UserError(
                ErrorKind.WIFI_OFF,
                "Wi-Fi is off. Turn on Wi-Fi and join the same network as the laptop (or connect the laptop to this phone's hotspot)."
            )
        } else {
            UserError(
                ErrorKind.NOT_FOUND,
                "Nothing answered at ${target.display}. Check that Dual Earbuds is sharing on the laptop (Phone link on) or the other phone (Share this phone), and that both are on the same Wi-Fi. On public Wi-Fi (cafe, hotel, campus), devices are often blocked from reaching each other — use this phone's hotspot and connect the laptop to it instead."
            )
        }

    fun serverFull() = UserError(
        ErrorKind.SERVER_FULL,
        "The host is full (8 listeners, or 16 in Party mode). Disconnect one and try again."
    )

    fun notDualEarbuds(target: LinkTarget) = UserError(
        ErrorKind.NOT_DUAL_EARBUDS,
        "Something answered at ${target.display}, but it isn't Dual Earbuds. Check the link."
    )

    /** The host removed this phone (protocol v3 `kick`). Never auto-reconnects. */
    fun kicked(reason: String?) = UserError(
        ErrorKind.KICKED,
        reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: "Removed by the host"
    )

    fun other(reason: String) = UserError(ErrorKind.OTHER, "Connection problem: $reason")

    fun from(result: InfoResult, context: Context, target: LinkTarget): UserError? = when (result) {
        is InfoResult.Ok -> null
        InfoResult.WrongKey -> wrongKey()
        InfoResult.ServerFull -> serverFull()
        InfoResult.NotDualEarbuds -> notDualEarbuds(target)
        is InfoResult.Unreachable -> notFound(context, target)
        is InfoResult.Failed -> other(result.reason)
    }
}
