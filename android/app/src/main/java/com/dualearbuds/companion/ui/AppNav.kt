package com.dualearbuds.companion.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dualearbuds.companion.ui.screens.AboutScreen
import com.dualearbuds.companion.ui.screens.CalibrationScreen
import com.dualearbuds.companion.ui.screens.GuideScreen
import com.dualearbuds.companion.ui.screens.HearingScreen
import com.dualearbuds.companion.ui.screens.SettingsScreen
import com.dualearbuds.companion.ui.screens.SmartFeaturesScreen
import com.dualearbuds.companion.ui.screens.home.HomeScreen
import com.dualearbuds.host.HostScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SMART = "smart"
    const val CALIBRATE = "calibrate"
    const val HEARING = "hearing"
    const val GUIDE = "guide"
    const val ABOUT = "about"
    const val HOST = "host"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() {
        if (!nav.popBackStack()) nav.navigate(Routes.HOME)
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGuide = { go(Routes.GUIDE) },
                onOpenSettings = { go(Routes.SETTINGS) },
                onOpenCalibration = { go(Routes.CALIBRATE) },
                onShareThisPhone = { go(Routes.HOST) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { back() },
                onOpenSmart = { go(Routes.SMART) },
                onOpenCalibration = { go(Routes.CALIBRATE) },
                onOpenHearing = { go(Routes.HEARING) },
                onOpenGuide = { go(Routes.GUIDE) },
                onOpenAbout = { go(Routes.ABOUT) }
            )
        }
        composable(Routes.SMART) { SmartFeaturesScreen(onBack = { back() }, onOpenHearing = { go(Routes.HEARING) }) }
        composable(Routes.CALIBRATE) { CalibrationScreen(onBack = { back() }) }
        composable(Routes.HEARING) { HearingScreen(onBack = { back() }) }
        composable(Routes.GUIDE) { GuideScreen(onBack = { back() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { back() }) }
        composable(Routes.HOST) { HostScreen(onBack = { back() }) }
    }
}
