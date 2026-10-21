package com.dualearbuds.companion.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dualearbuds.companion.ui.screens.AboutScreen
import com.dualearbuds.companion.ui.screens.GuideScreen
import com.dualearbuds.companion.ui.screens.HomeScreen

object Routes {
    const val HOME = "home"
    const val GUIDE = "guide"
    const val ABOUT = "about"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGuide = { nav.navigate(Routes.GUIDE) { launchSingleTop = true } },
                onOpenAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } }
            )
        }
        composable(Routes.GUIDE) {
            GuideScreen(onBack = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
