package com.screencast.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Casting : Screen("casting?id={id}&name={name}&type={type}&address={address}&controlUrl={controlUrl}&model={model}") {
        fun createRoute(device: Device): String {
            val encodedName = URLEncoder.encode(device.name, "UTF-8")
            val encodedControlUrl = URLEncoder.encode(device.controlUrl ?: "", "UTF-8")
            val encodedModel = URLEncoder.encode(device.modelName ?: "", "UTF-8")
            return "casting?id=${device.id}&name=$encodedName&type=${device.type.name}&address=${device.address}&controlUrl=$encodedControlUrl&model=$encodedModel"
        }
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
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "Unknown" },
                navArgument("type") { type = NavType.StringType; defaultValue = "DLNA" },
                navArgument("address") { type = NavType.StringType; defaultValue = "" },
                navArgument("controlUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("model") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments ?: return@composable
            
            val device = Device(
                id = args.getString("id") ?: "",
                name = URLDecoder.decode(args.getString("name") ?: "Unknown", "UTF-8"),
                type = try {
                    DeviceType.valueOf(args.getString("type") ?: "DLNA")
                } catch (e: Exception) {
                    DeviceType.DLNA
                },
                address = args.getString("address") ?: "",
                controlUrl = URLDecoder.decode(args.getString("controlUrl") ?: "", "UTF-8").takeIf { it.isNotEmpty() },
                modelName = URLDecoder.decode(args.getString("model") ?: "", "UTF-8").takeIf { it.isNotEmpty() }
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
