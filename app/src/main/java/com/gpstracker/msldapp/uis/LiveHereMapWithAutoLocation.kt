package com.gpstracker.msldapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.*

// HERE Maps Configuration
private const val CLIENT_ID = "L7tvWlgQEscYWBCO23QqYA"
private const val CLIENT_SECRET = "K-mv-j4q-3zDTYtfrSbhxSBXar7lULggsB4a2PBuG-B60D1g-5vc2QxNjkgvStnmSMEgAMPTi-9FkMboRWsYTA"
private const val TOKEN_URL = "https://auth.here.com/oauth2/token"
private const val ROUTE_MATCH_URL = "https://rme.api.here.com/v8/match/routes?attributes=SPEED_LIMITS"

// Global variables for HERE Maps + OSM
private var accessToken: String? = null
private var accessTokenExpiresAt: Long = 0L
private val speedLimitCache = mutableMapOf<String, CachedSpeedLimit>()
private var lastLocation: Pair<Double, Double>? = null
private var isMoving = false
private var consecutiveStationary = 0



private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private fun isTokenExpired() = System.currentTimeMillis() > accessTokenExpiresAt

@SuppressLint("MissingPermission")
@Composable
fun LiveHereMapWithAutoLocation() {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val traceBuffer = remember { mutableStateListOf<LocationPoint>() }
    val speedLimitText = remember { mutableStateOf("🔍 Searching...") }
    val trackingActive = remember { mutableStateOf(true) }
    val locationAccuracy = remember { mutableStateOf("--") }
    val updateCount = remember { mutableStateOf(0) }
    val cacheHits = remember { mutableStateOf(0) }
    val hereMapHits = remember { mutableStateOf(0) }
    val osmHits = remember { mutableStateOf(0) }
    val nullSpeedCount = remember { mutableStateOf(0) }

    // Enhanced: Last known valid speed tracking with smart timing
    val lastKnownValidSpeed = remember { mutableStateOf("--") }
    val lastKnownValidSource = remember { mutableStateOf("--") }
    val currentCheckInterval = remember { mutableStateOf(5000L) } // Start with 5 seconds (searching)
    val lastSpeedFound = remember { mutableStateOf(false) } // Start by searching for speed

    // Enhanced animation
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Enhanced tracking loop with smart dynamic timing
    LaunchedEffect(trackingActive.value) {
        while (trackingActive.value) {
            if (hasLocationPermission(context)) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null && location.accuracy <= 12f) { // Enhanced accuracy threshold
                            val latLng = location.latitude to location.longitude
                            val locationPoint = LocationPoint(
                                latLng.first,
                                latLng.second,
                                location.accuracy,
                                System.currentTimeMillis()
                            )

                            Log.i("GPS", "📍 Location: ${latLng.first}, ${latLng.second} (±${location.accuracy}m)")
                            locationAccuracy.value = "${location.accuracy.roundToInt()}m"
                            updateCount.value++

                            // Smart movement detection
                            detectMovement(latLng)

                            // Add to buffer with filtering
                            addLocationToBuffer(traceBuffer, locationPoint)

                            // Check cache first
                            val cacheKey = getCacheKey(latLng.first, latLng.second)
                            val cachedLimit = getCachedSpeedLimit(cacheKey, location.accuracy)

                            if (cachedLimit != null) {
                                val cleanCachedSpeed = cachedLimit.speedLimit.replace(Regex("[^0-9]"), "")
                                if (cleanCachedSpeed.isNotEmpty() && cleanCachedSpeed != "0") {
                                    // ✅ Valid cached speed found
                                    speedLimitText.value = "📋 ${cachedLimit.speedLimit}"
                                    lastKnownValidSpeed.value = cleanCachedSpeed
                                    lastKnownValidSource.value = "Cache"
                                    lastSpeedFound.value = true
                                    currentCheckInterval.value = 20000L // 20 seconds when speed found
                                    cacheHits.value++

                                    Log.i("CACHE", "✅ Cache hit: ${cachedLimit.speedLimit}")
                                    Toast.makeText(context, "✅ Speed limit found: $cleanCachedSpeed km/h (Cache) - checking every 20s", Toast.LENGTH_SHORT).show()
                                } else {
                                    // ❌ Invalid cached data - retain last known valid speed
                                    if (lastKnownValidSpeed.value != "--") {
                                        speedLimitText.value = "📋 ${lastKnownValidSpeed.value} KMH (Retained)"
                                    }
                                    lastSpeedFound.value = false
                                    currentCheckInterval.value = 5000L // 5 seconds when speed is null
                                    nullSpeedCount.value++
                                    Toast.makeText(context, "⚠️ Speed limit unavailable - checking every 5s", Toast.LENGTH_SHORT).show()
                                }

                            } else if (traceBuffer.size >= 2) { // Enhanced HERE Maps + OSM integration
                                val filteredTrace = getFilteredTrace(traceBuffer)
                                matchRoute(filteredTrace, context) { speed, source ->
                                    val cleanSpeed = speed.replace(Regex("[^0-9]"), "")
                                    if (cleanSpeed.isNotEmpty() && cleanSpeed != "0" &&
                                        !speed.contains("--") && !speed.contains("❌") &&
                                        !speed.contains("❓") && !speed.contains("No")) {

                                        // ✅ Valid new speed limit found - update immediately
                                        speedLimitText.value = "🚦 $speed"
                                        lastKnownValidSpeed.value = cleanSpeed
                                        lastKnownValidSource.value = source
                                        lastSpeedFound.value = true
                                        currentCheckInterval.value = 20000L // 20 seconds when speed found

                                        // Cache the valid result
                                        cacheSpeedLimit(cacheKey, speed, location.accuracy)

                                        // Track source statistics
                                        when (source) {
                                            "🗺️ HERE Maps" -> hereMapHits.value++
                                            "🌐 OSM" -> osmHits.value++
                                        }

                                        Log.i("SpeedLimit", "✅ Valid speed limit updated: $cleanSpeed from $source")
                                        Toast.makeText(context, "✅ Speed limit found: $cleanSpeed km/h ($source) - checking every 20s", Toast.LENGTH_SHORT).show()

                                    } else {
                                        // ❌ Invalid or missing speed limit - retain last known valid speed
                                        if (lastKnownValidSpeed.value != "--") {
                                            speedLimitText.value = "🚦 ${lastKnownValidSpeed.value} KMH (Retained)"
                                            Log.i("SpeedLimit", "⚠️ Invalid speed '$speed', retaining last valid: ${lastKnownValidSpeed.value}")
                                            Toast.makeText(context, "⚠️ Speed limit unavailable - retaining: ${lastKnownValidSpeed.value} km/h, checking every 5s", Toast.LENGTH_SHORT).show()
                                        } else {
                                            speedLimitText.value = "❓ No Data Available"
                                            Toast.makeText(context, "❌ Speed limit unavailable - checking every 5s", Toast.LENGTH_SHORT).show()
                                        }

                                        lastSpeedFound.value = false
                                        currentCheckInterval.value = 5000L // 5 seconds when speed is null
                                        nullSpeedCount.value++
                                    }
                                }
                            } else {
                                // Not enough GPS points yet - retain last known speed if available
                                if (lastKnownValidSpeed.value != "--") {
                                    speedLimitText.value = "🚦 ${lastKnownValidSpeed.value} KMH (Retained)"
                                }
                                currentCheckInterval.value = 10000L // 10 seconds when building buffer
                            }
                        } else if (location != null) {
                            Log.w("GPS", "⚠️ Low accuracy: ${location.accuracy}m (threshold: 12m)")
                            locationAccuracy.value = "${location.accuracy.roundToInt()}m (Low)"
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("GPS", "❌ Location error: ${e.message}")
                        locationAccuracy.value = "Error"
                        // Retain last known valid speed on GPS error
                        if (lastKnownValidSpeed.value != "--") {
                            speedLimitText.value = "🚦 ${lastKnownValidSpeed.value} KMH (GPS Error)"
                        }
                        currentCheckInterval.value = 5000L // 5 seconds on GPS error
                    }
            }
            // 🎯 Smart dynamic delay based on speed limit availability
            delay(currentCheckInterval.value)
        }
    }

    // Enhanced UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0f172a),
                        Color(0xFF1e293b),
                        Color(0xFF334155)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1e40af).copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🗺️ HERE Maps Live Tracking",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "🧠 Smart timing: 20s if found, 5s if null",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Speed Limit Display Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        speedLimitText.value.contains("Searching") -> Color(0xFF059669).copy(alpha = 0.2f)
                        speedLimitText.value.contains("Error") -> Color(0xFFdc2626).copy(alpha = 0.2f)
                        speedLimitText.value.contains("Retained") -> Color(0xFFf59e0b).copy(alpha = 0.2f)
                        lastSpeedFound.value -> Color(0xFF2563eb).copy(alpha = 0.2f) // Blue when speed found
                        else -> Color(0xFF059669).copy(alpha = 0.2f) // Green when searching
                    }
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedLimitText.value,
                        fontSize = (28 * pulseScale).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.alpha(alpha),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Enhanced Stats Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📊 Smart Tracking Stats",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatsRow("📍 GPS Accuracy:", locationAccuracy.value)
                            StatsRow("🔄 Updates:", "${updateCount.value}")
                            StatsRow("📋 Cache Hits:", "${cacheHits.value}")
                            StatsRow("🗺️ HERE Hits:", "${hereMapHits.value}")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            StatsRow("🌐 OSM Hits:", "${osmHits.value}")
                            StatsRow("❌ Null Count:", "${nullSpeedCount.value}")
                            StatsRow("📦 Buffer Size:", "${traceBuffer.size}")
                            StatsRow("🏃 Movement:", if (isMoving) "Moving" else "Still")
                        }
                    }

                    // Smart timing status
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (lastSpeedFound.value) Color(0xFF2563eb).copy(alpha = 0.2f) else Color(0xFF059669).copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = "⏱️ Current Mode: ${if (lastSpeedFound.value) "Slow (20s)" else "Fast (5s)"}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "🎯 Strategy: ${if (lastSpeedFound.value) "Conserving - we have data" else "Searching - looking for data"}",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Last Known Valid Speed Info
                    if (lastKnownValidSpeed.value != "--") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF10b981).copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "💾 Last Valid Speed: ${lastKnownValidSpeed.value} km/h",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "📡 Source: ${lastKnownValidSource.value}",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        trackingActive.value = !trackingActive.value
                        if (!trackingActive.value) {
                            speedLimitText.value = "⏸️ Tracking Stopped"
                            locationAccuracy.value = "--"
                        } else {
                            speedLimitText.value = "🔍 Resuming..."
                            updateCount.value = 0
                            cacheHits.value = 0
                            hereMapHits.value = 0
                            osmHits.value = 0
                            nullSpeedCount.value = 0
                            currentCheckInterval.value = 5000L // Start searching
                            lastSpeedFound.value = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (trackingActive.value) Color(0xFFef4444) else Color(0xFF10b981)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (trackingActive.value) "⏹️ Stop" else "▶️ Start",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Button(
                    onClick = {
                        speedLimitCache.clear()
                        cacheHits.value = 0
                        hereMapHits.value = 0
                        osmHits.value = 0
                        nullSpeedCount.value = 0
                        lastKnownValidSpeed.value = "--"
                        lastKnownValidSource.value = "--"
                        speedLimitText.value = "🔍 Searching..."
                        currentCheckInterval.value = 5000L // Reset to search mode
                        lastSpeedFound.value = false
                        Toast.makeText(context, "🗑️ All data cleared - starting fresh search", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8b5cf6)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "🗑️ Reset",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// All the enhanced functions for HERE Maps + OSM integration
private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun detectMovement(currentLocation: Pair<Double, Double>): Double {
    val movement = lastLocation?.let { last ->
        calculateDistance(last.first, last.second, currentLocation.first, currentLocation.second)
    } ?: 0.0

    lastLocation = currentLocation
    isMoving = movement > 5.0 // 5 meters threshold

    if (!isMoving) {
        consecutiveStationary++
    } else {
        consecutiveStationary = 0
    }

    return movement
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * asin(sqrt(a))
    return r * c
}

private fun addLocationToBuffer(buffer: MutableList<LocationPoint>, location: LocationPoint) {
    val cutoffTime = System.currentTimeMillis() - 120000
    buffer.removeAll { it.timestamp < cutoffTime }
    buffer.add(location)
    if (buffer.size > 8) buffer.removeAt(0)
}

private fun getFilteredTrace(buffer: List<LocationPoint>): List<Pair<Double, Double>> {
    return buffer
        .filter { it.accuracy <= 20f }
        .sortedBy { it.timestamp }
        .map { it.lat to it.lon }
}

private fun getCacheKey(lat: Double, lon: Double): String {
    val gridLat = (lat * 1000).roundToInt() / 1000.0
    val gridLon = (lon * 1000).roundToInt() / 1000.0
    return "$gridLat,$gridLon"
}

private fun getCachedSpeedLimit(key: String, currentAccuracy: Float): CachedSpeedLimit? {
    val cached = speedLimitCache[key] ?: return null
    val age = System.currentTimeMillis() - cached.timestamp
    return if (age < 300000 && cached.accuracy <= currentAccuracy + 5) cached else null
}

private fun cacheSpeedLimit(key: String, speedLimit: String, accuracy: Float) {
    speedLimitCache[key] = CachedSpeedLimit(speedLimit, System.currentTimeMillis(), accuracy)
    if (speedLimitCache.size > 100) {
        val oldestKey = speedLimitCache.minByOrNull { it.value.timestamp }?.key
        oldestKey?.let { speedLimitCache.remove(it) }
    }
}

private fun matchRoute(
    gpsPoints: List<Pair<Double, Double>>,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    if (gpsPoints.isEmpty()) return

    if (accessToken == null || isTokenExpired()) {
        fetchAccessToken(gpsPoints, context, onSpeedLimitUpdate)
    } else {
        sendRouteMatchRequest(gpsPoints, context, onSpeedLimitUpdate)
    }
}

private fun fetchAccessToken(
    gpsPoints: List<Pair<Double, Double>>,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    val credentials = "$CLIENT_ID:$CLIENT_SECRET"
    val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    val body = "grant_type=client_credentials".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(TOKEN_URL)
        .addHeader("Authorization", "Basic $encoded")
        .post(body)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("HereAPI", "🔑 Token error: ${e.message}")
            fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
        }

        override fun onResponse(call: Call, response: Response) {
            val json = response.body?.string()
            if (response.isSuccessful && json != null) {
                try {
                    val jsonObj = JSONObject(json)
                    accessToken = jsonObj.optString("access_token")
                    val expiresIn = jsonObj.optInt("expires_in", 3600)
                    accessTokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
                    Log.i("HereAPI", "✅ Token obtained, expires in ${expiresIn}s")
                    sendRouteMatchRequest(gpsPoints, context, onSpeedLimitUpdate)
                } catch (e: Exception) {
                    Log.e("HereAPI", "📋 Token parse error: ${e.message}")
                    fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
                }
            } else {
                Log.w("HereAPI", "⚠️ Token response: ${response.code}")
                fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
            }
        }
    })
}

