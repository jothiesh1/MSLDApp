package com.gpstracker.msldapp.uis

import android.content.Context

class OsmOfflineSpeedLookup(private val context: Context) {

    // Enhanced JSON lookup with smart road selection
    private val jsonLookup = OsmJsonSpeedLookup(context)

    data class SpeedLimitInfo(
        val speedLimit: Int?,
        val unit: String = "km/h",
        val roadName: String?,
        val roadType: String?,
        val source: String = "OSM"
    )

    /**
     * ENHANCED: Lookup speed limit with current speed for smart road selection
     * @param lat Latitude
     * @param lon Longitude
     * @param currentSpeed Current speed in km/h (optional, for smart selection)
     */
    fun lookupSpeedLimit(lat: Double, lon: Double, currentSpeed: Float = 0f): SpeedLimitInfo? {
        LogCollector.addLog("🔍 Smart lookup at $lat, $lon (speed: ${currentSpeed}km/h)")

        // Use enhanced JSON lookup with current speed for smart selection
        jsonLookup.findSpeedLimit(lat, lon, currentSpeed)?.let { jsonResult ->
            LogCollector.addLog("✅ Smart selection: ${jsonResult.speedLimit}km/h (${jsonResult.roadType})")
            return SpeedLimitInfo(
                speedLimit = jsonResult.speedLimit,
                roadName = jsonResult.roadName,
                roadType = jsonResult.roadType,
                source = "Smart OSM (${(jsonResult.confidence * 100).toInt()}% confidence)"
            )
        }

        // Try existing map-based lookup (if available)
        val mapResult = lookupFromExistingMap(lat, lon, currentSpeed)
        if (mapResult != null) {
            LogCollector.addLog("✅ Found from OSM map data: ${mapResult.speedLimit}km/h")
            return mapResult
        }

        // No OSM data found - return null
        LogCollector.addLog("🚫 No speed limit data in OSM for this area")
        return null
    }

    /**
     * Your existing map lookup logic (enhanced with speed parameter)
     */
    private fun lookupFromExistingMap(lat: Double, lon: Double, currentSpeed: Float): SpeedLimitInfo? {
        // TODO: Implement your existing OSM map-based speed lookup here
        // This should also use currentSpeed for smart selection if possible
        return null
    }

    /**
     * Get information about available OSM data sources
     */
    fun getDataInfo(): String {
        val jsonStats = jsonLookup.getHighwayStats()
        val cacheStats = jsonLookup.getCacheStats()

        return buildString {
            appendLine("✅ Enhanced OSM JSON Data: LOADED")
          //  appendLine(jsonStats)
            appendLine()
            appendLine("📊 Cache Statistics:")
            cacheStats.forEach { (key, value) ->
                appendLine("  • $key: $value")
            }

            appendLine()
            appendLine("🧠 Smart Selection Features:")
            appendLine("  ✅ Service road detection (speed < 25 km/h)")
            appendLine("  ✅ Bridge filtering (speed < 40 km/h)")
            appendLine("  ✅ Speed-based road type matching")
            appendLine("  ✅ Multi-road candidate analysis")

            appendLine()
            appendLine("🔄 Lookup Priority:")
            appendLine("  1. Smart OSM JSON selection")
            appendLine("  2. OSM map data")
            appendLine("  3. No data = No speed limit for area")

            appendLine()
            appendLine("🎯 Search Parameters:")
            appendLine("  📏 Search radius: 100 meters")
            appendLine("  🔝 Max candidates: 5 roads")
            appendLine("  🚫 No defaults - pure OSM data only")

            appendLine()
            appendLine("🌍 Available Regions:")
            appendLine("  • Dubai, Sharjah, Ajman")
            appendLine("  • Umm Al Quwain, Ras Al Khaimah, Fujairah")
            appendLine("  • Abu Dhabi East, Abu Dhabi West")
            appendLine("  • Bengaluru")
        }
    }

    /**
     * Enhanced test with smart selection scenarios
     */
    fun testLookupSystem(): String {
        val testPoints = listOf(
            // Bengaluru test points
            Triple(12.9082, 77.6245, 20f) to "Bengaluru Service Road Test",
            Triple(12.8456, 77.6612, 35f) to "Bengaluru Local Road Test",
            Triple(12.9698, 77.7499, 70f) to "Bengaluru Highway Test",
            // Dubai test point
            Triple(25.2048, 55.2708, 50f) to "Dubai Test"
        )

        return buildString {
            appendLine("🧪 Testing Smart OSM Lookup System:")
            appendLine()

            testPoints.forEach { (coords, scenario) ->
                val (lat, lon, speed) = coords
                appendLine("📍 $scenario:")
                appendLine("  Location: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}")
                appendLine("  Speed: ${speed}km/h")

                val result = lookupSpeedLimit(lat, lon, speed)
                if (result?.speedLimit != null) {
                    appendLine("  ✅ Result: ${result.speedLimit} km/h")
                    appendLine("  🛣️ Road: ${result.roadName}")
                    appendLine("  🏷️ Type: ${result.roadType}")
                    appendLine("  📊 Source: ${result.source}")
                } else {
                    appendLine("  🚫 No speed limit for this area")
                }
                appendLine()
            }
        }
    }

    /**
     * Get diagnostic information about current lookup
     */
    fun getDiagnostics(lat: Double, lon: Double, currentSpeed: Float): String {
        return buildString {
            appendLine("🔧 OSM Lookup Diagnostics:")
            appendLine("📍 Location: ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}")
            appendLine("🚗 Current Speed: ${currentSpeed} km/h")
            appendLine()

            // Check if location is in any supported region
            val isSupported = jsonLookup.isDataAvailable(lat, lon)
            if (isSupported) {
                appendLine("✅ Location is in supported region")
            } else {
                appendLine("❌ Location is outside supported regions")
            }

            appendLine()
            appendLine("📊 ${jsonLookup.getHighwayStats()}")
            appendLine()

            // Test current location
            val result = lookupSpeedLimit(lat, lon, currentSpeed)
            if (result != null) {
                appendLine("🎯 Current Result:")
                appendLine("  Speed Limit: ${result.speedLimit} km/h")
                appendLine("  Road: ${result.roadName}")
                appendLine("  Type: ${result.roadType}")
                appendLine("  Source: ${result.source}")
            } else {
                appendLine("🚫 No speed limit data for current location")
            }
        }
    }

    /**
     * Clear cache
     */
    fun clearCache() {
        jsonLookup.clearCache()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        jsonLookup.cleanup()
    }
}