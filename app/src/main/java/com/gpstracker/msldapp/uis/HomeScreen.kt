// File: app/src/main/java/com/gpstracker/msldapp/uis/HomeScreen.kt

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.gpstracker.msldapp.R

/**
 * HOME SCREEN - Fixed: Removed frequent TTL toasts
 * Features: Clean card layout, AUTO/CANCEL buttons, GPS control, TTL status
 * Fix: No more "TTL: 40km/h" toasts every 5-10 seconds
 */
@RequiresApi(Build.VERSION_CODES.N)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // === UI STATE VARIABLES ===
    var currentSpeedLimit by remember { mutableStateOf<Int?>(null) }
    var currentRoadType by remember { mutableStateOf("Ready to Start") }
    var isGpsActive by remember { mutableStateOf(false) }
    var autoSendEnabled by remember { mutableStateOf(true) }
    var ttlConnected by remember { mutableStateOf(false) }

    // === ADVANCED BACKEND SYSTEMS ===
    var lastTtlSendTime by remember { mutableStateOf(0L) }
    var lastSentSpeedLimit by remember { mutableStateOf<Int?>(null) }

    // Complete systems from DashboardScreen
    val speedLookup = remember {
        try {
            OsmJsonSpeedLookup(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize JSON OSM lookup", e)
            null
        }
    }

    val stableManager = remember { StableSpeedLimitManager() }

    val gpsManager = remember {
        try {
            GPSLocationManager(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize GPS manager", e)
            null
        }
    }

    // Advanced backend states
    var error by remember { mutableStateOf<String?>(null) }
    var currentLocation by remember { mutableStateOf<LocationData?>(null) }
    var isLookingUpSpeedLimit by remember { mutableStateOf(false) }
    var lastRegion by remember { mutableStateOf("Unknown") }
    var currentHighwayInfo by remember { mutableStateOf<StableSpeedLimitManager.HighwayInfo?>(null) }
    var currentAltitude by remember { mutableStateOf(0.0) }
    var currentCarDirection by remember { mutableStateOf<Float?>(null) }
    var isSpeedJumpVerifying by remember { mutableStateOf(false) }
    var consecutiveFailures by remember { mutableStateOf(0) }
    var lastSuccessfulLookup by remember { mutableStateOf(0L) }
    var ttlSendFailures by remember { mutableStateOf(0) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }

    // === PERMISSION LAUNCHER ===
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted && coarseGranted) {
            LogCollector.addDetailedLog(LogCollector.LogCategory.PERMISSION, "✅ Location permissions granted")
            isGpsActive = true
        } else {
            LogCollector.addDetailedLog(LogCollector.LogCategory.PERMISSION, "❌ Location permissions denied")
            Toast.makeText(context, "🔑 Location permission required", Toast.LENGTH_LONG).show()
        }
    }

    // === TTL CONNECTION SETUP ===
    LaunchedEffect(Unit) {
        try {
            LogCollector.addDetailedLog(LogCollector.LogCategory.BACKEND, "🔌 Initializing TTL connection...")
            delay(1000)

            val success = SerialTtlManager.init(context)
            ttlConnected = success

            if (success && autoSendEnabled) {
                Toast.makeText(context, "🚀 TTL Connected & Auto Mode ON", Toast.LENGTH_LONG).show()
            } else if (!success) {
                Toast.makeText(context, "⚠️ Connect TTL device", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            LogCollector.logError("TTL Init Error", e)
        }
    }

    // === TTL MONITORING ===
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val wasConnected = ttlConnected
            val isConnected = SerialTtlManager.isConnected
            ttlConnected = isConnected

            if (wasConnected != isConnected) {
                if (isConnected) {
                    Toast.makeText(context, "✅ TTL Connected", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ TTL Disconnected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // === ENHANCED TTL SENDING (FIXED: NO MORE FREQUENT TOASTS) ===
    suspend fun sendTtlWithRetry(speedLimitToSend: Int, reason: String): Boolean {
        var attempts = 0
        var sendSuccess = false

        while (attempts < 3 && !sendSuccess) {
            try {
                // 🔧 FIXED: Pass null context to prevent toast spam
                val bytesWritten = SerialTtlManager.sendSpeed(speedLimitToSend, null)
                sendSuccess = bytesWritten == 1

                if (sendSuccess) {
                    ttlSendFailures = 0
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "✅ TTL SUCCESS: ${speedLimitToSend}km/h ($reason) - Silent mode"
                    )
                    stableManager.recordTtlSent(speedLimitToSend)
                    lastTtlSendTime = System.currentTimeMillis()
                    lastSentSpeedLimit = speedLimitToSend
                } else {
                    attempts++
                    ttlSendFailures++
                    if (attempts < 3) delay(1000)
                }
            } catch (e: Exception) {
                attempts++
                ttlSendFailures++
                LogCollector.logError("❌ TTL send attempt $attempts failed", e)
                if (attempts < 3) delay(1000)
            }
        }
        return sendSuccess
    }

    // === ENHANCED OSM LOOKUP ===
    suspend fun lookupSpeedLimitWithRetry(
        lat: Double, lon: Double, speed: Float,
        carDirection: Float? = null, altitude: Double? = null
    ): Pair<SpeedLimitResult?, Map<String, String>> {
        var attempts = 0
        var result: SpeedLimitResult? = null
        var osmTags = emptyMap<String, String>()

        while (attempts < 3 && result == null) {
            try {
                result = speedLookup?.findSpeedLimit(lat, lon, speed, carDirection, altitude)
                if (result != null) {
                    osmTags = speedLookup?.getLastOsmTags() ?: emptyMap()
                    consecutiveFailures = 0
                    lastSuccessfulLookup = System.currentTimeMillis()
                    return Pair(result, osmTags)
                }
            } catch (e: Exception) {
                LogCollector.logError("❌ OSM lookup attempt ${attempts + 1} failed", e)
            }
            attempts++
            if (attempts < 3 && result == null) delay(500)
        }

        consecutiveFailures++
        return Pair(null, emptyMap())
    }

    // === BATTERY OPTIMIZATION CHECK ===
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Not applicable for older versions
        }
    }

    // === COMPLETE GPS TRACKING ===
    LaunchedEffect(isGpsActive) {
        if (isGpsActive && gpsManager?.hasLocationPermission() == true) {
            gpsManager.startLocationTracking()

            // Check battery optimization in a safer way
            if (!isBatteryOptimizationDisabled()) {
                delay(2000) // Give user time to see GPS is starting
                showBatteryOptimizationDialog = true
            }

            LogCollector.addDetailedLog(LogCollector.LogCategory.GPS, "🛣️ Advanced GPS tracking started")

            try {
                gpsManager.getLocationUpdates().collect { location ->
                    try {
                        currentLocation = location
                        currentAltitude = location.altitude
                        currentCarDirection = location.carDirection

                        if (!isLookingUpSpeedLimit && speedLookup != null) {
                            isLookingUpSpeedLimit = true

                            scope.launch {
                                try {
                                    val (rawSpeedInfo, osmTags) = lookupSpeedLimitWithRetry(
                                        location.latitude, location.longitude, location.speedKmh,
                                        location.carDirection, location.altitude
                                    )

                                    val stableResult = stableManager.getStableSpeedLimit(
                                        location.latitude, location.longitude, location.altitude,
                                        location.speedKmh, rawSpeedInfo?.speedLimit, osmTags
                                    )

                                    when (stableResult) {
                                        is StableSpeedResult.Confirmed -> {
                                            currentSpeedLimit = stableResult.speedLimit
                                            currentRoadType = stableResult.highwayInfo?.description ?: "Road Confirmed"
                                            if (stableResult.sendToTtl && autoSendEnabled && ttlConnected) {
                                                val speedToSend = if (stableResult.speedLimit > 140) 140 else stableResult.speedLimit
                                                sendTtlWithRetry(speedToSend, "stable")
                                            }
                                        }
                                        is StableSpeedResult.NewConfirmed -> {
                                            currentSpeedLimit = stableResult.speedLimit
                                            currentRoadType = "${stableResult.highwayInfo?.description ?: "Road"} - New"
                                            if (stableResult.sendToTtl && autoSendEnabled && ttlConnected) {
                                                val speedToSend = if (stableResult.speedLimit > 140) 140 else stableResult.speedLimit
                                                sendTtlWithRetry(speedToSend, "new")
                                            }
                                        }
                                        is StableSpeedResult.Voting -> {
                                            currentSpeedLimit = stableResult.leadingCandidate
                                            currentRoadType = "${stableResult.highwayInfo?.description ?: "Road"} - Analyzing"
                                            if (stableResult.sendToTtl && autoSendEnabled && ttlConnected) {
                                                val speedToSend = if (stableResult.leadingCandidate > 140) 140 else stableResult.leadingCandidate
                                                sendTtlWithRetry(speedToSend, "voting")
                                            }
                                        }
                                        is StableSpeedResult.UsingLastKnown -> {
                                            currentSpeedLimit = stableResult.speedLimit
                                            currentRoadType = "${stableResult.highwayInfo?.description ?: "Road"} - Last Known"
                                            if (stableResult.sendToTtl && autoSendEnabled && ttlConnected) {
                                                val speedToSend = if (stableResult.speedLimit > 140) 140 else stableResult.speedLimit
                                                sendTtlWithRetry(speedToSend, "last_known")
                                            }
                                        }
                                        else -> {
                                            if (currentSpeedLimit == null) {
                                                currentRoadType = "Detecting..."
                                            }
                                        }
                                    }

                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    LogCollector.logError("❌ Speed lookup error", e)
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
            } catch (e: Exception) {
                LogCollector.logError("❌ GPS tracking error", e)
                isGpsActive = false
            } finally {
                gpsManager.stopLocationTracking()
            }

        } else if (!isGpsActive) {
            gpsManager?.stopLocationTracking()
            currentSpeedLimit = null
            currentRoadType = "GPS Stopped"
        }
    }

    // === YOUR REQUESTED UI STYLE ===
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8F5E8),  // Very light green
                        Color(0xFFF1F8E9),  // Soft green tint
                        Color(0xFFF8FDF8),  // Almost white with green hint
                        Color(0xFFEBF4EB)   // Light green bottom
                    )
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        // LOGO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Thinture Logo",
                    modifier = Modifier.size(100.dp)
                )

                Text(
                    text = "Thinture MSLD",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // SPEED LIMIT DISPLAY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF5F5F5)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Speed Limit",
                    fontSize = 18.sp,
                    color = Color.Gray
                )

                Text(
                    text = if (currentSpeedLimit != null) "${currentSpeedLimit} km/h" else "-- km/h",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = currentRoadType,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // GPS STATUS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isGpsActive)
                    Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isGpsActive) "🟢 GPS Active" else "🔴 GPS Stopped",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (gpsManager?.hasLocationPermission() == true) {
                            isGpsActive = !isGpsActive
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGpsActive)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isGpsActive) "Stop" else "Start")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // AUTO / CANCEL BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AUTO BUTTON
            Button(
                onClick = {
                    autoSendEnabled = true
                    Toast.makeText(context, "✅ Auto mode enabled - New speeds will be sent automatically", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoSendEnabled)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "AUTO",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Accept New Speeds",
                        fontSize = 12.sp
                    )
                }
            }

            // CANCEL BUTTON
            Button(
                onClick = {
                    autoSendEnabled = false
                    Toast.makeText(context, "❌ Auto mode disabled - New speeds will NOT be sent", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!autoSendEnabled)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "CANCEL",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Stop Sending Speeds",
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TTL STATUS (FIXED: No more frequent toasts)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ttlConnected)
                    Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
            )
        ) {
            Text(
                text = if (ttlConnected)
                    "🔗 TTL Connected (Silent Mode)" else "❌ TTL Disconnected",
                modifier = Modifier.padding(16.dp),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        // 🔧 OPTIONAL: Uncomment to show last sent info without frequent toasts
        /*
        if (lastSentSpeedLimit != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
            ) {
                Text(
                    text = "Last sent: ${lastSentSpeedLimit}km/h • ${(System.currentTimeMillis() - lastTtlSendTime) / 1000}s ago",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF2E7D32)
                )
            }
        }
        */
    }

    // === IMPROVED BATTERY OPTIMIZATION DIALOG ===
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Battery Optimization",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF374151)
                    )
                }
            },
            text = {
                Column {
                    Text(
                        "To ensure reliable GPS tracking and TTL communication, please disable battery optimization for this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF374151)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "• Prevents Android from stopping the app in background\n" +
                                "• Ensures continuous speed limit detection\n" +
                                "• Maintains TTL device connectivity",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B7280)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, "Settings not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showBatteryOptimizationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open Settings", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatteryOptimizationDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF6B7280))
                ) {
                    Text("Later")
                }
            },
            containerColor = Color.White,
            titleContentColor = Color(0xFF374151),
            textContentColor = Color(0xFF374151)
        )
    }
}