// File: app/src/main/java/com/gpstracker/msldapp/uis/OsmJsonData.kt

package com.gpstracker.msldapp.uis

import com.google.gson.annotations.SerializedName

/**
 * Data classes for parsing OSM Overpass API JSON response
 * Used for loading speed limit data from bengaluru_speed_limits.json
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
)

data class OverpassNode(
    val lat: Double,
    val lon: Double
)

/**
 * Result class for speed limit lookups
 */
data class SpeedLimitResult(
    val speedLimit: Int?,
    val unit: String = "km/h",
    val roadName: String?,
    val roadType: String?,
    val confidence: Float, // 0.0 to 1.0
    val source: String,
    val distance: Double = 0.0 // Distance to road in meters
)