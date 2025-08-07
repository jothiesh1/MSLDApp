// ENHANCED OSM DATA WITH COMPLETE HIGHWAY CLASSIFICATION
// File: app/src/main/java/com/gpstracker/msldapp/uis/OsmJsonData.kt

package com.gpstracker.msldapp.uis

import com.google.gson.annotations.SerializedName

/**
 * 🛣️ ENHANCED OSM Data classes with complete highway classification
 * Features: Highway classification, ground level detection, altitude support, 80+ km/h enforcement
 */

data class OverpassResponse(
    val version: Float? = null,
    val generator: String? = null,
    val elements: List<OverpassElement> = emptyList()
)

data class OverpassElement(
    val type: String? = null,
    val id: Long,
    val tags: Map<String, String>? = null,
    val geometry: List<OverpassNode>? = null
) {
    // 🛣️ COMPLETE HIGHWAY CLASSIFICATION
    val isHighway: Boolean by lazy {
        tags?.get("highway") in listOf("motorway", "trunk")
    }

    val isMajorHighway: Boolean by lazy {
        tags?.get("highway") in listOf("motorway", "trunk")
    }

    val isMotorway: Boolean by lazy {
        tags?.get("highway") == "motorway"
    }

    val isTrunk: Boolean by lazy {
        tags?.get("highway") == "trunk"
    }

    val isPrimary: Boolean by lazy {
        tags?.get("highway") == "primary"
    }

    val isSecondary: Boolean by lazy {
        tags?.get("highway") == "secondary"
    }

    val isMainRoad: Boolean by lazy {
        tags?.get("highway") in listOf("motorway", "trunk", "primary", "secondary")
    }

    val hasSpeedLimit: Boolean by lazy {
        tags?.containsKey("maxspeed") == true
    }

    // 🛣️ HIGHWAY PRIORITY WITH CLASSIFICATION
    val highwayPriority: Int by lazy {
        when (tags?.get("highway")) {
            "motorway" -> 100
            "trunk" -> 90
            "primary" -> 80
            "secondary" -> 70
            "tertiary" -> 60
            "residential" -> 50
            "service" -> 40
            else -> 20
        }
    }

    // 🛣️ HIGHWAY TYPE CLASSIFICATION
    val highwayType: String by lazy {
        tags?.get("highway") ?: "unknown"
    }

    val highwayClassification: HighwayClassification by lazy {
        getHighwayClassification()
    }

    // 🌉 GROUND LEVEL DETECTION
    val layer: Int by lazy {
        tags?.get("layer")?.toIntOrNull() ?: 0
    }

    val isBridge: Boolean by lazy {
        tags?.get("bridge") == "yes"
    }

    val isTunnel: Boolean by lazy {
        tags?.get("tunnel") == "yes"
    }

    val groundLevel: GroundLevel by lazy {
        getGroundLevel()
    }

    // 🚨 HIGHWAY ENFORCEMENT (Manager requirement)
    val minimumSpeedLimit: Int by lazy {
        when {
            isMotorway -> 100  // Motorway never below 100
            isTrunk -> 80      // Trunk never below 80
            isMajorHighway -> 80  // Major highway never below 80
            isPrimary -> 50
            isSecondary -> 40
            tags?.get("highway") == "tertiary" -> 30
            tags?.get("highway") == "residential" -> 20
            tags?.get("highway") == "service" -> 20
            else -> 30
        }
    }

    val speedLimitValue: Int? by lazy {
        getSpeedLimitInt()
    }

    // 🚨 ENFORCED SPEED LIMIT (Manager requirement)
    val enforcedSpeedLimit: Int by lazy {
        val originalLimit = speedLimitValue
        when {
            originalLimit == null -> minimumSpeedLimit
            isMajorHighway -> maxOf(originalLimit, 80)  // Manager: Never below 80 for major highways
            else -> maxOf(originalLimit, minimumSpeedLimit)
        }
    }

    val roadName: String? by lazy {
        tags?.get("name")
    }

    val speedVsRoadRelation: String by lazy {
        getSpeedVsRoadRelation()
    }
}

