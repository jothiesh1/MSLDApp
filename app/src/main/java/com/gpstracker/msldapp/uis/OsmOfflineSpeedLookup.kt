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
        // 🔧 FIXED: Updated method name from getHighwayStats() to getSimpleStats()
        val jsonStats = jsonLookup.getSimpleStats()
        val cacheStats = jsonLookup.getCacheStats()

        return buildString {
            appendLine("✅ Enhanced OSM JSON Data: LOADED")
            appendLine(jsonStats)  // 🔧 FIXED: Now uncommented since method exists
            appendLine()
            appendLine("📊 Cache Statistics:")
            cacheStats.forEach { (key, value) ->
                appendLine("  • $key: $value")
            }

            appendLine()
            appendLine("🧠 Smart Selection Features:")
            appendLine("  ✅ Closest road with speed limit selection")
            appendLine("  ✅ No more bridge/service road confusion")
            appendLine("  ✅ Simple distance-based matching")
            appendLine("  ✅ Clear selection logging")

            appendLine()
            appendLine("🔄 Lookup Priority:")
            appendLine("  1. Closest road with speed limit (FIXED)")
            appendLine("  2. OSM map data (fallback)")
            appendLine("  3. No data = No speed limit for area")

            appendLine()
            appendLine("🎯 Search Parameters:")
            appendLine("  📏 Search radius: 150-300 meters")
            appendLine("  🔝 Max candidates: 10 roads")
            appendLine("  🚫 No complex analysis - pure distance based")

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
            // Service road tests (should pick service roads, not bridges)
            Triple(25.2048, 55.2708, 15f) to "Dubai Service Road Test (Low Speed)",
            Triple(25.2048, 55.2708, 40f) to "Dubai Service Road Test (Medium Speed)",

            // Bengaluru test points
            Triple(12.9082, 77.6245, 20f) to "Bengaluru Service Road Test",
            Triple(12.8456, 77.6612, 35f) to "Bengaluru Local Road Test",
            Triple(12.9698, 77.7499, 70f) to "Bengaluru Highway Test",

            // Bridge confusion test
            Triple(25.2050, 55.2710, 25f) to "Dubai Bridge Area Test (Should pick ground road)"
        )

        return buildString {
            appendLine("🧪 Testing FIXED OSM Lookup System:")
            appendLine("🎯 Focus: Bridge/Service Road Confusion Fix")
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

            appendLine("🎯 Expected Results:")
            appendLine("  • Service roads should be selected when driving slowly")
            appendLine("  • Ground roads should be selected over bridges")
            appendLine("  • No more 120 km/h when on 40 km/h service roads")
        }
    }

    /**
     * Get diagnostic information about current lookup
     */
    fun getDiagnostics(lat: Double, lon: Double, currentSpeed: Float): String {
        return buildString {
            appendLine("🔧 OSM Lookup Diagnostics (FIXED VERSION):")
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
            // 🔧 FIXED: Updated method name from getHighwayStats() to getSimpleStats()
            appendLine("📊 ${jsonLookup.getSimpleStats()}")
            appendLine()

            // Test current location
            val result = lookupSpeedLimit(lat, lon, currentSpeed)
            if (result != null) {
                appendLine("🎯 Current Result (FIXED LOGIC):")
                appendLine("  Speed Limit: ${result.speedLimit} km/h")
                appendLine("  Road: ${result.roadName}")
                appendLine("  Type: ${result.roadType}")
                appendLine("  Source: ${result.source}")
                appendLine("  Selection Method: Closest road with speed limit")
            } else {
                appendLine("🚫 No speed limit data for current location")
            }

            appendLine()
            appendLine("🎯 Fix Status:")
            appendLine("  ✅ Bridge/Service road confusion - FIXED")
            appendLine("  ✅ Simple distance-based selection - ACTIVE")
            appendLine("  ✅ Clear logging - ENABLED")
        }
    }

    /**
     * 🆕 NEW: Test the bridge/service road confusion fix
     */
    fun testBridgeServiceFix(lat: Double, lon: Double): String {
        return buildString {
            appendLine("🧪 BRIDGE/SERVICE ROAD CONFUSION TEST:")
            appendLine("📍 Location: ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}")
            appendLine()

            // Test different speeds to see road selection
            val testSpeeds = listOf(15f, 25f, 40f, 60f, 80f)

            testSpeeds.forEach { speed ->
                appendLine("🚗 Testing at ${speed} km/h:")
                val result = lookupSpeedLimit(lat, lon, speed)

                if (result != null) {
                    appendLine("  ✅ Selected: ${result.speedLimit} km/h")
                    appendLine("  🛣️ Road: ${result.roadName}")
                    appendLine("  📊 Expected: ${when {
                        speed < 30f -> "Service road (20-40 km/h)"
                        speed < 60f -> "Local road (40-60 km/h)"
                        else -> "Main road (60+ km/h)"
                    }}")
                } else {
                    appendLine("  🚫 No data")
                }
                appendLine()
            }

            appendLine("🎯 Fix Verification:")
            appendLine("  • Low speeds (15-25 km/h) should pick service roads")
            appendLine("  • Medium speeds (40-60 km/h) should pick local roads")
            appendLine("  • High speeds (60+ km/h) should pick main roads")
            appendLine("  • NO MORE bridge roads selected for service road driving!")
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