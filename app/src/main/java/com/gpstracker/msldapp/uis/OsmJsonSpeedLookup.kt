// COMPLETE OSM JSON SPEED LOOKUP - SEQUENTIAL FALLBACK + ALL ALGORITHMS
// File: OsmJsonSpeedLookup.kt

package com.gpstracker.msldapp.uis

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import java.io.InputStreamReader
import kotlin.math.*

class OsmJsonSpeedLookup(private val context: Context) {
    private val gson = Gson()
    private val regionCache = mutableMapOf<Region, CachedRegionData>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ultraAccurateMatcher = UltraAccurateRoadMatcher()
    private var lastOsmTags = mutableMapOf<String, String>()
    private var lastSelectedElement: OverpassElement? = null

    companion object {
        private const val TAG = "OsmLookup"
        private const val SEARCH_RADIUS = 4000.0
        private const val PRECISION_SEARCH_RADIUS = 2000.0
        private const val EMERGENCY_SEARCH_RADIUS = 6000.0
        private const val CACHE_SIZE = 12
        private const val CACHE_DURATION_MS = 600000L
    }

    data class LatLonPoint(val lat: Double, val lon: Double)

    data class OverpassElement(
        val id: Long,
        val tags: Map<String, String>?,
        val geometry: List<LatLonPoint>?
    )

    enum class Region(
        val fileName: String,
        val displayName: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val priority: Int
    ) {
        // UAE Regions (Priority 3)
        DUBAI("dubai.json", "Dubai", 24.7136, 25.4168, 54.8863, 55.5650, 3),
        SHARJAH("Sharjah.json", "Sharjah", 24.7859, 25.4544, 55.5131, 56.0895, 3),
        AJMAN("Ajman.json", "Ajman", 25.3626, 25.4544, 55.4338, 55.5131, 3),
        UMM_AL_QUWAIN("Umm Al Quwain.json", "Umm Al Quwain", 25.4544, 25.6658, 55.5131, 55.9348, 3),
        RAS_AL_KHAIMAH("Ras Al-Khaimah.json", "Ras Al Khaimah", 25.6139, 26.0859, 55.7481, 56.1886, 3),
        FUJAIRAH("Fujairah.json", "Fujairah", 24.7858, 25.6658, 56.0895, 56.3960, 3),
        ABU_DHABI_EAST("Abu Dhabi Eastern Region.json", "Abu Dhabi East", 22.6333, 24.7858, 53.6176, 56.0895, 3),
        ABU_DHABI_WEST("Abu Dhabi Western Region.json", "Abu Dhabi West", 22.6333, 24.5572, 51.5833, 54.8863, 3),

        // India - Route-Specific (Priority 1 - Try FIRST)
        BANGALORE_TN_BORDER("BangaloretoTamilNaduBorderKarnataka portion.json", "BLR-TN Border", 12.7, 13.2, 77.3, 78.2, 1),
        KRISHNAGIRI_VELLORE_CHENNAI("KrishnagiriVelloreChennaiTamilNaduportion.json", "Krishnagiri-Vellore-Chennai", 12.5, 13.5, 78.0, 80.2, 1),
        CHITTOOR_AREA("ChittoorAreaIfviaNH44AProute.json", "Chittoor Area NH44", 12.8, 13.5, 78.5, 79.5, 1),

        // India - Combined Routes (Priority 2)
        BANGALORE_CHENNAI_ROUTES("bangalore_chennai_routes.json", "BLR-Chennai Routes", 12.0, 13.5, 77.2, 80.3, 2),
        ENTIRE_ROUTE_CORRIDOR("EntireRouteCorridor.json", "Entire Route Corridor", 12.0, 13.5, 77.2, 80.3, 2),

        // India - City-Specific (Priority 3)
        BENGALURU("bengaluru_speed_limits.json", "Bengaluru City", 12.7158, 13.1735, 77.3672, 77.8472, 3),

        // India - State-Wide (Priority 4 - Try LAST)
        TAMIL_NADU_NH_MEDIUM("Tamil Nadu - NH + Medium Roads.json", "Tamil Nadu NH+Medium", 8.0, 13.5, 76.0, 80.5, 4),
        KARNATAKA_NH_MEDIUM("Karnataka - NH + Medium Roads.json", "Karnataka NH+Medium", 11.5, 18.5, 74.0, 78.5, 4),
        ANDHRA_PRADESH_NH_MEDIUM("Andhra Pradesh - NH + Medium Roads.json", "Andhra Pradesh NH+Medium", 12.5, 19.5, 76.5, 84.8, 4);

        fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon
    }