data class OverpassNode(
    val lat: Double,
    val lon: Double,
    val altitude: Double = 0.0  // 🏔️ ALTITUDE SUPPORT
) {
    fun fastDistanceTo(lat: Double, lon: Double): Double {
        val latDiff = lat - this.lat
        val lonDiff = lon - this.lon
        val degToM = 111000.0
        return kotlin.math.sqrt((latDiff * latDiff + lonDiff * lonDiff)) * degToM
    }

    fun preciseDistanceTo(lat: Double, lon: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat - this.lat)
        val dLon = Math.toRadians(lon - this.lon)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(this.lat)) * kotlin.math.cos(Math.toRadians(lat)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    // 🏔️ ALTITUDE-AWARE DISTANCE CALCULATION
    fun distanceWithAltitude(lat: Double, lon: Double, altitude: Double): Double {
        val horizontalDistance = preciseDistanceTo(lat, lon)
        val altitudeDiff = kotlin.math.abs(this.altitude - altitude)
        return kotlin.math.sqrt(horizontalDistance * horizontalDistance + altitudeDiff * altitudeDiff)
    }
}

/**
 * 🛣️ HIGHWAY CLASSIFICATION ENUM
 */
enum class HighwayClassification(
    val type: String,
    val displayName: String,
    val icon: String,
    val isHighway: Boolean,
    val priority: Int,
    val minSpeedLimit: Int,
    val description: String
) {
    MOTORWAY("motorway", "Motorway", "🛣️", true, 100, 100, "Major Highway"),
    TRUNK("trunk", "Trunk Highway", "🛣️", true, 90, 80, "Trunk Highway"),
    PRIMARY("primary", "Primary Road", "🛤️", false, 80, 50, "Primary Road"),
    SECONDARY("secondary", "Secondary Road", "🛤️", false, 70, 40, "Secondary Road"),
    TERTIARY("tertiary", "Local Road", "🛤️", false, 60, 30, "Local Road"),
    RESIDENTIAL("residential", "Residential", "🏘️", false, 50, 20, "Residential"),
    SERVICE("service", "Service Road", "🅿️", false, 40, 20, "Service Road"),
    UNKNOWN("unknown", "Unknown Road", "❓", false, 20, 30, "Unknown Road");

    companion object {
        fun fromHighwayTag(highway: String?): HighwayClassification {
            return values().find { it.type == highway } ?: UNKNOWN
        }
    }
}

/**
 * 🌉 GROUND LEVEL ENUM
 */
enum class GroundLevel(
    val type: String,
    val displayName: String,
    val icon: String,
    val description: String
) {
    GROUND("ground", "Ground Level", "🛣️", "Ground Level"),
    BRIDGE("bridge", "Bridge", "🌉", "Bridge"),
    TUNNEL("tunnel", "Tunnel", "🚇", "Tunnel"),
    ELEVATED("elevated", "Elevated", "🏗️", "Elevated"),
    UNDERGROUND("underground", "Underground", "🕳️", "Underground"),
    FLYOVER("flyover", "Flyover", "🛤️", "Flyover");

    companion object {
        fun fromTags(layer: Int, isBridge: Boolean, isTunnel: Boolean): GroundLevel {
            return when {
                isBridge && layer > 0 -> FLYOVER
                isTunnel && layer < 0 -> TUNNEL
                layer > 0 -> ELEVATED
                layer < 0 -> UNDERGROUND
                isBridge -> BRIDGE
                else -> GROUND
            }
        }
    }
}

/**
 * 🛣️ ENHANCED SPEED LIMIT RESULT with highway classification
 */
