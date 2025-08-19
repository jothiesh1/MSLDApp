// FIXED: Simple Closest Road Selection - No More Bridge/Service Road Confusion
// File: app/src/main/java/com/gpstracker/msldapp/uis/OsmJsonSpeedLookup.kt

package com.gpstracker.msldapp.uis

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import java.io.InputStreamReader
import kotlin.math.*

/**
 * 🎯 SIMPLIFIED OSM LOOKUP - FIXED BRIDGE/SERVICE ROAD CONFUSION
 * Features: Simple closest road selection, no complex multi-factor analysis
 * Fix: Always picks closest road with speed limit - solves level confusion
 */
class OsmJsonSpeedLookup(private val context: Context) {
    private val gson = Gson()
    private val regionCache = mutableMapOf<Region, CachedRegionData>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store last lookup results
    private var lastOsmTags = mutableMapOf<String, String>()
    private var lastSelectedElement: OverpassElement? = null

    companion object {
        private const val TAG = "SimpleOsmLookup"
        private const val MAX_SEARCH_DISTANCE_HIGHWAY = 300.0
        private const val MAX_SEARCH_DISTANCE_CITY = 150.0
        private const val CACHE_SIZE = 6
        private const val CACHE_DURATION_MS = 600000L
    }

    /**
     * Region definitions with file names and boundaries
     */
    enum class Region(
        val fileName: String,
        val displayName: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        DUBAI("dubai.json", "Dubai", 24.7136, 25.4168, 54.8863, 55.5650),
        SHARJAH("Sharjah.json", "Sharjah", 24.7859, 25.4544, 55.5131, 56.0895),
        AJMAN("Ajman.json", "Ajman", 25.3626, 25.4544, 55.4338, 55.5131),
        UMM_AL_QUWAIN("Umm Al Quwain.json", "Umm Al Quwain", 25.4544, 25.6658, 55.5131, 55.9348),
        RAS_AL_KHAIMAH("Ras Al-Khaimah.json", "Ras Al Khaimah", 25.6139, 26.0859, 55.7481, 56.1886),
        FUJAIRAH("Fujairah.json", "Fujairah", 24.7858, 25.6658, 56.0895, 56.3960),
        ABU_DHABI_EAST("Abu Dhabi Eastern Region.json", "Abu Dhabi East", 22.6333, 24.7858, 53.6176, 56.0895),
        ABU_DHABI_WEST("Abu Dhabi Western Region.json", "Abu Dhabi West", 22.6333, 24.5572, 51.5833, 54.8863),
        BENGALURU("bengaluru_speed_limits.json", "Bengaluru", 12.7158, 13.1735, 77.3672, 77.8472);

        fun contains(lat: Double, lon: Double): Boolean {
            return lat in minLat..maxLat && lon in minLon..maxLon
        }
    }

    /**
     * Cached region data
     */
    data class CachedRegionData(
        val region: Region,
        val elements: List<OverpassElement>,
        val highwayElements: List<OverpassElement>,
        val loadedAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - loadedAt > CACHE_DURATION_MS
    }

    /**
     * 🎯 SIMPLIFIED road candidate - just what we need
     */
    data class SimpleRoadCandidate(
        val element: OverpassElement,
        val distance: Double,
        val polylineDistance: Double,
        val roadDirection: Float,
        val speedLimit: Int?,
        val highwayType: String,
        val layer: Int,
        val isBridge: Boolean,
        val isTunnel: Boolean
    )

