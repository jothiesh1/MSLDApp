package com.gpstracker.msldapp.uis

import android.Manifest
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gpstracker.msldapp.R

@RequiresApi(Build.VERSION_CODES.N)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var currentSpeedLimit by remember { mutableStateOf<Int?>(null) }
    var currentRoadType by remember { mutableStateOf("Ready to Start") }
    var currentCourse by remember { mutableStateOf<Float?>(null) }
    var isGpsActive by remember { mutableStateOf(false) }
    var autoSendEnabled by remember { mutableStateOf(true) }
    var ttlConnected by remember { mutableStateOf(false) }

    var highToLowSeconds by remember { mutableStateOf(20) }
    var lowToHighSeconds by remember { mutableStateOf(5) }
    var highToLowMax by remember { mutableStateOf(60) }
    var lowToHighMax by remember { mutableStateOf(30) }
    var showMaxValueInputs by remember { mutableStateOf(false) }
    var highToLowMaxText by remember { mutableStateOf("60") }
    var lowToHighMaxText by remember { mutableStateOf("30") }
    var lockedHighToLow by remember { mutableStateOf(20) }
    var lockedLowToHigh by remember { mutableStateOf(5) }
    var isIntervalLocked by remember { mutableStateOf(false) }
    var showIntervalSettings by remember { mutableStateOf(false) }

    var ttlSendSuccessCount by remember { mutableStateOf(0) }
    var ttlSendFailureCount by remember { mutableStateOf(0) }
    var lastTtlSentSpeed by remember { mutableStateOf<Int?>(null) }
    var lastTtlSentTime by remember { mutableStateOf<Long?>(null) }

    // TTL CONTINUOUS SENDER STATE
    var ttlBackgroundSenderActive by remember { mutableStateOf(false) }
    var currentSpeedToSend by remember { mutableStateOf<Int?>(null) }
    var nextTtlSendIn by remember { mutableStateOf(0) }

    // AUTO REFRESH SETTINGS
    var autoRefreshEnabled by remember { mutableStateOf(true) }
    var autoRefreshIntervalMinutes by remember { mutableStateOf(10) }
    var lastRefreshTime by remember { mutableStateOf(0L) }
    var nextRefreshIn by remember { mutableStateOf(0) }
    var showRefreshSettings by remember { mutableStateOf(false) }

    val coordinator = remember { SpeedLimitCoordinator(context) }
    val gpsManager = remember {
        try {
            GPSLocationManager(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize GPS manager", e)
            null
        }
    }

    LaunchedEffect(lockedHighToLow, lockedLowToHigh) {
        gpsManager?.setHighToLowInterval(lockedHighToLow)
        gpsManager?.setLowToHighInterval(lockedLowToHigh)
    }

    var isLookingUpSpeedLimit by remember { mutableStateOf(false) }
    var consecutiveFailures by remember { mutableStateOf(0) }
    var lastSuccessfulLookup by remember { mutableStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted && coarseGranted) {
            isGpsActive = true
        } else {
            Toast.makeText(context, "Location permission required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        delay(1000)
        val success = SerialTtlManager.init(context)
        ttlConnected = success
        if (success && autoSendEnabled) {
            Toast.makeText(context, "TTL Connected & Auto Mode ON", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val wasConnected = ttlConnected
            val isConnected = SerialTtlManager.isConnected
            ttlConnected = isConnected
            if (wasConnected != isConnected) {
                Toast.makeText(
                    context,
                    if (isConnected) "TTL Connected" else "TTL Disconnected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    suspend fun sendTtlWithRetry(speedLimitToSend: Int, reason: String): Boolean {
        var attempts = 0
        var sendSuccess = false

        Log.d("HomeScreen", "Attempting to send TTL: $speedLimitToSend km/h (reason: $reason)")

        while (attempts < 3 && !sendSuccess) {
            try {
                Log.d("HomeScreen", "TTL send attempt ${attempts + 1}/3")
                sendSuccess = SerialTtlManager.sendSpeed(speedLimitToSend, context)

                if (sendSuccess) {
                    ttlSendSuccessCount++
                    ttlSendFailureCount = 0
                    lastTtlSentSpeed = speedLimitToSend
                    lastTtlSentTime = System.currentTimeMillis()
                    coordinator.recordTtlSent(speedLimitToSend)

                    Log.d("HomeScreen", "TTL sent successfully: $speedLimitToSend (attempt ${attempts + 1})")
                    break
                } else {
                    attempts++
                    ttlSendFailureCount++
                    Log.w("HomeScreen", "TTL send failed, attempt $attempts/3")
                    if (attempts < 3) delay(1000)
                }
            } catch (e: Exception) {
                attempts++
                ttlSendFailureCount++
                Log.e("HomeScreen", "TTL send exception: ${e.message}", e)
                if (attempts < 3) delay(1000)
            }
        }

        if (!sendSuccess) {
            Log.e("HomeScreen", "TTL send failed after $attempts attempts: $speedLimitToSend")
        }

        return sendSuccess
    }

    fun performSoftRefresh() {
        scope.launch {
            try {
                Log.d("HomeScreen", "Performing soft refresh...")
                lastRefreshTime = System.currentTimeMillis()
                coordinator.clearAll()
                Toast.makeText(context, "Soft refresh completed", Toast.LENGTH_SHORT).show()
                Log.d("HomeScreen", "Soft refresh completed")
            } catch (e: Exception) {
                Log.e("HomeScreen", "Soft refresh error: ${e.message}", e)
                Toast.makeText(context, "Soft refresh failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun performHardRefresh() {
        scope.launch {
            try {
                Log.d("HomeScreen", "Performing hard refresh...")
                lastRefreshTime = System.currentTimeMillis()

                val wasActive = isGpsActive
                if (isGpsActive) {
                    isGpsActive = false
                    delay(500)
                }

                coordinator.clearAll()
                gpsManager?.resetKalmanFilter()

                ttlSendSuccessCount = 0
                ttlSendFailureCount = 0
                lastTtlSentSpeed = null
                lastTtlSentTime = null

                currentSpeedLimit = null
                currentRoadType = "System Reset"
                consecutiveFailures = 0

                delay(1000)

                if (wasActive) {
                    isGpsActive = true
                }

                Toast.makeText(context, "Hard refresh completed", Toast.LENGTH_LONG).show()
                Log.d("HomeScreen", "Hard refresh completed")
            } catch (e: Exception) {
                Log.e("HomeScreen", "Hard refresh error: ${e.message}", e)
                Toast.makeText(context, "Hard refresh failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // CONTINUOUS TTL SENDER - RUNS INDEPENDENTLY OF GPS
    // ============================================================
    LaunchedEffect(autoSendEnabled, ttlConnected) {
        if (autoSendEnabled && ttlConnected) {
            ttlBackgroundSenderActive = true
            Log.d("HomeScreen", "===== TTL CONTINUOUS SENDER STARTED =====")

            while (autoSendEnabled && ttlConnected) {
                try {
                    // Get speed to send (current or last known)
                    val speedToSend = currentSpeedToSend
                        ?: coordinator.getLastKnownSpeedLimit()
                        ?: currentSpeedLimit

                    if (speedToSend != null) {
                        val clampedSpeed = speedToSend.coerceIn(0, 140)

                        // Calculate time since last send
                        val timeSinceLastSend = if (lastTtlSentTime != null) {
                            (System.currentTimeMillis() - lastTtlSentTime!!) / 1000
                        } else {
                            999 // Force first send
                        }

                        // Update countdown
                        nextTtlSendIn = (20 - timeSinceLastSend).toInt().coerceAtLeast(0)

                        // Send if 20 seconds elapsed OR speed changed
                        val shouldSend = timeSinceLastSend >= 20 ||
                                (lastTtlSentSpeed != null && lastTtlSentSpeed != clampedSpeed)

                        if (shouldSend) {
                            Log.d("HomeScreen", "TTL Background: Sending $clampedSpeed km/h (${timeSinceLastSend}s since last)")

                            val sent = sendTtlWithRetry(clampedSpeed, "continuous_background")

                            if (sent) {
                                Log.d("HomeScreen", "TTL Background: SUCCESS - $clampedSpeed km/h")
                            } else {
                                Log.w("HomeScreen", "TTL Background: FAILED - $clampedSpeed km/h")
                            }
                        }
                    } else {
                        Log.d("HomeScreen", "TTL Background: No speed available, waiting...")
                        nextTtlSendIn = 0
                    }

                    // Check every 1 second
                    delay(1000)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("HomeScreen", "TTL Background error: ${e.message}", e)
                    delay(2000)
                }
            }

            ttlBackgroundSenderActive = false
            nextTtlSendIn = 0
            Log.d("HomeScreen", "===== TTL CONTINUOUS SENDER STOPPED =====")
        }
    }

    // Auto-refresh timer
    LaunchedEffect(isGpsActive, autoRefreshEnabled, autoRefreshIntervalMinutes, ttlSendSuccessCount) {
        val shouldStartTimer = (isGpsActive || ttlSendSuccessCount > 0) && autoRefreshEnabled

        if (shouldStartTimer) {
            if (lastRefreshTime == 0L) {
                lastRefreshTime = System.currentTimeMillis()
                Log.d("HomeScreen", "Auto-refresh timer started")
            }

            while ((isGpsActive || ttlSendSuccessCount > 0) && autoRefreshEnabled) {
                delay(1000)

                val elapsed = (System.currentTimeMillis() - lastRefreshTime) / 1000
                val intervalSeconds = autoRefreshIntervalMinutes * 60
                nextRefreshIn = (intervalSeconds - elapsed).toInt()

                if (nextRefreshIn <= 0) {
                    Log.d("HomeScreen", "Auto-refresh triggered after $autoRefreshIntervalMinutes minutes")
                    performSoftRefresh()
                    lastRefreshTime = System.currentTimeMillis()
                }
            }
        } else {
            if (!isGpsActive && ttlSendSuccessCount == 0) {
                lastRefreshTime = 0L
                nextRefreshIn = 0
            }
        }
    }

    // GPS location updates - Updates speed value only
    LaunchedEffect(isGpsActive) {
        if (isGpsActive && gpsManager?.hasLocationPermission() == true) {
            gpsManager.startLocationTracking()
            try {
                gpsManager.getLocationUpdates().collect { location ->
                    if (!isLookingUpSpeedLimit) {
                        isLookingUpSpeedLimit = true
                        scope.launch {
                            try {
                                val result = coordinator.getAbsolutelyAccurateSpeedLimit(
                                    location.latitude,
                                    location.longitude,
                                    location.altitude,
                                    location.speedKmh,
                                    location.carDirection ?: 0f
                                )

                                currentSpeedLimit = result.speedLimit
                                currentRoadType = result.roadType
                                currentCourse = location.carDirection

                                // UPDATE speed for background sender
                                currentSpeedToSend = result.speedLimit

                                // Optional: Send immediately on speed change
                                if (result.sendToTtl && autoSendEnabled && ttlConnected) {
                                    val speedToSend = (result.speedLimit ?: 0).coerceAtMost(140)

                                    if (lastTtlSentSpeed != speedToSend) {
                                        Log.d("HomeScreen", "Speed changed: Immediate send $speedToSend")
                                        sendTtlWithRetry(speedToSend, result.enforcementReason)
                                    }
                                }

                                consecutiveFailures = 0
                                lastSuccessfulLookup = System.currentTimeMillis()

                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                LogCollector.logError("Speed lookup error", e)
                                consecutiveFailures++
                            } finally {
                                delay(100)
                                isLookingUpSpeedLimit = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogCollector.logError("GPS tracking error", e)
                isGpsActive = false
            } finally {
                gpsManager.stopLocationTracking()
            }
        } else if (!isGpsActive) {
            gpsManager?.stopLocationTracking()
            currentSpeedLimit = null
            currentRoadType = "GPS Stopped"
            // Don't clear currentSpeedToSend - keep sending last known
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE8F5E8), Color(0xFFF1F8E9), Color(0xFFF8FDF8), Color(0xFFEBF4EB))
                )
            )
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Thinture Logo",
                    modifier = Modifier.size(100.dp)
                )
                Text(
                    "Thinture MSLD",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier
                    .width(295.dp)
                    .height(175.dp),
                colors = CardDefaults.cardColors(
                    when (currentSpeedLimit) {
                        null -> Color(0xFFF5F5F5)
                        in 0..30 -> Color(0xFFE8F5E9)
                        in 31..60 -> Color(0xFFFFF3E0)
                        else -> Color(0xFFFFEBEE)
                    }
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "SPEED LIMIT",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (currentSpeedLimit != null) "$currentSpeedLimit" else "--",
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (currentSpeedLimit) {
                                null -> Color.Gray
                                in 0..30 -> Color(0xFF4CAF50)
                                in 31..60 -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            }
                        )
                        Text(
                            "km/h",
                            fontSize = 16.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(Color(0xFFF5F5F5)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    currentRoadType,
                    fontSize = 15.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // COMMENTED OUT: TTL CONTINUOUS MONITOR CARD
        /*
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                if (ttlBackgroundSenderActive) Color(0xFFE8F5E9) else Color(0xFFFFF9E6)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "TTL Continuous Sender",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (ttlBackgroundSenderActive) Color(0xFF2E7D32) else Color.Gray
                            )
                            if (ttlBackgroundSenderActive) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Active",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            if (ttlBackgroundSenderActive) "Sending every 20 seconds"
                            else "Enable AUTO + Connect TTL",
                            fontSize = 12.sp,
                            color = if (ttlBackgroundSenderActive) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                }

                if (ttlBackgroundSenderActive) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Current Speed:", fontSize = 13.sp, color = Color.Gray)
                        Text(
                            "${currentSpeedToSend ?: "None"} km/h",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    if (lastTtlSentSpeed != null && lastTtlSentTime != null) {
                        val secondsAgo = (System.currentTimeMillis() - lastTtlSentTime!!) / 1000

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Last Sent:", fontSize = 13.sp, color = Color.Gray)
                            Text(
                                "$lastTtlSentSpeed km/h (${secondsAgo}s ago)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Next Send:", fontSize = 13.sp, color = Color.Gray)
                            Text(
                                if (nextTtlSendIn > 0) "${nextTtlSendIn}s" else "Now",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (nextTtlSendIn > 0) Color(0xFFFF9800) else Color(0xFF4CAF50)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(Color(0xFFF5F5F5))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Success", fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                Text(
                                    "$ttlSendSuccessCount",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Failed", fontSize = 14.sp, color = Color(0xFFF44336), fontWeight = FontWeight.Bold)
                                Text(
                                    "$ttlSendFailureCount",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF44336)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Rate", fontSize = 14.sp, color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                val total = ttlSendSuccessCount + ttlSendFailureCount
                                val rate = if (total > 0) (ttlSendSuccessCount * 100 / total) else 0
                                Text(
                                    "$rate%",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2196F3)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (ttlBackgroundSenderActive && currentSpeedToSend != null) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Sending continuously - NO GAPS",
                            fontSize = 12.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    } else if (ttlBackgroundSenderActive && currentSpeedToSend == null) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Waiting for speed data",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Inactive - Enable AUTO and connect TTL",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        */

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                if (isGpsActive) Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
            )
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isGpsActive) "GPS Active" else "GPS Stopped",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (consecutiveFailures > 0) {
                        Text(
                            "Lookup failures: $consecutiveFailures",
                            fontSize = 12.sp,
                            color = Color.Red
                        )
                    }
                }
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
                        if (isGpsActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isGpsActive) "Stop" else "Start")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                if (isIntervalLocked) Color(0xFFE8F5E9) else Color(0xFFFFF9E6)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "GPS Update Intervals",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isIntervalLocked) Color(0xFF2E7D32) else Color(0xFF6D4C41)
                            )
                            if (isIntervalLocked) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }










                                    Text(
                                    if (isIntervalLocked) "Locked: ${lockedHighToLow}s / ${lockedLowToHigh}s"
                                    else "Adjust with fine control",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    IconButton(onClick = { showIntervalSettings = !showIntervalSettings }) {
                        Icon(
                            if (showIntervalSettings) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle",
                            tint = if (isIntervalLocked) Color(0xFF2E7D32) else Color(0xFF6D4C41)
                        )
                    }
                }

                if (showIntervalSettings) {
                    Spacer(Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(Color(0xFFE8F5F9)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "System Refresh",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color(0xFF01579B)
                                        )
                                        if (autoRefreshEnabled && isGpsActive) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Auto Refresh",
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (autoRefreshEnabled && isGpsActive && nextRefreshIn > 0) {
                                        val minutes = nextRefreshIn / 60
                                        val seconds = nextRefreshIn % 60
                                        Text(
                                            "Next refresh in: ${minutes}m ${seconds}s",
                                            fontSize = 11.sp,
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else if (autoRefreshEnabled && ttlSendSuccessCount > 0 && nextRefreshIn > 0) {
                                        val minutes = nextRefreshIn / 60
                                        val seconds = nextRefreshIn % 60
                                        Text(
                                            "Next refresh in: ${minutes}m ${seconds}s (TTL active)",
                                            fontSize = 11.sp,
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        Text(
                                            "Starts with GPS or TTL sending",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                IconButton(onClick = { showRefreshSettings = !showRefreshSettings }) {
                                    Icon(
                                        if (showRefreshSettings) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Toggle",
                                        tint = Color(0xFF01579B)
                                    )
                                }
                            }

                            if (showRefreshSettings) {
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(16.dp))

                                Text("Manual Refresh", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Clear cache and reset system", fontSize = 11.sp, color = Color.Gray)
                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { performSoftRefresh() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(Color(0xFF2196F3))
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Soft", fontSize = 13.sp)
                                    }
                                    Button(
                                        onClick = { performHardRefresh() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(Color(0xFFFF5722))
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Hard", fontSize = 13.sp)
                                    }
                                }

                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Auto Refresh", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (autoRefreshEnabled) "Starts with GPS or TTL" else "Disabled",
                                            fontSize = 11.sp,
                                            color = if (autoRefreshEnabled) Color(0xFF4CAF50) else Color.Gray
                                        )
                                    }
                                    Switch(
                                        checked = autoRefreshEnabled,
                                        onCheckedChange = {
                                            autoRefreshEnabled = it
                                            if (it) {
                                                lastRefreshTime = System.currentTimeMillis()
                                                Toast.makeText(
                                                    context,
                                                    "Auto-refresh enabled: Every $autoRefreshIntervalMinutes minutes",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(context, "Auto-refresh disabled", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF4CAF50),
                                            checkedTrackColor = Color(0xFFC8E6C9)
                                        )
                                    )
                                }

                                if (autoRefreshEnabled) {
                                    Spacer(Modifier.height(16.dp))

                                    Text("Refresh Interval", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("How often to auto-refresh (1-60 minutes)", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Slider(
                                            value = autoRefreshIntervalMinutes.toFloat(),
                                            onValueChange = { autoRefreshIntervalMinutes = it.toInt() },
                                            valueRange = 1f..60f,
                                            steps = 58,
                                            modifier = Modifier.weight(1f),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF2196F3),
                                                activeTrackColor = Color(0xFF2196F3)
                                            )
                                        )
                                        Text(
                                            "${autoRefreshIntervalMinutes}m",
                                            modifier = Modifier.width(50.dp),
                                            textAlign = TextAlign.End,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color(0xFF2196F3)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(Color(0xFFF5F5F5))
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                tint = Color(0xFF2196F3),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Refresh Types", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text("• Soft: Clears cache, keeps GPS running", fontSize = 11.sp, color = Color.Gray)
                                        Text("• Hard: Full reset, restarts GPS", fontSize = 11.sp, color = Color.Gray)
                                        Text("• Auto: Starts when GPS/TTL active", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Custom Max Values",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF6D4C41)
                        )
                        TextButton(
                            onClick = { showMaxValueInputs = !showMaxValueInputs },
                            enabled = !isIntervalLocked
                        ) {
                            Text(if (showMaxValueInputs) "Hide" else "Set Max", fontSize = 12.sp)
                            Icon(
                                if (showMaxValueInputs) Icons.Default.KeyboardArrowUp else Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (showMaxValueInputs && !isIntervalLocked) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = highToLowMaxText,
                                onValueChange = { highToLowMaxText = it.filter { char -> char.isDigit() } },
                                label = { Text("High→Low Max", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = lowToHighMaxText,
                                onValueChange = { lowToHighMaxText = it.filter { char -> char.isDigit() } },
                                label = { Text("Low→High Max", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val newHighMax = highToLowMaxText.toIntOrNull()?.coerceIn(10, 300) ?: 60
                                val newLowMax = lowToHighMaxText.toIntOrNull()?.coerceIn(5, 150) ?: 30
                                highToLowMax = newHighMax
                                lowToHighMax = newLowMax
                                if (highToLowSeconds > highToLowMax) highToLowSeconds = highToLowMax
                                if (lowToHighSeconds > lowToHighMax) lowToHighSeconds = lowToHighMax
                                showMaxValueInputs = false
                                Toast.makeText(context, "Max: ${highToLowMax}s / ${lowToHighMax}s", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apply Max Values")
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                    }

                    Text("High → Low Speed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Slowing down (3s - ${highToLowMax}s)", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = highToLowSeconds.toFloat(),
                            onValueChange = { if (!isIntervalLocked) highToLowSeconds = it.toInt() },
                            enabled = !isIntervalLocked,
                            valueRange = 3f..highToLowMax.toFloat(),
                            steps = 0,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = if (isIntervalLocked) Color.Gray else Color(0xFFFF9800),
                                activeTrackColor = if (isIntervalLocked) Color.Gray else Color(0xFFFF9800)
                            )
                        )
                        Text(
                            "${highToLowSeconds}s",
                            modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.End,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isIntervalLocked) Color.Gray else Color(0xFFFF9800)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("Low → High Speed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Accelerating (3s - ${lowToHighMax}s)", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = lowToHighSeconds.toFloat(),
                            onValueChange = { if (!isIntervalLocked) lowToHighSeconds = it.toInt() },
                            enabled = !isIntervalLocked,
                            valueRange = 3f..lowToHighMax.toFloat(),
                            steps = 0,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = if (isIntervalLocked) Color.Gray else Color(0xFF4CAF50),
                                activeTrackColor = if (isIntervalLocked) Color.Gray else Color(0xFF4CAF50)
                            )
                        )
                        Text(
                            "${lowToHighSeconds}s",
                            modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.End,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isIntervalLocked) Color.Gray else Color(0xFF4CAF50)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (isIntervalLocked) {
                                isIntervalLocked = false
                                Toast.makeText(context, "Unlocked", Toast.LENGTH_SHORT).show()
                            } else {
                                lockedHighToLow = highToLowSeconds
                                lockedLowToHigh = lowToHighSeconds
                                isIntervalLocked = true
                                Toast.makeText(context, "Locked: ${lockedHighToLow}s / ${lockedLowToHigh}s", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            if (isIntervalLocked) Color(0xFF2E7D32) else Color(0xFF1976D2)
                        )
                    ) {
                        Icon(
                            if (isIntervalLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isIntervalLocked) "UNLOCK" else "LOCK & APPLY",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = {
                    autoSendEnabled = true
                    Toast.makeText(context, "Auto TTL enabled", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    if (autoSendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            ) {
                Text("AUTO", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    autoSendEnabled = false
                    Toast.makeText(context, "Auto TTL disabled", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    if (!autoSendEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            ) {
                Text("CANCEL", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(if (ttlConnected) Color(0xFFE8F5E8) else Color(0xFFFFEBEE))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (ttlConnected) "TTL Connected" else "TTL Disconnected",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (ttlConnected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Success: $ttlSendSuccessCount | Failures: $ttlSendFailureCount",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (lastTtlSentSpeed != null && lastTtlSentTime != null) {
                        val secondsAgo = (System.currentTimeMillis() - lastTtlSentTime!!) / 1000
                        Text(
                            "Last sent: $lastTtlSentSpeed km/h (${secondsAgo}s ago)",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (ttlSendFailureCount > 0) {
                    Text(
                        "Recent failures: $ttlSendFailureCount",
                        fontSize = 12.sp,
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // COMMENTED OUT: Test TTL buttons
        /*
        if (ttlConnected) {
            Button(
                onClick = {
                    scope.launch {
                        val testSpeed = 60
                        Log.d("HomeScreen", "Manual TTL test initiated: $testSpeed km/h")
                        val success = SerialTtlManager.sendSpeed(testSpeed, context)
                        Toast.makeText(
                            context,
                            "Manual TTL Test: ${if (success) "SUCCESS" else "FAILED"}",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.d("HomeScreen", "Manual TTL test result: $success")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(Color(0xFF9C27B0))
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Test TTL (60 km/h)", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    scope.launch {
                        currentSpeedToSend = 80
                        Log.d("HomeScreen", "===== TTL CONTINUOUS TEST START =====")

                        for (i in 1..3) {
                            val testSpeed = 80
                            Log.d("HomeScreen", "Test send #$i: $testSpeed km/h")

                            val success = sendTtlWithRetry(testSpeed, "manual_test_$i")

                            Toast.makeText(
                                context,
                                "Test #$i: ${if (success) "SUCCESS" else "FAILED"} ($testSpeed km/h)",
                                Toast.LENGTH_SHORT
                            ).show()

                            if (i < 3) {
                                for (countdown in 20 downTo 1) {
                                    Log.d("HomeScreen", "Next test in ${countdown}s...")
                                    delay(1000)
                                }
                            }
                        }

                        Log.d("HomeScreen", "===== TTL CONTINUOUS TEST COMPLETE =====")
                        Toast.makeText(
                            context,
                            "Continuous test complete - Check logs",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(Color(0xFF00BCD4))
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Test Continuous (3x 20s)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
        */

        // COMMENTED OUT: System Status Card
        /*
        if (isGpsActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(Color(0xFFF0F0F0))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("System Status", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Complete: Continuous TTL + Road Lock + Distance Priority", fontSize = 12.sp, color = Color.Gray)
                    Text("NO GAPS - Sends every 20s", fontSize = 11.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    if (isIntervalLocked) {
                        Text(
                            "GPS Intervals: ${lockedHighToLow}s / ${lockedLowToHigh}s",
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("TTL Status:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Auto: ${if (autoSendEnabled) "ON" else "OFF"}", fontSize = 11.sp, color = Color.Gray)
                    Text("Connected: ${if (ttlConnected) "YES" else "NO"}", fontSize = 11.sp, color = Color.Gray)
                    Text("Background: ${if (ttlBackgroundSenderActive) "ACTIVE" else "INACTIVE"}",
                        fontSize = 11.sp,
                        color = if (ttlBackgroundSenderActive) Color(0xFF4CAF50) else Color.Gray,
                        fontWeight = if (ttlBackgroundSenderActive) FontWeight.Bold else FontWeight.Normal
                    )
                    val total = ttlSendSuccessCount + ttlSendFailureCount
                    val rate = if (total > 0) (ttlSendSuccessCount * 100 / total) else 0
                    Text("Success Rate: $rate%", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        */

        // COMMENTED OUT: Show TTL Debug Info button
        /*
        Button(
            onClick = {
                val debugInfo = SerialTtlManager.getDebugStatus()
                Log.d("HomeScreen", "TTL Debug Status:\n$debugInfo")
                Toast.makeText(context, "Debug info logged", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(Color(0xFF607D8B))
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Show TTL Debug Info")
        }
        */
    }
}
