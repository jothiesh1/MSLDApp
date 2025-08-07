// ENHANCED OSM LOOKUP WITH HIGHWAY CLASSIFICATION INTEGRATION
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
 * 🛣️ HIGHWAY CLASSIFICATION OSM LOOKUP
 * Features: Complete highway classification, bridge/tunnel detection, altitude integration
 */
class OsmJsonSpeedLookup(private val context: Context) {
    private val gson = Gson()
    private val regionCache = mutableMapOf<Region, CachedRegionData>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 🛣️ STORE LAST LOOKUP RESULTS FOR HIGHWAY CLASSIFICATION
    private var lastOsmTags = mutableMapOf<String, String>()
    private var lastSelectedElement: OverpassElement? = null

    companion object {
        private const val TAG = "HighwayOsmLookup"
        private const val MAX_SEARCH_DISTANCE_HIGHWAY = 300.0
        private const val MAX_SEARCH_DISTANCE_CITY = 150.0
        private const val CACHE_SIZE = 6
        private const val CACHE_DURATION_MS = 600000L
        private const val PREDICTIVE_DISTANCE = 2000.0
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
     * Cached region data with highway indexing
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
     * 🛣️ Enhanced road candidate with highway classification
     */
    data class HighwayRoadCandidate(
        val element: OverpassElement,
        val distance: Double,
        val isHighway: Boolean,
        val priority: Int,
        val highwayType: String,
        val isMotorway: Boolean,
        val isTrunk: Boolean,
        val layer: Int,
        val isBridge: Boolean,
        val isTunnel: Boolean
    )

    init {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.JSON,
            "🛣️ Highway Classification OSM Lookup Initialized",
            mapOf(
                "Highway Search Radius" to "${MAX_SEARCH_DISTANCE_HIGHWAY}m",
                "City Search Radius" to "${MAX_SEARCH_DISTANCE_CITY}m",
                "Highway Classification" to "ENABLED",
                "Bridge/Tunnel Detection" to "ENABLED",
                "80+ km/h Enforcement" to "ENABLED"
            )
        )
    }

    /**
     * 🛣️ MAIN HIGHWAY-AWARE SPEED LOOKUP
     */
    fun findSpeedLimit(lat: Double, lon: Double, currentSpeed: Float = 0f): SpeedLimitResult? {
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

            val nearbyRoads = findNearbyHighwayRoads(regionData, lat, lon, currentSpeed, searchRadius)

            if (nearbyRoads.isEmpty()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔍 No roads within ${searchRadius.toInt()}m"
                )
                return null
            }

            val bestRoad = selectBestHighwayRoad(nearbyRoads, currentSpeed)

            return bestRoad?.let { road ->
                val speedLimit = parseSpeedLimit(road.element.tags?.get("maxspeed"))
                val confidence = calculateHighwayConfidence(road, currentSpeed)
                val roadName = road.element.tags?.get("name") ?: "Unnamed Road"

                // 🛣️ STORE OSM TAGS FOR HIGHWAY CLASSIFICATION
                lastOsmTags.clear()
                road.element.tags?.let { tags ->
                    lastOsmTags.putAll(tags)
                }
                lastSelectedElement = road.element

                // 🛣️ HIGHWAY ENFORCEMENT (Manager requirement)
                val enforcedSpeedLimit = enforceHighwayMinimum(speedLimit, road)

                val roadLevel = when {
                    road.isBridge && road.layer > 0 -> "BRIDGE_L${road.layer}"
                    road.isTunnel && road.layer < 0 -> "TUNNEL_L${Math.abs(road.layer)}"
                    road.layer > 0 -> "ELEVATED_L${road.layer}"
                    road.layer < 0 -> "UNDERGROUND_L${Math.abs(road.layer)}"
                    road.isBridge -> "BRIDGE"
                    road.isMotorway || road.isTrunk -> "HIGHWAY"
                    else -> "GROUND"
                }

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🛣️ HIGHWAY: ${enforcedSpeedLimit}km/h on ${road.highwayType.uppercase()} (${road.distance.toInt()}m) [$roadLevel]",
                    mapOf(
                        "road" to roadName,
                        "highway_type" to road.highwayType,
                        "is_highway" to if (road.isHighway) "YES" else "NO",
                        "layer" to road.layer.toString(),
                        "level" to roadLevel,
                        "bridge" to if (road.isBridge) "YES" else "NO",
                        "tunnel" to if (road.isTunnel) "YES" else "NO",
                        "original_limit" to "${speedLimit}km/h",
                        "enforced_limit" to "${enforcedSpeedLimit}km/h",
                        "enforcement" to if (enforcedSpeedLimit != speedLimit) "APPLIED" else "NONE",
                        "speed_match" to calculateSpeedMatch(currentSpeed, road),
                        "priority" to road.priority.toString(),
                        "region" to region.displayName
                    )
                )