private fun sendRouteMatchRequest(
    gpsPoints: List<Pair<Double, Double>>,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    val traceArray = JSONArray()
    gpsPoints.forEach { (lat, lon) ->
        traceArray.put(JSONObject().put("lat", lat).put("lon", lon))
    }

    val jsonBody = JSONObject().put("trace", traceArray).toString()
        .toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(ROUTE_MATCH_URL)
        .addHeader("Authorization", "Bearer $accessToken")
        .addHeader("Content-Type", "application/json")
        .post(jsonBody)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("HereAPI", "🗺️ Match fail: ${e.message}")
            fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
        }

        override fun onResponse(call: Call, response: Response) {
            val json = response.body?.string()
            if (response.isSuccessful && json != null) {
                try {
                    parseMatchResponse(JSONObject(json), gpsPoints.first(), context, onSpeedLimitUpdate)
                } catch (e: Exception) {
                    Log.e("HereAPI", "📋 Parse error: ${e.message}")
                    fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
                }
            } else {
                Log.w("HereAPI", "⚠️ Match response: ${response.code}")
                fallbackToOSM(gpsPoints, context, onSpeedLimitUpdate)
            }
        }
    })
}

private fun parseMatchResponse(
    json: JSONObject,
    fallbackLocation: Pair<Double, Double>,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    val matches = json.optJSONArray("match") ?: return fallbackToOSM(listOf(fallbackLocation), context, onSpeedLimitUpdate)

    for (i in 0 until matches.length()) {
        val links = matches.getJSONObject(i).optJSONArray("routeLinks") ?: continue
        for (j in 0 until links.length()) {
            val attr = links.getJSONObject(j).optJSONObject("attributes")
            val speed = attr?.optInt("FROM_REF_SPEED_LIMIT")
            val unit = attr?.optString("UNIT", "KMH")

            if (speed != null && speed > 0) {
                val speedText = "$speed $unit"
                Log.i("HereAPI", "✅ HERE speed limit: $speedText")
                onSpeedLimitUpdate(speedText, "🗺️ HERE Maps")
                return
            }
        }
    }

    Log.i("HereAPI", "🔄 No speed limit in HERE, trying OSM...")
    fallbackToOSM(listOf(fallbackLocation), context, onSpeedLimitUpdate)
}