    data class CachedRegionData(
        val region: Region,
        val elements: List<OverpassElement>,
        val highwayElements: List<OverpassElement>,
        val loadedAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - loadedAt > CACHE_DURATION_MS
    }

    data class RoadCandidate(
        val element: OverpassElement,
        val polylineDistance: Double,
        val speedLimit: Int?,
        val highwayType: String,
        val layer: Int,
        val isBridge: Boolean,
        val isTunnel: Boolean
    )

    data class UltraAccurateMatchResult(
        val candidate: RoadCandidate,
        val confidence: Double,
        val matchReason: String,
        val distanceFromRoad: Double
    )

    init {
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.JSON,
            "OSM Lookup - Sequential Fallback System",
            mapOf(
                "Total Regions" to Region.values().size.toString(),
                "Strategy" to "Try ALL matching regions in priority order"
            )
        )
    }

    /**
     * MAIN FUNCTION - Sequential Fallback Logic
     * Tries ALL matching regions in priority order until roads are found
     */
    fun findSpeedLimit(
        lat: Double,
        lon: Double,
        currentSpeed: Float = 0f,
        carDirection: Float? = null,
        altitude: Double? = null
    ): SpeedLimitResult? {
        try {
            val matchingRegions = findAllMatchingRegions(lat, lon)

            if (matchingRegions.isEmpty()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "No regions cover this location",
                    mapOf("lat" to "%.6f".format(lat), "lon" to "%.6f".format(lon))
                )
                return null
            }

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "${matchingRegions.size} regions found - trying in priority order",
                mapOf("regions" to matchingRegions.joinToString { "${it.displayName}(P${it.priority})" })
            )

            // Try each region sequentially until we find roads
            for ((index, region) in matchingRegions.withIndex()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "Attempt ${index + 1}/${matchingRegions.size}: ${region.displayName}",
                    mapOf("priority" to "P${region.priority}")
                )

                val regionData = loadRegionData(region)
                if (regionData == null) {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.OSM,
                        "Failed to load - trying next region"
                    )
                    continue
                }

                val nearbyRoads = findNearbyRoadsWithMultiRadius(regionData, lat, lon)

                if (nearbyRoads.isEmpty()) {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.OSM,
                        "No roads found - trying next region"
                    )
                    continue
                }

                // Found roads! Use advanced matching algorithms
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "SUCCESS: Found ${nearbyRoads.size} roads",
                    mapOf(
                        "region" to region.displayName,
                        "attempt" to "${index + 1}/${matchingRegions.size}"
                    )
                )

                val bestMatch = findUltraAccurateMatch(lat, lon, currentSpeed, carDirection, nearbyRoads)

                if (bestMatch != null) {
                    return createSpeedLimitResult(bestMatch, region, carDirection, altitude, currentSpeed)
                }

                val fallbackRoad = selectFallbackRoad(nearbyRoads)
                if (fallbackRoad != null) {
                    return createFallbackResult(fallbackRoad, region, currentSpeed)
                }
            }

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "Exhausted all ${matchingRegions.size} regions - no speed limits found"
            )
            return null

        } catch (e: Exception) {
            LogCollector.logError("OSM lookup error", e)
            return null
        }
    }

    private fun findAllMatchingRegions(lat: Double, lon: Double): List<Region> {
        return Region.values()
            .filter { it.contains(lat, lon) }
            .sortedBy { it.priority }
    }

    private fun createSpeedLimitResult(
        match: UltraAccurateMatchResult,
        region: Region,
        carDirection: Float?,
        altitude: Double?,
        currentSpeed: Float
    ): SpeedLimitResult {
        val candidate = match.candidate
        val element = candidate.element
        val rawSpeedLimit = candidate.speedLimit
        val confidence = match.confidence.toFloat()
        val roadName = element.tags?.get("name") ?: "Unnamed Road"
        val roadNameKn = element.tags?.get("name:kn") ?: ""

        lastOsmTags.clear()
        element.tags?.let { tags -> lastOsmTags.putAll(tags) }
        lastSelectedElement = element

        val roadLevel = determineRoadLevel(element.tags ?: emptyMap())
        val roadType = candidate.highwayType
        val finalSpeedLimit = enforceRoadTypeAccuracy(rawSpeedLimit, roadType, roadLevel, currentSpeed)

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "Speed: ${finalSpeedLimit}km/h from ${region.displayName}",
            mapOf(
                "road" to roadName,
                "type" to roadType,
                "distance" to "${match.distanceFromRoad.toInt()}m",
                "confidence" to "${(confidence * 100).toInt()}%"
            )
        )

        return SpeedLimitResult(
            speedLimit = finalSpeedLimit,
            roadName = if (roadNameKn.isNotBlank()) "$roadName ($roadNameKn)" else roadName,
            roadType = roadType,
            confidence = confidence,
            source = "osm_${region.name.lowercase()}",
            distance = match.distanceFromRoad
        )
    }

    private fun createFallbackResult(road: RoadCandidate, region: Region, currentSpeed: Float): SpeedLimitResult {
        val rawSpeedLimit = road.speedLimit
        val roadName = road.element.tags?.get("name") ?: "Unnamed Road"
        val roadType = road.highwayType
        val roadLevel = determineRoadLevel(road.element.tags ?: emptyMap())
        val finalSpeedLimit = enforceRoadTypeAccuracy(rawSpeedLimit, roadType, roadLevel, currentSpeed)

        lastOsmTags.clear()
        road.element.tags?.let { tags -> lastOsmTags.putAll(tags) }
        lastSelectedElement = road.element

        return SpeedLimitResult(
            speedLimit = finalSpeedLimit,
            roadName = "$roadName (Fallback)",
            roadType = roadType,
            confidence = 0.7f,
            source = "fallback_${region.name.lowercase()}",
            distance = road.polylineDistance
        )
    }

    private fun findNearbyRoadsWithMultiRadius(
        regionData: CachedRegionData,
        lat: Double,
        lon: Double
    ): List<RoadCandidate> {
        val searchRadii = listOf(PRECISION_SEARCH_RADIUS, SEARCH_RADIUS, EMERGENCY_SEARCH_RADIUS)

        for (radius in searchRadii) {
            val roads = findRoadsWithinRadius(regionData, lat, lon, radius)
            if (roads.isNotEmpty()) return roads
        }
        return emptyList()
    }

    private fun findRoadsWithinRadius(
        regionData: CachedRegionData,
        lat: Double,
        lon: Double,
        radius: Double
    ): List<RoadCandidate> {
        return regionData.elements.mapNotNull { element ->
            val polylineDistance = calculatePolylineDistance(lat, lon, element)

            if (polylineDistance < radius) {
                val tags = element.tags ?: emptyMap()
                RoadCandidate(
                    element = element,
                    polylineDistance = polylineDistance,
                    speedLimit = parseSpeedLimit(tags["maxspeed"]),
                    highwayType = tags["highway"] ?: "unknown",
                    layer = tags["layer"]?.toIntOrNull() ?: 0,
                    isBridge = tags["bridge"] == "yes",
                    isTunnel = tags["tunnel"] == "yes"
                )
            } else null
        }.sortedBy { it.polylineDistance }.take(20)
    }

    private fun findKNearestRoads(roads: List<RoadCandidate>, k: Int = 5) = roads.sortedBy { it.polylineDistance }.take(k)

    private fun calculateWeightedScore(candidate: RoadCandidate, factors: Map<String, Double>): Double {
        var score = 0.0
        score += (1.0 - candidate.polylineDistance / EMERGENCY_SEARCH_RADIUS) * (factors["distance"] ?: 0.4)
        if (candidate.speedLimit != null) score += (factors["speed_available"] ?: 0.3)

        val typeScore = when (candidate.highwayType) {
            "motorway" -> 1.0; "trunk" -> 0.9; "primary" -> 0.8; "secondary" -> 0.7
            "tertiary" -> 0.6; "residential" -> 0.5; else -> 0.3
        }
        score += typeScore * (factors["road_type"] ?: 0.2)

        if (candidate.element.tags?.get("name")?.isNotBlank() == true) score += (factors["has_name"] ?: 0.1)
        return score.coerceIn(0.0, 1.0)
    }

    private fun clusterNearbyRoads(roads: List<RoadCandidate>, clusterRadius: Double = 500.0): List<List<RoadCandidate>> {
        val clusters = mutableListOf<MutableList<RoadCandidate>>()
        val processed = mutableSetOf<RoadCandidate>()

        for (road in roads) {
            if (road in processed) continue
            val cluster = mutableListOf(road)
            processed.add(road)

            for (otherRoad in roads) {
                if (otherRoad in processed) continue
                if (abs(road.polylineDistance - otherRoad.polylineDistance) <= clusterRadius) {
                    cluster.add(otherRoad)
                    processed.add(otherRoad)
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun calculateDirectionalMatch(userBearing: Float, roadBearing: Float, tolerance: Float = 45f): Double {
        val bearingDiff = abs(normalizeAngle(userBearing - roadBearing))
        return if (bearingDiff <= tolerance) 1.0 - (bearingDiff / tolerance) else 0.0
    }

    private fun findUltraAccurateMatch(
        lat: Double, lon: Double, currentSpeed: Float, carDirection: Float?, nearbyRoads: List<RoadCandidate>
    ): UltraAccurateMatchResult? {
        if (nearbyRoads.isEmpty()) return null

        ultraAccurateMatcher.updateGPSHistory(lat, lon, currentSpeed, carDirection ?: 0f, 10f)

        val topCandidates = findKNearestRoads(nearbyRoads, k = 5)
        val clusters = clusterNearbyRoads(topCandidates)
        val bestCluster = clusters.maxByOrNull { cluster ->
            cluster.sumOf { if (it.speedLimit != null) 1.0 else 0.0 }
        } ?: topCandidates

        val scoredRoads = bestCluster.mapNotNull { candidate ->
            val weights = mapOf("distance" to 0.4, "speed_available" to 0.3, "road_type" to 0.2, "has_name" to 0.1)
            var baseScore = calculateWeightedScore(candidate, weights)

            carDirection?.let { direction ->
                val roadBearing = calculateRoadBearing(candidate.element)
                val directionalScore = calculateDirectionalMatch(direction, roadBearing)
                baseScore = (baseScore * 0.8) + (directionalScore * 0.2)
            }

            val vehicleScore = calculateVehicleSpecificScore(candidate, currentSpeed)
            val finalScore = (baseScore * 0.7) + (vehicleScore * 0.3)

            if (finalScore > 0.5) {
                UltraAccurateMatchResult(
                    candidate, finalScore,
                    if (finalScore > 0.7) "GOOD_MATCH" else "ACCEPTABLE_MATCH",
                    candidate.polylineDistance
                )
            } else null
        }

        return scoredRoads.maxByOrNull { it.confidence }
    }

    private fun calculateVehicleSpecificScore(candidate: RoadCandidate, currentSpeed: Float): Double {
        var score = 0.0
        if (candidate.speedLimit != null) {
            score += 0.4
            val speedDiff = abs(currentSpeed - candidate.speedLimit!!)
            score += when {
                speedDiff <= 10 -> 0.3; speedDiff <= 20 -> 0.2; speedDiff <= 30 -> 0.1; else -> 0.0
            }
        }
        score += when (candidate.highwayType) {
            "motorway", "trunk" -> 0.2; "primary", "secondary" -> 0.15; "tertiary" -> 0.1; else -> 0.05
        }
        if (candidate.element.tags?.get("name")?.isNotBlank() == true) score += 0.1
        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateRoadBearing(element: OverpassElement): Float {
        val geometry = element.geometry ?: return 0f
        if (geometry.size < 2) return 0f

        val segments = mutableListOf<Float>()
        var totalLength = 0.0

        for (i in 0 until geometry.size - 1) {
            val segmentLength = calculateDistance(geometry[i].lat, geometry[i].lon, geometry[i + 1].lat, geometry[i + 1].lon)
            if (segmentLength > 5.0) {
                segments.add(calculateSegmentBearing(geometry[i].lat, geometry[i].lon, geometry[i + 1].lat, geometry[i + 1].lon) * segmentLength.toFloat())
                totalLength += segmentLength
            }
        }
        return if (segments.isNotEmpty() && totalLength > 0) segments.sum() / totalLength.toFloat() else 0f
    }

    private fun calculateSegmentBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        if (normalized > 180f) normalized -= 360f
        return abs(normalized)
    }

    private fun selectFallbackRoad(roads: List<RoadCandidate>): RoadCandidate? {
        roads.filter { it.speedLimit != null }.minByOrNull { it.polylineDistance }?.let { return it }
        roads.filter { it.highwayType in listOf("motorway", "trunk", "primary") }.minByOrNull { it.polylineDistance }?.let { return it }
        return roads.minByOrNull { it.polylineDistance }
    }

    private fun enforceRoadTypeAccuracy(speedLimit: Int?, roadType: String, roadLevel: String, currentSpeed: Float): Int {
        if (speedLimit != null && isValidSpeedForRoadType(speedLimit, roadType, roadLevel)) return speedLimit

        return when {
            roadLevel.contains("BRIDGE") || roadLevel.contains("FLYOVER") || roadLevel.contains("ELEVATED") -> when (roadType) {
                "motorway" -> 120; "trunk" -> 100; "primary" -> 80; "secondary" -> 60; else -> 60
            }
            roadType == "service" -> when {
                currentSpeed > 40f -> 50; currentSpeed > 20f -> 40; else -> 30
            }
            else -> when (roadType) {
                "motorway" -> 100; "trunk" -> 80; "primary" -> 60; "secondary" -> 50
                "tertiary" -> 40; "residential" -> 30; "service" -> 40; else -> 50
            }
        }
    }

    private fun isValidSpeedForRoadType(speedLimit: Int, roadType: String, roadLevel: String): Boolean {
        val expectedRange = when {
            roadType == "service" -> 20..60
            roadLevel.contains("BRIDGE") || roadLevel.contains("ELEVATED") -> when (roadType) {
                "motorway" -> 80..140; "trunk" -> 60..120; "primary" -> 40..100; else -> 30..80
            }
            else -> when (roadType) {
                "motorway" -> 60..120; "trunk" -> 50..100; "primary" -> 30..80; "secondary" -> 20..70
                "tertiary" -> 15..60; "residential" -> 10..40; else -> 20..80
            }
        }
        return speedLimit in expectedRange
    }

    private fun determineRoadLevel(tags: Map<String, String>): String {
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val bridge = tags["bridge"] == "yes"
        val tunnel = tags["tunnel"] == "yes"
        val name = extractBestRoadName(tags).lowercase()

        return when {
            tunnel -> "TUNNEL"; bridge && layer > 1 -> "BRIDGE_L${layer}"; bridge -> "BRIDGE"
            layer > 0 -> "ELEVATED_L${layer}"; name.contains("flyover") -> "FLYOVER"
            name.contains("overpass") -> "OVERPASS"; tags["highway"] == "motorway" -> "MOTORWAY"
            else -> "GROUND"
        }
    }

    private fun extractBestRoadName(tags: Map<String, String>): String {
        listOf("name:en", "name", "name:ar", "name:hi", "name:kn", "name:ta", "ref").forEach { key ->
            tags[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "Unnamed Road"
    }

    private fun calculatePolylineDistance(lat: Double, lon: Double, element: OverpassElement): Double {
        val geometry = element.geometry ?: return Double.MAX_VALUE
        var minDistance = Double.MAX_VALUE

        for (i in 0 until geometry.size - 1) {
            minDistance = minOf(minDistance, distanceToLineSegment(
                lat, lon, geometry[i].lat, geometry[i].lon, geometry[i + 1].lat, geometry[i + 1].lon
            ))
        }
        return minDistance
    }

    private fun distanceToLineSegment(
        pointLat: Double, pointLon: Double, lineLat1: Double, lineLon1: Double, lineLat2: Double, lineLon2: Double
    ): Double {
        val A = pointLat - lineLat1; val B = pointLon - lineLon1
        val C = lineLat2 - lineLat1; val D = lineLon2 - lineLon1
        val dot = A * C + B * D; val lenSq = C * C + D * D

        if (lenSq == 0.0) return calculateDistance(pointLat, pointLon, lineLat1, lineLon1)

        val param = (dot / lenSq).coerceIn(0.0, 1.0)
        val closestLat = lineLat1 + param * C
        val closestLon = lineLon1 + param * D

        return calculateDistance(pointLat, pointLon, closestLat, closestLon)
    }

    private fun loadRegionData(region: Region): CachedRegionData? {
        synchronized(regionCache) {
            regionCache[region]?.takeIf { !it.isExpired() }?.let { return it }

            try {
                val elements = loadJsonFileOptimized(region.fileName)
                val highwayElements = elements.filter {
                    it.tags?.get("highway") in listOf("motorway", "trunk", "primary", "secondary")
                }
                val cachedData = CachedRegionData(region, elements, highwayElements)
                regionCache[region] = cachedData
                cleanupCache()

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.JSON,
                    "${region.displayName} loaded: ${elements.size} roads",
                    mapOf("with_speed" to elements.count { it.tags?.containsKey("maxspeed") == true }.toString())
                )
                return cachedData
            } catch (e: Exception) {
                LogCollector.logError("Failed to load ${region.displayName}", e)
                return null
            }
        }
    }

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
                                val highway = element.tags?.get("highway")
                                if (element.tags?.containsKey("maxspeed") == true ||
                                    highway in listOf("motorway", "trunk", "primary", "secondary", "tertiary", "residential", "service")) {
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

    private fun cleanupCache() {
        if (regionCache.size > CACHE_SIZE) {
            regionCache.entries.sortedBy { it.value.loadedAt }.take(regionCache.size - CACHE_SIZE).forEach {
                regionCache.remove(it.key)
            }
        }
    }

    private fun parseSpeedLimit(speedString: String?): Int? {
        if (speedString.isNullOrBlank()) return null
        return when {
            speedString in listOf("none", "unlimited") -> null
            speedString.contains("mph", ignoreCase = true) ->
                speedString.filter { it.isDigit() }.toIntOrNull()?.let { (it * 1.60934).toInt() }
            else -> speedString.filter { it.isDigit() }.toIntOrNull()
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2) * sin(dLat/2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2) * sin(dLon/2)
        return R * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    fun getLastOsmTags() = lastOsmTags.toMap()
    fun getLastSelectedElement() = lastSelectedElement
    fun isDataAvailable(lat: Double, lon: Double) = findAllMatchingRegions(lat, lon).isNotEmpty()

    fun clearCache() {
        synchronized(regionCache) {
            regionCache.clear()
            lastOsmTags.clear()
            lastSelectedElement = null
            ultraAccurateMatcher.reset()
        }
    }

    fun cleanup() {
        scope.cancel()
        clearCache()
    }

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
                appendLine("OSM Lookup - Sequential Fallback:")
                appendLine("  Total Regions: ${Region.values().size}")
                appendLine("  Cached: ${regionCache.size}/$CACHE_SIZE")
                appendLine("  Total Roads: $totalElements")
                appendLine("  With Speed Limits: $totalWithSpeed")
                appendLine("  Bridges: $totalBridges")
                appendLine("  Strategy: Try all matching regions in priority order")
            }
        }
    }

    fun getFlyoverStats(): String {
        synchronized(regionCache) {
            val totalElements = regionCache.values.sumOf { it.elements.size }
            val totalWithSpeed = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.containsKey("maxspeed") == true }
            }
            val totalBridges = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("bridge") == "yes" }
            }
            val totalMotorways = regionCache.values.sumOf { cached ->
                cached.elements.count { it.tags?.get("highway") == "motorway" }
            }

            return buildString {
                appendLine("OSM Lookup - Enhanced Statistics:")
                appendLine("  Total Regions: ${Region.values().size}")
                appendLine("  India Regions: 11")
                appendLine("  UAE Regions: 8")
                appendLine("  Cached: ${regionCache.size}/$CACHE_SIZE")
                appendLine("  Total Roads: $totalElements")
                appendLine("  With Speed Limits: $totalWithSpeed")
                appendLine("  Bridges/Flyovers: $totalBridges")
                appendLine("  Motorways: $totalMotorways")
                appendLine("  Algorithms: KNN, Weighted Scoring, Clustering, Directional")
                appendLine("  Fallback: Sequential region retry until roads found")
            }
        }
    }

    fun getCacheStats(): Map<String, String> {
        synchronized(regionCache) {
            return mapOf(
                "Total Regions" to Region.values().size.toString(),
                "Cached Regions" to regionCache.size.toString(),
                "Total Elements" to regionCache.values.sumOf { it.elements.size }.toString(),
                "With Speed Limits" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.containsKey("maxspeed") == true }
                }.toString(),
                "Bridges" to regionCache.values.sumOf { cached ->
                    cached.elements.count { it.tags?.get("bridge") == "yes" }
                }.toString(),
                "Selection Method" to "SEQUENTIAL_FALLBACK_PRIORITY",
                "Search Radius" to "${SEARCH_RADIUS.toInt()}m"
            )
        }
    }
}