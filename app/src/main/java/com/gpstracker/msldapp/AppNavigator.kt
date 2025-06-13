package com.gpstracker.msldapp.utils

import android.util.Log
import androidx.navigation.NavHostController

object AppNavigator {
    private var navController: NavHostController? = null

    fun setController(controller: NavHostController) {
        navController = controller
        Log.d("AppNavigator", "NavController set: $controller")
    }

    fun navigate(route: String) {
        if (navController == null) {
            Log.e("AppNavigator", "❌ NavController is NULL! Cannot navigate to $route")
        } else {
            Log.d("AppNavigator", "✅ Navigating to: $route")
            navController?.navigate(route)
        }
    }

    fun popBackStack() {
        navController?.popBackStack()
    }
}