private fun fallbackToOSM(
    gpsPoints: List<Pair<Double, Double>>,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    gpsPoints.firstOrNull()?.let { (lat, lon) ->
        fetchSpeedLimitFromOsm(lat, lon, context, onSpeedLimitUpdate)
    }
}

private fun fetchSpeedLimitFromOsm(
    lat: Double,
    lon: Double,
    context: Context,
    onSpeedLimitUpdate: (String, String) -> Unit
) {
    val delta = 0.003 // ~300m radius
    val bbox = "${lat - delta},${lon - delta},${lat + delta},${lon + delta}"

    val query = """
        [out:json][timeout:5];
        (
          way[maxspeed]($bbox);
          rel[maxspeed]($bbox);
        );
        out tags;
    """.trimIndent()

    val body = "data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

    val request = Request.Builder()
        .url("https://overpass-api.de/api/interpreter")
        .post(body)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("OSM", "🗺️ OSM error: ${e.message}")
            onSpeedLimitUpdate("❌ No Data", "❌ Error")
        }

        override fun onResponse(call: Call, response: Response) {
            val json = response.body?.string()
            if (response.isSuccessful && json != null) {
                try {
                    val elements = JSONObject(json).optJSONArray("elements") ?: return

                    for (i in 0 until elements.length()) {
                        val tags = elements.getJSONObject(i).optJSONObject("tags")
                        val speed = tags?.optString("maxspeed")

                        if (!speed.isNullOrBlank() && speed != "none") {
                            val cleanSpeed = speed.replace(Regex("[^0-9]"), "")
                            if (cleanSpeed.isNotEmpty()) {
                                val speedText = "$cleanSpeed KMH"
                                Log.i("OSM", "✅ OSM speed limit: $speedText")
                                onSpeedLimitUpdate(speedText, "🌐 OSM")
                                return
                            }
                        }
                    }

                    Log.i("OSM", "⚠️ No speed limit found in OSM")
                    onSpeedLimitUpdate("❓ No Limit", "❓ No Data")
                } catch (e: Exception) {
                    Log.e("OSM", "📋 OSM parse error: ${e.message}")
                    onSpeedLimitUpdate("❌ Parse Error", "❌ Error")
                }
            } else {
                Log.w("OSM", "⚠️ OSM response: ${response.code}")
                onSpeedLimitUpdate("❌ API Error", "❌ Error")
            }
        }
    })
}