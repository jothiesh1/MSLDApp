// COMPLETE FIXED: Simple and Reliable OSM Speed Lookup
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
 * FIXED OSM LOOKUP - SIMPLE AND RELIABLE
 * Removed: Complex flyover detection, altitude dependencies, redundant calculations
 * Added: Simple OSM tag priority, reliable selection logic
 * Works perfectly with DashboardScreen.kt highway classification system
 */
class OsmJsonSpeedLookup(private val context: Context) {
    private val gson = Gson()
    private val regionCache = mutableMapOf<Region, CachedRegionData>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store last lookup results
    private var lastOsmTags = mutableMapOf<String, String>()
    private var lastSelectedElement: OverpassElement? = null

    companion object {
        private const val TAG = "OsmLookup"
        private const val SEARCH_RADIUS = 200.0
        private const val CACHE_SIZE = 6
        private const val CACHE_DURATION_MS = 600000L
    }

    /**
     * Data classes for OSM elements
     */
    data class LatLonPoint(
        val lat: Double,
        val lon: Double
    )

    data class OverpassElement(
        val id: Long,
        val tags: Map<String, String>?,
        val geometry: List<LatLonPoint>?
    )

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
     * SIMPLIFIED road candidate - no redundant distance calculation
     */
    data class RoadCandidate(
        val element: OverpassElement,
        val polylineDistance: Double,
        val speedLimit: Int?,
        val highwayType: String,
        val layer: Int,
        val isBridge: Boolean,
        val isTunnel: Boolean,
        val priority: Int
    )

