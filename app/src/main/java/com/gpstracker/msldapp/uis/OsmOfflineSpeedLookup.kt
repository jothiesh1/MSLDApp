package com.gpstracker.msldapp.uis

import android.content.Context

class OsmOfflineSpeedLookup(private val context: Context) {

    // Add the JSON lookup
    private val jsonLookup = OsmJsonSpeedLookup(context)

    data class SpeedLimitInfo(
        val speedLimit: Int?,
        val unit: String = "km/h",
        val roadName: String?,
        val roadType: String?,
        val source: String = "OSM"
    )

    fun lookupSpeedLimit(lat: Double, lon: Double): SpeedLimitInfo? {
        LogCollector.addLog("🔍 Looking up speed limit at $lat, $lon")

        // Only try OSM JSON data - no defaults
        jsonLookup.findSpeedLimit(lat, lon)?.let { jsonResult ->
            LogCollector.addLog("✅ Found from OSM JSON: ${jsonResult.speedLimit}km/h")
            return SpeedLimitInfo(
                speedLimit = jsonResult.speedLimit,
                roadName = jsonResult.roadName,
                roadType = jsonResult.roadType,
                source = "OSM JSON (${(jsonResult.confidence * 100).toInt()}% confidence)"
            )
        }

        // Try existing map-based lookup (if available)
        val mapResult = lookupFromExistingMap(lat, lon)
        if (mapResult != null) {
            LogCollector.addLog("✅ Found from OSM map data: ${mapResult.speedLimit}km/h")
            return mapResult
        }

        // No OSM data found - return null
        LogCollector.addLog("🚫 No speed limit data in OSM for this area")
        return null
    }

    /**
     * Your existing map lookup logic (placeholder - implement if you have map data)
     */
    private fun lookupFromExistingMap(lat: Double, lon: Double): SpeedLimitInfo? {
        // TODO: Implement your existing OSM map-based speed lookup here
        // This should only return OSM data, not defaults
        return null
    }

    /**
     * Get information about available OSM data sources
     */
    fun getDataInfo(): String {
        val jsonStats = jsonLookup.getDataStats()
        val isJsonLoaded = jsonLookup.isDataLoaded()
        val speedRoadCount = jsonLookup.getSpeedLimitRoadCount()

        return buildString {
            if (isJsonLoaded) {
                appendLine("✅ OSM JSON Data: LOADED")
                appendLine(jsonStats ?: "📊 OSM data available")
                appendLine("🎯 Roads with speed limits: $speedRoadCount")

                // Show sample roads
                val samples = jsonLookup.getSampleRoads(3)
                if (samples.isNotEmpty()) {
                    appendLine("📝 Sample OSM roads:")
                    samples.forEach { sample ->
                        appendLine("  • $sample")
                    }
                }
            } else {
                appendLine("❌ OSM JSON Data: NOT LOADED")
                appendLine("📁 File should be at: assets/osmdroid/bengaluru_speed_limits.json")
            }

            appendLine()
            appendLine("🔄 Lookup Priority:")
            appendLine("  1. OSM JSON data")
            appendLine("  2. OSM map data")
            appendLine("  3. No data = No speed limit for area")

            appendLine()
            appendLine("🎯 Search area: OSM Bengaluru data")
            appendLine("📏 Search radius: 100 meters")
            appendLine("🚫 No defaults - pure OSM data only")
        }
    }

    /**
     * Test the lookup system with known coordinates
     */
    fun testLookupSystem(): String {
        val testPoints = listOf(
            Pair(12.9082, 77.6245) to "Hosur Road",
            Pair(12.8456, 77.6612) to "Electronic City",
            Pair(12.9698, 77.7499) to "Outer Ring Road"
        )

        return buildString {
            appendLine("🧪 Testing OSM lookup system:")
            testPoints.forEach { (coords, location) ->
                val result = lookupSpeedLimit(coords.first, coords.second)
                appendLine("📍 $location:")
                if (result?.speedLimit != null) {
                    appendLine("  Speed: ${result.speedLimit} km/h")
                    appendLine("  Source: ${result.source}")
                } else {
                    appendLine("  🚫 No speed limit for this area")
                }
                appendLine()
            }
        }
    }
}