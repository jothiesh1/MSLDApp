package com.gpstracker.msldapp.uis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
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
import kotlin.math.*
import java.util.ArrayDeque
import java.util.Locale

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val speedKmh: Float,
    val altitude: Double,
    val bearing: Float,
    val timestamp: Long,
    val provider: String,
    val carDirection: Float? = null
) {
    fun getCoordinatesString(decimalPlaces: Int = 8): String {
        val formatString = "%.${decimalPlaces}f"
        return "${String.format(Locale.getDefault(), formatString, latitude)}, ${String.format(Locale.getDefault(), formatString, longitude)}"
    }
    fun getHighPrecisionCoordinates(): String = getCoordinatesString(8)
    fun getUltraHighPrecisionCoordinates(): String = getCoordinatesString(10)
    fun getSurveyGradeCoordinates(): String = getCoordinatesString(12)
    fun getStandardCoordinates(): String = getCoordinatesString(6)
    fun getOSMCoordinates(): String = getCoordinatesString(7)
    fun getDatabaseCoordinates(): String = getCoordinatesString(8)
}

class GPSLocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockAcquired = false
    private var lastLocationTime = 0L
    private var locationUpdateCount = 0
    private var averageAccuracy = 0f
    private var currentSpeed = 0f
    private var previousSpeed = 0f
    private var bestAccuracy = Float.MAX_VALUE
    private var consecutiveNoSpeedCount = 0
    private var consecutiveLocationFailures = 0

    private var currentSatelliteCount = 0
    private var satellitesUsedInFix = 0
    private var currentHdop = 0f
    private var gpsSignalStrength = 0f
    private var glonassSignalStrength = 0f

    private var kalmanLat = 0.0
    private var kalmanLon = 0.0
    private var kalmanVariance = 30.0
    private var kalmanQ = 0.01

    private val recentLocations = mutableListOf<Location>()
    private val maxLocationHistory = 5

    private val carDirectionTracker = CarDirectionTracker()
    private var debugMode = true

    // ===== DYNAMIC INTERVAL SETTINGS =====
    private var highToLowInterval = 20000L // 20 seconds default for high→low speed
    private var lowToHighInterval = 5000L  // 5 seconds default for low→high speed
    private val minInterval = 3000L        // 3 seconds minimum
    private val maxIntervalLimit = 300000L // 300 seconds (5 minutes) absolute maximum
    private val speedTransitionThreshold = 20f // Speed change threshold to trigger interval change

    init {
        debugLog("GPSLocationManager initialized with dynamic intervals")
        performInitialSystemCheck()
    }

    // ===== INTERVAL CONFIGURATION METHODS =====

    /**
     * Set the GPS update interval for High to Low speed transitions (e.g., 60→30 km/h)
     * @param seconds Interval in seconds (minimum 3, maximum 300)
     */
    fun setHighToLowInterval(seconds: Int) {
        val clampedSeconds = seconds.coerceIn(3, 300)
        highToLowInterval = (clampedSeconds * 1000L).coerceIn(minInterval, maxIntervalLimit)
        debugLog("High→Low interval set to ${highToLowInterval}ms (${clampedSeconds}s)", mapOf(
            "Requested" to "${seconds}s",
            "Applied" to "${clampedSeconds}s",
            "Range" to "3-300s"
        ))
    }

    /**
     * Set the GPS update interval for Low to High speed transitions (e.g., 30→60 km/h)
     * @param seconds Interval in seconds (minimum 3, maximum 150)
     */
    fun setLowToHighInterval(seconds: Int) {
        val clampedSeconds = seconds.coerceIn(3, 150)
        lowToHighInterval = (clampedSeconds * 1000L).coerceIn(minInterval, 150000L)
        debugLog("Low→High interval set to ${lowToHighInterval}ms (${clampedSeconds}s)", mapOf(
            "Requested" to "${seconds}s",
            "Applied" to "${clampedSeconds}s",
            "Range" to "3-150s"
        ))
    }

    /**
     * Get current interval settings
     */
    fun getIntervalSettings(): Map<String, String> {
        return mapOf(
            "High→Low Interval" to "${highToLowInterval / 1000}s",
            "Low→High Interval" to "${lowToHighInterval / 1000}s",
            "Min Interval" to "${minInterval / 1000}s",
            "Max Interval Limit" to "${maxIntervalLimit / 1000}s",
            "Speed Threshold" to "${speedTransitionThreshold}km/h",
            "Current Speed" to "${currentSpeed.toInt()}km/h",
            "Previous Speed" to "${previousSpeed.toInt()}km/h"
        )
    }

    /**
     * Validate and get safe interval value
     */
    private fun getSafeInterval(intervalMs: Long): Long {
        return intervalMs.coerceIn(minInterval, maxIntervalLimit)
    }

    private fun performInitialSystemCheck() {
        try {
            val hasPermissions = hasLocationPermission()
            val gpsEnabled = isGpsEnabled()

            debugLog("Initial system check completed", mapOf(
                "Permissions" to if (hasPermissions) "OK" else "MISSING",
                "GPS_Enabled" to if (gpsEnabled) "YES" else "NO"
            ))
        } catch (e: Exception) {
            debugLog("Initial system check failed: ${e.message}")
        }
    }

    private fun debugLog(message: String, details: Map<String, String> = emptyMap()) {
        if (debugMode) {
            LogCollector.addDetailedLog(LogCollector.LogCategory.GPS, "DEBUG: $message", details)
        }
        Log.d("GPSManager", message + if (details.isNotEmpty()) " - $details" else "")
    }

    private fun logLocationCoordinates(location: Location, context: String) {
        if (debugMode) {
            val coordDetails = mapOf(
                "Context" to context,
                "Lat_7dp" to String.format(Locale.getDefault(), "%.7f", location.latitude),
                "Lon_7dp" to String.format(Locale.getDefault(), "%.7f", location.longitude),
                "Lat_8dp" to String.format(Locale.getDefault(), "%.8f", location.latitude),
                "Lon_8dp" to String.format(Locale.getDefault(), "%.8f", location.longitude),
                "Accuracy" to String.format(Locale.getDefault(), "%.1f", location.accuracy) + "m",
                "Provider" to (location.provider ?: "Unknown")
            )
            debugLog("LOCATION COORDINATES: $context", coordDetails)
        }
    }

    private fun logLocationData(locationData: LocationData, context: String) {
        if (debugMode) {
            val coordDetails = mapOf(
                "Context" to context,
                "High_8dp" to locationData.getHighPrecisionCoordinates(),
                "Speed_kmh" to String.format(Locale.getDefault(), "%.1f", locationData.speedKmh),
                "Direction" to if (locationData.carDirection != null) "${locationData.carDirection!!.toInt()}°" else "N/A",
                "Accuracy" to String.format(Locale.getDefault(), "%.1f", locationData.accuracy) + "m"
            )
            debugLog("LOCATION DATA: $context", coordDetails)
        }
    }

    fun formatHighPrecisionCoordinates(lat: Double, lon: Double, decimalPlaces: Int = 8): String {
        val formatString = "%.${decimalPlaces}f"
        return "${String.format(Locale.getDefault(), formatString, lat)}, ${String.format(Locale.getDefault(), formatString, lon)}"
    }

    inner class CarDirectionTracker {
        private val locationHistory = ArrayDeque<LocationData>()
        private val maxHistorySize = 8
        private var lastStableDirection: Float? = null
        private var directionConfidenceCount = 0
        private val minConfidenceForDirection = 3

        fun updateLocation(location: LocationData): Float? {
            locationHistory.addLast(location)
            if (locationHistory.size > maxHistorySize) {
                locationHistory.removeFirst()
            }

            if (locationHistory.size < 3) return lastStableDirection

            val calculatedDirection = calculateStableDirection()
            if (calculatedDirection != null) {
                if (lastStableDirection != null) {
                    val directionDiff = abs(calculatedDirection - lastStableDirection!!)
                    val minDiff = min(directionDiff, 360 - directionDiff)

                    if (minDiff <= 45f || location.speedKmh < 10f) {
                        directionConfidenceCount++
                        if (directionConfidenceCount >= minConfidenceForDirection) {
                            lastStableDirection = calculatedDirection
                        }
                    } else {
                        directionConfidenceCount = 0
                    }
                } else {
                    lastStableDirection = calculatedDirection
                    directionConfidenceCount = 1
                }
                return lastStableDirection
            }
            return lastStableDirection
        }

        private fun calculateStableDirection(): Float? {
            if (locationHistory.size < 3) return null

            val recent = locationHistory.toList().takeLast(3)
            val start = recent.first()
            val end = recent.last()

            val deltaTime = (end.timestamp - start.timestamp) / 1000.0
            if (deltaTime < 1.0) return null

            val dLon = Math.toRadians(end.longitude - start.longitude)
            val startLatRad = Math.toRadians(start.latitude)
            val endLatRad = Math.toRadians(end.latitude)

            val y = sin(dLon) * cos(endLatRad)
            val x = cos(startLatRad) * sin(endLatRad) -
                    sin(startLatRad) * cos(endLatRad) * cos(dLon)

            var bearing = Math.toDegrees(atan2(y, x))
            bearing = (bearing + 360) % 360

            val avgSpeed = recent.map { it.speedKmh }.average()
            if (avgSpeed < 5f) return null

            val distance = calculateDistance(
                start.latitude, start.longitude,
                end.latitude, end.longitude
            )

            if (distance < 10.0) return null
            return bearing.toFloat()
        }

        fun getCurrentDirection(): Float? = lastStableDirection

        fun reset() {
            locationHistory.clear()
            lastStableDirection = null
            directionConfidenceCount = 0
            debugLog("Direction tracker reset")
        }

        fun getStatus(): Map<String, String> {
            return mapOf(
                "History Points" to locationHistory.size.toString(),
                "Current Direction" to if (lastStableDirection != null) "${lastStableDirection!!.toInt()}°" else "None",
                "Confidence" to "$directionConfidenceCount/$minConfidenceForDirection"
            )
        }
    }

    /**
     * ENHANCED: Create location request with dynamic intervals based on speed transitions
     */
    private fun createLocationRequest(): LocationRequest {
        // Detect speed transition type
        val isHighToLow = previousSpeed > 50f && currentSpeed < previousSpeed - speedTransitionThreshold
        val isLowToHigh = previousSpeed < 50f && currentSpeed > previousSpeed + speedTransitionThreshold

        val updateInterval = when {
            isHighToLow -> {
                debugLog("⬇️ High→Low transition detected", mapOf(
                    "Previous" to "${previousSpeed.toInt()}km/h",
                    "Current" to "${currentSpeed.toInt()}km/h",
                    "Interval" to "${highToLowInterval}ms"
                ))
                highToLowInterval
            }
            isLowToHigh -> {
                debugLog("⬆️ Low→High transition detected", mapOf(
                    "Previous" to "${previousSpeed.toInt()}km/h",
                    "Current" to "${currentSpeed.toInt()}km/h",
                    "Interval" to "${lowToHighInterval}ms"
                ))
                lowToHighInterval
            }
            currentSpeed > 80f -> 3000L  // Very fast
            currentSpeed > 40f -> 5000L  // Fast
            currentSpeed > 20f -> 8000L  // Medium
            else -> 10000L               // Slow/stopped
        }

        val minDistance = when {
            currentSpeed > 60f -> 5f
            currentSpeed > 20f -> 3f
            else -> 1f
        }

        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateInterval).apply {
            setGranularity(Granularity.GRANULARITY_FINE)
            setMinUpdateDistanceMeters(minDistance)
            setMinUpdateIntervalMillis(minInterval) // Use minimum interval (3 seconds)
            setWaitForAccurateLocation(true)
            setMaxUpdates(Int.MAX_VALUE)
        }.build()
    }

    private val reliableLocationRequest = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(15000L)
        .setMaxUpdateAgeMillis(0L)
        .setGranularity(Granularity.GRANULARITY_FINE)
        .build()

    fun startLocationTracking() {
        debugLog("Starting location tracking...")

        if (!hasLocationPermission()) {
            debugLog("ERROR: Cannot start - missing location permissions")
            return
        }

        if (!isGpsEnabled()) {
            debugLog("ERROR: Cannot start - GPS/Location services disabled")
            return
        }

        try {
            acquireWakeLock()
            addGpsStatusListener()
            setupRecentLocations()
            carDirectionTracker.reset()
            previousSpeed = 0f
            currentSpeed = 0f

            debugLog("Location tracking started successfully", mapOf(
                "Wake_Lock" to if (isWakeLockAcquired) "ACTIVE" else "FAILED",
                "GPS_Enabled" to if (isGpsEnabled()) "YES" else "NO",
                "High→Low Interval" to "${highToLowInterval}ms",
                "Low→High Interval" to "${lowToHighInterval}ms"
            ))
        } catch (e: Exception) {
            debugLog("Failed to start location tracking: ${e.message}")
            consecutiveLocationFailures++
        }
    }

    private fun setupRecentLocations() {
        recentLocations.clear()
    }

    fun stopLocationTracking() {
        releaseWakeLock()
        carDirectionTracker.reset()

        val stats = mapOf(
            "Total_Updates" to locationUpdateCount.toString(),
            "Avg_Accuracy" to String.format(Locale.getDefault(), "%.1f", averageAccuracy) + "m",
            "Best_Accuracy" to String.format(Locale.getDefault(), "%.1f", bestAccuracy) + "m",
            "Max_Speed" to String.format(Locale.getDefault(), "%.1f", currentSpeed) + " km/h"
        )

        debugLog("Location tracking stopped", stats)

        locationUpdateCount = 0
        averageAccuracy = 0f
        currentSpeed = 0f
        previousSpeed = 0f
        bestAccuracy = Float.MAX_VALUE
        consecutiveNoSpeedCount = 0
        consecutiveLocationFailures = 0
        recentLocations.clear()
        resetKalmanFilter()
    }

    private fun addGpsStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.registerGnssStatusCallback(object : GnssStatus.Callback() {
                        override fun onSatelliteStatusChanged(status: GnssStatus) {
                            processSatelliteStatus(status)
                        }

                        override fun onFirstFix(ttffMillis: Int) {
                            debugLog("First GPS fix obtained in ${ttffMillis}ms")
                        }
                    }, null)
                    debugLog("GNSS status callback registered")
                }
            } catch (e: Exception) {
                debugLog("Failed to register GNSS status callback: ${e.message}")
            }
        }
    }

    private fun processSatelliteStatus(status: GnssStatus) {
        currentSatelliteCount = status.satelliteCount
        var usedInFix = 0

        for (i in 0 until status.satelliteCount) {
            if (status.usedInFix(i)) usedInFix++
        }

        satellitesUsedInFix = usedInFix
        currentHdop = if (usedInFix >= 4) 4f / usedInFix.toFloat() else 5f
    }

    private fun acquireWakeLock() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MSLDApp:GPSTracking"
                )
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
                isWakeLockAcquired = true
                debugLog("Wake lock acquired")
            }
        } catch (e: Exception) {
            debugLog("Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            isWakeLockAcquired = false
        } catch (e: Exception) {
            debugLog("Failed to release wake lock: ${e.message}")
        }
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted && coarseGranted
    }

    fun isGpsEnabled(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getCurrentLocation(): LocationData? {
        if (!hasLocationPermission() || !isGpsEnabled()) return null

        return try {
            val location = withTimeoutOrNull(15000L) {
                getCurrentLocationInternal()
            }

            if (location != null && location.accuracy <= 10f) {
                logLocationCoordinates(location, "RAW_LOCATION_RECEIVED")
                val filteredLocation = applyKalmanFilter(location)
                val locationData = createLocationDataWithDirection(filteredLocation)
                locationData
            } else null
        } catch (e: Exception) {
            debugLog("getCurrentLocation exception: ${e.message}")
            null
        }
    }

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private suspend fun getCurrentLocationInternal(): Location? {
        return try {
            fusedLocationClient.getCurrentLocation(reliableLocationRequest, null).await()
        } catch (e: Exception) {
            null
        }
    }

    fun getLocationUpdates(): Flow<LocationData> = callbackFlow {
        if (!hasLocationPermission() || !isGpsEnabled()) {
            close()
            return@callbackFlow
        }

        var currentLocationRequest = createLocationRequest()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    processLocationUpdate(location)?.let { locationData ->
                        // Update speed tracking for interval calculation
                        previousSpeed = currentSpeed
                        currentSpeed = locationData.speedKmh

                        // Recreate location request if speed transition detected
                        val newRequest = createLocationRequest()
                        if (newRequest != currentLocationRequest) {
                            currentLocationRequest = newRequest
                            try {
                                fusedLocationClient.removeLocationUpdates(this)
                                fusedLocationClient.requestLocationUpdates(
                                    currentLocationRequest,
                                    this,
                                    Looper.getMainLooper()
                                )
                            } catch (e: SecurityException) {
                                // Ignore
                            }
                        }

                        trySend(locationData)
                    }
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    consecutiveLocationFailures++
                }
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                close()
                return@callbackFlow
            }

            fusedLocationClient.requestLocationUpdates(
                currentLocationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                releaseWakeLock()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }.distinctUntilChanged { old, new ->
        try {
            val distance = FloatArray(1)
            Location.distanceBetween(
                old.latitude, old.longitude,
                new.latitude, new.longitude,
                distance
            )

            val timeDiff = new.timestamp - old.timestamp
            val speedDiff = abs(new.speedKmh - old.speedKmh)

            when {
                speedDiff > 5f -> false
                new.speedKmh > 80f -> distance[0] < 5f && timeDiff < 1000
                new.speedKmh > 40f -> distance[0] < 3f && timeDiff < 2000
                else -> distance[0] < 1f && timeDiff < 5000
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun processLocationUpdate(location: Location): LocationData? {
        return try {
            locationUpdateCount++
            consecutiveLocationFailures = 0

            if (isLocationValidReliable(location)) {
                logLocationCoordinates(location, "LOCATION_UPDATE_#$locationUpdateCount")

                addToRecentLocations(location)
                val filteredLocation = applyKalmanFilter(location)

                val averagedLocation = if (recentLocations.size >= 3) {
                    calculateAveragedLocation() ?: filteredLocation
                } else {
                    filteredLocation
                }

                val bestLocation = if (averagedLocation.accuracy < filteredLocation.accuracy) {
                    averagedLocation
                } else {
                    filteredLocation
                }

                val locationData = createLocationDataWithDirection(bestLocation)

                if (bestLocation.accuracy < bestAccuracy) {
                    bestAccuracy = bestLocation.accuracy
                }

                averageAccuracy = if (averageAccuracy == 0f) {
                    bestLocation.accuracy
                } else {
                    (averageAccuracy + bestLocation.accuracy) / 2f
                }

                lastLocationTime = System.currentTimeMillis()
                locationData
            } else null
        } catch (e: Exception) {
            debugLog("Error processing location: ${e.message}")
            null
        }
    }

    private fun applyKalmanFilter(location: Location): Location {
        if (kalmanLat == 0.0 && kalmanLon == 0.0) {
            kalmanLat = location.latitude
            kalmanLon = location.longitude
            return location
        }

        val k = kalmanVariance / (kalmanVariance + location.accuracy * location.accuracy)
        kalmanLat += k * (location.latitude - kalmanLat)
        kalmanLon += k * (location.longitude - kalmanLon)
        kalmanVariance = (1 - k) * kalmanVariance + kalmanQ

        val filteredLocation = Location(location)
        filteredLocation.latitude = kalmanLat
        filteredLocation.longitude = kalmanLon
        filteredLocation.accuracy = location.accuracy * 0.85f

        return filteredLocation
    }

    private fun addToRecentLocations(location: Location) {
        if (location.accuracy <= 50f) {
            recentLocations.add(location)
            while (recentLocations.size > maxLocationHistory) {
                recentLocations.removeAt(0)
            }
        }
    }

    private fun calculateAveragedLocation(): Location? {
        if (recentLocations.size < 3) return null

        val sortedLocations = recentLocations
            .filter { it.accuracy <= 50f }
            .sortedBy { it.accuracy }
            .take(5)

        if (sortedLocations.isEmpty()) return null

        var weightedLat = 0.0
        var weightedLon = 0.0
        var weightedAlt = 0.0
        var totalWeight = 0.0
        var totalAccuracy = 0f

        val currentTime = System.currentTimeMillis()

        sortedLocations.forEach { loc ->
            val accuracyWeight = 1.0 / (loc.accuracy.pow(2))
            val ageWeight = 1.0 / (1.0 + (currentTime - loc.time) / 1000.0)
            val weight = accuracyWeight * ageWeight

            weightedLat += loc.latitude * weight
            weightedLon += loc.longitude * weight

            if (loc.hasAltitude()) {
                weightedAlt += loc.altitude * weight
            }

            totalWeight += weight
            totalAccuracy += loc.accuracy
        }

        val avgLocation = Location("averaged")
        avgLocation.latitude = weightedLat / totalWeight
        avgLocation.longitude = weightedLon / totalWeight

        if (sortedLocations.any { it.hasAltitude() }) {
            avgLocation.altitude = weightedAlt / totalWeight
        }

        avgLocation.accuracy = (totalAccuracy / sortedLocations.size) * 0.8f
        avgLocation.time = currentTime

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

    private fun isLocationValidReliable(location: Location): Boolean {
        val currentTime = System.currentTimeMillis()
        val locationAge = currentTime - location.time

        return when {
            locationAge > 30000 -> false
            location.accuracy > 10f -> false
            location.latitude == 0.0 && location.longitude == 0.0 -> false
            location.latitude < -90 || location.latitude > 90 -> false
            location.longitude < -180 || location.longitude > 180 -> false
            else -> true
        }
    }

    private fun createLocationDataWithDirection(location: Location): LocationData {
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

        val carDirection = carDirectionTracker.updateLocation(locationData)

        return LocationData(
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            accuracy = locationData.accuracy,
            speed = locationData.speed,
            speedKmh = locationData.speedKmh,
            altitude = locationData.altitude,
            bearing = locationData.bearing,
            timestamp = locationData.timestamp,
            provider = locationData.provider,
            carDirection = carDirection
        )
    }

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun resetKalmanFilter() {
        kalmanLat = 0.0
        kalmanLon = 0.0
        kalmanVariance = 30.0
        carDirectionTracker.reset()
    }

    fun isWakeLockActive(): Boolean = isWakeLockAcquired

    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
    }

    fun getPerformanceStats(): Map<String, String> {
        return mapOf(
            "Location Updates" to locationUpdateCount.toString(),
            "Current Speed" to "${currentSpeed.toInt()} km/h",
            "Previous Speed" to "${previousSpeed.toInt()} km/h",
            "High→Low Interval" to "${highToLowInterval / 1000}s",
            "Low→High Interval" to "${lowToHighInterval / 1000}s",
            "Min Interval" to "${minInterval / 1000}s"
        )
    }
}