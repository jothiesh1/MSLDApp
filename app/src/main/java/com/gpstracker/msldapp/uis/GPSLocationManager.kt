// File: app/src/main/java/com/gpstracker/msldapp/uis/GPSLocationManager.kt

package com.gpstracker.msldapp.uis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.math.*
import java.util.ArrayDeque

// 🆕 Enhanced LocationData with car direction
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float, // m/s
    val speedKmh: Float, // km/h
    val altitude: Double,
    val bearing: Float,
    val timestamp: Long,
    val provider: String,
    val carDirection: Float? = null  // 🆕 Car direction from GPS track in degrees (0-360)
)

/**
 * HIGH PRECISION GPS Location Manager with CAR DIRECTION TRACKING
 * Features:
 * - Consistent speed detection
 * - Reliable TTL sending
 * - Robust OSM lookup
 * - Multi-constellation GNSS support
 * - Kalman filtering for smoother tracking
 * - Advanced satellite monitoring
 * - 🆕 CAR DIRECTION TRACKING from GPS movement
 * - 🆕 Stable direction calculation from location history
 * - 🆕 Direction smoothing and validation
 */
class GPSLocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val settingsClient: SettingsClient =
        LocationServices.getSettingsClient(context)

    // Wake lock and performance tracking
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockAcquired = false
    private var lastLocationTime = 0L
    private var locationUpdateCount = 0
    private var averageAccuracy = 0f
    private var currentSpeed = 0f
    private var bestAccuracy = Float.MAX_VALUE
    private var consecutiveNoSpeedCount = 0 // Track speed detection issues

    // High precision enhancements
    private var currentSatelliteCount = 0
    private var satellitesUsedInFix = 0
    private var currentHdop = 0f
    private var gpsSignalStrength = 0f
    private var glonassSignalStrength = 0f

    // Kalman filter variables for location smoothing
    private var kalmanLat = 0.0
    private var kalmanLon = 0.0
    private var kalmanVariance = 30.0 // Initial variance
    private var kalmanQ = 0.01        // Process noise

    // Location averaging
    private val recentLocations = mutableListOf<Location>()
    private val maxLocationHistory = 5  // Max locations to store for averaging

    // 🆕 CAR DIRECTION TRACKER - Inner class for direction calculation
    inner class CarDirectionTracker {
        private val locationHistory = ArrayDeque<LocationData>()
        private val maxHistorySize = 8  // Keep last 8 positions for direction calculation
        private var lastStableDirection: Float? = null
        private var directionConfidenceCount = 0
        private val minConfidenceForDirection = 3  // Need 3 consistent readings

        /**
         * 🆕 Update location and calculate car direction from GPS track
         */
        fun updateLocation(location: LocationData): Float? {
            // Add to history
            locationHistory.addLast(location)
            if (locationHistory.size > maxHistorySize) {
                locationHistory.removeFirst()
            }

            // Need at least 3 points for direction calculation
            if (locationHistory.size < 3) {
                return lastStableDirection
            }

            // Calculate direction from GPS track
            val calculatedDirection = calculateStableDirection()

            if (calculatedDirection != null) {
                // Validate and smooth direction
                if (lastStableDirection != null) {
                    val directionDiff = abs(calculatedDirection - lastStableDirection!!)
                    val minDiff = min(directionDiff, 360 - directionDiff)

                    // If direction change is reasonable, accept it
                    if (minDiff <= 45f || location.speedKmh < 10f) {
                        directionConfidenceCount++
                        if (directionConfidenceCount >= minConfidenceForDirection) {
                            lastStableDirection = calculatedDirection
                        }
                    } else {
                        // Large direction change - reset confidence
                        directionConfidenceCount = 0
                    }
                } else {
                    // First stable direction
                    lastStableDirection = calculatedDirection
                    directionConfidenceCount = 1
                }

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "🧭 Direction: ${calculatedDirection.toInt()}° (confidence: $directionConfidenceCount/${minConfidenceForDirection})"
                )

                return lastStableDirection
            }

            return lastStableDirection
        }

        /**
         * 🆕 Calculate stable direction from location history
         */
        private fun calculateStableDirection(): Float? {
            if (locationHistory.size < 3) return null

            val recent = locationHistory.toList().takeLast(3)

            // Calculate total displacement for more stable direction
            val start = recent.first()
            val end = recent.last()

            val deltaTime = (end.timestamp - start.timestamp) / 1000.0 // seconds
            if (deltaTime < 1.0) return null // Too short time span

            // Calculate bearing from start to end point
            val dLon = Math.toRadians(end.longitude - start.longitude)
            val startLatRad = Math.toRadians(start.latitude)
            val endLatRad = Math.toRadians(end.latitude)

            val y = sin(dLon) * cos(endLatRad)
            val x = cos(startLatRad) * sin(endLatRad) -
                    sin(startLatRad) * cos(endLatRad) * cos(dLon)

            var bearing = Math.toDegrees(atan2(y, x))
            bearing = (bearing + 360) % 360

            // Validate with speed - only trust direction when moving
            val avgSpeed = recent.map { it.speedKmh }.average()
            if (avgSpeed < 5f) {
                // Too slow to determine reliable direction
                return null
            }

            // Calculate movement distance to validate
            val distance = calculateDistance(
                start.latitude, start.longitude,
                end.latitude, end.longitude
            )

            // Must have moved at least 10 meters for reliable direction
            if (distance < 10.0) return null

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "🧭 Calculated direction: ${bearing.toInt()}° (${String.format("%.1f", avgSpeed)}km/h, ${distance.toInt()}m, ${deltaTime.toInt()}s)"
            )

            return bearing.toFloat()
        }

        /**
         * Get current stable direction
         */
        fun getCurrentDirection(): Float? = lastStableDirection

        /**
         * Reset direction tracking
         */
        fun reset() {
            locationHistory.clear()
            lastStableDirection = null
            directionConfidenceCount = 0

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "🧭 Direction tracker reset"
            )
        }

        /**
         * Get direction tracking status
         */
        fun getStatus(): Map<String, String> {
            return mapOf(
                "History Points" to locationHistory.size.toString(),
                "Current Direction" to if (lastStableDirection != null) "${lastStableDirection!!.toInt()}°" else "None",
                "Confidence" to "$directionConfidenceCount/$minConfidenceForDirection",
                "Status" to if (lastStableDirection != null) "Tracking" else "Acquiring"
            )
        }
    }

    // 🆕 Car direction tracker instance
    private val carDirectionTracker = CarDirectionTracker()

    // Enhanced location request for higher precision
    private fun createLocationRequest(currentSpeed: Float = 0f): LocationRequest {
        // More frequent updates for better precision and direction tracking
        val updateInterval = when {
            currentSpeed > 60f -> 800L     // High speed: 0.8s (faster for direction)
            currentSpeed > 20f -> 1500L    // Medium speed: 1.5s (faster for direction)
            currentSpeed > 5f -> 2500L     // Low speed: 2.5s (faster for direction)
            else -> 4000L                  // Stationary: 4s (faster for direction)
        }

        val fastestInterval = when {
            currentSpeed > 60f -> 400L     // Very responsive at high speed
            currentSpeed > 20f -> 800L     // More responsive at medium speed
            else -> 1500L                  // Standard for low speed
        }

        // More sensitive distance thresholds for better direction tracking
        val minDistance = when {
            currentSpeed > 60f -> 3f       // Highway: 3m (for direction accuracy)
            currentSpeed > 20f -> 2f       // City: 2m (for direction accuracy)
            currentSpeed > 5f -> 1f        // Slow: 1m (for direction accuracy)
            else -> 0f                     // Stationary: Any movement
        }

        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            updateInterval
        ).apply {
            setGranularity(Granularity.GRANULARITY_FINE)
            setMinUpdateDistanceMeters(minDistance)
            setMaxUpdateDelayMillis(fastestInterval)
            setMinUpdateIntervalMillis(fastestInterval)
            setWaitForAccurateLocation(true) // Wait for high accuracy
            setMaxUpdates(Int.MAX_VALUE)
        }.build()
    }

    // Improved timeout for faster response
    private val reliableLocationRequest = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(10000L) // 10 seconds
        .setMaxUpdateAgeMillis(0L) // Only fresh locations
        .setGranularity(Granularity.GRANULARITY_FINE)
        .build()

    /**
     * Start GPS with high precision tracking + direction tracking
     */
    fun startLocationTracking() {
        acquireWakeLock()
        addGpsStatusListener()
        setupRecentLocations()
        carDirectionTracker.reset() // 🆕 Reset direction tracker

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.GPS,
            "🔍 HIGH PRECISION GPS + DIRECTION TRACKING Started",
            mapOf(
                "Wake Lock" to if (isWakeLockAcquired) "✅ Active" else "❌ Failed",
                "Mode" to "HIGH PRECISION + DIRECTION",
                "High Speed" to "0.8s updates",
                "Medium Speed" to "1.5s updates",
                "Low Speed" to "2.5s updates",
                "Stationary" to "4s updates",
                "Kalman Filter" to "ENABLED",
                "Multi-GNSS" to "ENABLED",
                "Speed Detection" to "✅ Enhanced",
                "Direction Tracking" to "✅ ENABLED"
            )
        )
    }

    /**
     * Initialize location history for averaging
     */
    private fun setupRecentLocations() {
        recentLocations.clear()
    }

    /**
     * Stop GPS and release wake lock
     */
    fun stopLocationTracking() {
        releaseWakeLock()
        carDirectionTracker.reset() // 🆕 Reset direction tracker

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.GPS,
            "⏹️ High Precision GPS + Direction Tracking Stopped",
            mapOf(
                "Total Updates" to locationUpdateCount.toString(),
                "Average Accuracy" to "${String.format("%.1f", averageAccuracy)}m",
                "Best Accuracy" to "${String.format("%.1f", bestAccuracy)}m",
                "Max Speed" to "${String.format("%.1f", currentSpeed)} km/h",
                "Speed Issues" to "$consecutiveNoSpeedCount times",
                "Satellites" to "$satellitesUsedInFix/$currentSatelliteCount",
                "Direction Status" to carDirectionTracker.getStatus()["Status"]!!
            )
        )
        locationUpdateCount = 0
        averageAccuracy = 0f
        currentSpeed = 0f
        bestAccuracy = Float.MAX_VALUE
        consecutiveNoSpeedCount = 0
        recentLocations.clear()

        // Reset Kalman filter
        kalmanLat = 0.0
        kalmanLon = 0.0
        kalmanVariance = 30.0
    }

    /**
     * Enhanced GPS satellite status monitoring with multi-constellation support
     */
    fun addGpsStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                            currentSatelliteCount = status.satelliteCount
                            var usedInFix = 0

                            // Track signal strength per constellation
                            var totalGpsSnr = 0f
                            var totalGlonassSnr = 0f
                            var gpsCount = 0
                            var glonassCount = 0

                            for (i in 0 until status.satelliteCount) {
                                if (status.usedInFix(i)) {
                                    usedInFix++

                                    // Get signal strength and categorize by constellation
                                    try {
                                        val snr = status.getCn0DbHz(i)
                                        when (status.getConstellationType(i)) {
                                            GnssStatus.CONSTELLATION_GPS -> {
                                                totalGpsSnr += snr
                                                gpsCount++
                                            }
                                            GnssStatus.CONSTELLATION_GLONASS -> {
                                                totalGlonassSnr += snr
                                                glonassCount++
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Skip if can't get SNR
                                    }
                                }
                            }

                            // Update globals
                            satellitesUsedInFix = usedInFix
                            gpsSignalStrength = if (gpsCount > 0) totalGpsSnr / gpsCount else 0f
                            glonassSignalStrength = if (glonassCount > 0) totalGlonassSnr / glonassCount else 0f

                            // Estimate HDOP from satellite geometry and count
                            currentHdop = if (usedInFix >= 4) {
                                // Better geometry = lower HDOP
                                4f / usedInFix.toFloat()
                            } else {
                                // Poor geometry
                                5f
                            }

                            // Fix Quality Assessment
                            val fixQuality = when {
                                usedInFix >= 10 -> "Excellent"
                                usedInFix >= 8 -> "Very Good"
                                usedInFix >= 6 -> "Good"
                                usedInFix >= 4 -> "Fair"
                                usedInFix >= 1 -> "Poor"
                                else -> "No Fix"
                            }

                            LogCollector.addDetailedLog(
                                LogCollector.LogCategory.GPS,
                                "🛰️ Fix Quality: $fixQuality",
                                mapOf(
                                    "satellites_used" to "$usedInFix/$currentSatelliteCount",
                                    "GPS" to if (gpsCount > 0) "$gpsCount (${String.format("%.1f", gpsSignalStrength)} dB)" else "0",
                                    "GLONASS" to if (glonassCount > 0) "$glonassCount (${String.format("%.1f", glonassSignalStrength)} dB)" else "0",
                                    "HDOP" to String.format("%.1f", currentHdop),
                                    "Direction_Status" to carDirectionTracker.getStatus()["Status"]!!
                                )
                            )

                            if (usedInFix < 4) {
                                LogCollector.addDetailedLog(
                                    LogCollector.LogCategory.GPS,
                                    "⚠️ Poor GPS signal: $usedInFix/$currentSatelliteCount - May affect precision and direction"
                                )
                            }
                        }

                        override fun onFirstFix(ttffMillis: Int) {
                            // Time to first fix - important metric
                            LogCollector.addDetailedLog(
                                LogCollector.LogCategory.GPS,
                                "⚡ First GPS fix obtained in ${ttffMillis}ms - Direction tracking ready"
                            )
                        }
                    }, null)
                }
            } catch (e: Exception) {
                LogCollector.logError("Failed to register GNSS status callback", e)
            }
        }
    }

    /**
     * Acquire wake lock to prevent app from sleeping
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MSLDApp:HighPrecisionGPS+Direction"
            )

            // Keep app awake for 12 hours max
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
            isWakeLockAcquired = true

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "🔋 High Precision + Direction Wake Lock Acquired (12h max)"
            )
        } catch (e: Exception) {
            LogCollector.logError("❌ Failed to acquire wake lock", e)
            isWakeLockAcquired = false
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "🔋 Wake Lock Released"
                    )
                }
            }
            wakeLock = null
            isWakeLockAcquired = false
        } catch (e: Exception) {
            LogCollector.logError("❌ Failed to release wake lock", e)
        }
    }

    /**
     * Check if battery optimization is disabled
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                LogCollector.logError("❌ Failed to check battery optimization", e)
                false
            }
        } else {
            true
        }
    }

    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermission(): Boolean {
        val fineLocationGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocationGranted && coarseLocationGranted
    }

    /**
     * High precision current location with Kalman filtering + direction
     */
    suspend fun getCurrentLocation(): LocationData? {
        if (!hasLocationPermission()) {
            LogCollector.logError("❌ No location permission")
            return null
        }

        return try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "🔍 High precision + direction location request (10s timeout)"
            )

            val location = withTimeoutOrNull(10000L) { // 10 seconds
                getCurrentLocationInternal()
            }

            if (location != null && location.accuracy <= 30f) {
                // Apply Kalman filtering for higher precision
                val filteredLocation = applyKalmanFilter(location)
                val locationData = createLocationDataWithDirection(filteredLocation)

                // Track best accuracy achieved
                if (filteredLocation.accuracy < bestAccuracy) {
                    bestAccuracy = filteredLocation.accuracy
                }

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "✅ High precision + direction location acquired",
                    mapOf(
                        "Raw Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                        "Filtered Accuracy" to "${String.format("%.1f", filteredLocation.accuracy)}m",
                        "Speed" to "${String.format("%.1f", locationData.speedKmh)} km/h",
                        "Direction" to if (locationData.carDirection != null) "${locationData.carDirection!!.toInt()}°" else "N/A",
                        "Has Speed" to if (location.hasSpeed()) "YES" else "NO",
                        "Satellites" to "$satellitesUsedInFix/$currentSatelliteCount",
                        "HDOP" to "${String.format("%.1f", currentHdop)}",
                        "Provider" to (location.provider ?: "Unknown")
                    )
                )
                locationData
            } else {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "⚠️ Location accuracy insufficient (${location?.accuracy ?: "null"}m) - need ≤30m"
                )
                null
            }
        } catch (e: Exception) {
            LogCollector.logError("❌ High precision + direction location request failed", e)
            null
        }
    }

    /**
     * Internal method with proper permission annotation
     */
    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private suspend fun getCurrentLocationInternal(): Location? {
        return try {
            fusedLocationClient.getCurrentLocation(
                reliableLocationRequest,
                null
            ).await()
        } catch (e: Exception) {
            LogCollector.logError("❌ Internal getCurrentLocation failed", e)
            null
        }
    }

    /**
     * High precision location updates with Kalman filtering, averaging + DIRECTION TRACKING
     */
    fun getLocationUpdates(): Flow<LocationData> = callbackFlow {

        if (!hasLocationPermission()) {
            LogCollector.logError("❌ Location permission not granted")
            close()
            return@callbackFlow
        }

        var currentLocationRequest = createLocationRequest(0f)

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    try {
                        locationUpdateCount++

                        if (isLocationValidReliable(location)) {
                            // Add to recent locations for possible averaging
                            addToRecentLocations(location)

                            // Apply Kalman filter for smoother coordinates
                            val filteredLocation = applyKalmanFilter(location)

                            // Try averaging if we have enough points
                            val averagedLocation = if (recentLocations.size >= 3) {
                                calculateAveragedLocation() ?: filteredLocation
                            } else {
                                filteredLocation
                            }

                            // Use the best location (filtered or averaged)
                            val bestLocation = if (averagedLocation.accuracy < filteredLocation.accuracy) {
                                averagedLocation
                            } else {
                                filteredLocation
                            }

                            // 🆕 CREATE LOCATION DATA WITH DIRECTION TRACKING
                            val locationData = createLocationDataWithDirection(bestLocation)
                            val previousSpeed = currentSpeed
                            currentSpeed = locationData.speedKmh

                            // Track speed detection issues
                            if (!bestLocation.hasSpeed() && currentSpeed == 0f) {
                                consecutiveNoSpeedCount++
                                if (consecutiveNoSpeedCount > 5) {
                                    LogCollector.addDetailedLog(
                                        LogCollector.LogCategory.GPS,
                                        "⚠️ Speed detection issue - $consecutiveNoSpeedCount consecutive no-speed readings"
                                    )
                                }
                            } else {
                                consecutiveNoSpeedCount = 0
                            }

                            // Track best accuracy
                            if (bestLocation.accuracy < bestAccuracy) {
                                bestAccuracy = bestLocation.accuracy
                            }

                            // Update accuracy tracking
                            averageAccuracy = if (averageAccuracy == 0f) {
                                bestLocation.accuracy
                            } else {
                                (averageAccuracy + bestLocation.accuracy) / 2f
                            }

                            // More frequent logging for debugging - include direction
                            val shouldLog = locationUpdateCount % 3 == 0 ||
                                    Math.abs(currentSpeed - previousSpeed) > 5f ||
                                    consecutiveNoSpeedCount > 3 ||
                                    bestLocation.accuracy <= 5f || // Log very precise locations
                                    locationData.carDirection != null // Log when direction is available

                            if (shouldLog) {
                                LogCollector.addDetailedLog(
                                    LogCollector.LogCategory.GPS,
                                    "🔍 High Precision + Direction GPS Update #$locationUpdateCount",
                                    mapOf(
                                        "Speed" to "${String.format("%.1f", locationData.speedKmh)} km/h",
                                        "Direction" to if (locationData.carDirection != null) "${locationData.carDirection!!.toInt()}°" else "Calculating...",
                                        "Raw Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                                        "Final Accuracy" to "${String.format("%.1f", bestLocation.accuracy)}m",
                                        "Method" to if (bestLocation === averagedLocation) "AVERAGED" else "KALMAN",
                                        "Has Speed" to if (bestLocation.hasSpeed()) "YES" else "NO",
                                        "Satellites" to "$satellitesUsedInFix/$currentSatelliteCount",
                                        "HDOP" to "${String.format("%.1f", currentHdop)}",
                                        "Provider" to (bestLocation.provider ?: "Unknown"),
                                        "Age" to "${(System.currentTimeMillis() - bestLocation.time) / 1000}s"
                                    )
                                )
                            }

                            // Dynamic interval adjustment - more responsive for direction tracking
                            val speedDiff = Math.abs(currentSpeed - previousSpeed)
                            if (speedDiff > 10f || locationUpdateCount % 10 == 0) {
                                try {
                                    fusedLocationClient.removeLocationUpdates(this)
                                    currentLocationRequest = createLocationRequest(currentSpeed)

                                    if (ActivityCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        fusedLocationClient.requestLocationUpdates(
                                            currentLocationRequest,
                                            this,
                                            Looper.getMainLooper()
                                        )

                                        val interval = when {
                                            currentSpeed > 60f -> "0.8s"
                                            currentSpeed > 20f -> "1.5s"
                                            currentSpeed > 5f -> "2.5s"
                                            else -> "4s"
                                        }

                                        LogCollector.addDetailedLog(
                                            LogCollector.LogCategory.GPS,
                                            "⚡ GPS + Direction interval adjusted: $interval (Speed: ${String.format("%.1f", currentSpeed)} km/h)"
                                        )
                                    }
                                } catch (e: Exception) {
                                    LogCollector.logError("❌ Failed to update GPS interval", e)
                                }
                            }

                            trySend(locationData)
                            lastLocationTime = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        LogCollector.logError("❌ Error processing location result", e)
                    }
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.GPS,
                        "❌ GPS signal lost - This may cause TTL sending issues and direction tracking problems"
                    )
                }
            }
        }

        try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "🔍 High Precision GPS + Direction Tracking Started",
                mapOf(
                    "High Speed Interval" to "0.8 seconds",
                    "Medium Speed Interval" to "1.5 seconds",
                    "Low Speed Interval" to "2.5 seconds",
                    "Stationary Interval" to "4 seconds",
                    "Kalman Filter" to "ENABLED",
                    "Location Averaging" to "ENABLED",
                    "Speed Detection" to "✅ Enhanced",
                    "Direction Tracking" to "✅ ENABLED",
                    "TTL Reliability" to "✅ Improved"
                )
            )

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                LogCollector.logError("❌ Missing location permissions")
                close()
                return@callbackFlow
            }

            fusedLocationClient.requestLocationUpdates(
                currentLocationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

        } catch (e: SecurityException) {
            LogCollector.logError("❌ GPS Security Exception", e)
            close(e)
        } catch (e: Exception) {
            LogCollector.logError("❌ GPS General Exception", e)
            close(e)
        }

        awaitClose {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "⏹️ High Precision GPS + Direction Stopped",
                mapOf(
                    "Total Updates" to locationUpdateCount.toString(),
                    "Best Accuracy" to "${String.format("%.1f", bestAccuracy)}m",
                    "Speed Issues" to "$consecutiveNoSpeedCount",
                    "Max Speed Reached" to "${String.format("%.1f", currentSpeed)} km/h",
                    "Direction Tracker" to carDirectionTracker.getStatus()["Status"]!!
                )
            )
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                releaseWakeLock()
            } catch (e: Exception) {
                LogCollector.logError("❌ Error removing location updates", e)
            }
        }
    }.distinctUntilChanged { old, new ->
        // Less aggressive filtering for higher precision and direction accuracy
        try {
            val distance = FloatArray(1)
            Location.distanceBetween(
                old.latitude, old.longitude,
                new.latitude, new.longitude,
                distance
            )

            val timeDiff = new.timestamp - old.timestamp
            val speedDiff = Math.abs(new.speedKmh - old.speedKmh)

            // 🆕 Consider direction changes for filtering
            val directionChanged = if (old.carDirection != null && new.carDirection != null) {
                val directionDiff = abs(old.carDirection!! - new.carDirection!!)
                val minDiff = min(directionDiff, 360 - directionDiff)
                minDiff > 10f // Direction changed by more than 10 degrees
            } else {
                old.carDirection != new.carDirection // Direction availability changed
            }

            // More sensitive filters - less filtering to ensure all significant updates come through
            val shouldSkip = when {
                speedDiff > 3f -> false // Don't skip if speed changed
                directionChanged -> false // 🆕 Don't skip if direction changed
                new.speedKmh > 50f -> distance[0] < 2f && timeDiff < 800   // Highway: 2m/0.8s (for direction)
                new.speedKmh > 20f -> distance[0] < 1f && timeDiff < 1500  // City: 1m/1.5s (for direction)
                else -> distance[0] < 0.5f && timeDiff < 2500             // Slow: 0.5m/2.5s (for direction)
            }

            shouldSkip
        } catch (e: Exception) {
            LogCollector.logError("❌ Error in location filtering", e)
            false
        }
    }

    /**
     * Apply Kalman filter to smooth location data
     * This significantly improves GPS precision by filtering out noise
     */
    private fun applyKalmanFilter(location: Location): Location {
        // Initialize filter with first location
        if (kalmanLat == 0.0 && kalmanLon == 0.0) {
            kalmanLat = location.latitude
            kalmanLon = location.longitude
            return location
        }

        // Kalman gain calculation
        val k = kalmanVariance / (kalmanVariance + location.accuracy * location.accuracy)

        // Update state estimates
        kalmanLat += k * (location.latitude - kalmanLat)
        kalmanLon += k * (location.longitude - kalmanLon)

        // Update error covariance
        kalmanVariance = (1 - k) * kalmanVariance + kalmanQ

        // Create filtered location
        val filteredLocation = Location(location)
        filteredLocation.latitude = kalmanLat
        filteredLocation.longitude = kalmanLon

        // Improve accuracy estimate due to filtering
        filteredLocation.accuracy = location.accuracy * 0.85f

        return filteredLocation
    }

    /**
     * Add location to recent history for averaging
     */
    private fun addToRecentLocations(location: Location) {
        // Only add reasonably accurate locations
        if (location.accuracy <= 25f) {
            recentLocations.add(location)

            // Limit size
            while (recentLocations.size > maxLocationHistory) {
                recentLocations.removeAt(0)
            }
        }
    }

    /**
     * Calculate averaged location from recent history
     * This significantly improves precision by combining multiple readings
     */
    private fun calculateAveragedLocation(): Location? {
        if (recentLocations.size < 3) {
            return null // Need at least 3 points for meaningful average
        }

        // Sort by accuracy
        val sortedLocations = recentLocations
            .filter { it.accuracy <= 25f } // Only use reasonably accurate locations
            .sortedBy { it.accuracy }
            .take(5) // Use up to 5 best locations

        if (sortedLocations.isEmpty()) {
            return null
        }

        // Weighted average based on accuracy (better accuracy = higher weight)
        var weightedLat = 0.0
        var weightedLon = 0.0
        var weightedAlt = 0.0
        var totalWeight = 0.0
        var totalAccuracy = 0f

        val currentTime = System.currentTimeMillis()

        sortedLocations.forEach { loc ->
            // Calculate weight based on accuracy and recency
            val accuracyWeight = 1.0 / (loc.accuracy.pow(2))
            val ageWeight = 1.0 / (1.0 + (currentTime - loc.time) / 1000.0) // Newer locations get higher weight
            val weight = accuracyWeight * ageWeight

            // Apply weight to coordinates
            weightedLat += loc.latitude * weight
            weightedLon += loc.longitude * weight

            if (loc.hasAltitude()) {
                weightedAlt += loc.altitude * weight
            }

            totalWeight += weight
            totalAccuracy += loc.accuracy
        }

        // Create new averaged location
        val avgLocation = Location("averaged")
        avgLocation.latitude = weightedLat / totalWeight
        avgLocation.longitude = weightedLon / totalWeight

        if (sortedLocations.any { it.hasAltitude() }) {
            avgLocation.altitude = weightedAlt / totalWeight
        }

        // Set accuracy as average of input locations, but slightly better
        // due to the averaging effect
        avgLocation.accuracy = (totalAccuracy / sortedLocations.size) * 0.8f

        // Set time to now
        avgLocation.time = currentTime

        // Copy speed from most recent location with speed data
        val mostRecentWithSpeed = sortedLocations
            .filter { it.hasSpeed() }
            .maxByOrNull { it.time }

        if (mostRecentWithSpeed != null) {
            avgLocation.speed = mostRecentWithSpeed.speed

            if (mostRecentWithSpeed.hasBearing()) {
                avgLocation.bearing = mostRecentWithSpeed.bearing
            }
        }

        return avgLocation
    }

    /**
     * Stricter location validation for high precision
     */
    private fun isLocationValidReliable(location: Location): Boolean {
        val currentTime = System.currentTimeMillis()
        val locationAge = currentTime - location.time

        return when {
            locationAge > 10000 -> false // 10 seconds max age
            location.accuracy > 50f -> false // 50m max accuracy
            location.latitude == 0.0 && location.longitude == 0.0 -> false
            location.latitude < -90 || location.latitude > 90 -> false
            location.longitude < -180 || location.longitude > 180 -> false
            else -> true
        }
    }

    /**
     * 🆕 Create LocationData with enhanced information + DIRECTION TRACKING
     * This preserves your original LocationData structure and adds direction
     */
    private fun createLocationDataWithDirection(location: Location): LocationData {
        // Create basic location data first
        val locationData = LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else 0f,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            timestamp = location.time,
            provider = location.provider ?: "Unknown"
        )

        // 🆕 UPDATE CAR DIRECTION TRACKER AND GET DIRECTION
        val carDirection = carDirectionTracker.updateLocation(locationData)

        // Return enhanced location data with direction
        val enhancedLocationData = LocationData(
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            accuracy = locationData.accuracy,
            speed = locationData.speed,
            speedKmh = locationData.speedKmh,
            altitude = locationData.altitude,
            bearing = locationData.bearing,
            timestamp = locationData.timestamp,
            provider = locationData.provider,
            carDirection = carDirection  // 🆕 Car direction from GPS track
        )

        // Enhance logging with satellite info and direction when accuracy is good
        if (location.accuracy <= 10f) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "🎯 High Precision + Direction Data",
                mapOf(
                    "Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                    "Direction" to if (carDirection != null) "${carDirection.toInt()}°" else "Calculating...",
                    "Satellites" to "$satellitesUsedInFix/$currentSatelliteCount",
                    "HDOP" to "${String.format("%.1f", currentHdop)}",
                    "Fix Quality" to getFixQualityText()
                )
            )
        }

        return enhancedLocationData
    }

    /**
     * Get fix quality text based on satellite data
     */
    private fun getFixQualityText(): String {
        return when {
            satellitesUsedInFix >= 12 -> "Excellent"
            satellitesUsedInFix >= 9 -> "Very Good"
            satellitesUsedInFix >= 6 -> "Good"
            satellitesUsedInFix >= 4 -> "Fair"
            satellitesUsedInFix > 0 -> "Poor"
            else -> "No Fix"
        }
    }

    /**
     * Get last known location with validation + direction
     */
    suspend fun getLastKnownLocation(): LocationData? {
        if (!hasLocationPermission()) {
            LogCollector.logError("❌ No location permission")
            return null
        }

        return try {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }

            val location = fusedLocationClient.lastLocation.await()

            if (location != null && isLocationValidReliable(location)) {
                // Apply Kalman filtering for better precision, even with last known
                val filteredLocation = applyKalmanFilter(location)
                createLocationDataWithDirection(filteredLocation)
            } else {
                null
            }
        } catch (e: Exception) {
            LogCollector.logError("❌ Failed to get last known location", e)
            null
        }
    }

    /**
     * Enhanced accuracy description with finer granularity
     */
    fun getLocationAccuracyDescription(accuracy: Float): String {
        return when {
            accuracy <= 3f -> "🎯 Survey-Grade (±${String.format("%.1f", accuracy)}m)"
            accuracy <= 5f -> "🔷 Excellent (±${String.format("%.1f", accuracy)}m)"
            accuracy <= 8f -> "🔵 Very Good (±${String.format("%.1f", accuracy)}m)"
            accuracy <= 15f -> "🟢 Good (±${String.format("%.1f", accuracy)}m)"
            accuracy <= 25f -> "🟡 Moderate (±${String.format("%.1f", accuracy)}m)"
            accuracy <= 50f -> "🟠 Poor (±${String.format("%.1f", accuracy)}m)"
            else -> "🔴 Very Poor (±${String.format("%.1f", accuracy)}m)"
        }
    }

    /**
     * Check if GPS is enabled
     */
    fun isGpsEnabled(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            LogCollector.logError("❌ Error checking GPS enabled status", e)
            false
        }
    }

    /**
     * Safe method to request a single location with high precision + direction
     */
    suspend fun requestSingleLocation(): LocationData? {
        return try {
            // First try high precision current location
            val highPrecision = getCurrentLocation()

            if (highPrecision != null && highPrecision.accuracy <= 15f) {
                // Found high precision fix, use it
                return highPrecision
            }

            // Fall back to last known if precision is insufficient
            val lastKnown = getLastKnownLocation()

            if (lastKnown != null) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "⚠️ Using last known location as fallback",
                    mapOf(
                        "Accuracy" to "${String.format("%.1f", lastKnown.accuracy)}m",
                        "Direction" to if (lastKnown.carDirection != null) "${lastKnown.carDirection!!.toInt()}°" else "N/A",
                        "Age" to "${(System.currentTimeMillis() - lastKnown.timestamp) / 1000}s"
                    )
                )
            }

            lastKnown
        } catch (e: Exception) {
            LogCollector.logError("❌ Failed to request single location", e)
            null
        }
    }

    /**
     * Check if the GPS accuracy is sufficient for precision applications
     */
    fun isHighPrecisionAvailable(): Boolean {
        return satellitesUsedInFix >= 6 && currentHdop <= 2.0f && bestAccuracy <= 10f
    }

    /**
     * Get wake lock status
     */
    fun isWakeLockActive(): Boolean = isWakeLockAcquired

    /**
     * 🆕 Get car direction tracker status
     */
    fun getCarDirectionStatus(): Map<String, String> {
        return carDirectionTracker.getStatus()
    }

    /**
     * Enhanced performance stats with satellite metrics + direction
     */
    fun getPerformanceStats(): Map<String, String> {
        val mode = when {
            currentSpeed > 60f -> "HIGH SPEED (0.8s)"
            currentSpeed > 20f -> "MEDIUM SPEED (1.5s)"
            currentSpeed > 5f -> "LOW SPEED (2.5s)"
            else -> "STATIONARY (4s)"
        }

        val precisionMode = when {
            satellitesUsedInFix >= 10 && currentHdop <= 1.5f -> "SURVEY-GRADE"
            satellitesUsedInFix >= 8 && currentHdop <= 2.0f -> "HIGH-PRECISION"
            satellitesUsedInFix >= 6 -> "ENHANCED"
            else -> "STANDARD"
        }

        val directionStatus = carDirectionTracker.getStatus()

        return mapOf(
            "Location Updates" to locationUpdateCount.toString(),
            "Current Speed" to "${String.format("%.1f", currentSpeed)} km/h",
            "Current Direction" to (directionStatus["Current Direction"] ?: "N/A"),
            "Direction Status" to (directionStatus["Status"] ?: "N/A"),
            "Direction Confidence" to (directionStatus["Confidence"] ?: "N/A"),
            "Average Accuracy" to if (averageAccuracy > 0) "${String.format("%.1f", averageAccuracy)}m" else "N/A",
            "Best Accuracy" to if (bestAccuracy != Float.MAX_VALUE) "${String.format("%.1f", bestAccuracy)}m" else "N/A",
            "Current Mode" to mode,
            "Precision Level" to precisionMode,
            "Satellites" to "$satellitesUsedInFix/$currentSatelliteCount",
            "HDOP" to "${String.format("%.1f", currentHdop)}",
            "Kalman Filter" to "ACTIVE",
            "Location Averaging" to "${recentLocations.size} points",
            "Speed Issues" to "$consecutiveNoSpeedCount",
            "Wake Lock" to if (isWakeLockAcquired) "Active" else "Inactive"
        )
    }

    /**
     * Get satellite status text for UI display + direction
     */
    fun getSatelliteStatusText(): String {
        val constellations = mutableListOf<String>()

        if (gpsSignalStrength > 0) constellations.add("GPS")
        if (glonassSignalStrength > 0) constellations.add("GLONASS")

        val constellationText = if (constellations.isNotEmpty()) {
            constellations.joinToString("+")
        } else {
            "GPS"
        }

        val qualityText = getFixQualityText()
        val directionText = carDirectionTracker.getCurrentDirection()?.let { "${it.toInt()}°" } ?: "N/A"

        return "$qualityText ($satellitesUsedInFix/$currentSatelliteCount) - $constellationText - Dir:$directionText"
    }

    /**
     * Create enhanced location settings request
     */
    fun createLocationSettingsRequest(): LocationSettingsRequest {
        return LocationSettingsRequest.Builder()
            .addLocationRequest(createLocationRequest(currentSpeed))
            .setAlwaysShow(true)
            .setNeedBle(false)
            .build()
    }

    /**
     * Prompt for optimal location settings
     */
    suspend fun checkAndPromptLocationSettings(activity: Activity): Boolean {
        return try {
            val settingsClient = LocationServices.getSettingsClient(activity)
            val locationSettingsRequest = createLocationSettingsRequest()
            settingsClient.checkLocationSettings(locationSettingsRequest).await()
            true
        } catch (e: ResolvableApiException) {
            try {
                e.startResolutionForResult(activity, 1001)
                false
            } catch (sendEx: IntentSender.SendIntentException) {
                LogCollector.logError("Error showing location settings dialog", sendEx)
                false
            }
        } catch (e: Exception) {
            LogCollector.logError("Error checking location settings", e)
            false
        }
    }

    /**
     * Calculate distance between two points
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * Adjust Kalman filter parameters based on desired precision
     * Call this to tune the filtering behavior
     */
    fun tuneKalmanFilter(precisionMode: PrecisionMode) {
        when (precisionMode) {
            PrecisionMode.HIGH_PRECISION -> {
                // Less filtering, more responsive to actual location changes
                kalmanQ = 0.01
                kalmanVariance = 30.0
            }
            PrecisionMode.SMOOTH_TRACKING -> {
                // More filtering, smoother path but less responsive
                kalmanQ = 0.005
                kalmanVariance = 20.0
            }
            PrecisionMode.BALANCED -> {
                // Default balanced settings
                kalmanQ = 0.008
                kalmanVariance = 25.0
            }
            PrecisionMode.DIRECTION_OPTIMIZED -> {
                // 🆕 Optimized for direction tracking
                kalmanQ = 0.007
                kalmanVariance = 25.0
            }
        }

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.GPS,
            "🔧 Kalman filter tuned to ${precisionMode.name}",
            mapOf(
                "Q" to kalmanQ.toString(),
                "Variance" to kalmanVariance.toString()
            )
        )
    }

    /**
     * Precision modes for Kalman filter tuning
     */
    enum class PrecisionMode {
        HIGH_PRECISION,      // More responsive, less smoothing
        SMOOTH_TRACKING,     // More smoothing, less responsive
        BALANCED,            // Default balanced approach
        DIRECTION_OPTIMIZED  // 🆕 Optimized for direction tracking
    }

    /**
     * Reset Kalman filter and direction tracker to start fresh
     */
    fun resetKalmanFilter() {
        kalmanLat = 0.0
        kalmanLon = 0.0
        kalmanVariance = 30.0
        carDirectionTracker.reset() // 🆕 Also reset direction tracker

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.GPS,
            "🧹 Kalman filter + direction tracker reset"
        )
    }
}