    init {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.JSON,
            "FIXED OSM Lookup Initialized - Simple and Reliable",
            mapOf(
                "Search Strategy" to "OSM tag priority",
                "Search Radius" to "${SEARCH_RADIUS}m",
                "Selection Method" to "Bridge > Motorway > Regular > Service"
            )
        )
    }

    /**
     * MAIN SIMPLIFIED SPEED LOOKUP - Compatible with DashboardScreen.kt
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
                    "Location outside coverage",
                    mapOf("lat" to "%.6f".format(lat), "lon" to "%.6f".format(lon))
                )
                return null
            }

            val regionData = loadRegionData(region) ?: return null
            val nearbyRoads = findNearbyRoads(regionData, lat, lon)

            if (nearbyRoads.isEmpty()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "No roads within ${SEARCH_RADIUS.toInt()}m"
                )
                return null
            }

            // SIMPLE SELECTION - OSM tag priority only
            val bestRoad = selectBestRoad(nearbyRoads)

            return bestRoad?.let { road ->
                val speedLimit = road.speedLimit
                val confidence = calculateConfidence(road.polylineDistance)
                val roadName = road.element.tags?.get("name") ?: "Unnamed Road"

                // Store OSM tags for DashboardScreen highway classification
                lastOsmTags.clear()
                road.element.tags?.let { tags -> lastOsmTags.putAll(tags) }
                lastSelectedElement = road.element

                val roadLevel = when {
                    road.isBridge && road.layer > 0 -> "BRIDGE_L${road.layer}"
                    road.isTunnel && road.layer < 0 -> "TUNNEL_L${Math.abs(road.layer)}"
                    road.layer > 0 -> "ELEVATED_L${road.layer}"
                    road.layer < 0 -> "UNDERGROUND_L${Math.abs(road.layer)}"
                    road.isBridge -> "BRIDGE"
                    road.highwayType == "motorway" -> "MOTORWAY"
                    else -> "GROUND"
                }

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "Selected: ${speedLimit}km/h ${road.highwayType} (${road.polylineDistance.toInt()}m) [$roadLevel]",
                    mapOf(
                        "road" to roadName,
                        "highway_type" to road.highwayType,
                        "layer" to road.layer.toString(),
                        "bridge" to if (road.isBridge) "YES" else "NO",
                        "priority" to road.priority.toString(),
                        "region" to region.displayName,
                        "direction" to "${carDirection?.toInt() ?: "N/A"}°",
                        "altitude" to "${altitude?.let { String.format("%.1f", it) } ?: "N/A"}m"
                    )
                )

                SpeedLimitResult(
                    speedLimit = speedLimit,
                    roadName = "$roadName ($roadLevel)",
                    roadType = road.highwayType,
                    confidence = confidence,
                    source = "osm_${region.name.lowercase()}",
                    distance = road.polylineDistance
                )
            }
        } catch (e: Exception) {
            LogCollector.logError("OSM lookup error", e)
            return null
        }
    }

    /**
     * SIMPLE ROAD SELECTION - OSM tag priority only
     */
    private fun selectBestRoad(roads: List<RoadCandidate>): RoadCandidate? {
        // Only consider roads with speed limits
        val roadsWithSpeed = roads.filter { it.speedLimit != null }

        if (roadsWithSpeed.isEmpty()) {
            LogCollector.addDetailedLog(LogCollector.LogCategory.OSM, "No roads with speed limits found")
            return null
        }

        // Select by priority, then by distance
        val bestRoad = roadsWithSpeed.minWithOrNull(
            compareBy<RoadCandidate> { it.priority }
                .thenBy { it.polylineDistance }
        )

        bestRoad?.let { road ->
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "Road selection: Priority ${road.priority} (${getPriorityName(road.priority)}) at ${road.polylineDistance.toInt()}m"
            )
        }

        return bestRoad
    }

    private fun getPriorityName(priority: Int): String {
        return when (priority) {
            1 -> "BRIDGE"
            2 -> "MOTORWAY"
            3 -> "HIGHWAY"
            4 -> "REGULAR"
            5 -> "SERVICE"
            else -> "UNKNOWN"
        }
    }

    /**
     * Calculate polyline distance only (no redundant calculations)
     */
    private fun calculatePolylineDistance(lat: Double, lon: Double, element: OverpassElement): Double {
        val geometry = element.geometry ?: return Double.MAX_VALUE

        var minDistance = Double.MAX_VALUE

        for (i in 0 until geometry.size - 1) {
            val segmentDistance = distanceToLineSegment(
                lat, lon,
                geometry[i].lat, geometry[i].lon,
                geometry[i + 1].lat, geometry[i + 1].lon
            )

            if (segmentDistance < minDistance) {
                minDistance = segmentDistance
            }
        }

        return minDistance
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
                    "Loading ${region.displayName}"
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
                    "${region.displayName} loaded",
                    mapOf(
                        "total_roads" to elements.size.toString(),
                        "highways" to highwayElements.size.toString(),
                        "with_speed_limits" to elements.count { it.tags?.containsKey("maxspeed") == true }.toString(),
                        "bridges" to elements.count { it.tags?.get("bridge") == "yes" }.toString(),
                        "motorways" to elements.count { it.tags?.get("highway") == "motorway" }.toString()
                    )
                )

                return cachedData

            } catch (e: Exception) {
                LogCollector.logError("Failed to load ${region.displayName}", e)
                return null
            }
        }
    }

    /**
     * Load JSON file optimized
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
     * Find nearby roads - simplified with single radius
     */
    private fun findNearbyRoads(
        regionData: CachedRegionData,
        lat: Double,
        lon: Double
    ): List<RoadCandidate> {

        return regionData.elements.mapNotNull { element ->
            val polylineDistance = calculatePolylineDistance(lat, lon, element)

            if (polylineDistance < SEARCH_RADIUS) {
                val tags = element.tags ?: emptyMap()
                val highway = tags["highway"] ?: "unknown"
                val layer = tags["layer"]?.toIntOrNull() ?: 0
                val isBridge = tags["bridge"] == "yes"
                val isTunnel = tags["tunnel"] == "yes"
                val speedLimit = parseSpeedLimit(tags["maxspeed"])

                // Calculate priority based on OSM tags only
                val priority = calculateRoadPriority(highway, isBridge, layer)

                RoadCandidate(
                    element = element,
                    polylineDistance = polylineDistance,
                    speedLimit = speedLimit,
                    highwayType = highway,
                    layer = layer,
                    isBridge = isBridge,
                    isTunnel = isTunnel,
                    priority = priority
                )
            } else null
        }.sortedBy { it.polylineDistance }
            .take(10)  // Keep top 10 closest roads
    }

    /**
     * Calculate road priority based on OSM tags only
     */
    private fun calculateRoadPriority(highway: String, isBridge: Boolean, layer: Int): Int {
        return when {
            // Bridges and elevated roads first (most specific)
            isBridge || layer > 0 -> 1
            // Motorways second (usually elevated in cities)
            highway == "motorway" -> 2
            // Major highways third
            highway in listOf("trunk", "primary") -> 3
            // Regular roads fourth
            highway in listOf("secondary", "tertiary", "residential") -> 4
            // Service roads last (usually ground level)
            highway == "service" -> 5
            // Unknown roads
            else -> 6
        }
    }

    /**
     * Calculate confidence based on distance only
     */
    private fun calculateConfidence(distance: Double): Float {
        return when {
            distance <= 5.0 -> 0.95f
            distance <= 15.0 -> 0.90f
            distance <= 30.0 -> 0.85f
            distance <= 50.0 -> 0.80f
            distance <= 100.0 -> 0.75f
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
     * Get last OSM tags - Required by DashboardScreen.kt for highway classification
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
     * Get statistics
     */
    fun getStats(): String {
        synchronized(regionCache) {
            val totalElements = regionCache.values.sumOf { it.elements.size }
            val totalWithSpeed = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.containsKey("maxspeed") == true }
            }
            val totalBridges = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("bridge") == "yes" }
            }

            return buildString {
                appendLine("FIXED OSM Lookup Statistics:")
                appendLine("  Total Roads: $totalElements")
                appendLine("  With Speed Limits: $totalWithSpeed")
                appendLine("  Bridges: $totalBridges")
                appendLine("  Cache: ${regionCache.size}/$CACHE_SIZE regions")
                appendLine("  Selection: OSM tag priority")
                appendLine("  Method: Simple and reliable")
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
                "Bridges" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.get("bridge") == "yes" }
                }.toString(),
                "Selection Method" to "OSM_TAG_PRIORITY",
                "Search Radius" to "${SEARCH_RADIUS}m"
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