// File: app/src/main/java/com/gpstracker/msldapp/uis/GPSLocationManager.kt

package com.gpstracker.msldapp.uis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float, // m/s
    val speedKmh: Float, // km/h
    val altitude: Double,
    val bearing: Float,
    val timestamp: Long,
    val provider: String
)

class GPSLocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, // Highest accuracy GPS
        1000L // Update every 1 second
    ).apply {
        setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
        setMinUpdateDistanceMeters(2f) // Update every 2 meters
        setMaxUpdateDelayMillis(2000L) // Max delay 2 seconds
        setMinUpdateIntervalMillis(500L) // Min interval 0.5 seconds
        setWaitForAccurateLocation(true) // Wait for accurate location
    }.build()

    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermission(): Boolean {
        val hasPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.PERMISSION,
            "Location Permission Check",
            mapOf(
                "Fine Location" to if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) "✅ Granted" else "❌ Denied",
                "Coarse Location" to if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) "✅ Granted" else "❌ Denied",
                "Overall Status" to if (hasPermission) "✅ All permissions granted" else "❌ Missing permissions"
            )
        )

        return hasPermission
    }

    /**
     * Get continuous location updates as Flow
     */
    fun getLocationUpdates(): Flow<LocationData> = callbackFlow {

        if (!hasLocationPermission()) {
            LogCollector.logError("Location permission not granted for getLocationUpdates")
            close()
            return@callbackFlow
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    val locationData = LocationData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        speedKmh = location.speed * 3.6f, // Convert m/s to km/h
                        altitude = location.altitude,
                        bearing = location.bearing,
                        timestamp = location.time,
                        provider = location.provider ?: "Unknown"
                    )

                    // Log detailed GPS data
                    LogCollector.logGPSLocation(locationData)

                    // Additional GPS quality assessment
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.GPS,
                        "GPS Quality Assessment",
                        mapOf(
                            "Accuracy Level" to getLocationAccuracyDescription(location.accuracy),
                            "Signal Quality" to getSignalQuality(location.accuracy),
                            "Speed Available" to if (location.hasSpeed()) "✅ Yes" else "❌ No",
                            "Bearing Available" to if (location.hasBearing()) "✅ Yes" else "❌ No",
                            "Altitude Available" to if (location.hasAltitude()) "✅ Yes" else "❌ No",
                            "Time Since Fix" to "${System.currentTimeMillis() - location.time}ms ago"
                        )
                    )

                    trySend(locationData)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "GPS Availability Changed",
                    mapOf(
                        "Available" to if (availability.isLocationAvailable) "✅ Yes" else "❌ No",
                        "Status" to if (availability.isLocationAvailable) "GPS signal acquired" else "GPS signal lost - Check if GPS is enabled"
                    )
                )
            }
        }

        try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "GPS Tracking Started",
                mapOf(
                    "Update Interval" to "1000ms",
                    "Min Distance" to "2 meters",
                    "Priority" to "HIGH_ACCURACY",
                    "Max Update Delay" to "2000ms",
                    "Min Update Interval" to "500ms",
                    "Wait for Accurate" to "true"
                )
            )

            LogCollector.logBackendOperation(
                "Location Request Configuration",
                mapOf(
                    "Priority" to "PRIORITY_HIGH_ACCURACY",
                    "Interval" to "${locationRequest.intervalMillis}ms",
                    "FastestInterval" to "${locationRequest.minUpdateIntervalMillis}ms",
                    "SmallestDisplacement" to "${locationRequest.minUpdateDistanceMeters}m"
                )
            )

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

        } catch (e: SecurityException) {
            LogCollector.logError("GPS Security Exception", e)
            close(e)
        } catch (e: Exception) {
            LogCollector.logError("GPS General Exception", e)
            close(e)
        }

        awaitClose {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "GPS Tracking Stopped",
                mapOf(
                    "Reason" to "User stopped tracking or app closed",
                    "Final Status" to "Location updates removed"
                )
            )
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }.distinctUntilChanged { old, new ->
        // Only emit if location changed significantly (>1 meter)
        val distance = FloatArray(1)
        Location.distanceBetween(
            old.latitude, old.longitude,
            new.latitude, new.longitude,
            distance
        )
        val shouldSkip = distance[0] < 1.0f

        if (shouldSkip) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "Location Update Filtered",
                mapOf(
                    "Reason" to "Movement less than 1 meter",
                    "Distance Moved" to "${String.format("%.2f", distance[0])}m",
                    "Action" to "Skipped update"
                )
            )
        }

        shouldSkip
    }

    /**
     * Get current location once (not continuous)
     */
    suspend fun getCurrentLocation(): LocationData? {
        if (!hasLocationPermission()) {
            LogCollector.logError("No location permission for getCurrentLocation")
            return null
        }

        return try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.GPS,
                "Single Location Request Started",
                mapOf(
                    "Method" to "getCurrentLocation",
                    "Priority" to "HIGH_ACCURACY",
                    "Timeout" to "10 seconds"
                )
            )

            val location = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).await()

            if (location != null) {
                val locationData = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    speedKmh = location.speed * 3.6f,
                    altitude = location.altitude,
                    bearing = location.bearing,
                    timestamp = location.time,
                    provider = location.provider ?: "Unknown"
                )

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.GPS,
                    "Single Location Request Success",
                    mapOf(
                        "Coordinates" to "${String.format("%.7f", location.latitude)}, ${String.format("%.7f", location.longitude)}",
                        "Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                        "Provider" to (location.provider ?: "Unknown"),
                        "Age" to "${System.currentTimeMillis() - location.time}ms"
                    )
                )

                locationData
            } else {
                LogCollector.logError("Current location is null - GPS may be disabled or no signal")
                null
            }
        } catch (e: Exception) {
            LogCollector.logError("Failed to get current location", e)
            null
        }
    }

    /**
     * Get human-readable accuracy description
     */
    fun getLocationAccuracyDescription(accuracy: Float): String {
        return when {
            accuracy <= 3f -> "🎯 Excellent"
            accuracy <= 10f -> "✅ Good"
            accuracy <= 20f -> "⚠️ Fair"
            accuracy <= 50f -> "❌ Poor"
            else -> "🚫 Very Poor"
        }
    }

    /**
     * Get signal quality assessment
     */
    private fun getSignalQuality(accuracy: Float): String {
        return when {
            accuracy <= 5f -> "🟢 Excellent (GPS + GLONASS)"
            accuracy <= 15f -> "🟡 Good (Multiple satellites)"
            accuracy <= 30f -> "🟠 Fair (Limited satellites)"
            else -> "🔴 Poor (Weak signal)"
        }
    }

    /**
     * Get GPS status information
     */
    fun getGPSStatus(): String {
        return buildString {
            appendLine("🛰️ GPS Configuration:")
            appendLine("• Update interval: 1 second")
            appendLine("• Minimum distance: 2 meters")
            appendLine("• Priority: High accuracy")
            appendLine("• Permission: ${if (hasLocationPermission()) "✅ Granted" else "❌ Denied"}")
            appendLine()
            appendLine("💡 Tips for better GPS:")
            appendLine("• Go outdoors for best signal")
            appendLine("• Wait 10-30 seconds for GPS lock")
            appendLine("• Move around to improve accuracy")
            appendLine("• Avoid tall buildings/tunnels")
        }
    }

    /**
     * Format location for display
     */
    fun formatLocation(location: LocationData): String {
        return buildString {
            appendLine("📍 ${String.format("%.7f", location.latitude)}, ${String.format("%.7f", location.longitude)}")
            appendLine("🎯 ${getLocationAccuracyDescription(location.accuracy)} (±${String.format("%.1f", location.accuracy)}m)")
            appendLine("🚀 Speed: ${String.format("%.1f", location.speedKmh)} km/h")
            appendLine("⛰️ Altitude: ${location.altitude.toInt()}m")
            appendLine("🧭 Bearing: ${location.bearing.toInt()}°")
            appendLine("📡 Provider: ${location.provider}")
            appendLine("⏰ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(location.timestamp))}")
        }
    }

    /**
     * Get detailed GPS diagnostics
     */
    fun getGPSDiagnostics(): String {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.DEBUG,
            "GPS Diagnostics Requested",
            mapOf(
                "Permission Status" to if (hasLocationPermission()) "✅ Granted" else "❌ Denied",
                "Location Services" to "Checking...",
                "System Info" to "Android GPS Manager"
            )
        )

        return buildString {
            appendLine("🔧 GPS Diagnostics:")
            appendLine("• Permissions: ${if (hasLocationPermission()) "✅ OK" else "❌ Missing"}")
            appendLine("• Location Request: Configured for high accuracy")
            appendLine("• Update Frequency: Every 1 second or 2 meters")
            appendLine("• Provider: Fused Location Provider")
            appendLine()
            appendLine("📊 Recent GPS Stats:")
            val stats = LogCollector.getSystemStats()
            appendLine("• GPS Updates: ${stats["GPS Updates"]}")
            appendLine("• Total Logs: ${stats["Total Logs"]}")
            appendLine("• Uptime: ${stats["Uptime"]}")
        }
    }
}