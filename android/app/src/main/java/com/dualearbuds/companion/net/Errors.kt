package com.dualearbuds.companion.net

import android.content.Context

enum class ErrorKind { WRONG_KEY, NOT_FOUND, WIFI_OFF, SERVER_FULL, NOT_DUAL_EARBUDS, BAD_LINK, OTHER }

data class UserError(val kind: ErrorKind, val message: String)

object Errors {
    fun wrongKey() = UserError(
        ErrorKind.WRONG_KEY,
        "Wrong link key. The laptop refused it — scan the QR code in the Windows app again (the key changes if you reset it)."
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
                "Laptop not found at ${target.display}. Check that Dual Earbuds is open on the laptop with Phone link on, and that both are on the same Wi-Fi. On public Wi-Fi (cafe, hotel), devices are often blocked from reaching each other — use this phone's hotspot and connect the laptop to it instead."
            )
        }

    fun serverFull() = UserError(ErrorKind.SERVER_FULL, "The laptop already has 8 phones connected. Disconnect one and try again.")

    fun notDualEarbuds(target: LinkTarget) = UserError(
        ErrorKind.NOT_DUAL_EARBUDS,
        "Something answered at ${target.display}, but it isn't the Dual Earbuds app. Check the link."
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
