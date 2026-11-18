package com.dualearbuds.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.SoundSettings
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dualearbuds.companion.ui.screens.AboutScreen
import com.dualearbuds.companion.ui.screens.AskScreen
import com.dualearbuds.companion.ui.screens.BestSoundScreen
import com.dualearbuds.companion.ui.screens.CalibrationScreen
import com.dualearbuds.companion.ui.screens.GuideScreen
import com.dualearbuds.companion.ui.screens.HearingScreen
import com.dualearbuds.companion.ui.screens.SettingsScreen
import com.dualearbuds.companion.ui.screens.SetupCheckScreen
import com.dualearbuds.companion.ui.screens.SmartFeaturesScreen
import com.dualearbuds.companion.ui.screens.WelcomeScreen
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
    const val SETUP = "setup"
    const val BEST = "best"
    const val ASK = "ask"
    const val WELCOME = "welcome"
    const val WELCOME_AGAIN = "welcome_again"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() {
        if (!nav.popBackStack()) nav.navigate(Routes.HOME)
    }

    // First-run guide once on a fresh install (not for people who already joined a host before this version).
    val start = remember { if (!Prefs.firstRunGuideSeen && Prefs.lastLink.isBlank()) Routes.WELCOME else Routes.HOME }
    /** Leaves the first-run guide for Home (replacing it), then opens [route] on top if given. */
    fun leaveWelcome(route: String?) {
        nav.navigate(Routes.HOME) {
            popUpTo(Routes.WELCOME) { inclusive = true }
            launchSingleTop = true
        }
        if (route != null) go(route)
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onDone = { leaveWelcome(null) },
                onBack = null,
                onJoin = { leaveWelcome(null) },
                onShareThisPhone = { leaveWelcome(Routes.HOST) },
                onCalibrate = { leaveWelcome(Routes.CALIBRATE) }
            )
        }
        composable(Routes.WELCOME_AGAIN) {
            WelcomeScreen(
                onDone = { back() },
                onBack = { back() },
                onJoin = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onShareThisPhone = { go(Routes.HOST) },
                onCalibrate = { go(Routes.CALIBRATE) }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGuide = { go(Routes.GUIDE) },
                onOpenSettings = { go(Routes.SETTINGS) },
                onOpenCalibration = { go(Routes.CALIBRATE) },
                onShareThisPhone = { go(Routes.HOST) },
                onOpenSetup = { go(Routes.SETUP) },
                onOpenAsk = { go(Routes.ASK) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { back() },
                onOpenSmart = { go(Routes.SMART) },
                onOpenCalibration = { go(Routes.CALIBRATE) },
                onOpenHearing = { go(Routes.HEARING) },
                onOpenGuide = { go(Routes.GUIDE) },
                onOpenAbout = { go(Routes.ABOUT) },
                onOpenSetup = { go(Routes.SETUP) },
                onOpenBestSound = { go(Routes.BEST) },
                onOpenAsk = { go(Routes.ASK) },
                onOpenWelcome = { go(Routes.WELCOME_AGAIN) }
            )
        }
        composable(Routes.ASK) { AskScreen(onBack = { back() }) }
        composable(Routes.SMART) { SmartFeaturesScreen(onBack = { back() }, onOpenHearing = { go(Routes.HEARING) }) }
        composable(Routes.CALIBRATE) { CalibrationScreen(onBack = { back() }) }
        composable(Routes.HEARING) { HearingScreen(onBack = { back() }) }
        composable(Routes.GUIDE) { GuideScreen(onBack = { back() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { back() }) }
        composable(Routes.HOST) {
            HostScreen(
                onBack = { back() },
                // the host asks per output device name; calibrated values are stored the same way
                localOutputLatencyMs = { name -> SoundSettings.latencyFor(name) },
                onCalibrate = { go(Routes.CALIBRATE) }
            )
        }
        composable(Routes.SETUP) {
            SetupCheckScreen(
                onBack = { back() },
                onOpenBrandSteps = { go(Routes.GUIDE) },
                onOpenCalibration = { go(Routes.CALIBRATE) },
                onOpenBestSound = { go(Routes.BEST) }
            )
        }
        composable(Routes.BEST) { BestSoundScreen(onBack = { back() }, onOpenCalibration = { go(Routes.CALIBRATE) }) }
    }
}