data class SpeedLimitResult(
    val speedLimit: Int?,
    val unit: String = "km/h",
    val roadName: String?,
    val roadType: String?,
    val confidence: Float,
    val source: String,
    val distance: Double = 0.0,
    val isHighway: Boolean = false,
    val processingTimeMs: Long = 0L,
    // 🛣️ HIGHWAY CLASSIFICATION FIELDS
    val highwayClassification: HighwayClassification? = null,
    val groundLevel: GroundLevel? = null,
    val altitude: Double = 0.0,  // 🏔️ ALTITUDE
    val enforcedSpeedLimit: Int? = null,  // 🚨 ENFORCED SPEED
    val speedVsRoadRelation: String? = null,  // 📊 SPEED VS ROAD RELATIONSHIP
    val highwayEnforcement: String? = null  // 🚨 ENFORCEMENT STATUS
) {
    fun isHighwaySpeed(): Boolean = (speedLimit ?: 0) > 80

    fun getSpeedCategory(): String = when {
        (speedLimit ?: 0) >= 100 -> "HIGHWAY"
        (speedLimit ?: 0) >= 80 -> "FAST_ROAD"
        (speedLimit ?: 0) >= 60 -> "ARTERIAL"
        (speedLimit ?: 0) >= 40 -> "CITY"
        else -> "RESIDENTIAL"
    }

    fun isValidForHighSpeed(): Boolean = speedLimit != null && speedLimit!! > 0

    fun getDisplayText(): String = "${speedLimit ?: "No limit"} km/h"

    fun getConfidenceLevel(): String = when {
        confidence >= 0.9f -> "Very High"
        confidence >= 0.7f -> "High"
        confidence >= 0.5f -> "Medium"
        confidence >= 0.3f -> "Low"
        else -> "Very Low"
    }

    // 🛣️ HIGHWAY CLASSIFICATION DISPLAY
    fun getHighwayDisplayText(): String {
        return if (highwayClassification != null && groundLevel != null) {
            "${highwayClassification.icon} ${highwayClassification.displayName} (${groundLevel.displayName})"
        } else {
            roadName ?: "Unknown Road"
        }
    }

    // 🚨 ENFORCEMENT DISPLAY
    fun getEnforcementDisplayText(): String {
        return highwayEnforcement ?: "No enforcement"
    }

    // 📊 SPEED VS ROAD RELATIONSHIP DISPLAY
    fun getSpeedRelationText(): String {
        return speedVsRoadRelation ?: "Unknown relationship"
    }
}

/**
 * 🏔️ ENHANCED LOCATION DATA with altitude support
 */
data class EnhancedLocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,  // 🏔️ ALTITUDE REQUIRED
    val accuracy: Float,
    val speedKmh: Float,
    val timestamp: Long,
    // 🛣️ HIGHWAY CONTEXT
    val currentHighwayType: String? = null,
    val currentGroundLevel: String? = null
)

/**
 * 🛣️ EXTENSION FUNCTIONS for enhanced highway operations
 */
fun LocationData.isHighSpeed(): Boolean = speedKmh > 80f
fun LocationData.isCitySpeed(): Boolean = speedKmh < 50f
fun LocationData.isHighwaySpeed(): Boolean = speedKmh > 100f
fun LocationData.isStationary(): Boolean = speedKmh < 5f

fun LocationData.getSpeedCategory(): String = when {
    speedKmh > 100f -> "HIGHWAY"
    speedKmh > 80f -> "FAST"
    speedKmh > 50f -> "MODERATE"
    speedKmh > 20f -> "SLOW"
    else -> "STATIONARY"
}

fun LocationData.getAccuracyLevel(): String = when {
    accuracy <= 5f -> "Excellent"
    accuracy <= 10f -> "Very Good"
    accuracy <= 15f -> "Good"
    accuracy <= 25f -> "Fair"
    accuracy <= 50f -> "Poor"
    else -> "Very Poor"
}

fun LocationData.getUpdateFrequency(): String = when {
    speedKmh > 80f -> "500ms (Highway)"
    speedKmh > 50f -> "1000ms (Fast)"
    speedKmh > 20f -> "2000ms (City)"
    else -> "5000ms (Slow)"
}

