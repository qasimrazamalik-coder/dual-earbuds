package com.dualearbuds.companion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.ui.AppNav
import com.dualearbuds.companion.ui.theme.DualEarbudsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            DualEarbudsTheme {
                AppNav()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EarbudsMonitor.refresh()
    }
}
