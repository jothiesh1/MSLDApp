// File: app/src/main/java/com/gpstracker/msldapp/uis/OsmJsonSpeedLookup.kt

package com.gpstracker.msldapp.uis

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlin.math.*

/**
 * Loads and searches speed limit data from OSM JSON file
 * File should be located at: assets/osmdroid/bengaluru_speed_limits.json
 */
class OsmJsonSpeedLookup(private val context: Context) {
    private var speedData: OverpassResponse? = null
    private val gson = Gson()

    companion object {
        private const val TAG = "OsmJsonSpeedLookup"
        private const val JSON_FILE_PATH = "osmdroid/bengaluru_speed_limits.json"
        private const val MAX_SEARCH_DISTANCE = 100.0 // meters
    }

    init {
        loadSpeedData()
    }

    /**
     * Load speed limit data from JSON file in assets
     */
    private fun loadSpeedData() {
        try {
            LogCollector.addLog("📦 Loading OSM speed limit data...")

            val jsonString = context.assets.open(JSON_FILE_PATH)
                .bufferedReader().use { it.readText() }

            speedData = gson.fromJson(jsonString, OverpassResponse::class.java)

            val roadsWithSpeed = speedData?.elements?.count {
                it.tags?.containsKey("maxspeed") == true
            } ?: 0

            val totalRoads = speedData?.elements?.size ?: 0

            LogCollector.addLog("✅ Loaded OSM JSON: $roadsWithSpeed/$totalRoads roads with speed limits")
            Log.i(TAG, "Successfully loaded $roadsWithSpeed roads with speed limits")

        } catch (e: Exception) {
            LogCollector.addLog("❌ Error loading OSM JSON: ${e.message}")
            Log.e(TAG, "Error loading speed data from $JSON_FILE_PATH", e)
            speedData = null
        }
    }

    /**
     * Find speed limit for given coordinates
     * @param lat Latitude
     * @param lon Longitude
     * @return SpeedLimitResult or null if not found
     */
    fun findSpeedLimit(lat: Double, lon: Double): SpeedLimitResult? {
        val data = speedData ?: return null

        var bestMatch: OverpassElement? = null
        var minDistance = Double.MAX_VALUE

        LogCollector.addLog("🔍 Searching ${data.elements.size} roads for speed limit...")

        // Search through all ways with speed limits
        data.elements.forEach { element ->
            if (element.tags?.containsKey("maxspeed") == true && !element.geometry.isNullOrEmpty()) {

                // Find closest point on this road
                val closestDistance = element.geometry.minOf { point ->
                    calculateDistance(lat, lon, point.lat, point.lon)
                }

                // Within search distance and closer than previous matches
                if (closestDistance < MAX_SEARCH_DISTANCE && closestDistance < minDistance) {
                    minDistance = closestDistance
                    bestMatch = element
                }
            }
        }

        return bestMatch?.let { way ->
            val speedLimit = parseSpeedLimit(way.tags?.get("maxspeed"))
            val confidence = calculateConfidence(minDistance)
            val roadName = way.tags?.get("name") ?: "Unnamed Road"

            LogCollector.addLog("🎯 Found: ${speedLimit}km/h on $roadName (${minDistance.toInt()}m away, ${(confidence * 100).toInt()}% confidence)")

            SpeedLimitResult(
                speedLimit = speedLimit,
                roadName = roadName,
                roadType = way.tags?.get("highway"),
                confidence = confidence,
                source = "osm_json",
                distance = minDistance
            )
        }
    }

    /**
     * Parse speed limit string from OSM tag
     * Handles various formats: "50", "80 km/h", "30 mph", etc.
     */
    private fun parseSpeedLimit(speedString: String?): Int? {
        if (speedString.isNullOrBlank()) return null

        return when {
            speedString == "none" || speedString == "unlimited" -> null
            speedString == "walk" || speedString == "walking" -> 5
            speedString.contains("mph", ignoreCase = true) -> {
                val speed = speedString.filter { it.isDigit() }.toIntOrNull()
                speed?.let { (it * 1.60934).toInt() } // Convert mph to km/h
            }
            else -> speedString.filter { it.isDigit() }.toIntOrNull()
        }
    }

    /**
     * Calculate distance between two points using Haversine formula
     * @return Distance in meters
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Calculate confidence based on distance to road
     * Closer = higher confidence
     */
    private fun calculateConfidence(distance: Double): Float {
        return when {
            distance <= 10.0 -> 0.95f   // Very close - high confidence
            distance <= 25.0 -> 0.85f   // Close - good confidence
            distance <= 50.0 -> 0.70f   // Medium distance - ok confidence
            distance <= 100.0 -> 0.50f  // Far - low confidence
            else -> 0.20f               // Very far - very low confidence
        }
    }

    /**
     * Get statistics about loaded data
     */
    fun getDataStats(): String? {
        val data = speedData ?: return null

        val roadsWithSpeed = data.elements.count { it.tags?.containsKey("maxspeed") == true }
        val totalRoads = data.elements.size
        val coverage = if (totalRoads > 0) (roadsWithSpeed * 100) / totalRoads else 0

        return "📊 OSM JSON: $roadsWithSpeed/$totalRoads roads ($coverage% have speed limits)"
    }

    /**
     * Check if data is loaded successfully
     */
    fun isDataLoaded(): Boolean = speedData != null

    /**
     * Get total number of roads with speed limits
     */
    fun getSpeedLimitRoadCount(): Int {
        return speedData?.elements?.count {
            it.tags?.containsKey("maxspeed") == true
        } ?: 0
    }

    /**
     * Get sample of roads with speed limits (for debugging)
     */
    fun getSampleRoads(limit: Int = 5): List<String> {
        return speedData?.elements?.filter {
            it.tags?.containsKey("maxspeed") == true
        }?.take(limit)?.mapNotNull { element ->
            val name = element.tags?.get("name") ?: "Unnamed"
            val speed = element.tags?.get("maxspeed")
            val type = element.tags?.get("highway")
            "$name: $speed km/h ($type)"
        } ?: emptyList()
    }
}