    init {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.JSON,
            "🎯 SIMPLE OSM Lookup Initialized - Fixed Bridge/Service Road Confusion",
            mapOf(
                "Search Strategy" to "Closest road with speed limit",
                "Complex Analysis" to "DISABLED",
                "Bridge Confusion Fix" to "ENABLED",
                "Highway Search Radius" to "${MAX_SEARCH_DISTANCE_HIGHWAY}m",
                "City Search Radius" to "${MAX_SEARCH_DISTANCE_CITY}m"
            )
        )
    }

    /**
     * 🎯 MAIN SIMPLE SPEED LOOKUP - FIXED BRIDGE/SERVICE CONFUSION
     */
    fun findSpeedLimit(
        lat: Double,
        lon: Double,
        currentSpeed: Float = 0f,
        carDirection: Float? = null,
        altitude: Double? = null
    ): SpeedLimitResult? {
        try {
            val region = findRegion(lat, lon) ?: run {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "❌ Location outside coverage",
                    mapOf("lat" to "%.6f".format(lat), "lon" to "%.6f".format(lon))
                )
                return null
            }

            val regionData = loadRegionData(region) ?: return null

            val searchRadius = if (currentSpeed > 60f) {
                MAX_SEARCH_DISTANCE_HIGHWAY
            } else {
                MAX_SEARCH_DISTANCE_CITY
            }

            val nearbyRoads = findNearbyRoads(regionData, lat, lon, searchRadius)

            if (nearbyRoads.isEmpty()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔍 No roads within ${searchRadius.toInt()}m"
                )
                return null
            }

            // 🎯 SIMPLE SELECTION - CLOSEST ROAD WITH SPEED LIMIT
            val bestRoad = selectClosestRoadWithSpeed(nearbyRoads)

            return bestRoad?.let { road ->
                val speedLimit = road.speedLimit
                val confidence = calculateSimpleConfidence(road)
                val roadName = road.element.tags?.get("name") ?: "Unnamed Road"

                // Store OSM tags
                lastOsmTags.clear()
                road.element.tags?.let { tags ->
                    lastOsmTags.putAll(tags)
                }
                lastSelectedElement = road.element

                val roadLevel = when {
                    road.isBridge && road.layer > 0 -> "BRIDGE_L${road.layer}"
                    road.isTunnel && road.layer < 0 -> "TUNNEL_L${Math.abs(road.layer)}"
                    road.layer > 0 -> "ELEVATED_L${road.layer}"
                    road.layer < 0 -> "UNDERGROUND_L${Math.abs(road.layer)}"
                    road.isBridge -> "BRIDGE"
                    else -> "GROUND"
                }

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🎯 SIMPLE SELECTION: ${speedLimit}km/h on ${road.highwayType.uppercase()} (${road.polylineDistance.toInt()}m) [$roadLevel]",
                    mapOf(
                        "road" to roadName,
                        "highway_type" to road.highwayType,
                        "layer" to road.layer.toString(),
                        "level" to roadLevel,
                        "bridge" to if (road.isBridge) "YES" else "NO",
                        "tunnel" to if (road.isTunnel) "YES" else "NO",
                        "speed_limit" to "${speedLimit}km/h",
                        "distance" to "${road.distance.toInt()}m",
                        "polyline_distance" to "${road.polylineDistance.toInt()}m",
                        "selection_method" to "CLOSEST_WITH_SPEED",
                        "region" to region.displayName
                    )
                )

                SpeedLimitResult(
                    speedLimit = speedLimit,
                    roadName = "$roadName ($roadLevel)",
                    roadType = road.highwayType,
                    confidence = confidence,
                    source = "simple_closest_${region.name.lowercase()}",
                    distance = road.polylineDistance
                )
            }
        } catch (e: Exception) {
            LogCollector.logError("❌ Simple lookup error", e)
            return null
        }
    }

    /**
     * 🎯 NEW: Simple closest road selection - FIXES BRIDGE/SERVICE CONFUSION
     */
    private fun selectClosestRoadWithSpeed(roads: List<SimpleRoadCandidate>): SimpleRoadCandidate? {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "🎯 SIMPLE MODE: Finding closest road with speed limit from ${roads.size} candidates"
        )

        // Only consider roads with speed limits
        val roadsWithSpeed = roads.filter { it.speedLimit != null }

        if (roadsWithSpeed.isEmpty()) {
            LogCollector.addDetailedLog(LogCollector.LogCategory.OSM, "❌ No roads with speed limits found")
            return null
        }

        // Log all candidates
        roadsWithSpeed.forEachIndexed { index, road ->
            val tags = road.element.tags ?: emptyMap()
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🎯 Candidate #${index + 1}: ${tags["name"] ?: "Unnamed"}",
                mapOf(
                    "highway" to road.highwayType,
                    "layer" to road.layer.toString(),
                    "bridge" to if (road.isBridge) "YES" else "NO",
                    "tunnel" to if (road.isTunnel) "YES" else "NO",
                    "speed_limit" to "${road.speedLimit}km/h",
                    "polyline_distance" to "${road.polylineDistance.toInt()}m"
                )
            )
        }

        // Simply pick the closest one by polyline distance
        val closest = roadsWithSpeed.minByOrNull { it.polylineDistance }

        closest?.let { road ->
            val tags = road.element.tags ?: emptyMap()
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "✅ SELECTED: ${tags["name"] ?: "Unnamed"} - ${road.highwayType} with ${road.speedLimit}km/h (${road.polylineDistance.toInt()}m away)",
                mapOf(
                    "selection_reason" to "Closest road with speed limit",
                    "method" to "SIMPLE_DISTANCE_BASED",
                    "beats_complex_analysis" to "YES - No more bridge confusion!"
                )
            )
        }

        return closest
    }

    /**
     * Get last OSM tags
     */
    fun getLastOsmTags(): Map<String, String> {
        return lastOsmTags.toMap()
    }

    /**
     * Get last selected element
     */
    fun getLastSelectedElement(): OverpassElement? {
        return lastSelectedElement
    }

    /**
     * Calculate polyline distance and road direction
     */
    private fun calculatePolylineDistance(lat: Double, lon: Double, element: OverpassElement): Pair<Double, Float> {
        val geometry = element.geometry ?: return Pair(Double.MAX_VALUE, 0f)

        var minDistance = Double.MAX_VALUE
        var roadDirection = 0f

        for (i in 0 until geometry.size - 1) {
            val segmentDistance = distanceToLineSegment(
                lat, lon,
                geometry[i].lat, geometry[i].lon,
                geometry[i + 1].lat, geometry[i + 1].lon
            )

            if (segmentDistance < minDistance) {
                minDistance = segmentDistance
                roadDirection = calculateBearing(
                    geometry[i].lat, geometry[i].lon,
                    geometry[i + 1].lat, geometry[i + 1].lon
                )
            }
        }

        return Pair(minDistance, roadDirection)
    }

    /**
     * Distance to line segment
     */
    private fun distanceToLineSegment(
        pointLat: Double, pointLon: Double,
        lineLat1: Double, lineLon1: Double,
        lineLat2: Double, lineLon2: Double
    ): Double {
        val A = pointLat - lineLat1
        val B = pointLon - lineLon1
        val C = lineLat2 - lineLat1
        val D = lineLon2 - lineLon1

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0.0) {
            return calculateDistance(pointLat, pointLon, lineLat1, lineLon1)
        }

        val param = dot / lenSq
        val closestLat: Double
        val closestLon: Double

        when {
            param < 0 -> {
                closestLat = lineLat1
                closestLon = lineLon1
            }
            param > 1 -> {
                closestLat = lineLat2
                closestLon = lineLon2
            }
            else -> {
                closestLat = lineLat1 + param * C
                closestLon = lineLon1 + param * D
            }
        }

        return calculateDistance(pointLat, pointLon, closestLat, closestLon)
    }

    /**
     * Calculate bearing
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /**
     * Find which region contains the coordinates
     */
    private fun findRegion(lat: Double, lon: Double): Region? {
        return Region.values().find { it.contains(lat, lon) }
    }

    /**
     * Load region data
     */
    private fun loadRegionData(region: Region): CachedRegionData? {
        synchronized(regionCache) {
            regionCache[region]?.let { cached ->
                if (!cached.isExpired()) {
                    return cached
                }
            }

            try {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.JSON,
                    "⚡ Loading ${region.displayName} (simple mode)"
                )

                val elements = loadJsonFileOptimized(region.fileName)
                val highwayElements = elements.filter { element ->
                    val highway = element.tags?.get("highway")
                    highway in listOf("motorway", "trunk", "primary", "secondary")
                }

                val cachedData = CachedRegionData(region, elements, highwayElements)
                regionCache[region] = cachedData
                cleanupCache()

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.JSON,
                    "✅ ${region.displayName} loaded (simple mode)",
                    mapOf(
                        "total_roads" to elements.size.toString(),
                        "highways" to highwayElements.size.toString(),
                        "with_speed_limits" to elements.count { it.tags?.containsKey("maxspeed") == true }.toString()
                    )
                )

                return cachedData

            } catch (e: Exception) {
                LogCollector.logError("❌ Failed to load ${region.displayName}", e)
                return null
            }
        }
    }

    /**
     * Load JSON file
     */
    private fun loadJsonFileOptimized(fileName: String): List<OverpassElement> {
        val elements = mutableListOf<OverpassElement>()

        context.assets.open("osmdroid/$fileName").use { inputStream ->
            JsonReader(InputStreamReader(inputStream)).use { reader ->
                reader.beginObject()

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "elements" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val element = gson.fromJson<OverpassElement>(reader, OverpassElement::class.java)

                                val tags = element.tags
                                val highway = tags?.get("highway")
                                val hasSpeed = tags?.containsKey("maxspeed") == true
                                val isImportantRoad = highway in listOf(
                                    "motorway", "trunk", "primary", "secondary",
                                    "tertiary", "residential", "service"
                                )

                                if (hasSpeed || isImportantRoad) {
                                    elements.add(element)
                                }
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        return elements
    }

    /**
     * Find nearby roads - simplified
     */
    private fun findNearbyRoads(
        regionData: CachedRegionData,
        lat: Double,
        lon: Double,
        searchRadius: Double
    ): List<SimpleRoadCandidate> {

        return regionData.elements.mapNotNull { element ->
            val distance = element.geometry?.minOfOrNull { point ->
                calculateDistance(lat, lon, point.lat, point.lon)
            }

            if (distance != null && distance < searchRadius) {
                val tags = element.tags ?: emptyMap()
                val highway = tags["highway"] ?: "unknown"
                val layer = tags["layer"]?.toIntOrNull() ?: 0
                val isBridge = tags["bridge"] == "yes"
                val isTunnel = tags["tunnel"] == "yes"

                val (polylineDistance, roadDirection) = calculatePolylineDistance(lat, lon, element)
                val speedLimit = parseSpeedLimit(tags["maxspeed"])

                SimpleRoadCandidate(
                    element = element,
                    distance = distance,
                    polylineDistance = polylineDistance,
                    roadDirection = roadDirection,
                    speedLimit = speedLimit,
                    highwayType = highway,
                    layer = layer,
                    isBridge = isBridge,
                    isTunnel = isTunnel
                )
            } else null
        }.sortedBy { it.polylineDistance }
            .take(10)  // Keep top 10 closest roads
    }

    /**
     * Calculate simple confidence
     */
    private fun calculateSimpleConfidence(road: SimpleRoadCandidate): Float {
        return when {
            road.polylineDistance <= 5.0 -> 0.95f
            road.polylineDistance <= 15.0 -> 0.90f
            road.polylineDistance <= 30.0 -> 0.85f
            road.polylineDistance <= 50.0 -> 0.80f
            else -> 0.70f
        }
    }

    /**
     * Clean up cache
     */
    private fun cleanupCache() {
        if (regionCache.size > CACHE_SIZE) {
            val sortedEntries = regionCache.entries.sortedBy { it.value.loadedAt }
            val toRemove = sortedEntries.take(regionCache.size - CACHE_SIZE)

            toRemove.forEach { entry ->
                regionCache.remove(entry.key)
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.JSON,
                    "🧹 Removed ${entry.key.displayName} from cache"
                )
            }
        }
    }

    /**
     * Parse speed limit from OSM tag
     */
    private fun parseSpeedLimit(speedString: String?): Int? {
        if (speedString.isNullOrBlank()) return null

        return when {
            speedString == "none" || speedString == "unlimited" -> null
            speedString == "walk" || speedString == "walking" -> 5
            speedString.contains("mph", ignoreCase = true) -> {
                val speed = speedString.filter { it.isDigit() }.toIntOrNull()
                speed?.let { (it * 1.60934).toInt() }
            }
            else -> speedString.filter { it.isDigit() }.toIntOrNull()
        }
    }

    /**
     * Calculate distance using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Get simple statistics
     */
    fun getSimpleStats(): String {
        synchronized(regionCache) {
            val totalElements = regionCache.values.sumOf { it.elements.size }
            val totalWithSpeed = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.containsKey("maxspeed") == true }
            }

            return buildString {
                appendLine("🎯 Simple OSM Lookup Statistics:")
                appendLine("  📊 Total Roads: $totalElements")
                appendLine("  🔢 With Speed Limits: $totalWithSpeed")
                appendLine("  💾 Cache: ${regionCache.size}/$CACHE_SIZE regions")
                appendLine("  🎯 Selection Method: Closest road with speed limit")
                appendLine("  🔧 Bridge Confusion Fix: ENABLED")
                appendLine("  ⚡ Performance: OPTIMIZED")
            }
        }
    }

    /**
     * Check if data is available for a location
     */
    fun isDataAvailable(lat: Double, lon: Double): Boolean {
        return findRegion(lat, lon) != null
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, String> {
        synchronized(regionCache) {
            return mapOf(
                "Cached Regions" to regionCache.size.toString(),
                "Total Elements" to regionCache.values.sumOf { it.elements.size }.toString(),
                "With Speed Limits" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.containsKey("maxspeed") == true }
                }.toString(),
                "Selection Method" to "SIMPLE_CLOSEST",
                "Bridge Confusion Fix" to "ENABLED",
                "Complex Analysis" to "DISABLED"
            )
        }
    }

    /**
     * Clear all cached data
     */
    fun clearCache() {
        synchronized(regionCache) {
            regionCache.clear()
            lastOsmTags.clear()
            lastSelectedElement = null
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.JSON,
                "🧹 Simple lookup cache cleared"
            )
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        clearCache()
    }
}