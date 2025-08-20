// COMPLETE HIGHWAY CLASSIFICATION DASHBOARD WITH ALL BUG FIXES
// File: app/src/main/java/com/gpstracker/msldapp/uis/DashboardScreen.kt

package com.gpstracker.msldapp.uis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.gpstracker.msldapp.uis.SerialTtlManager
import com.gpstracker.msldapp.R

@RequiresApi(Build.VERSION_CODES.N)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastTtlSendTime by remember { mutableStateOf(0L) }
    var lastSentSpeedLimit by remember { mutableStateOf<Int?>(null) }

    // OSM Speed Lookup with Multi-Factor Selection
    val speedLookup = remember {
        try {
            OsmJsonSpeedLookup(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize JSON OSM lookup", e)
            null
        }
    }

    // 🛣️ Complete Highway Classification Manager with Speed Jump Detection
    val stableManager = remember { StableSpeedLimitManager() }

    val gpsManager = remember {
        try {
            GPSLocationManager(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize GPS manager", e)
            null
        }
    }

    // States
    var error by remember { mutableStateOf<String?>(null) }
    var isGpsTracking by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<LocationData?>(null) }
    var currentSpeedLimit by remember { mutableStateOf<SpeedLimitResult?>(null) }
    var isLookingUpSpeedLimit by remember { mutableStateOf(false) }
    var lastRegion by remember { mutableStateOf("Unknown") }

    // 🛣️ Highway Classification States
    var currentHighwayInfo by remember { mutableStateOf<StableSpeedLimitManager.HighwayInfo?>(null) }
    var currentAltitude by remember { mutableStateOf(0.0) }
    var speedRoadRelation by remember { mutableStateOf("") }
    var enforcementStatus by remember { mutableStateOf("") }

    // 🆕 Direction Tracking States
    var currentCarDirection by remember { mutableStateOf<Float?>(null) }
    var directionTrackingStatus by remember { mutableStateOf("Acquiring...") }

    // 🆕 Speed Jump Detection States
    var isSpeedJumpVerifying by remember { mutableStateOf(false) }
    var speedJumpInfo by remember { mutableStateOf("") }

    // Connection health monitoring
    var consecutiveFailures by remember { mutableStateOf(0) }
    var lastSuccessfulLookup by remember { mutableStateOf(0L) }
    var ttlSendFailures by remember { mutableStateOf(0) }

    // Battery optimization dialog state
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }

    // Manual data sending states
    var manualSpeedLimit by remember { mutableStateOf("") }
    var autoSendSpeedLimits by remember { mutableStateOf(true) }

    // Log state management
    var logUpdateTrigger by remember { mutableStateOf(0) }
    val logs = remember(logUpdateTrigger) {
        try {
            LogCollector.getLogs().takeLast(15).reversed()
        } catch (e: Exception) {
            listOf("❌ Error loading logs: ${e.message}")
        }
    }

    // Memory monitoring
    LaunchedEffect(Unit) {
        while (true) {
            delay(60000)

            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val percentage = (usedMemory * 100 / maxMemory)

            if (percentage > 70) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "🔋 Memory: ${usedMemory}MB/${maxMemory}MB (${percentage}%)"
                )

                if (percentage > 85) {
                    try {
                        speedLookup?.clearCache()
                        System.gc()
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "🧹 Cache cleared - high memory usage"
                        )
                    } catch (e: Exception) {
                        LogCollector.logError("Failed to clear cache", e)
                    }
                }
            }
        }
    }

    // Log update frequency
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000)
            logUpdateTrigger++
        }
    }

    val scrollState = rememberLazyListState()

    // Auto-scroll to latest logs
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollToItem(0)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                speedLookup?.cleanup()
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "🧹 Resources cleaned up"
                )
            } catch (e: Exception) {
                LogCollector.logError("Failed to cleanup resources", e)
            }
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        try {
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineGranted && coarseGranted) {
                LogCollector.addDetailedLog(LogCollector.LogCategory.PERMISSION, "✅ Location permissions granted")
            } else {
                LogCollector.addDetailedLog(LogCollector.LogCategory.PERMISSION, "❌ Location permissions denied")
            }
        } catch (e: Exception) {
            LogCollector.logError("Permission callback error", e)
        }
    }

    // TTL connection handling
    LaunchedEffect(Unit) {
        try {
            LogCollector.addDetailedLog(LogCollector.LogCategory.BACKEND, "🔌 Initializing TTL connection...")
            delay(1000)

            val success = SerialTtlManager.init(context)
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "TTL: ${if (success) "✅ Connected" else "❌ Failed"}"
            )

            if (!success) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ TTL connection failed", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            LogCollector.logError("TTL Init Error", e)
            error = "TTL Init failed: ${e.message}"
        }
    }

    // TTL sending with retry logic
    suspend fun sendTtlWithRetry(speedLimitToSend: Int, reason: String): Boolean {
        var attempts = 0
        var sendSuccess = false

        while (attempts < 3 && !sendSuccess) {
            try {
                // Call sendSpeed and check the return value
                val bytesWritten = SerialTtlManager.sendSpeed(speedLimitToSend, context)

                // A successful write should return exactly 1 byte
                sendSuccess = bytesWritten == 1

                if (sendSuccess) {
                    ttlSendFailures = 0

                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "✅ TTL SUCCESS: ${speedLimitToSend}km/h ($reason) ${currentHighwayInfo?.description ?: ""}"
                    )

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "✅ ${speedLimitToSend}km/h sent ($reason)", Toast.LENGTH_SHORT).show()
                    }

                    stableManager.recordTtlSent(speedLimitToSend)
                } else {
                    // Wrote 0 bytes, which means the command wasn't sent properly
                    attempts++
                    ttlSendFailures++
                    LogCollector.logError("❌ TTL send attempt $attempts failed: wrote $bytesWritten bytes")
                    if (attempts < 3) {
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                attempts++
                ttlSendFailures++
                LogCollector.logError("❌ TTL send attempt $attempts failed", e)
                if (attempts < 3) {
                    delay(1000)
                }
            }
        }

        if (!sendSuccess) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "❌ TTL FAILED after 3 attempts: ${speedLimitToSend}km/h"
            )
        }

        return sendSuccess
    }

    // 🆕 Enhanced OSM lookup with direction and altitude
    suspend fun lookupSpeedLimitWithRetry(
        lat: Double,
        lon: Double,
        speed: Float,
        carDirection: Float? = null,     // 🆕 Car direction parameter
        altitude: Double? = null         // 🆕 Altitude parameter
    ): Pair<SpeedLimitResult?, Map<String, String>> {
        var attempts = 0
        var result: SpeedLimitResult? = null
        var osmTags = emptyMap<String, String>()

        while (attempts < 3 && result == null) {
            try {
                // 🆕 Call enhanced findSpeedLimit with direction and altitude
                result = speedLookup?.findSpeedLimit(lat, lon, speed, carDirection, altitude)
                if (result != null) {
                    // 🛣️ Extract OSM tags for highway classification
                    osmTags = speedLookup?.getLastOsmTags() ?: emptyMap()

                    consecutiveFailures = 0
                    lastSuccessfulLookup = System.currentTimeMillis()
                    if (attempts > 0) {
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.OSM,
                            "✅ Multi-factor OSM lookup succeeded on attempt ${attempts + 1}"
                        )
                    }
                    return Pair(result, osmTags)
                }
            } catch (e: Exception) {
                LogCollector.logError("❌ Multi-factor OSM lookup attempt ${attempts + 1} failed", e)
            }

            attempts++
            if (attempts < 3 && result == null) {
                delay(500)
            }
        }

        consecutiveFailures++
        if (consecutiveFailures > 5) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "⚠️ Multiple multi-factor OSM lookup failures ($consecutiveFailures) - possible connection issue"
            )
        }

        return Pair(null, emptyMap())
    }

    // 🛣️ GPS TRACKING WITH COMPLETE HIGHWAY CLASSIFICATION + DIRECTION + SPEED JUMP DETECTION
    LaunchedEffect(isGpsTracking) {
        if (isGpsTracking && gpsManager?.hasLocationPermission() == true) {

            gpsManager.startLocationTracking()

            if (!gpsManager.isBatteryOptimizationDisabled()) {
                showBatteryOptimizationDialog = true
            }

            LogCollector.addDetailedLog(LogCollector.LogCategory.GPS, "🛣️ GPS tracking with complete highway classification + direction + speed jump detection started")

            try {
                gpsManager.getLocationUpdates().collect { location ->
                    try {
                        currentLocation = location
                        currentAltitude = location.altitude
                        currentCarDirection = location.carDirection  // 🆕 Update car direction

                        // 🆕 Update direction tracking status
                        directionTrackingStatus = if (location.carDirection != null) {
                            "Tracking: ${location.carDirection!!.toInt()}°"
                        } else {
                            when {
                                location.speedKmh > 10f -> "Calculating..."
                                else -> "Too slow for direction"
                            }
                        }

                        val currentRegion = when {
                            location.latitude in 24.7..25.5 && location.longitude in 54.8..55.7 -> "Dubai/Sharjah"
                            location.latitude in 25.3..25.7 && location.longitude in 55.4..56.0 -> "Northern Emirates"
                            location.latitude in 22.6..24.8 && location.longitude in 51.5..56.1 -> "Abu Dhabi"
                            location.latitude in 12.7..13.2 && location.longitude in 77.3..77.9 -> "Bengaluru"
                            else -> "Outside Coverage"
                        }

                        if (currentRegion != lastRegion) {
                            LogCollector.addDetailedLog(
                                LogCollector.LogCategory.GPS,
                                "📍 Region: $currentRegion (${String.format("%.1f", location.speedKmh)} km/h, Dir: ${if (location.carDirection != null) "${location.carDirection!!.toInt()}°" else "N/A"}, Alt: ${String.format("%.1f", location.altitude)}m)"
                            )
                            lastRegion = currentRegion
                        }

                        if (!isLookingUpSpeedLimit && speedLookup != null) {
                            isLookingUpSpeedLimit = true

                            val shouldLog = location.speedKmh > 60f || (location.speedKmh > 20f && System.currentTimeMillis() % 5000 < 1000)

                            if (shouldLog) {
                                LogCollector.addDetailedLog(
                                    LogCollector.LogCategory.OSM,
                                    "🗺️ Multi-factor highway lookup: ${String.format("%.1f", location.speedKmh)} km/h (Dir: ${if (location.carDirection != null) "${location.carDirection!!.toInt()}°" else "N/A"}, Alt: ${String.format("%.1f", location.altitude)}m)"
                                )
                            }

                            scope.launch {
                                try {
                                    // 🆕 Enhanced OSM data lookup with direction and altitude
                                    val (rawSpeedInfo, osmTags) = lookupSpeedLimitWithRetry(
                                        location.latitude,
                                        location.longitude,
                                        location.speedKmh,
                                        location.carDirection,  // 🆕 Pass car direction
                                        location.altitude       // 🆕 Pass altitude
                                    )

                                    // 🛣️ Use complete highway classification manager with speed jump detection
                                    val stableResult = stableManager.getStableSpeedLimit(
                                        location.latitude,
                                        location.longitude,
                                        location.altitude,  // 🆕 ALTITUDE
                                        location.speedKmh,
                                        rawSpeedInfo?.speedLimit,
                                        osmTags  // 🆕 OSM TAGS FOR HIGHWAY CLASSIFICATION
                                    )

                                    // 🆕 Handle SPEED JUMP VERIFICATION states
                                    when (stableResult) {
                                        // 🆕 NEW: Handle Speed Jump Verification
                                        is StableSpeedResult.SpeedJumpVerification -> {
                                            val currentLimit = stableResult.currentSpeedLimit
                                            val pendingLimit = stableResult.newSpeedLimit
                                            val shouldSendTtl = stableResult.sendToTtl
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = true
                                            speedJumpInfo = "🚨 SPEED JUMP: ${currentLimit}→${pendingLimit}km/h (${stableResult.jumpSize}km/h jump, ${stableResult.verificationCount}/${stableResult.requiredVerifications} checks)"

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → VERIFYING ${currentLimit}km/h→${pendingLimit}km/h (Jump: ${stableResult.jumpSize}km/h)"
                                            } else {
                                                "Unknown road → VERIFYING ${currentLimit}km/h→${pendingLimit}km/h"
                                            }

                                            enforcementStatus = "🚨 SPEED JUMP VERIFICATION: ${stableResult.jumpSize}km/h change needs ${stableResult.requiredVerifications} checks (${stableResult.nextCheckIn}s)"

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = currentLimit,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - 🚨 Speed Jump Verification (${stableResult.verificationCount}/${stableResult.requiredVerifications}) ${if (shouldSendTtl) "📤 TTL SENT" else "🔄 TTL WAIT"}",
                                                roadType = "speed_jump_verification",
                                                confidence = 0.9f,
                                                source = "speed_jump_detection",
                                                distance = 0.0
                                            )

                                            LogCollector.addDetailedLog(
                                                LogCollector.LogCategory.OSM,
                                                "🚨 SPEED JUMP VERIFICATION: ${currentLimit}km/h→${pendingLimit}km/h on ${highwayInfo?.description ?: "Unknown"} (${stableResult.jumpSize}km/h jump) - TTL: ${if (shouldSendTtl) "SEND" else "WAIT"}"
                                            )

                                            if (shouldSendTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (currentLimit > 140) 140 else currentLimit
                                                val success = sendTtlWithRetry(speedLimitToSend, "speed_jump_verification")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        // 🛣️ STABLE/CONFIRMED - WITH HIGHWAY INFO
                                        is StableSpeedResult.Confirmed -> {
                                            val limitValue = stableResult.speedLimit
                                            val shouldSendTtl = stableResult.sendToTtl
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            // 🛣️ Speed vs Road Type Relationship
                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${limitValue}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${limitValue}km/h"
                                            }

                                            // 🛣️ Highway Enforcement Status
                                            enforcementStatus = if (highwayInfo?.isHighway == true) {
                                                if (limitValue >= 80) "✅ Highway rule OK (${limitValue}≥80)"
                                                else "🚨 Highway enforced (${limitValue}→80+)"
                                            } else {
                                                "ℹ️ Regular road (no enforcement)"
                                            }

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = limitValue,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} (${stableResult.timeSinceConfirmed/1000}s) ${if (shouldSendTtl) "📤 TTL SENT" else "🔄 TTL WAIT"}",
                                                roadType = "confirmed",
                                                confidence = 0.9f,
                                                source = "stable_highway_with_direction",
                                                distance = 0.0
                                            )

                                            LogCollector.addDetailedLog(
                                                LogCollector.LogCategory.OSM,
                                                "🔒 STABLE: ${limitValue}km/h on ${highwayInfo?.description ?: "Unknown"} (Dir: ${if (location.carDirection != null) "${location.carDirection!!.toInt()}°" else "N/A"}, Alt: ${String.format("%.1f", altitude)}m) - TTL: ${if (shouldSendTtl) "SEND" else "WAIT"}"
                                            )

                                            if (shouldSendTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (limitValue > 140) 140 else limitValue
                                                val success = sendTtlWithRetry(speedLimitToSend, "stable_highway_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        // 🛣️ NEW CONFIRMED - WITH HIGHWAY INFO
                                        is StableSpeedResult.NewConfirmed -> {
                                            val limitValue = stableResult.speedLimit
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${limitValue}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${limitValue}km/h"
                                            }

                                            enforcementStatus = if (highwayInfo?.isHighway == true) {
                                                if (limitValue >= 80) "✅ Highway rule OK (${limitValue}≥80)"
                                                else "🚨 Highway enforced (${limitValue}→80+)"
                                            } else {
                                                "ℹ️ Regular road (no enforcement)"
                                            }

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = limitValue,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - New Road Confirmed (${stableResult.votesUsed} votes) 📤 TTL SENT",
                                                roadType = "new_confirmed",
                                                confidence = 0.95f,
                                                source = "voting_highway_winner_with_direction",
                                                distance = 0.0
                                            )

                                            LogCollector.addDetailedLog(
                                                LogCollector.LogCategory.OSM,
                                                "🆕 NEW ROAD: ${limitValue}km/h on ${highwayInfo?.description ?: "Unknown"} (Dir: ${if (location.carDirection != null) "${location.carDirection!!.toInt()}°" else "N/A"}, Alt: ${String.format("%.1f", altitude)}m) - TTL: SEND"
                                            )

                                            if (stableResult.sendToTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (limitValue > 140) 140 else limitValue
                                                val success = sendTtlWithRetry(speedLimitToSend, "new_highway_confirmed_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        // 🛣️ VOTING - WITH HIGHWAY INFO
                                        is StableSpeedResult.Voting -> {
                                            val limitValue = stableResult.leadingCandidate
                                            val shouldSendTtl = stableResult.sendToTtl
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${limitValue}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${limitValue}km/h"
                                            }

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = limitValue,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - Voting... ${stableResult.progress} ${if (shouldSendTtl) "📤 TTL SENT" else "🔄 TTL WAIT"}",
                                                roadType = "voting",
                                                confidence = 0.5f,
                                                source = "voting_highway_progress_with_direction",
                                                distance = 0.0
                                            )

                                            LogCollector.addDetailedLog(
                                                LogCollector.LogCategory.OSM,
                                                "🗳️ VOTING: ${stableResult.progress}, leading: ${limitValue}km/h on ${highwayInfo?.description ?: "Unknown"} (Dir: ${if (location.carDirection != null) "${location.carDirection!!.toInt()}°" else "N/A"}) - TTL: ${if (shouldSendTtl) "SEND" else "WAIT"}"
                                            )

                                            if (shouldSendTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (limitValue > 140) 140 else limitValue
                                                val success = sendTtlWithRetry(speedLimitToSend, "voting_highway_continuous_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        // Handle other existing result types...
                                        is StableSpeedResult.UsingLastKnown -> {
                                            val limitValue = stableResult.speedLimit
                                            val shouldSendTtl = stableResult.sendToTtl
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${limitValue}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${limitValue}km/h"
                                            }

                                            enforcementStatus = if (highwayInfo?.isHighway == true) {
                                                if (limitValue >= 80) "✅ Highway rule OK (${limitValue}≥80)"
                                                else "🚨 Highway enforced (${limitValue}→80+)"
                                            } else {
                                                "ℹ️ Regular road (no enforcement)"
                                            }

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = limitValue,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - Last Known (${stableResult.timeSinceLastKnown/1000}s ago, #${stableResult.noDataCount}) ${if (shouldSendTtl) "📤 TTL SENT" else "🔄 TTL WAIT"}",
                                                roadType = "last_known",
                                                confidence = if (shouldSendTtl) 0.8f else 0.6f,
                                                source = "last_known_highway_with_direction_${stableResult.ttlReason}",
                                                distance = 0.0
                                            )

                                            if (shouldSendTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (limitValue > 140) 140 else limitValue
                                                val success = sendTtlWithRetry(speedLimitToSend, "last_known_highway_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        is StableSpeedResult.Verifying -> {
                                            val currentLimit = stableResult.currentSpeedLimit
                                            val pendingLimit = stableResult.newSpeedLimit
                                            val shouldSendTtl = stableResult.sendToTtl
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${currentLimit}km/h→${pendingLimit}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${currentLimit}km/h→${pendingLimit}km/h"
                                            }

                                            val verificationMode = if (pendingLimit > currentLimit) "FAST (2 checks)" else "SLOW (${stableResult.requiredVerifications} checks)"

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = currentLimit,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - Verifying ${pendingLimit}km/h (${stableResult.verificationCount}/${stableResult.requiredVerifications}, $verificationMode) ${if (shouldSendTtl) "📤 TTL SENT" else "🔄 TTL WAIT"}",
                                                roadType = "verifying",
                                                confidence = 0.8f,
                                                source = "highway_verification_smart_with_direction",
                                                distance = 0.0
                                            )

                                            if (shouldSendTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (currentLimit > 140) 140 else currentLimit
                                                val success = sendTtlWithRetry(speedLimitToSend, "verification_highway_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        is StableSpeedResult.VerificationComplete -> {
                                            val limitValue = stableResult.speedLimit
                                            val highwayInfo = stableResult.highwayInfo
                                            val altitude = stableResult.altitude

                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""

                                            currentHighwayInfo = highwayInfo
                                            currentAltitude = altitude

                                            speedRoadRelation = if (highwayInfo != null) {
                                                "${highwayInfo.icon} ${highwayInfo.type} → ${limitValue}km/h (Min: ${highwayInfo.minSpeedLimit}km/h)"
                                            } else {
                                                "Unknown road → ${limitValue}km/h"
                                            }

                                            enforcementStatus = if (highwayInfo?.isHighway == true) {
                                                if (limitValue >= 80) "✅ Highway rule OK (${limitValue}≥80)"
                                                else "🚨 Highway enforced (${limitValue}→80+)"
                                            } else {
                                                "ℹ️ Regular road (no enforcement)"
                                            }

                                            currentSpeedLimit = SpeedLimitResult(
                                                speedLimit = limitValue,
                                                roadName = "${highwayInfo?.description ?: "Unknown Road"} - Verified Speed (${stableResult.checksPerformed} checks) 📤 TTL SENT",
                                                roadType = "verification_complete",
                                                confidence = 1.0f,
                                                source = "highway_verification_complete_with_direction",
                                                distance = 0.0
                                            )

                                            if (stableResult.sendToTtl && autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                                val speedLimitToSend = if (limitValue > 140) 140 else limitValue
                                                val success = sendTtlWithRetry(speedLimitToSend, "verification_highway_complete_direction")
                                                if (success) {
                                                    lastTtlSendTime = System.currentTimeMillis()
                                                    lastSentSpeedLimit = speedLimitToSend
                                                }
                                            }
                                        }

                                        is StableSpeedResult.NoData -> {
                                            isSpeedJumpVerifying = false
                                            speedJumpInfo = ""
                                            LogCollector.addDetailedLog(
                                                LogCollector.LogCategory.OSM,
                                                "🚫 No speed data available and no last known"
                                            )
                                        }
                                    }

                                    // Speed warning with highway info and direction
                                    if (stableResult is StableSpeedResult.Confirmed ||
                                        stableResult is StableSpeedResult.NewConfirmed ||
                                        stableResult is StableSpeedResult.UsingLastKnown ||
                                        stableResult is StableSpeedResult.Verifying ||
                                        stableResult is StableSpeedResult.VerificationComplete ||
                                        stableResult is StableSpeedResult.SpeedJumpVerification) {
                                        val limitValue = when (stableResult) {
                                            is StableSpeedResult.Confirmed -> stableResult.speedLimit
                                            is StableSpeedResult.NewConfirmed -> stableResult.speedLimit
                                            is StableSpeedResult.UsingLastKnown -> stableResult.speedLimit
                                            is StableSpeedResult.Verifying -> stableResult.currentSpeedLimit
                                            is StableSpeedResult.VerificationComplete -> stableResult.speedLimit
                                            is StableSpeedResult.SpeedJumpVerification -> stableResult.currentSpeedLimit
                                            else -> 0
                                        }

                                        if (location.speedKmh > limitValue + 10) {
                                            val highwayType = currentHighwayInfo?.type ?: "Road"
                                            val directionInfo = if (location.carDirection != null) " heading ${location.carDirection!!.toInt()}°" else ""
                                            Handler(Looper.getMainLooper()).post {
                                                Toast.makeText(
                                                    context,
                                                    "⚠️ ${String.format("%.0f", location.speedKmh)}>${limitValue}km/h on $highwayType$directionInfo!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }

                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    currentSpeedLimit = null
                                    isSpeedJumpVerifying = false
                                    speedJumpInfo = ""
                                    LogCollector.logError("❌ Highway speed lookup error", e)
                                } finally {
                                    delay(100)
                                    isLookingUpSpeedLimit = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogCollector.logError("❌ Location processing error", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.logError("❌ GPS tracking error", e)
                isGpsTracking = false
                error = "GPS tracking failed: ${e.message}"
            } finally {
                gpsManager.stopLocationTracking()
            }

        } else if (!isGpsTracking) {
            gpsManager?.stopLocationTracking()
            currentCarDirection = null  // 🆕 Reset direction
            directionTrackingStatus = "Stopped"  // 🆕 Reset direction status
            isSpeedJumpVerifying = false  // 🆕 Reset speed jump status
            speedJumpInfo = ""  // 🆕 Reset speed jump info
            LogCollector.addDetailedLog(LogCollector.LogCategory.GPS, "⏹️ GPS tracking stopped")
        }
    }

    // MAIN UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // LOGO AND TITLE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Thinture Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .padding(bottom = 8.dp)
                )

                Text(
                    "MSLD Complete Bug Fix System",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    "🛣️ ALL BUGS FIXED: Speed Jump Detection + Direction Awareness + Multi-Factor Road Selection + Highway Stickiness + Smart Verification",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // MAIN CONTENT
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 🆕 SPEED JUMP DETECTION STATUS CARD
            if (isSpeedJumpVerifying && speedJumpInfo.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("🚨 Speed Jump Detection", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFFC62828))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                speedJumpInfo,
                                color = Color(0xFFC62828),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "System is carefully verifying large speed changes to prevent false alerts.",
                                color = Color(0xFF757575),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // GPS STATUS CARD WITH DIRECTION
            item {
                FixedGpsCard(
                    isGpsTracking = isGpsTracking,
                    currentLocation = currentLocation,
                    gpsManager = gpsManager,
                    currentRegion = lastRegion,
                    consecutiveFailures = consecutiveFailures,
                    ttlSendFailures = ttlSendFailures,
                    stableManager = stableManager,
                    currentHighwayInfo = currentHighwayInfo,
                    currentAltitude = currentAltitude,
                    currentCarDirection = currentCarDirection,  // 🆕 Car direction
                    directionTrackingStatus = directionTrackingStatus,  // 🆕 Direction status
                    onToggleTracking = {
                        try {
                            val hasPermission = gpsManager?.hasLocationPermission() ?: false
                            if (!hasPermission) {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else {
                                isGpsTracking = !isGpsTracking
                                if (!isGpsTracking) {
                                    currentLocation = null
                                    currentSpeedLimit = null
                                    currentHighwayInfo = null
                                    currentAltitude = 0.0
                                    currentCarDirection = null  // 🆕 Reset direction
                                    directionTrackingStatus = "Stopped"  // 🆕 Reset direction status
                                    isSpeedJumpVerifying = false  // 🆕 Reset speed jump status
                                    speedJumpInfo = ""  // 🆕 Reset speed jump info
                                    speedRoadRelation = ""
                                    enforcementStatus = ""
                                    stableManager.clearAll()
                                }
                            }
                        } catch (e: Exception) {
                            LogCollector.logError("GPS button error", e)
                        }
                    },
                    onRefreshLocation = {
                        scope.launch {
                            try {
                                val loc = gpsManager?.requestSingleLocation()
                                if (loc != null) {
                                    currentLocation = loc
                                    currentCarDirection = loc.carDirection  // 🆕 Update direction
                                    Toast.makeText(context, "📍 Location updated", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "⚠️ Could not get location", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                LogCollector.logError("Location refresh error", e)
                            }
                        }
                    }
                )
            }

            // 🛣️ HIGHWAY CLASSIFICATION CARD WITH DIRECTION
            item {
                FixedHighwayClassificationCard(
                    currentHighwayInfo = currentHighwayInfo,
                    currentAltitude = currentAltitude,
                    speedRoadRelation = speedRoadRelation,
                    enforcementStatus = enforcementStatus,
                    currentCarDirection = currentCarDirection,  // 🆕 Car direction
                    directionTrackingStatus = directionTrackingStatus  // 🆕 Direction status
                )
            }

            // SPEED LIMIT CARD WITH DIRECTION AND JUMP DETECTION
            item {
                FixedHighwaySpeedCard(
                    currentSpeedLimit = currentSpeedLimit,
                    currentLocation = currentLocation,
                    isLookingUpSpeedLimit = isLookingUpSpeedLimit,
                    currentHighwayInfo = currentHighwayInfo,
                    currentAltitude = currentAltitude,
                    currentCarDirection = currentCarDirection,  // 🆕 Car direction
                    isSpeedJumpVerifying = isSpeedJumpVerifying  // 🆕 Speed jump status
                )
            }

            // TTL CONTROL CARD
            item {
                FixedTtlControlCard(
                    autoSendSpeedLimits = autoSendSpeedLimits,
                    onAutoSendToggle = {
                        autoSendSpeedLimits = it
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "Auto-send: ${if (it) "ON (complete bug fix system - 20s)" else "OFF"}"
                        )
                    },
                    manualSpeedLimit = manualSpeedLimit,
                    onManualSpeedChange = { newValue ->
                        if (newValue.isEmpty() || (newValue.toIntOrNull()?.let { num -> num in 0..255 } == true)) {
                            manualSpeedLimit = newValue
                        }
                    },
                    onSendManual = {
                        scope.launch {
                            try {
                                val speedInt = manualSpeedLimit.toIntOrNull()
                                if (speedInt != null && speedInt in 0..255 && SerialTtlManager.isConnected) {
                                    val cappedSpeed = if (speedInt > 140) 140 else speedInt
                                    val success = sendTtlWithRetry(cappedSpeed, "manual_fixed_system")

                                    if (success) {
                                        manualSpeedLimit = ""
                                        val message = if (speedInt > 140) {
                                            "✅ Speed $cappedSpeed sent (manual fixed system, capped from $speedInt)"
                                        } else {
                                            "✅ Speed $cappedSpeed sent (manual fixed system)"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "⚠️ Invalid speed or not connected", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                LogCollector.logError("Manual send failed", e)
                            }
                        }
                    },
                    onQuickSend = { value ->
                        scope.launch {
                            try {
                                if (SerialTtlManager.isConnected) {
                                    val cappedValue = if (value > 140) 140 else value
                                    val success = sendTtlWithRetry(cappedValue, "quick_fixed_system")

                                    if (success) {
                                        val message = if (value > 140) {
                                            "✅ Sent: $cappedValue km/h (quick fixed system, capped from $value km/h)"
                                        } else {
                                            "✅ Sent: $cappedValue km/h (quick fixed system)"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "❌ TTL not connected", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                LogCollector.logError("Quick send failed", e)
                            }
                        }
                    },
                    speedLookup = speedLookup,
                    currentLocation = currentLocation,
                    context = context,
                    lastTtlSendTime = lastTtlSendTime,
                    stableManager = stableManager
                )
            }

            // ERROR DISPLAY
            error?.let { errorMessage ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { error = null }) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            // ACTIVITY LOG
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📋 Complete Bug Fix System Log", fontWeight = FontWeight.Bold)

                            Button(
                                onClick = {
                                    LogCollector.clearLogs()
                                    Toast.makeText(context, "🧹 Logs cleared", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (logs.isEmpty()) {
                            Text(
                                "No activity yet...",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            logs.take(6).forEach { logLine ->
                                val isSpeedJumpLog = logLine.contains("SPEED JUMP") || logLine.contains("🚨")
                                val isDirectionLog = logLine.contains("Direction") || logLine.contains("🧭")
                                val isHighwayLog = logLine.contains("MOTORWAY") || logLine.contains("TRUNK") || logLine.contains("PRIMARY") || logLine.contains("BRIDGE") || logLine.contains("TUNNEL")
                                val isTtlLog = logLine.contains("TTL") || logLine.contains("📤")
                                val isMultiFactorLog = logLine.contains("Multi-factor") || logLine.contains("MULTI-FACTOR")

                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                    fontSize = 11.sp,
                                    color = when {
                                        isSpeedJumpLog -> Color(0xFFE91E63) // Pink for speed jump
                                        isDirectionLog -> Color(0xFF9C27B0) // Purple for direction
                                        isMultiFactorLog -> Color(0xFF673AB7) // Deep purple for multi-factor
                                        isHighwayLog -> Color(0xFF2196F3) // Blue for highway
                                        isTtlLog -> Color(0xFF4CAF50) // Green for TTL
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // BATTERY OPTIMIZATION DIALOG
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = { Text("🔋 Battery Optimization", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "For uninterrupted tracking with all bug fixes, please disable battery optimization.\n\n" +
                            "This ensures TTL is sent every 20 seconds with complete highway analysis, direction tracking, and speed jump detection.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "🔋 Battery optimization settings opened"
                        )
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e2: Exception) {
                            LogCollector.logError("❌ Failed to open battery settings", e2)
                            Toast.makeText(context, "❌ Could not open settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showBatteryOptimizationDialog = false
                }) {
                    Text("Open Settings", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryOptimizationDialog = false
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "⚠️ Battery optimization dialog dismissed"
                    )
                }) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// 🆕 FIXED GPS CARD WITH DIRECTION TRACKING
@Composable
private fun FixedGpsCard(
    isGpsTracking: Boolean,
    currentLocation: LocationData?,
    gpsManager: GPSLocationManager?,
    currentRegion: String,
    consecutiveFailures: Int,
    ttlSendFailures: Int,
    stableManager: StableSpeedLimitManager,
    currentHighwayInfo: StableSpeedLimitManager.HighwayInfo?,
    currentAltitude: Double,
    currentCarDirection: Float?,  // 🆕 Car direction
    directionTrackingStatus: String,  // 🆕 Direction status
    onToggleTracking: () -> Unit,
    onRefreshLocation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGpsTracking)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🛣️ GPS + Complete Bug Fix System", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Spacer(modifier = Modifier.height(8.dp))

            currentLocation?.let { loc ->
                Text("📍 ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}")
                Text("🎯 Accuracy: ${String.format("%.1f", loc.accuracy)}m")
                Text("🏔️ Altitude: ${String.format("%.1f", currentAltitude)}m", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)

                val speedColor = when {
                    loc.speedKmh > 80f -> MaterialTheme.colorScheme.error
                    loc.speedKmh > 50f -> Color(0xFFFFC107)
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(
                    "🚀 Speed: ${String.format("%.1f", loc.speedKmh)} km/h",
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )

                // 🆕 DIRECTION TRACKING DISPLAY
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "🧭 Direction Tracking: $directionTrackingStatus",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3F51B5),
                            fontSize = 12.sp
                        )
                        if (currentCarDirection != null) {
                            Text(
                                "Heading: ${currentCarDirection.toInt()}° (${getDirectionText(currentCarDirection)})",
                                color = Color(0xFF3F51B5),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text("🗺️ Region: $currentRegion", fontWeight = FontWeight.Bold)

                // Highway classification status
                currentHighwayInfo?.let { highway ->
                    Text(
                        "${highway.icon} Highway Type: ${highway.description}",
                        color = if (highway.isHighway) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                } ?: Text(
                    "❓ Highway: Analyzing...",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

                // Speed limit status
                val hasStableLimit = stableManager.hasStableSpeedLimit()
                val confirmedLimit = stableManager.getConfirmedSpeedLimit()
                val lastKnown = stableManager.getLastKnownSpeedLimit()
                val canUseLast = stableManager.canUseLastKnown()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (hasStableLimit && confirmedLimit != null) {
                        Text(
                            "🔒 Confirmed: ${confirmedLimit}km/h",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "🔍 Processing...",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (lastKnown != null) {
                        Text(
                            "🔄 Last: ${lastKnown}km/h${if (canUseLast) " ✅" else " ❌"}",
                            color = if (canUseLast) Color(0xFF4CAF50) else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                Text(
                    "📤 TTL Mode: Complete Bug Fix System + Continuous (20s)",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                // 🆕 Bug fix status display
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "✅ All Bug Fixes Active:",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32),
                            fontSize = 12.sp
                        )
                        Text("• Speed Jump Detection", color = Color(0xFF388E3C), fontSize = 10.sp)
                        Text("• Direction Awareness", color = Color(0xFF388E3C), fontSize = 10.sp)
                        Text("• Multi-Factor Road Selection", color = Color(0xFF388E3C), fontSize = 10.sp)
                        Text("• Highway Stickiness", color = Color(0xFF388E3C), fontSize = 10.sp)
                    }
                }

                // Health monitoring
                if (consecutiveFailures > 0 || ttlSendFailures > 0) {
                    Text(
                        "⚠️ Issues: OSM:$consecutiveFailures TTL:$ttlSendFailures",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        "✅ All systems healthy",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Text(
                    "🔋 Protection: ${if (gpsManager?.isWakeLockActive() == true) "✅ Active" else "⚠️ Inactive"}",
                    color = if (gpsManager?.isWakeLockActive() == true)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            } ?: Text(
                if (isGpsTracking) "📡 Acquiring GPS signal for complete analysis..." else "📍 Complete bug fix system stopped",
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleTracking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGpsTracking)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    val hasPermission = gpsManager?.hasLocationPermission() ?: false
                    Text(
                        if (isGpsTracking) "⏹️ Stop"
                        else if (hasPermission) "🛣️ Start Fixed"
                        else "🔓 Enable"
                    )
                }

                Button(
                    onClick = onRefreshLocation,
                    enabled = gpsManager?.hasLocationPermission() ?: false,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🔄 Refresh")
                }
            }
        }
    }
}

// 🆕 Helper function to get direction text
private fun getDirectionText(degrees: Float): String {
    return when ((degrees + 22.5f) % 360) {
        in 0f..45f -> "N"
        in 45f..90f -> "NE"
        in 90f..135f -> "E"
        in 135f..180f -> "SE"
        in 180f..225f -> "S"
        in 225f..270f -> "SW"
        in 270f..315f -> "W"
        in 315f..360f -> "NW"
        else -> "N"
    }
}

// 🆕 FIXED HIGHWAY CLASSIFICATION CARD WITH DIRECTION // CHANGES NEEDED IN DashboardScreen.kt to remove ground/flyover level display

// 1. REMOVE the Ground Level Card from FixedHighwayClassificationCard
@Composable
private fun FixedHighwayClassificationCard(
    currentHighwayInfo: StableSpeedLimitManager.HighwayInfo?,
    currentAltitude: Double,
    speedRoadRelation: String,
    enforcementStatus: String,
    currentCarDirection: Float?,
    directionTrackingStatus: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                currentHighwayInfo?.isHighway == true -> MaterialTheme.colorScheme.primaryContainer
                currentHighwayInfo != null -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🛣️ Road Classification", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Spacer(modifier = Modifier.height(8.dp))

            // Direction Information Card (keep this)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🧭 Direction Awareness",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3F51B5)
                    )

                    Text(
                        "Status: $directionTrackingStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3F51B5)
                    )

                    if (currentCarDirection != null) {
                        Text(
                            "Car Heading: ${currentCarDirection.toInt()}° (${getDirectionText(currentCarDirection)})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A237E)
                        )
                        Text(
                            "✅ Direction data used for accurate road selection",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    } else {
                        Text(
                            "⏳ Acquiring direction data for improved accuracy...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF757575)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            currentHighwayInfo?.let { highway ->
                // Highway Type (simplified - no level info)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (highway.isHighway) Color(0xFFE3F2FD) else Color(0xFFFFF3E0)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${highway.icon} ${highway.type}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (highway.isHighway) Color(0xFF1976D2) else Color(0xFFE65100)
                        )

                        // SIMPLIFIED description without level info
                        Text(
                            "${highway.type} Road",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Priority: ${highway.priority}/100 • Min Speed: ${highway.minSpeedLimit}km/h",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // REMOVE the Ground Level Card entirely

                Spacer(modifier = Modifier.height(8.dp))

                // Speed vs Road Relationship (simplified)
                if (speedRoadRelation.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "📊 Road Analysis:",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                            // SIMPLIFIED relation without level info
                            val simplifiedRelation = speedRoadRelation
                                .replace(Regex("\\(BRIDGE.*?\\)"), "")
                                .replace(Regex("\\(TUNNEL.*?\\)"), "")
                                .replace(Regex("\\(ELEVATED.*?\\)"), "")
                                .replace(Regex("\\(GROUND.*?\\)"), "")
                                .replace(Regex("\\(EXPRESSWAY.*?\\)"), "")
                                .trim()

                            Text(
                                simplifiedRelation,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF388E3C),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "✅ Direction + Distance + Speed matching for accurate selection",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Highway Enforcement
                if (enforcementStatus.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (enforcementStatus.startsWith("✅")) Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "🚨 System Status:",
                                fontWeight = FontWeight.Bold,
                                color = if (enforcementStatus.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                            Text(
                                enforcementStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enforcementStatus.startsWith("✅")) Color(0xFF388E3C) else Color(0xFFD32F2F),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

            } ?: run {
                Text(
                    "🔍 Analyzing road type...",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Start GPS tracking to see road classification",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// 2. SIMPLIFY the Speed Card info section
@Composable
private fun FixedHighwaySpeedCard(
    currentSpeedLimit: SpeedLimitResult?,
    currentLocation: LocationData?,
    isLookingUpSpeedLimit: Boolean,
    currentHighwayInfo: StableSpeedLimitManager.HighwayInfo?,
    currentAltitude: Double,
    currentCarDirection: Float?,
    isSpeedJumpVerifying: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSpeedJumpVerifying -> Color(0xFFFFEBEE)
                currentSpeedLimit?.roadName?.contains("📤 TTL SENT") == true -> Color(0xFFE8F5E8)
                currentSpeedLimit?.roadName?.contains("🔄 TTL WAIT") == true -> Color(0xFFFFF3E0)
                currentSpeedLimit?.source?.contains("highway") == true -> MaterialTheme.colorScheme.primaryContainer
                currentSpeedLimit?.source?.contains("voting") == true -> MaterialTheme.colorScheme.secondaryContainer
                currentSpeedLimit?.source?.contains("verification") == true -> Color(0xFFFFF3E0)
                isLookingUpSpeedLimit -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛣️ Speed Limit System", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (isLookingUpSpeedLimit) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLookingUpSpeedLimit -> {
                    Text("⚡ Analyzing road and speed limit...", color = Color.Gray)
                    Text("🛣️ Using direction, distance, and speed matching", color = Color.Gray, fontSize = 12.sp)
                }

                currentSpeedLimit?.speedLimit != null -> {
                    val actualLimit = currentSpeedLimit.speedLimit!!

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${actualLimit} km/h",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isSpeedJumpVerifying -> Color(0xFFC62828)
                                currentSpeedLimit.roadName?.contains("📤 TTL SENT") == true -> Color(0xFF4CAF50)
                                currentSpeedLimit.roadName?.contains("🔄 TTL WAIT") == true -> Color(0xFFFF9800)
                                currentSpeedLimit.source?.contains("verification") == true -> Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            currentHighwayInfo?.let { highway ->
                                Text(
                                    highway.icon,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    highway.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (highway.isHighway) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            }

                            // Direction display
                            if (currentCarDirection != null) {
                                Text(
                                    "🧭 ${currentCarDirection.toInt()}°",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF3F51B5)
                                )
                            }
                        }
                    }

                    // SIMPLIFIED System status indicator (no level info)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isSpeedJumpVerifying -> Color(0xFFFFCDD2)
                                currentSpeedLimit.roadName?.contains("📤 TTL SENT") == true -> Color(0xFFE8F5E8)
                                currentSpeedLimit.roadName?.contains("🔄 TTL WAIT") == true -> Color(0xFFFFF3E0)
                                currentSpeedLimit.source?.contains("highway") == true -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // SIMPLIFIED road name without level info
                            val simplifiedRoadName = currentSpeedLimit.roadName
                                ?.replace(Regex("\\(BRIDGE.*?\\)"), "")
                                ?.replace(Regex("\\(TUNNEL.*?\\)"), "")
                                ?.replace(Regex("\\(ELEVATED.*?\\)"), "")
                                ?.replace(Regex("\\(GROUND.*?\\)"), "")
                                ?.replace(Regex("\\(EXPRESSWAY.*?\\)"), "")
                                ?.trim() ?: "Road"

                            Text(
                                when {
                                    isSpeedJumpVerifying -> "🚨 SPEED JUMP VERIFICATION: ${simplifiedRoadName}"
                                    currentSpeedLimit.source?.contains("stable_highway") == true -> "🔒 STABLE: ${simplifiedRoadName}"
                                    currentSpeedLimit.source?.contains("voting_highway") == true -> "🗳️ VOTING: ${simplifiedRoadName}"
                                    currentSpeedLimit.source?.contains("verification_highway") == true -> "🔍 VERIFYING: ${simplifiedRoadName}"
                                    currentSpeedLimit.source?.contains("highway") == true -> "🛣️ ACTIVE: ${simplifiedRoadName}"
                                    else -> "📤 Active: ${simplifiedRoadName}"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isSpeedJumpVerifying -> Color(0xFFC62828)
                                    currentSpeedLimit.roadName?.contains("📤 TTL SENT") == true -> Color(0xFF2E7D32)
                                    currentSpeedLimit.roadName?.contains("🔄 TTL WAIT") == true -> Color(0xFFE65100)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )

                            // SIMPLIFIED system info (no altitude)
                            Text(
                                "🛣️ System: Speed Jump Detection + Direction Tracking + Multi-Factor Selection + Highway Analysis",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    currentLocation?.let { loc ->
                        val limit = actualLimit
                        val isOverLimit = loc.speedKmh > limit + 5
                        val directionInfo = if (loc.carDirection != null) " heading ${loc.carDirection!!.toInt()}°" else ""

                        Text(
                            if (isOverLimit)
                                "⚠️ OVER LIMIT (${String.format("%.1f", loc.speedKmh)} km/h on ${currentHighwayInfo?.type ?: "road"}$directionInfo)"
                            else
                                "✅ Within limit (${String.format("%.1f", loc.speedKmh)} km/h on ${currentHighwayInfo?.type ?: "road"}$directionInfo)",
                            color = if (isOverLimit)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                currentLocation != null -> {
                    Text("📍 Move to detect speed limits", color = Color.Gray)
                    Text("🛣️ System ready: Direction + Multi-Factor + Speed Jump + Highway Analysis", color = Color.Gray, fontSize = 12.sp)
                }

                else -> {
                    Text("📡 Start GPS to check speed limits", color = Color.Gray)
                    Text("🛣️ Complete system ready for road analysis", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}
// 🆕 FIXED TTL CONTROL CARD
@Composable
private fun FixedTtlControlCard(
    autoSendSpeedLimits: Boolean,
    onAutoSendToggle: (Boolean) -> Unit,
    manualSpeedLimit: String,
    onManualSpeedChange: (String) -> Unit,
    onSendManual: () -> Unit,
    onQuickSend: (Int) -> Unit,
    speedLookup: OsmJsonSpeedLookup?,
    currentLocation: LocationData?,
    context: Context,
    lastTtlSendTime: Long,
    stableManager: StableSpeedLimitManager
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🛣️ Complete Bug Fix TTL Control", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Spacer(modifier = Modifier.height(4.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    "🛣️ ALL BUGS FIXED: Speed Jump Detection + Direction Awareness + Multi-Factor Road Selection + Highway Stickiness + Smart Verification + Continuous TTL",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                if (SerialTtlManager.isConnected) "✅ Connected" else "❌ Disconnected",
                color = if (SerialTtlManager.isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )

            // Show last TTL send info
            if (lastTtlSendTime > 0) {
                val timeSinceLastSend = System.currentTimeMillis() - lastTtlSendTime
                val timeSinceText = when {
                    timeSinceLastSend < 60000 -> "${timeSinceLastSend / 1000}s ago"
                    timeSinceLastSend < 3600000 -> "${timeSinceLastSend / 60000}m ago"
                    else -> "${timeSinceLastSend / 3600000}h ago"
                }

                Text(
                    "📤 Last TTL: $timeSinceText (complete fixed system - 20s)",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    "📤 No TTL sent yet (complete fixed system ready - 20s)",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Auto-send toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🛣️ Auto-send (complete fixed system - 20s)")
                Switch(checked = autoSendSpeedLimits, onCheckedChange = onAutoSendToggle)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual speed input
            OutlinedTextField(
                value = manualSpeedLimit,
                onValueChange = onManualSpeedChange,
                label = { Text("Manual Speed (0-140)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onSendManual,
                enabled = SerialTtlManager.isConnected && manualSpeedLimit.toIntOrNull()?.let { it in 0..255 } == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📤 Send Manual (Complete Fixed System)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Complete Fixed System Quick Send:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(30, 50, 80, 120).forEach { value ->
                    Button(
                        onClick = { onQuickSend(value) },
                        enabled = SerialTtlManager.isConnected,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(value.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Complete System Controls
            Spacer(modifier = Modifier.height(12.dp))

            Text("🛣️ Complete Bug Fix System Status:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = {
                        val status = stableManager.getCurrentStatus()
                        val statusText = status.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        LogCollector.addLog("📊 Complete Bug Fix System Status:\n$statusText")
                        Toast.makeText(context, "Check logs for complete system status", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("📊 Status", style = MaterialTheme.typography.bodySmall, color = Color.White)
                }

                Button(
                    onClick = {
                        val config = stableManager.getPersistenceConfig()
                        val configText = config.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        LogCollector.addLog("🛣️ Complete Fixed System Config:\n$configText")
                        Toast.makeText(context, "Check logs for complete system config", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("🛣️ Config", style = MaterialTheme.typography.bodySmall, color = Color.White)
                }

                Button(
                    onClick = {
                        stableManager.clearAll()
                        Toast.makeText(context, "🧹 Complete bug fix system cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("🧹 Clear", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}