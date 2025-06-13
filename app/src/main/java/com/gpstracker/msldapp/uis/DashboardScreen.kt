package com.gpstracker.msldapp.uis

import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gpstracker.msldapp.uis.SerialTtlManager

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speedLookup = remember { OsmOfflineSpeedLookup(context) }
    val gpsManager = remember { GPSLocationManager(context) }

    // States
    var error by remember { mutableStateOf<String?>(null) }
    var isGpsTracking by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<LocationData?>(null) }
    var currentSpeedLimit by remember { mutableStateOf<OsmOfflineSpeedLookup.SpeedLimitInfo?>(null) }
    var isLookingUpSpeedLimit by remember { mutableStateOf(false) }

    // Manual data sending states
    var manualSpeedLimit by remember { mutableStateOf("") }
    var autoSendSpeedLimits by remember { mutableStateOf(true) }

    val logs = remember { derivedStateOf { LogCollector.getLogs().reversed() } }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted && coarseGranted) {
            LogCollector.addLog("✅ Location permissions granted")
        } else {
            LogCollector.addLog("❌ Location permissions denied")
        }
    }

    // 1. Initialize TTL connection once when this Composable enters composition
    LaunchedEffect(Unit) {
        val success = SerialTtlManager.init(context)
        LogCollector.addLog(
            "TTL Init Attempt: ${if (success) "✅ Connected" else "❌ Failed"}"
        )

        if (!success) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ TTL connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. GPS tracking + auto speed-limit lookup + auto TTL send
    LaunchedEffect(isGpsTracking) {
        if (isGpsTracking && gpsManager.hasLocationPermission()) {
            LogCollector.addLog("🚀 Starting high-accuracy GPS tracking...")
            gpsManager.getLocationUpdates().collect { location ->
                currentLocation = location
                LogCollector.addLog(
                    "📍 Location updated: ${"%.7f".format(location.latitude)}, ${
                        "%.7f".format(location.longitude)
                    } — Speed: ${"%.1f".format(location.speedKmh)} km/h — Accuracy: ${
                        "%.1f".format(location.accuracy)
                    }m"
                )

                // Only lookup if not already in progress
                if (!isLookingUpSpeedLimit) {
                    isLookingUpSpeedLimit = true

                    scope.launch {
                        try {
                            // 2a. Lookup OSM speed limit for this coordinate
                            val speedInfo = speedLookup.lookupSpeedLimit(
                                location.latitude,
                                location.longitude
                            )
                            currentSpeedLimit = speedInfo

                            if (speedInfo?.speedLimit != null) {
                                val limitValue = speedInfo.speedLimit!!
                                LogCollector.addLog("🚦 OSM speed limit: $limitValue ${speedInfo.unit}")

                                // 2b. Only send if TTL is connected and auto-send is enabled
                                if (autoSendSpeedLimits && SerialTtlManager.isConnected) {
                                    // Convert speed limit to integer (0-255 range for TTL)
                                    val speedLimitInt = limitValue.toInt().coerceIn(0, 255)
                                    try {
                                        SerialTtlManager.sendSpeed(speedLimitInt, context)
                                        LogCollector.addLog("📤 Auto-sent speed limit to TTL: $speedLimitInt")
                                    } catch (e: Exception) {
                                        LogCollector.addLog("❌ Auto speed limit send failed: ${e.localizedMessage}")
                                    }
                                } else if (!autoSendSpeedLimits) {
                                    LogCollector.addLog("🔒 Auto speed limit sending disabled")
                                } else {
                                    LogCollector.addLog("❌ TTL not connected — cannot send speed limit")
                                }

                                // 2c. Optional: show toast on over-speed
                                val isOverLimit = location.speedKmh > limitValue + 5
                                if (isOverLimit) {
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(
                                            context,
                                            "⚠️ Over speed limit!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } else {
                                LogCollector.addLog("🚫 No speed limit data for this area")
                            }
                        } catch (e: Exception) {
                            currentSpeedLimit = null
                            LogCollector.addLog("❌ Error accessing OSM: ${e.message}")
                        } finally {
                            // Small delay to prevent hammering OSM on very frequent GPS updates
                            delay(1000)
                            isLookingUpSpeedLimit = false
                        }
                    }
                }
            }
        } else if (!isGpsTracking) {
            LogCollector.addLog("🛑 GPS tracking stopped")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title
        Text(
            "🚗 GPS Speed Limit Tracker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // GPS Status Card
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = if (isGpsTracking)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("📍 GPS Location", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                currentLocation?.let { loc ->
                    Text("📍 Lat: ${"%.7f".format(loc.latitude)}")
                    Text("📍 Lng: ${"%.7f".format(loc.longitude)}")
                    Text(
                        "🎯 Accuracy: ${gpsManager.getLocationAccuracyDescription(loc.accuracy)} (±${"%.1f".format(loc.accuracy)}m)"
                    )
                    Text(
                        "🚀 Current Speed: ${"%.1f".format(loc.speedKmh)} km/h",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "⏰ Updated: ${
                            java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(loc.timestamp))
                        }"
                    )
                } ?: Text(
                    if (isGpsTracking) "📍 Acquiring high-accuracy GPS signal..."
                    else "📍 GPS tracking stopped",
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (!gpsManager.hasLocationPermission()) {
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
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isGpsTracking)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (isGpsTracking) "🔴 Stop Tracking"
                            else if (gpsManager.hasLocationPermission()) "🟢 Start Tracking"
                            else "📍 Enable Location"
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val loc = gpsManager.getCurrentLocation()
                                if (loc != null) {
                                    currentLocation = loc
                                    LogCollector.addLog("📍 Manual location refresh successful")
                                } else {
                                    LogCollector.addLog("❌ Failed to get current location")
                                }
                            }
                        },
                        enabled = gpsManager.hasLocationPermission() && !isLookingUpSpeedLimit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("🔄 Refresh")
                    }
                }
            }
        }

        // Current Speed Limit Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    currentSpeedLimit?.speedLimit != null ->
                        MaterialTheme.colorScheme.secondaryContainer

                    isLookingUpSpeedLimit ->
                        MaterialTheme.colorScheme.tertiaryContainer

                    else ->
                        MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚦 Current Speed Limit", fontWeight = FontWeight.Bold)
                    if (isLookingUpSpeedLimit) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLookingUpSpeedLimit -> {
                        Text("🔍 Checking OSM data for speed limit...", color = Color.Gray)
                    }

                    currentSpeedLimit != null -> {
                        val info = currentSpeedLimit!!
                        if (info.speedLimit != null) {
                            Text(
                                "🎯 ${info.speedLimit} ${info.unit}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            info.roadName?.let { name ->
                                Text("📛 Road: $name")
                            }
                            info.roadType?.let { type ->
                                Text("🛣️ Type: $type")
                            }
                            Text("📋 ${info.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )

                            currentLocation?.let { loc ->
                                val limit = info.speedLimit!!
                                val speedDiff = loc.speedKmh - limit
                                val isOverLimit = speedDiff > 5

                                LogCollector.logSpeedComparison(loc.speedKmh, limit, isOverLimit)

                                if (isOverLimit) {
                                    Text(
                                        "⚠️ Speed: ${"%.1f".format(loc.speedKmh)} km/h (${"+%.1f".format(speedDiff)} over limit)",
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        "✅ Speed: ${"%.1f".format(loc.speedKmh)} km/h (within limit)",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Text(
                                "🚫 No speed limit for this area",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            currentLocation?.let { loc ->
                                Text(
                                    "🚀 Current Speed: ${"%.1f".format(loc.speedKmh)} km/h",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    currentLocation != null -> {
                        Text("📍 Move to check for OSM speed limit data", color = Color.Gray)
                    }

                    else -> {
                        Text("📍 Start GPS to check for speed limits", color = Color.Gray)
                    }
                }
            }
        }

        // TTL Connection and Manual Controls Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = if (SerialTtlManager.isConnected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("🔌 TTL Connection & Controls", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    if (SerialTtlManager.isConnected)
                        "✅ TTL Connected"
                    else
                        "❌ TTL Disconnected",
                    color = if (SerialTtlManager.isConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )

                Text(
                    SerialTtlManager.getCacheStats()
                        .ifEmpty { "📉 No TTL data sent yet." },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto-send toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🚦 Auto-send Speed Limits")
                    Switch(
                        checked = autoSendSpeedLimits,
                        onCheckedChange = {
                            autoSendSpeedLimits = it
                            LogCollector.addLog("🔧 Auto speed limit sending: ${if (it) "ON" else "OFF"}")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Manual speed limit input
                OutlinedTextField(
                    value = manualSpeedLimit,
                    onValueChange = {
                        // Only allow numbers 0-255
                        if (it.isEmpty() || (it.toIntOrNull()?.let { num -> num in 0..255 } == true)) {
                            manualSpeedLimit = it
                        }
                    },
                    label = { Text("Manual Speed Limit (0-255)") },
                    placeholder = { Text("Enter speed limit") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Manual send buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val speedInt = manualSpeedLimit.toIntOrNull()
                            if (speedInt != null && speedInt in 0..255 && SerialTtlManager.isConnected) {
                                try {
                                    SerialTtlManager.sendSpeed(speedInt, context)
                                    LogCollector.addLog("📤 Manual speed limit sent: $speedInt")
                                    manualSpeedLimit = "" // Clear after sending
                                } catch (e: Exception) {
                                    LogCollector.addLog("❌ Manual send failed: ${e.localizedMessage}")
                                }
                            } else if (!SerialTtlManager.isConnected) {
                                Toast.makeText(context, "❌ TTL not connected", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "⚠️ Enter valid speed (0-255)", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = SerialTtlManager.isConnected && manualSpeedLimit.toIntOrNull()?.let { it in 0..255 } == true,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("📤 Send Manual")
                    }

                    Button(
                        onClick = {
                            currentSpeedLimit?.speedLimit?.let { limit ->
                                if (SerialTtlManager.isConnected) {
                                    try {
                                        val speedLimitInt = limit.toInt().coerceIn(0, 255)
                                        SerialTtlManager.sendSpeed(speedLimitInt, context)
                                        LogCollector.addLog("📤 Current speed limit sent: $speedLimitInt")
                                    } catch (e: Exception) {
                                        LogCollector.addLog("❌ Current speed limit send failed: ${e.localizedMessage}")
                                    }
                                } else {
                                    Toast.makeText(context, "❌ TTL not connected", Toast.LENGTH_SHORT).show()
                                }
                            } ?: run {
                                Toast.makeText(context, "⚠️ No speed limit available", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = SerialTtlManager.isConnected && currentSpeedLimit?.speedLimit != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("🚦 Send Current")
                    }
                }

                // Quick send buttons for common speed limits
                Spacer(modifier = Modifier.height(8.dp))
                Text("Quick Send:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(30, 50, 70, 90, 120).forEach { value ->
                        Button(
                            onClick = {
                                if (SerialTtlManager.isConnected) {
                                    try {
                                        SerialTtlManager.sendSpeed(value, context)
                                        LogCollector.addLog("📤 Quick send: $value")
                                    } catch (e: Exception) {
                                        LogCollector.addLog("❌ Quick send failed: ${e.localizedMessage}")
                                    }
                                } else {
                                    Toast.makeText(context, "❌ TTL not connected", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = SerialTtlManager.isConnected,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text(value.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // OSM Data Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("📊 OSM Data Status", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val info = speedLookup.getDataInfo()
                        LogCollector.addLog("📊 OSM Data Coverage:\n$info")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("📊 Check OSM Data Coverage")
                }
            }
        }

        // Error Display (if needed)
        error?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // Activity Log
        Text("📜 Activity Log", fontWeight = FontWeight.Bold)

        // Log control buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val stats = LogCollector.getSystemStats()
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.INFO,
                        "System Statistics",
                        stats
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("📊 Show Stats")
            }

            Button(
                onClick = {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.DEBUG,
                        "GPS Diagnostics",
                        mapOf(
                            "Permission Status" to if (gpsManager.hasLocationPermission()) "✅ Granted" else "❌ Denied",
                            "Tracking Status" to if (isGpsTracking) "🟢 Active" else "🔴 Stopped",
                            "Current Location" to if (currentLocation != null) "✅ Available" else "❌ None",
                            "Speed Limit Data" to if (currentSpeedLimit != null) "✅ Available" else "❌ None",
                            "Auto Speed Limits" to if (autoSendSpeedLimits) "🟢 ON" else "🔴 OFF"
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🔧 Diagnostics")
            }

            Button(
                onClick = {
                    val ttlStats = SerialTtlManager.getDebugStatus()
                    LogCollector.addLog("🔧 TTL Debug Status:\n$ttlStats")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🔧 TTL Info")
            }
        }

        // Scrollable Log Panel
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (logs.value.isEmpty()) {
                    Text(
                        "No activity yet...",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    logs.value.forEach { line ->
                        Text(
                            text = line,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}