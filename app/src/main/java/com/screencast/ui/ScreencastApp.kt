package com.screencast.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.screencast.ui.screens.HomeScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Casting : Screen("casting/{deviceId}") {
        fun createRoute(deviceId: String) = "casting/$deviceId"
    }
    data object Settings : Screen("settings")
}

@Composable
fun ScreencastApp() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onDeviceSelected = { device ->
                    navController.navigate(Screen.Casting.createRoute(device.id))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.Casting.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: return@composable
            // CastingScreen will be implemented next
        }
        
        composable(Screen.Settings.route) {
            // SettingsScreen will be implemented next
        }
    }
}
