package com.dualearbuds.host.ui

import androidx.compose.runtime.Composable
import com.dualearbuds.host.setup.CheckItem
import com.dualearbuds.host.setup.CheckLevel
import com.dualearbuds.host.setup.Fix

/**
 * "Setup check": what on this phone helps or hurts sharing (battery, background limits, Wi-Fi band,
 * hotspot, earbuds, weak listeners), each with one line and a fix button where Android has one.
 */
@Composable
internal fun SetupSection(items: List<CheckItem>, onFix: (Fix) -> Unit) {
    if (items.isEmpty()) return
    val toFix = items.count { it.level == CheckLevel.WARN }
    SectionLabel("Setup check", if (toFix == 0) "all good" else "$toFix to fix")
    Panel {
        items.forEachIndexed { i, item ->
            if (i > 0) PanelDivider()
            val fix = item.fix
            PanelRow(
                title = item.title,
                hint = item.detail,
                leading = {
                    StatusDot(
                        item.level,
                        when (item.level) {
                            CheckLevel.OK -> "ok"
                            CheckLevel.WARN -> "fix"
                            CheckLevel.INFO -> "tip"
                        }
                    )
                },
                trailing = if (fix != null) {
                    { QuietButton(item.fixLabel ?: "Fix", onClick = { onFix(fix) }) }
                } else {
                    null
                }
            )
        }
    }
}