// 🏔️ ALTITUDE EXTENSIONS
fun LocationData.getAltitudeCategory(): String = when {
    altitude > 1000.0 -> "High Altitude"
    altitude > 500.0 -> "Elevated"
    altitude > 100.0 -> "Moderate Elevation"
    altitude > 0.0 -> "Low Elevation"
    else -> "Below Sea Level"
}

fun LocationData.distanceTo(other: LocationData): Double {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(
        latitude, longitude,
        other.latitude, other.longitude,
        results
    )
    return results[0].toDouble()
}

// 🏔️ ALTITUDE-AWARE DISTANCE
fun LocationData.distanceWithAltitude(other: LocationData): Double {
    val horizontalDistance = distanceTo(other)
    val altitudeDiff = kotlin.math.abs(this.altitude - other.altitude)
    return kotlin.math.sqrt(horizontalDistance * horizontalDistance + altitudeDiff * altitudeDiff)
}

fun LocationData.getAgeMillis(): Long = System.currentTimeMillis() - timestamp
fun LocationData.getAgeSeconds(): Long = getAgeMillis() / 1000

fun LocationData.isRecent(): Boolean = getAgeMillis() < 5000
fun LocationData.isStale(): Boolean = getAgeMillis() > 30000

/**
 * 🛣️ ENHANCED OVERPASS ELEMENT EXTENSIONS
 */
fun OverpassElement.getHighwayClassification(): HighwayClassification {
    return HighwayClassification.fromHighwayTag(tags?.get("highway"))
}

fun OverpassElement.getGroundLevel(): GroundLevel {
    return GroundLevel.fromTags(layer, isBridge, isTunnel)
}

