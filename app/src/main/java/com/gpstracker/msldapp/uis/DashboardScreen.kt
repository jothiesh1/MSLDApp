package com.gpstracker.msldapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gpstracker.msldapp.utils.AppNavigator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gpstracker.msldapp.uis.HereApiManager
import com.gpstracker.msldapp.uis.SerialTtlManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import okhttp3.*
import java.io.IOException
import kotlin.math.abs

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    var latitude by remember { mutableStateOf("--") }
    var longitude by remember { mutableStateOf("--") }
    var speed by remember { mutableStateOf("--") }
    var routeStatus by remember { mutableStateOf("Waiting") }
    var speedLimit by remember { mutableStateOf("--") }
    var lastSpeed by remember { mutableStateOf(0.0) }
    var trackingStarted by remember { mutableStateOf(false) }
    var ttlStatus by remember { mutableStateOf("\uD83D\uDD0C TTL: Not Connected") }
    val logs = remember { mutableStateListOf<String>() }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var lastLat by remember { mutableStateOf(0.0) }
    var lastLon by remember { mutableStateOf(0.0) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var refreshKey by remember { mutableStateOf(0) }

    val token = ""

    LaunchedEffect(refreshKey) {
        if (SerialTtlManager.init(context)) {
            ttlStatus = "\u2705 TTL: Connected"
        } else {
            ttlStatus = "\u274C TTL: Not Found"
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isVisible by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isVisible = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            SerialTtlManager.close()
        }
    }

    LaunchedEffect(trackingStarted, refreshKey) {
        while (trackingStarted && isActive) {
            if (isVisible) {
                getCurrentLocation(context, fusedLocationClient) { location ->
                    latitude = location?.latitude?.toString() ?: "--"
                    longitude = location?.longitude?.toString() ?: "--"

                    val currentSpeed = location?.speed?.times(3.6) ?: 0.0
                    val adjustedSpeed = if (abs(currentSpeed - lastSpeed) < 20) currentSpeed else lastSpeed
                    lastSpeed = adjustedSpeed
                    speed = String.format("%.2f", adjustedSpeed)

                    if (location != null) {
                        val now = System.currentTimeMillis()
                        val moved = abs(location.latitude - lastLat) > 0.0001 || abs(location.longitude - lastLon) > 0.0001

                        if (now >= cooldownUntil && moved) {
                            lastLat = location.latitude
                            lastLon = location.longitude

                            // Inside HereApiManager.matchRouteWithCallback
                            HereApiManager.matchRouteWithCallback(
                                listOf(location.latitude to location.longitude)
                            ) { hereResult ->
                                val hereLimit = hereResult
                                    .split("➔").lastOrNull()
                                    ?.trim()
                                    ?.split(" ")?.firstOrNull()
                                    ?.toIntOrNull()

                                if (hereLimit != null && hereLimit in 10..200) {
                                    routeStatus = "✅ Speed from HERE"
                                    speedLimit = "$hereLimit"
                                    SerialTtlManager.sendSpeed(hereLimit)
                                    logs.add("TTL Sent (HERE): $hereLimit")
                                } else {
                                    fetchSpeedLimitFromOSM(location.latitude, location.longitude) { osmResult ->
                                        val cleaned = osmResult.trim()
                                        val osmLimit = cleaned.toIntOrNull()
                                            ?: cleaned.split(" ").firstOrNull()?.toIntOrNull()

                                        if (osmLimit != null && osmLimit in 10..200) {
                                            routeStatus = "✅ Speed from OSM"
                                            speedLimit = "$osmLimit"
                                            SerialTtlManager.sendSpeed(osmLimit)
                                            logs.add("TTL Sent (OSM): $osmLimit")
                                        } else {
                                            // Final fallback: default speed
                                            val fallbackSpeed = 40
                                            speedLimit = "$fallbackSpeed"
                                            routeStatus = "⚠️ Default speed used"
                                            SerialTtlManager.sendSpeed(fallbackSpeed)
                                            logs.add("⚠️ No valid speed from HERE/OSM. Sent default: $fallbackSpeed")
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
            delay(20000)
        }
    }

    key(refreshKey) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Color(0xFF2193b0), Color(0xFF6dd5ed))))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Button(onClick = {
                    logs.clear()
                    trackingStarted = false
                    refreshKey++
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("\uD83D\uDD04 Hard Refresh")
                }

                Text("\uD83D\uDCCD Latitude: $latitude", color = Color.White)
                Text("\uD83D\uDCCD Longitude: $longitude", color = Color.White)
                Text("\uD83C\uDFCE\uFE0F Speed: $speed km/h", color = Color.White)
                Text(routeStatus, color = Color.Yellow)
                Text("\uD83D\uDCCF Speed Limit: $speedLimit", color = Color.White)
                Text(ttlStatus, color = Color.Cyan)

                Button(onClick = { trackingStarted = true }) { Text("Start Speed Tracking") }
                Button(onClick = { trackingStarted = false }) { Text("Stop Tracking") }
                Button(onClick = { logs.clear() }) { Text("Refresh Logs") }
                Button(onClick = { AppNavigator.navigate("map") }) { Text("Open Map") }

                Spacer(modifier = Modifier.height(16.dp))
                Text("\uD83D\uDCCB Logs:", color = Color.LightGray)
                logs.takeLast(10).forEach {
                    Text(it, color = Color.White)
                }
            }
        }
    }

    LaunchedEffect(refreshKey) {
        SerialTtlManager.readCallback = {
            logs.add("\uD83D\uDCE5 TTL Read: $it")
        }
        SerialTtlManager.startReading()
    }
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient,
    callback: (Location?) -> Unit
) {
    try {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    Log.e("GPS", "⚠️ getCurrentLocation returned null")
                    Toast.makeText(context, "GPS location unavailable", Toast.LENGTH_SHORT).show()
                } else {
                    Log.i("GPS", "✅ Location: ${location.latitude}, ${location.longitude}")
                }
                callback(location)
            }
            .addOnFailureListener { e ->
                Log.e("GPS", "❌ Failed to get location: ${e.message}", e)
                Toast.makeText(context, "❌ GPS Error: ${e.message}", Toast.LENGTH_SHORT).show()
                callback(null)
            }
    } catch (e: Exception) {
        Log.e("GPS", "❌ getCurrentLocation threw exception: ${e.message}", e)
        Toast.makeText(context, "❌ Location Error: ${e.message}", Toast.LENGTH_LONG).show()
        callback(null)
    }
}


fun fetchSpeedLimitFromOSM(lat: Double, lon: Double, onResult: (String) -> Unit) {
    val url = "https://overpass-api.de/api/interpreter?data=[out:json];way(around:20,$lat,$lon)[\"maxspeed\"];out;"
    val request = Request.Builder().url(url).build()
    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("OSM", "\u274C Failed to fetch from OSM: ${e.message}", e)
            onResult("--")
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { json ->
                val obj = JSONObject(json)
                val elements = obj.optJSONArray("elements") ?: return onResult("--")
                for (i in 0 until elements.length()) {
                    val tags = elements.getJSONObject(i).optJSONObject("tags")
                    val speed = tags?.optString("maxspeed")
                    if (!speed.isNullOrEmpty()) return onResult(speed)
                }
            }
            onResult("--")
        }
    })
}

