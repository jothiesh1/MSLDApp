package com.gpstracker.msldapp

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gpstracker.msldapp.uis.DashboardScreen
import com.gpstracker.msldapp.uis.HereMapScreen

@Composable
fun AppNavigator(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen(navController) }
        composable("heremap") { HereMapScreen(navController) }
    }
}