fun OverpassElement.getSpeedLimitInt(): Int? {
    val speedString = tags?.get("maxspeed") ?: return null

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

// 📊 SPEED VS ROAD TYPE RELATIONSHIP
fun OverpassElement.getSpeedVsRoadRelation(): String {
    val classification = highwayClassification
    val speedLimit = enforcedSpeedLimit
    return "${classification.icon} ${classification.type.uppercase()} → ${speedLimit}km/h (Min: ${classification.minSpeedLimit}km/h)"
}

// 🚨 HIGHWAY ENFORCEMENT STATUS
fun OverpassElement.getEnforcementStatus(): String {
    val originalLimit = speedLimitValue
    val enforcedLimit = enforcedSpeedLimit

    return when {
        isMajorHighway && enforcedLimit >= 80 -> "✅ Highway rule OK (${enforcedLimit}≥80)"
        isMajorHighway && originalLimit != null && originalLimit < 80 -> "🚨 Highway enforced (${originalLimit}→${enforcedLimit})"
        isMajorHighway && originalLimit == null -> "🚨 Highway default enforced (→${enforcedLimit})"
        else -> "ℹ️ Regular road (no enforcement)"
    }
}

fun OverpassElement.isRoadType(vararg types: String): Boolean {
    val highway = tags?.get("highway") ?: return false
    return highway in types
}

fun OverpassElement.getBridge(): String? = tags?.get("bridge")
fun OverpassElement.getTunnel(): String? = tags?.get("tunnel")
fun OverpassElement.getLayer(): String? = tags?.get("layer")

/**
 * 🛣️ ENHANCED UTILITY FUNCTIONS for highway classification
 */
object EnhancedOsmDataUtils {

    fun filterHighwayElements(elements: List<OverpassElement>): List<OverpassElement> {
        return elements.filter { it.isHighway }
    }

    fun filterMajorHighways(elements: List<OverpassElement>): List<OverpassElement> {
        return elements.filter { it.isMajorHighway }
    }

    fun filterByGroundLevel(elements: List<OverpassElement>, groundLevel: GroundLevel): List<OverpassElement> {
        return elements.filter { it.groundLevel == groundLevel }
    }

    fun filterElementsWithSpeedLimits(elements: List<OverpassElement>): List<OverpassElement> {
        return elements.filter { it.hasSpeedLimit }
    }

    fun sortByPriority(elements: List<OverpassElement>): List<OverpassElement> {
        return elements.sortedByDescending { it.highwayPriority }
    }

    fun sortByHighwayClassification(elements: List<OverpassElement>): List<OverpassElement> {
        return elements.sortedByDescending { it.highwayClassification.priority }
    }

    fun findNearestElement(
        elements: List<OverpassElement>,
        lat: Double,
        lon: Double,
        maxDistance: Double = 300.0
    ): OverpassElement? {
        return elements
            .mapNotNull { element ->
                val minDistance = element.geometry?.minOfOrNull { node ->
                    node.fastDistanceTo(lat, lon)
                }
                if (minDistance != null && minDistance <= maxDistance) {
                    element to minDistance
                } else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    // 🛣️ HIGHWAY CLASSIFICATION STATISTICS
    fun getHighwayStatistics(elements: List<OverpassElement>): Map<String, Any> {
        val withSpeedLimits = elements.filter { it.hasSpeedLimit }
        val speedLimits = withSpeedLimits.mapNotNull { it.speedLimitValue }
        val enforcedLimits = elements.map { it.enforcedSpeedLimit }

        return mapOf(
            "total_elements" to elements.size,
            "with_speed_limits" to withSpeedLimits.size,
            "coverage_percentage" to if (elements.isNotEmpty()) {
                (withSpeedLimits.size * 100.0 / elements.size)
            } else 0.0,
            "average_speed_limit" to if (speedLimits.isNotEmpty()) {
                speedLimits.average()
            } else 0.0,
            "average_enforced_limit" to enforcedLimits.average(),
            "min_speed_limit" to (speedLimits.minOrNull() ?: 0),
            "max_speed_limit" to (speedLimits.maxOrNull() ?: 0),
            // 🛣️ HIGHWAY CLASSIFICATION BREAKDOWN
            "motorways" to elements.count { it.isMotorway },
            "trunk_highways" to elements.count { it.isTrunk },
            "primary_roads" to elements.count { it.isPrimary },
            "secondary_roads" to elements.count { it.isSecondary },
            "major_highways" to elements.count { it.isMajorHighway },
            // 🌉 GROUND LEVEL BREAKDOWN
            "bridges" to elements.count { it.isBridge },
            "tunnels" to elements.count { it.isTunnel },
            "ground_level" to elements.count { it.groundLevel == GroundLevel.GROUND },
            "elevated" to elements.count { it.layer > 0 },
            // 🚨 ENFORCEMENT STATISTICS
            "enforced_highways" to elements.count { it.isMajorHighway && it.enforcedSpeedLimit > (it.speedLimitValue ?: 0) },
            "enforcement_rate" to if (elements.count { it.isMajorHighway } > 0) {
                (elements.count { it.isMajorHighway && it.enforcedSpeedLimit > (it.speedLimitValue ?: 0) } * 100.0 /
                        elements.count { it.isMajorHighway })
            } else 0.0
        )
    }

    // 🛣️ HIGHWAY CLASSIFICATION ANALYSIS
    fun analyzeHighwayClassification(elements: List<OverpassElement>): String {
        val stats = getHighwayStatistics(elements)

        return buildString {
            appendLine("🛣️ Highway Classification Analysis:")
            appendLine("  📊 Total Roads: ${stats["total_elements"]}")
            appendLine("  🛣️ Motorways: ${stats["motorways"]}")
            appendLine("  🛣️ Trunk Highways: ${stats["trunk_highways"]}")
            appendLine("  🛤️ Primary Roads: ${stats["primary_roads"]}")
            appendLine("  🛤️ Secondary Roads: ${stats["secondary_roads"]}")
            appendLine("  🌉 Bridges: ${stats["bridges"]}")
            appendLine("  🚇 Tunnels: ${stats["tunnels"]}")
            appendLine("  🚨 Highway Enforcement Rate: ${String.format("%.1f", stats["enforcement_rate"])}%")
            appendLine("  📈 Speed Limit Coverage: ${String.format("%.1f", stats["coverage_percentage"])}%")
        }
    }
}