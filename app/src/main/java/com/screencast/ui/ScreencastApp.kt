package com.screencast.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.screencast.model.Device
import com.screencast.model.DeviceType
import com.screencast.ui.screens.*

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Casting : Screen("casting/{deviceId}/{deviceName}/{deviceType}/{deviceAddress}") {
        fun createRoute(device: Device) = "casting/${device.id}/${device.name}/${device.type.name}/${device.address}"
    }
    data object Settings : Screen("settings")
}

@Composable
fun ScreencastApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onDeviceSelected = { device ->
                    navController.navigate(Screen.Casting.createRoute(device))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(
            route = Screen.Casting.route,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType },
                navArgument("deviceName") { type = NavType.StringType },
                navArgument("deviceType") { type = NavType.StringType },
                navArgument("deviceAddress") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: return@composable
            val deviceName = backStackEntry.arguments?.getString("deviceName") ?: "Unknown"
            val deviceType = backStackEntry.arguments?.getString("deviceType")?.let { 
                DeviceType.valueOf(it) 
            } ?: DeviceType.DLNA
            val deviceAddress = backStackEntry.arguments?.getString("deviceAddress") ?: ""
            
            val device = Device(
                id = deviceId,
                name = deviceName,
                type = deviceType,
                address = deviceAddress
            )
            
            val viewModel: CastingViewModel = hiltViewModel()
            
            // MediaProjection permission launcher
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    viewModel.startCasting(result.resultCode, result.data!!)
                }
            }
            
            CastingScreen(
                device = device,
                onNavigateBack = { navController.popBackStack() },
                onRequestPermission = {
                    permissionLauncher.launch(projectionManager.createScreenCaptureIntent())
                },
                viewModel = viewModel
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