                SpeedLimitResult(
                    speedLimit = enforcedSpeedLimit,
                    roadName = "$roadName ($roadLevel)",
                    roadType = road.highwayType,
                    confidence = confidence,
                    source = "highway_${region.name.lowercase()}",
                    distance = road.distance
                )
            }
        } catch (e: Exception) {
            LogCollector.logError("❌ Highway lookup error", e)
            return null
        }
    }

    /**
     * 🛣️ GET LAST OSM TAGS FOR HIGHWAY CLASSIFICATION (Dashboard integration)
     */
    fun getLastOsmTags(): Map<String, String> {
        return lastOsmTags.toMap()
    }

    /**
     * 🛣️ GET LAST SELECTED ELEMENT FOR DEBUGGING
     */
    fun getLastSelectedElement(): OverpassElement? {
        return lastSelectedElement
    }

    /**
     * 🚨 HIGHWAY SPEED LIMIT ENFORCEMENT (Manager requirement)
     */
    private fun enforceHighwayMinimum(speedLimit: Int?, road: HighwayRoadCandidate): Int {
        if (speedLimit == null) {
            // No speed limit - use highway defaults
            val defaultSpeed = when {
                road.isMotorway -> 100
                road.isTrunk -> 80
                road.highwayType == "primary" -> 60
                road.highwayType == "secondary" -> 50
                road.highwayType == "tertiary" -> 40
                road.highwayType == "residential" -> 30
                road.highwayType == "service" -> 20
                else -> 50
            }

            // Manager requirement: Major highways never below 80
            val enforcedSpeed = if (road.isHighway) maxOf(defaultSpeed, 80) else defaultSpeed

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🚨 HIGHWAY ENFORCEMENT: No speed limit → ${enforcedSpeed}km/h (${road.highwayType})"
            )

            return enforcedSpeed
        }

        // Speed limit exists - enforce highway minimum
        val enforcedSpeed = when {
            road.isMotorway -> maxOf(speedLimit, 100) // Motorway never below 100
            road.isTrunk -> maxOf(speedLimit, 80)     // Trunk never below 80
            road.isHighway -> maxOf(speedLimit, 80)   // Manager: Major highway never below 80
            else -> speedLimit
        }

        if (enforcedSpeed != speedLimit) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🚨 HIGHWAY ENFORCEMENT: ${speedLimit}km/h → ${enforcedSpeed}km/h (${road.highwayType} minimum)"
            )
        }

        return enforcedSpeed
    }

    /**
     * Find which region contains the coordinates
     */
    private fun findRegion(lat: Double, lon: Double): Region? {
        return Region.values().find { it.contains(lat, lon) }
    }

    /**
     * Load region data with highway optimization
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
                    "⚡ Loading ${region.displayName} (highway classification mode)"
                )

                val elements = loadJsonFileOptimized(region.fileName)
                val highwayElements = elements.filter { element ->
                    val highway = element.tags?.get("highway")
                    highway in listOf("motorway", "trunk", "primary", "secondary")
                }

                val cachedData = CachedRegionData(region, elements, highwayElements)
                regionCache[region] = cachedData
                cleanupCacheHighSpeed()

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.JSON,
                    "✅ ${region.displayName} loaded (highway mode)",
                    mapOf(
                        "total_roads" to elements.size.toString(),
                        "highways" to highwayElements.size.toString(),
                        "motorways" to elements.count { it.tags?.get("highway") == "motorway" }.toString(),
                        "trunks" to elements.count { it.tags?.get("highway") == "trunk" }.toString(),
                        "bridges" to elements.count { it.tags?.get("bridge") == "yes" }.toString(),
                        "tunnels" to elements.count { it.tags?.get("tunnel") == "yes" }.toString()
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
     * Load JSON file optimized for highway classification
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
     * 🛣️ ENHANCED: Find nearby roads with complete highway classification
     */
    private fun findNearbyHighwayRoads(
        regionData: CachedRegionData,
        lat: Double,
        lon: Double,
        currentSpeed: Float,
        searchRadius: Double
    ): List<HighwayRoadCandidate> {

        val searchElements = if (currentSpeed > 60f) {
            regionData.highwayElements + regionData.elements
        } else {
            regionData.elements
        }

        return searchElements.mapNotNull { element ->
            val distance = element.geometry?.minOfOrNull { point ->
                calculateDistance(lat, lon, point.lat, point.lon)
            }

            if (distance != null && distance < searchRadius) {
                val tags = element.tags ?: emptyMap()
                val highway = tags["highway"] ?: "unknown"
                val layer = tags["layer"]?.toIntOrNull() ?: 0
                val isBridge = tags["bridge"] == "yes"
                val isTunnel = tags["tunnel"] == "yes"

                val isHighway = highway in listOf("motorway", "trunk")
                val isMotorway = highway == "motorway"
                val isTrunk = highway == "trunk"

                val priority = calculateHighwayPriority(
                    highway, currentSpeed, distance, layer, isBridge, isTunnel
                )

                HighwayRoadCandidate(
                    element = element,
                    distance = distance,
                    isHighway = isHighway,
                    priority = priority,
                    highwayType = highway,
                    isMotorway = isMotorway,
                    isTrunk = isTrunk,
                    layer = layer,
                    isBridge = isBridge,
                    isTunnel = isTunnel
                )
            } else null
        }.sortedWith(compareByDescending<HighwayRoadCandidate> { it.priority }
            .thenBy { it.distance })
            .take(15)
    }

    /**
     * 🛣️ HIGHWAY PRIORITY CALCULATION
     */
    private fun calculateHighwayPriority(
        highway: String,
        currentSpeed: Float,
        distance: Double,
        layer: Int,
        isBridge: Boolean,
        isTunnel: Boolean
    ): Int {
        var priority = 0

        // Base highway priority
        priority += when (highway) {
            "motorway" -> if (currentSpeed > 80f) 100 else 50
            "trunk" -> if (currentSpeed > 60f) 90 else 40
            "primary" -> if (currentSpeed > 40f) 80 else 60
            "secondary" -> if (currentSpeed > 30f) 70 else 70
            "tertiary" -> if (currentSpeed < 50f) 60 else 30
            "residential" -> if (currentSpeed < 40f) 50 else 20
            "service" -> if (currentSpeed < 30f) 40 else 10
            else -> 20
        }

        // 🛣️ HIGHWAY CLASSIFICATION BONUS
        when {
            // High speed on major highways
            currentSpeed > 80f -> {
                if (highway == "motorway") priority += 60
                if (highway == "trunk") priority += 50
                if (isBridge || layer > 0) priority += 40 // Prefer flyovers
                if (highway == "service") priority -= 50
            }

            // Medium speed on main roads
            currentSpeed > 50f -> {
                if (highway in listOf("primary", "secondary", "trunk")) priority += 30
                if (layer == 0 && !isBridge) priority += 20
                if (highway == "service" && layer <= 0) priority -= 30
            }

            // Low speed on local roads
            currentSpeed < 30f -> {
                if (highway in listOf("service", "residential", "tertiary")) priority += 25
                if (layer <= 0 && !isBridge) priority += 20
                if (highway == "motorway") priority -= 40
            }
        }

        // Distance bonus
        priority += when {
            distance < 5.0 -> 40
            distance < 15.0 -> 30
            distance < 30.0 -> 20
            distance < 50.0 -> 10
            else -> 0
        }

        return priority
    }

    /**
     * 🛣️ SELECT BEST HIGHWAY ROAD
     */
    private fun selectBestHighwayRoad(roads: List<HighwayRoadCandidate>, currentSpeed: Float): HighwayRoadCandidate? {
        if (roads.isEmpty()) return null

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "🛣️ HIGHWAY SELECTION: Found ${roads.size} roads for ${currentSpeed}km/h"
        )

        roads.take(5).forEachIndexed { index, road ->
            val tags = road.element.tags ?: emptyMap()
            val speedLimit = parseSpeedLimit(tags["maxspeed"])

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🛣️ Road #${index + 1}: ${tags["name"] ?: "Unnamed"}",
                mapOf(
                    "highway" to road.highwayType,
                    "is_highway" to if (road.isHighway) "YES" else "NO",
                    "layer" to road.layer.toString(),
                    "bridge" to if (road.isBridge) "YES" else "NO",
                    "tunnel" to if (road.isTunnel) "YES" else "NO",
                    "speed_limit" to "${speedLimit}km/h",
                    "distance" to "${road.distance.toInt()}m",
                    "priority" to road.priority.toString()
                )
            )
        }

        val selectedRoad = when {
            // High speed: Prefer motorways and major highways
            currentSpeed > 80f -> {
                roads.find { it.isMotorway } ?:
                roads.find { it.isTrunk } ?:
                roads.find { it.isHighway } ?:
                roads.maxByOrNull { parseSpeedLimit(it.element.tags?.get("maxspeed")) ?: 0 }
            }

            // Medium speed: Prefer main roads
            currentSpeed >= 30f -> {
                roads.find { it.highwayType in listOf("primary", "secondary", "trunk") && it.layer == 0 } ?:
                roads.find { it.highwayType in listOf("primary", "secondary", "trunk") } ?:
                roads.minByOrNull { kotlin.math.abs((parseSpeedLimit(it.element.tags?.get("maxspeed")) ?: 50) - currentSpeed.toInt()) }
            }

            // Low speed: Prefer local roads
            else -> {
                roads.find { it.highwayType in listOf("service", "residential") && it.layer <= 0 } ?:
                roads.find { it.highwayType in listOf("service", "residential") } ?:
                roads.minByOrNull { it.distance }
            }
        }

        selectedRoad?.let { road ->
            val tags = road.element.tags ?: emptyMap()
            val speedLimit = parseSpeedLimit(tags["maxspeed"])
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🎯 HIGHWAY SELECTED: ${tags["name"] ?: "Unnamed"}",
                mapOf(
                    "highway_type" to road.highwayType,
                    "is_major_highway" to if (road.isHighway) "YES" else "NO",
                    "speed_limit" to "${speedLimit}km/h",
                    "layer" to road.layer.toString(),
                    "level" to when {
                        road.isBridge && road.layer > 0 -> "BRIDGE_L${road.layer}"
                        road.isTunnel && road.layer < 0 -> "TUNNEL_L${Math.abs(road.layer)}"
                        else -> "GROUND"
                    },
                    "selection_reason" to when {
                        currentSpeed > 80f -> "High speed highway preference"
                        currentSpeed >= 30f -> "Main road preference"
                        else -> "Local road preference"
                    }
                )
            )
        }

        return selectedRoad
    }

    /**
     * 🛣️ CALCULATE HIGHWAY CONFIDENCE
     */
    private fun calculateHighwayConfidence(road: HighwayRoadCandidate, currentSpeed: Float): Float {
        var confidence = when {
            road.distance <= 10.0 -> 0.95f
            road.distance <= 25.0 -> 0.90f
            road.distance <= 50.0 -> 0.80f
            road.distance <= 100.0 -> 0.70f
            road.distance <= 200.0 -> 0.60f
            else -> 0.40f
        }

        // Highway type matching bonus
        when {
            currentSpeed > 80f && road.isHighway -> confidence += 0.15f
            currentSpeed in 40f..80f && road.highwayType in listOf("primary", "secondary", "trunk") -> confidence += 0.10f
            currentSpeed < 40f && road.highwayType in listOf("service", "residential") -> confidence += 0.10f
            currentSpeed > 60f && road.highwayType == "service" -> confidence -= 0.20f
            currentSpeed < 30f && road.isHighway -> confidence -= 0.15f
        }

        // Layer matching bonus
        when {
            currentSpeed > 80f && (road.layer > 0 || road.isBridge) -> confidence += 0.10f
            currentSpeed < 30f && (road.layer > 0 || road.isBridge) -> confidence -= 0.15f
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculate speed to road type match
     */
    private fun calculateSpeedMatch(currentSpeed: Float, road: HighwayRoadCandidate): String {
        return when {
            currentSpeed > 80f && road.isHighway && (road.isBridge || road.layer > 0) -> "EXCELLENT"
            currentSpeed < 30f && road.highwayType == "service" && road.layer <= 0 -> "EXCELLENT"
            currentSpeed > 60f && road.highwayType == "service" -> "POOR"
            currentSpeed < 30f && road.isHighway -> "POOR"
            currentSpeed in 40f..80f && road.highwayType in listOf("primary", "secondary") -> "GOOD"
            else -> "OK"
        }
    }

    /**
     * Clean up cache
     */
    private fun cleanupCacheHighSpeed() {
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
     * 🛣️ GET HIGHWAY STATISTICS
     */
    fun getHighwayStats(): String {
        synchronized(regionCache) {
            val totalElements = regionCache.values.sumOf { it.elements.size }
            val totalMotorways = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("highway") == "motorway" }
            }
            val totalTrunks = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("highway") == "trunk" }
            }
            val totalBridges = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("bridge") == "yes" }
            }
            val totalTunnels = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("tunnel") == "yes" }
            }

            return buildString {
                appendLine("🛣️ Highway Classification Statistics:")
                appendLine("  📊 Total Roads: $totalElements")
                appendLine("  🛣️ Motorways: $totalMotorways")
                appendLine("  🛣️ Trunk Highways: $totalTrunks")
                appendLine("  🌉 Bridges: $totalBridges")
                appendLine("  🚇 Tunnels: $totalTunnels")
                appendLine("  💾 Cache: ${regionCache.size}/$CACHE_SIZE regions")
                appendLine("  🚨 Enforcement: Major highways never below 80 km/h")
                appendLine("  🎯 Classification: Motorway/Trunk/Primary/Secondary/Residential/Service")
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
                "Highway Elements" to regionCache.values.sumOf { it.highwayElements.size }.toString(),
                "Total Elements" to regionCache.values.sumOf { it.elements.size }.toString(),
                "Motorways" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.get("highway") == "motorway" }
                }.toString(),
                "Trunk Highways" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.get("highway") == "trunk" }
                }.toString(),
                "Highway Classification" to "ENABLED",
                "80+ km/h Enforcement" to "ENABLED"
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
                "🧹 Highway classification cache cleared"
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