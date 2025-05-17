package com.gpstracker.msldapp.uis

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationServices

@SuppressLint("MissingPermission")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var currentLocation by remember { mutableStateOf<Location?>(null) }

    LaunchedEffect(Unit) {
        try {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d("MapScreen", "📍 Got location: ${location.latitude}, ${location.longitude}")
                    currentLocation = location
                } else {
                    Log.w("MapScreen", "⚠️ Location is null")
                }
            }
        } catch (e: Exception) {
            Log.e("MapScreen", "❌ Error getting location: ${e.message}", e)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🗺 Map Screen", fontSize = 24.sp)
        Spacer(modifier = Modifier.height(20.dp))
        if (currentLocation != null) {
            Text("Lat: ${currentLocation!!.latitude}", fontSize = 18.sp)
            Text("Lng: ${currentLocation!!.longitude}", fontSize = 18.sp)
        } else {
            Text("Fetching location...", fontSize = 18.sp)
        }
    }
}
