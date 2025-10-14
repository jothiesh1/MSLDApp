// File: SpeedLimitCoordinator.kt
// Updated for Continuous TTL Support

package com.gpstracker.msldapp.uis

import android.content.Context
import android.util.Log

class SpeedLimitCoordinator(private val context: Context) {

    private val stableManager = StableSpeedLimitManager()

    private val osmLookup by lazy {
        try {
            OsmJsonSpeedLookup(context)
        } catch (e: Exception) {
            LogCollector.logError("Failed to initialize OSM lookup", e)
            null
        }
    }

    /**
     * Main method - Get accurate speed limit with continuous TTL support
     */
    suspend fun getAbsolutelyAccurateSpeedLimit(
        lat: Double,
        lon: Double,
        altitude: Double,
        currentSpeed: Float,
        carDirection: Float
    ): CoordinatorResult {

        try {
            // Step 1: Get OSM data
            val osmResult = osmLookup?.findSpeedLimit(
                lat, lon, currentSpeed, carDirection, altitude
            )

            val osmSpeedLimit = osmResult?.speedLimit
            val osmTags = osmLookup?.getLastOsmTags() ?: emptyMap()

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "Coordinator processing location",
                mapOf(
                    "lat" to "%.6f".format(lat),
                    "lon" to "%.6f".format(lon),
                    "speed" to "${currentSpeed.toInt()}km/h",
                    "direction" to "${carDirection.toInt()}°",
                    "osm_speed" to (osmSpeedLimit?.toString() ?: "null"),
                    "osm_tags" to osmTags.size.toString()
                )
            )

            // Step 2: Process through stable manager
            val stableResult = stableManager.getStableSpeedLimit(
                rawLat = lat,
                rawLon = lon,
                rawAltitude = altitude,
                currentSpeed = currentSpeed,
                rawSpeedLimit = osmSpeedLimit,
                osmTags = osmTags,
                bearing = carDirection
            )

            // Step 3: Convert to coordinator result with TTL support
            return convertToCoordinatorResult(stableResult)

        } catch (e: Exception) {
            LogCollector.logError("Coordinator error", e)

            // Even on error, try to use last known for continuous TTL
            val lastKnown = stableManager.getLastKnownSpeedLimitForTtl()
            if (lastKnown != null) {
                val shouldSend = stableManager.shouldSendTtlNow(lastKnown)
                return CoordinatorResult(
                    speedLimit = lastKnown,
                    roadType = "Error: Using Last Known",
                    enforcementReason = "error_recovery",
                    sendToTtl = shouldSend,
                    ttlReason = "error_last_known"
                )
            }

            return CoordinatorResult(
                speedLimit = null,
                roadType = "Error: ${e.message}",
                enforcementReason = "error",
                sendToTtl = false,
                ttlReason = "error"
            )
        }
    }

    /**
     * Convert StableSpeedResult to CoordinatorResult with TTL support
     */
    private fun convertToCoordinatorResult(
        stableResult: StableSpeedResult
    ): CoordinatorResult {

        return when (stableResult) {
            is StableSpeedResult.Confirmed -> {
                val roadInfo = buildRoadInfo(stableResult.highwayInfo, "Stable")
                val timeInfo = " (${stableResult.timeSinceConfirmed/1000}s)"

                CoordinatorResult(
                    speedLimit = stableResult.speedLimit,
                    roadType = "$roadInfo$timeInfo",
                    enforcementReason = stableResult.enforcementReason,
                    sendToTtl = stableResult.sendToTtl,
                    ttlReason = stableResult.ttlReason
                )
            }

            is StableSpeedResult.NewConfirmed -> {
                val roadInfo = buildRoadInfo(stableResult.highwayInfo, "New")
                val voteInfo = " (${stableResult.votesUsed} votes)"

                CoordinatorResult(
                    speedLimit = stableResult.speedLimit,
                    roadType = "$roadInfo$voteInfo",
                    enforcementReason = stableResult.enforcementReason,
                    sendToTtl = stableResult.sendToTtl,
                    ttlReason = stableResult.ttlReason
                )
            }

            is StableSpeedResult.Voting -> {
                val roadInfo = buildRoadInfo(stableResult.highwayInfo, "Voting")
                val progressInfo = " (${stableResult.progress})"

                CoordinatorResult(
                    speedLimit = stableResult.leadingCandidate,
                    roadType = "$roadInfo$progressInfo",
                    enforcementReason = stableResult.enforcementReason,
                    sendToTtl = stableResult.sendToTtl,
                    ttlReason = stableResult.ttlReason
                )
            }

            is StableSpeedResult.UsingLastKnown -> {
                val roadInfo = buildRoadInfo(stableResult.highwayInfo, "Last Known")
                val timeInfo = " (${stableResult.timeSinceLastKnown/1000}s ago)"

                CoordinatorResult(
                    speedLimit = stableResult.speedLimit,
                    roadType = "$roadInfo$timeInfo",
                    enforcementReason = stableResult.enforcementReason,
                    sendToTtl = stableResult.sendToTtl,
                    ttlReason = stableResult.ttlReason
                )
            }

            StableSpeedResult.NoData -> {
                // FIXED: Even with no OSM data, try to use last known for continuous TTL
                val lastKnown = stableManager.getLastKnownSpeedLimitForTtl()
                if (lastKnown != null) {
                    val shouldSend = stableManager.shouldSendTtlNow(lastKnown)
                    CoordinatorResult(
                        speedLimit = lastKnown,
                        roadType = "No GPS Data (Using Last Known)",
                        enforcementReason = "last_known",
                        sendToTtl = shouldSend,
                        ttlReason = "no_data_continuous"
                    )
                } else {
                    CoordinatorResult(
                        speedLimit = null,
                        roadType = "No GPS Data Available",
                        enforcementReason = "no_data",
                        sendToTtl = false,
                        ttlReason = "no_data"
                    )
                }
            }
        }
    }

    /**
     * Build readable road information string
     */
    private fun buildRoadInfo(highwayInfo: StableSpeedLimitManager.HighwayInfo?, status: String): String {
        return if (highwayInfo != null) {
            val roadName = if (highwayInfo.roadName != "Unknown" && highwayInfo.roadName.isNotBlank()) {
                highwayInfo.roadName
            } else {
                highwayInfo.description
            }

            val lockInfo = if (stableManager.isRoadLocked()) {
                val lockMode = stableManager.getCurrentRoadLock()?.mode?.name?.replace("_LOCKED", "") ?: ""
                " [${lockMode}]"
            } else ""

            "$roadName$lockInfo - $status"
        } else {
            "Road - $status"
        }
    }

    /**
     * Record TTL sent - IMPORTANT: Call this after sending to TTL
     */
    fun recordTtlSent(speedLimit: Int) {
        stableManager.recordTtlSent(speedLimit)

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.BACKEND,
            "TTL speed sent: ${speedLimit}km/h",
            mapOf("timestamp" to System.currentTimeMillis().toString())
        )
    }

    // ===== NEW FUNCTIONS FOR CONTINUOUS TTL =====

    /**
     * Get last known speed limit for continuous TTL sending
     */
    fun getLastKnownSpeedLimit(): Int? {
        return stableManager.getLastKnownSpeedLimitForTtl()
    }

    /**
     * Check if TTL should send now (respects 20s interval)
     */
    fun shouldSendTtlNow(speed: Int): Boolean {
        return stableManager.shouldSendTtlNow(speed)
    }

    /**
     * Get TTL status for monitoring
     */
    fun getTtlStatus(): Map<String, Any> {
        return stableManager.getTtlStatus()
    }

    // ===== EXISTING FUNCTIONS =====

    /**
     * Get current system status
     */
    fun getCurrentStatus(): Map<String, String> {
        return stableManager.getCurrentStatus()
    }

    /**
     * Get road lock status
     */
    fun getRoadLockStatus(): Map<String, String> {
        return stableManager.getRoadLockStatus()
    }

    /**
     * Get transition rules
     */
    fun getTransitionRules(): Map<String, String> {
        return stableManager.getTransitionRules()
    }

    /**
     * Get persistence configuration
     */
    fun getPersistenceConfig(): Map<String, String> {
        return stableManager.getPersistenceConfig()
    }

    /**
     * Get verification status
     */
    fun getVerificationStatus(): VerificationStatus {
        return stableManager.getVerificationStatus()
    }

    /**
     * Clear all data and reset system
     */
    fun clearAll() {
        stableManager.clearAll()
        osmLookup?.clearCache()

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.BACKEND,
            "Coordinator system reset - All data cleared"
        )
    }

    /**
     * Check if we have stable speed limit
     */
    fun hasStableSpeedLimit(): Boolean {
        return stableManager.hasStableSpeedLimit()
    }

    /**
     * Get confirmed speed limit if available
     */
    fun getConfirmedSpeedLimit(): Int? {
        return stableManager.getConfirmedSpeedLimit()
    }

    /**
     * Get confirmed highway info if available
     */
    fun getConfirmedHighwayInfo(): StableSpeedLimitManager.HighwayInfo? {
        return stableManager.getConfirmedHighwayInfo()
    }

    /**
     * Set last known speed limit for fallback
     */
    fun setLastKnownSpeedLimit(speedLimit: Int) {
        stableManager.setLastKnownSpeedLimit(speedLimit)
    }

    /**
     * Check if OSM data is available for location
     */
    fun isOsmDataAvailable(lat: Double, lon: Double): Boolean {
        return osmLookup?.isDataAvailable(lat, lon) ?: false
    }

    /**
     * Get comprehensive system statistics
     */
    fun getSystemStats(): Map<String, String> {
        val stableStats = stableManager.getCurrentStatus()
        val osmStats = osmLookup?.getCacheStats() ?: mapOf("OSM" to "Not Available")

        return stableStats + osmStats + mapOf(
            "Coordinator" to "Active",
            "Integration" to "StableManager + OSM + RoadLock + Continuous TTL",
            "TTL Status" to "CONTINUOUS",
            "Features" to "All Features Active"
        )
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        osmLookup?.cleanup()

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.BACKEND,
            "SpeedLimitCoordinator cleanup completed"
        )
    }
}

/**
 * Result from coordinator for UI consumption
 */
data class CoordinatorResult(
    val speedLimit: Int?,
    val roadType: String,
    val enforcementReason: String,
    val sendToTtl: Boolean,
    val ttlReason: String = ""
)