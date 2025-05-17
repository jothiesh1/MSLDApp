package com.gpstracker.msldapp.uis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.*
import com.gpstracker.msldapp.ui.theme.MSLDAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MSLDAppTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "dashboard"
                ) {
                    composable("dashboard") {
                        DashboardScreen(navController)
                    }
                    composable("heremap") {
                        HereMapScreen(navController)
                    }
                }
            }
        }
    }
}
