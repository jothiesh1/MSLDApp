// COMPLETE FIXED: All Complex Systems Working with IMPROVED Bridge Detection
// File: app/src/main/java/com/gpstracker/msldapp/uis/StableSpeedLimitManager.kt

package com.gpstracker.msldapp.uis

import java.lang.Math.toRadians
import kotlin.math.*
import java.util.ArrayDeque
import kotlinx.coroutines.*

/**
 * FIXED COMPLETE SYSTEM: All complex features working with improved bridge detection
 * 1. Highway classification (motorway, trunk, primary, etc.)
 * 2. IMPROVED Ground level detection (bridge, tunnel, ground) - FIXES flyover detection
 * 3. SPEED JUMP DETECTION (prevents rapid changes between ANY speeds)
 * 4. Highway stickiness logic (prevents rapid transitions)
 * 5. Smart verification timing (different speeds for different transitions)
 * 6. SIMPLIFIED enforcement (OSM direct or defaults)
 * 7. Altitude collection and display
 * 8. Continuous TTL for all states
 * 9. All systems working together consistently
 */
class StableSpeedLimitManager {

    // Current stable state with highway info
    private var confirmedSpeedLimit: Int? = null
    private var confirmedRoadCenter: Pair<Double, Double>? = null
    private var confirmedAltitude: Double? = null
    private var confirmedHighwayInfo: HighwayInfo? = null
    private var confirmedEnforcementReason: String? = null
    private var roadConfirmedAt = 0L
    private var consecutiveSameReadings = 0

    // Voting system
    private val recentReadings = ArrayDeque<Int>()
    private val votingStartTime = mutableMapOf<Int, Long>()

    // GPS smoothing with altitude
    private val gpsHistory = ArrayDeque<Triple<Double, Double, Double>>()

    // Last known persistence with highway info
    private var lastKnownSpeedLimit: Int? = null
    private var lastKnownHighwayInfo: HighwayInfo? = null
    private var lastKnownEnforcementReason: String? = null
    private var lastKnownLimitTime = 0L
    private var noDataCount = 0

    // Continuous TTL system
    private var lastTtlSendTime = 0L
    private var lastSentSpeedLimit: Int? = null
    private val TTL_SEND_INTERVAL = 20000L // 20 seconds

    // Smart verification system
    private var pendingSpeedLimit: Int? = null
    private var pendingEnforcementReason: String? = null
    private var verificationCount = 0
    private var lastVerificationTime = 0L
    private var isVerifying = false
    private var requiredVerifications = 3
    private var verificationInterval = 1000L

    // FIXED CONSTANTS: Now work with any speed changes
    private companion object {
        private const val LARGE_SPEED_JUMP_THRESHOLD = 60
        private const val MEDIUM_SPEED_JUMP_THRESHOLD = 40
        private const val HIGH_SPEED_THRESHOLD = 80
        private const val HIGH_SPEED_STICK_TIME = 15000L
    }

    // Highway classification system (unchanged)
    data class HighwayInfo(
        val type: String,
        val isHighway: Boolean,
        val priority: Int,
        val description: String,
        val icon: String,
        val layer: Int,
        val levelType: String,
        val minSpeedLimit: Int,
        val maxSpeedLimit: Int,
        val defaultSpeed: Int
    )

    /**
     * FIXED: Universal road level detection for UAE and India OSM data
     * This fixes the bridge detection issue where flyovers were showing as GROUND
     */
    private fun detectRoadLevel(tags: Map<String, String>): String {
        val highway = tags["highway"] ?: "unknown"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val bridge = tags["bridge"] == "yes"
        val tunnel = tags["tunnel"] == "yes"
        val name = tags["name"]?.lowercase() ?: ""
        val embankment = tags["embankment"] == "yes"

        return when {
            // Underground structures
            tunnel -> "TUNNEL"
            tunnel && layer < 0 -> "TUNNEL_L${Math.abs(layer)}"
            layer < 0 -> "UNDERGROUND_L${Math.abs(layer)}"

            // Elevated structures (FIXED: More comprehensive detection)
            bridge && layer > 1 -> "BRIDGE_L${layer}"     // Multi-level bridges
            bridge -> "BRIDGE"                             // ANY bridge=yes = elevated
            layer > 0 -> "ELEVATED_L${layer}"             // Positive layer without bridge tag
            embankment -> "EMBANKMENT"                     // Raised earthwork

            // Name-based detection (common in India/UAE)
            name.contains("flyover") -> "FLYOVER"
            name.contains("overpass") -> "OVERPASS"
            name.contains("bridge") -> "BRIDGE"
            name.contains("elevated") -> "ELEVATED"
            name.contains("expressway") -> "EXPRESSWAY"

            // Highway-specific defaults (UAE style)
            highway == "motorway" -> "EXPRESSWAY"          // Typically elevated in cities
            highway == "trunk" && layer >= 0 -> "HIGHWAY"  // Major highways often elevated

            // Ground level (default)
            else -> "GROUND"
        }
    }

    /**
     * FIXED HIGHWAY ANALYSIS: Now uses improved bridge detection
     */
    private fun analyzeHighway(tags: Map<String, String>): HighwayInfo {
        val highway = tags["highway"] ?: "unknown"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val bridge = tags["bridge"] == "yes"
        val tunnel = tags["tunnel"] == "yes"

        // USE NEW IMPROVED DETECTION METHOD
        val levelType = detectRoadLevel(tags)

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "IMPROVED BRIDGE DETECTION: ${highway} → ${levelType}",
            mapOf(
                "bridge" to if (bridge) "YES" else "NO",
                "layer" to layer.toString(),
                "tunnel" to if (tunnel) "YES" else "NO",
                "name" to (tags["name"] ?: "N/A"),
                "detected_level" to levelType
            )
        )

        return when (highway) {
            "motorway" -> HighwayInfo(
                type = "MOTORWAY", isHighway = true, priority = 90,
                description = "MOTORWAY ($levelType)", icon = "🛣️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 80
            )
            "trunk" -> HighwayInfo(
                type = "TRUNK", isHighway = true, priority = 85,
                description = "TRUNK HIGHWAY ($levelType)", icon = "🛣️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 80
            )
            "primary" -> HighwayInfo(
                type = "PRIMARY", isHighway = false, priority = 70,
                description = "PRIMARY ROAD ($levelType)", icon = "🛤️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 60
            )
            "secondary" -> HighwayInfo(
                type = "SECONDARY", isHighway = false, priority = 60,
                description = "SECONDARY ROAD ($levelType)", icon = "🛤️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 40
            )
            "tertiary" -> HighwayInfo(
                type = "TERTIARY", isHighway = false, priority = 50,
                description = "LOCAL ROAD ($levelType)", icon = "🛤️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 40
            )
            "residential" -> HighwayInfo(
                type = "RESIDENTIAL", isHighway = false, priority = 40,
                description = "RESIDENTIAL ($levelType)", icon = "🏘️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 30
            )
            "service" -> HighwayInfo(
                type = "SERVICE", isHighway = false, priority = 30,
                description = "SERVICE ROAD ($levelType)", icon = "🅿️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 40
            )
            else -> HighwayInfo(
                type = "UNKNOWN", isHighway = false, priority = 20,
                description = "UNKNOWN ROAD ($levelType)", icon = "❓",
                layer = layer, levelType = levelType,
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 50
            )
        }
    }

    /**
     * FIXED SPEED JUMP DETECTION: Now works with any speed changes
     */
    private fun isLargeSpeedJump(currentSpeed: Int?, newSpeed: Int): Boolean {
        if (currentSpeed == null) return false
        val speedDiff = abs(newSpeed - currentSpeed)

        return when {
            speedDiff >= LARGE_SPEED_JUMP_THRESHOLD -> true  // Any 60+ km/h jump
            speedDiff >= MEDIUM_SPEED_JUMP_THRESHOLD && currentSpeed > HIGH_SPEED_THRESHOLD -> true  // 40+ jump from high speed
            speedDiff >= MEDIUM_SPEED_JUMP_THRESHOLD && newSpeed < currentSpeed -> true  // 40+ decreases
            else -> false
        }
    }

    /**
     * FIXED HIGHWAY STICKINESS: Now works with simplified enforcement
     */
    private fun shouldStickToHighwaySpeed(
        currentSpeedLimit: Int?,
        newSpeedLimit: Int,
        drivingSpeed: Float,
        timeSinceConfirmed: Long
    ): Boolean {
        if (currentSpeedLimit == null) return false

        return when {
            // Stick to high speeds when driving fast
            drivingSpeed > HIGH_SPEED_THRESHOLD &&
                    currentSpeedLimit >= 100 &&
                    newSpeedLimit <= 60 &&
                    timeSinceConfirmed < HIGH_SPEED_STICK_TIME -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔒 HIGHWAY STICKINESS: Staying on ${currentSpeedLimit}km/h (driving ${drivingSpeed.toInt()}km/h)"
                )
                true
            }
            // Stick to any high speeds temporarily to prevent rapid changes
            currentSpeedLimit >= 80 &&
                    newSpeedLimit <= 50 &&
                    timeSinceConfirmed < 8000L -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "⏱️ TEMPORARY STICK: ${currentSpeedLimit}km/h→${newSpeedLimit}km/h (${timeSinceConfirmed/1000}s)"
                )
                true
            }
            else -> false
        }
    }

    /**
     * FIXED ENFORCEMENT: Simplified and consistent
     */
    private fun enforceHighwaySpeed(speedLimit: Int?, highwayInfo: HighwayInfo): Pair<Int, String> {
        return if (speedLimit == null) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "No OSM Speed Data: ${highwayInfo.description} → Default ${highwayInfo.defaultSpeed}km/h"
            )
            Pair(highwayInfo.defaultSpeed, "default_speed")
        } else {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "OSM Speed Available: ${highwayInfo.description} → ${speedLimit}km/h (direct from OSM)"
            )
            Pair(speedLimit, "osm_speed")
        }
    }

    /**
     * FIXED SMART VERIFICATION: Now works with new reason codes
     */
    private fun getSmartVerificationSettings(
        currentSpeed: Int,
        newSpeed: Int,
        currentReason: String,
        newReason: String,
        highwayInfo: HighwayInfo
    ): Pair<Int, Long> {

        val speedDiff = abs(newSpeed - currentSpeed)
        val transitionType = when {
            // Both from OSM
            currentReason == "osm_speed" && newReason == "osm_speed" -> {
                if (newSpeed > currentSpeed) "osm_increase" else "osm_decrease"
            }
            // From default to OSM (found actual speed!)
            currentReason == "default_speed" && newReason == "osm_speed" -> "default_to_osm"
            // From OSM to default (lost speed data)
            currentReason == "osm_speed" && newReason == "default_speed" -> "osm_to_default"
            // Between defaults
            else -> "default_change"
        }

        return when {
            // Large speed jumps need more verification regardless of source
            speedDiff >= LARGE_SPEED_JUMP_THRESHOLD -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🚨 LARGE JUMP: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → CAREFUL (5 checks)"
                )
                Pair(5, 1500L)
            }
            // Found OSM data - fast verification
            transitionType == "default_to_osm" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🎯 DEFAULT→OSM: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → FAST (2 checks)"
                )
                Pair(2, 1000L)
            }
            // Lost OSM data - immediate
            transitionType == "osm_to_default" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "⚠️ OSM→DEFAULT: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → IMMEDIATE (1 check)"
                )
                Pair(1, 500L)
            }
            // OSM speed increases - fast
            transitionType == "osm_increase" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "📈 OSM INCREASE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → FAST (2 checks)"
                )
                Pair(2, 1000L)
            }
            // OSM speed decreases - slower
            transitionType == "osm_decrease" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "📉 OSM DECREASE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → SLOW (4 checks)"
                )
                Pair(4, 1000L)
            }
            // Default cases
            else -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔄 NORMAL CHANGE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → NORMAL (3 checks)"
                )
                Pair(3, 1000L)
            }
        }
    }

    /**
     * FIXED SPEED JUMP VERIFICATION: Works with new enforcement
     */
    private fun handleSpeedJumpVerification(
        currentSpeedLimit: Int,
        newSpeedLimit: Int,
        currentSpeed: Float,
        location: Triple<Double, Double, Double>,
        highwayInfo: HighwayInfo,
        enforcementReason: String
    ): StableSpeedResult {

        val speedDiff = abs(newSpeedLimit - currentSpeedLimit)
        val currentTime = System.currentTimeMillis()

        // Determine verification requirements based on jump size
        val (requiredVerifications, verificationInterval) = when {
            speedDiff >= 80 && currentSpeed > 100f -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🚨 EXTREME SPEED JUMP: ${currentSpeedLimit}→${newSpeedLimit}km/h - 6 verifications needed"
                )
                Pair(6, 2000L)
            }
            speedDiff >= LARGE_SPEED_JUMP_THRESHOLD -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "⚠️ LARGE SPEED JUMP: ${currentSpeedLimit}→${newSpeedLimit}km/h - 5 verifications needed"
                )
                Pair(5, 1500L)
            }
            speedDiff >= MEDIUM_SPEED_JUMP_THRESHOLD -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "⚠️ MEDIUM JUMP: ${currentSpeedLimit}→${newSpeedLimit}km/h - 4 verifications needed"
                )
                Pair(4, 1000L)
            }
            else -> Pair(3, 1000L)
        }

        when {
            !isVerifying || pendingSpeedLimit != newSpeedLimit -> {
                startSpeedJumpVerification(newSpeedLimit, enforcementReason, requiredVerifications, verificationInterval)
                val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                return StableSpeedResult.SpeedJumpVerification(
                    currentSpeedLimit = currentSpeedLimit,
                    newSpeedLimit = newSpeedLimit,
                    verificationCount = 1,
                    requiredVerifications = requiredVerifications,
                    jumpSize = speedDiff,
                    drivingSpeed = currentSpeed,
                    nextCheckIn = verificationInterval / 1000,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "speed_jump_verification"
                )
            }

            currentTime - lastVerificationTime >= verificationInterval -> {
                verificationCount++
                lastVerificationTime = currentTime

                if (verificationCount >= requiredVerifications) {
                    completeVerificationWithHighway(newSpeedLimit, enforcementReason, location, highwayInfo)

                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.OSM,
                        "✅ SPEED JUMP VERIFIED: ${currentSpeedLimit}→${newSpeedLimit}km/h after ${requiredVerifications} checks"
                    )

                    return StableSpeedResult.VerificationComplete(
                        speedLimit = newSpeedLimit,
                        reason = "speed_jump_verified_${enforcementReason}",
                        checksPerformed = requiredVerifications,
                        altitude = location.third,
                        highwayInfo = highwayInfo,
                        enforcementReason = enforcementReason,
                        sendToTtl = true,
                        ttlReason = "speed_jump_complete"
                    )
                } else {
                    val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                    return StableSpeedResult.SpeedJumpVerification(
                        currentSpeedLimit = currentSpeedLimit,
                        newSpeedLimit = newSpeedLimit,
                        verificationCount = verificationCount,
                        requiredVerifications = requiredVerifications,
                        jumpSize = speedDiff,
                        drivingSpeed = currentSpeed,
                        nextCheckIn = verificationInterval / 1000,
                        altitude = location.third,
                        highwayInfo = highwayInfo,
                        enforcementReason = enforcementReason,
                        sendToTtl = shouldSendTtl,
                        ttlReason = "speed_jump_verification_continuous"
                    )
                }
            }

            else -> {
                val timeRemaining = verificationInterval - (currentTime - lastVerificationTime)
                val secondsRemaining = (timeRemaining / 1000).coerceAtLeast(0)
                val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                return StableSpeedResult.SpeedJumpVerification(
                    currentSpeedLimit = currentSpeedLimit,
                    newSpeedLimit = newSpeedLimit,
                    verificationCount = verificationCount,
                    requiredVerifications = requiredVerifications,
                    jumpSize = speedDiff,
                    drivingSpeed = currentSpeed,
                    nextCheckIn = secondsRemaining,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "speed_jump_waiting"
                )
            }
        }
    }

    private fun startSpeedJumpVerification(speedLimit: Int, enforcementReason: String, reqVerifications: Int, verifyInterval: Long) {
        pendingSpeedLimit = speedLimit
        pendingEnforcementReason = enforcementReason
        verificationCount = 1
        lastVerificationTime = System.currentTimeMillis()
        isVerifying = true
        requiredVerifications = reqVerifications
        verificationInterval = verifyInterval
    }

    /**
     * CONTINUOUS TTL CHECKER (unchanged)
     */
    private fun shouldSendContinuousTtl(currentSpeed: Int): Boolean {
        val timeSinceLastSend = System.currentTimeMillis() - lastTtlSendTime
        return when {
            lastSentSpeedLimit == null -> true
            lastSentSpeedLimit != currentSpeed -> true
            timeSinceLastSend >= TTL_SEND_INTERVAL -> true
            else -> false
        }
    }

    fun recordTtlSent(speedLimit: Int) {
        lastTtlSendTime = System.currentTimeMillis()
        lastSentSpeedLimit = speedLimit
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.BACKEND,
            "📤 Continuous TTL sent: ${speedLimit}km/h (20s interval)"
        )
    }

    // Compatibility methods (unchanged)
    private fun ArrayDeque<Int>.addToEnd(item: Int) {
        this.add(item)
        while (this.size > 7) {
            this.removeFirst()
        }
    }

    private fun ArrayDeque<Triple<Double, Double, Double>>.addToEnd(item: Triple<Double, Double, Double>) {
        this.add(item)
        while (this.size > 3) {
            this.removeFirst()
        }
    }

    /**
     * MAIN FUNCTION: Fixed integration of all systems with improved bridge detection
     */
    fun getStableSpeedLimit(
        rawLat: Double,
        rawLon: Double,
        rawAltitude: Double,
        currentSpeed: Float,
        rawSpeedLimit: Int?,
        osmTags: Map<String, String> = emptyMap()
    ): StableSpeedResult {

        val highwayInfo = if (osmTags.isNotEmpty()) {
            analyzeHighway(osmTags)
        } else {
            HighwayInfo(
                type = "UNKNOWN", isHighway = false, priority = 50,
                description = "UNKNOWN ROAD (GROUND)", icon = "❓",
                layer = 0, levelType = "GROUND",
                minSpeedLimit = 0, maxSpeedLimit = 999, defaultSpeed = 50
            )
        }

        // Case 1: No OSM data
        if (rawSpeedLimit == null) {
            noDataCount++
            if (isVerifying) {
                cancelVerification("no_osm_data")
            }

            val lastKnown = lastKnownSpeedLimit
            val lastHighway = lastKnownHighwayInfo ?: highwayInfo
            val timeSinceLastKnown = System.currentTimeMillis() - lastKnownLimitTime

            return if (lastKnown != null) {
                val (enforcedLimit, enforcementReason) = enforceHighwaySpeed(null, lastHighway)
                val shouldSendTtl = shouldSendContinuousTtl(enforcedLimit)

                StableSpeedResult.UsingLastKnown(
                    speedLimit = enforcedLimit,
                    reason = "no_osm_${enforcementReason}",
                    timeSinceLastKnown = timeSinceLastKnown,
                    noDataCount = noDataCount,
                    altitude = rawAltitude,
                    highwayInfo = lastHighway,
                    enforcementReason = enforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "no_osm_continuous"
                )
            } else {
                StableSpeedResult.NoData
            }
        }

        // Case 2: OSM works
        noDataCount = 0
        val smoothLocation = smoothGPSCoordinates(rawLat, rawLon, rawAltitude)
        val (enforcedSpeedLimit, enforcementReason) = enforceHighwaySpeed(rawSpeedLimit, highwayInfo)

        // Check road stickiness
        val shouldStick = shouldStickToConfirmedRoad(smoothLocation, enforcedSpeedLimit, enforcementReason, highwayInfo)
        if (shouldStick) {
            if (isVerifying) {
                cancelVerification("road_stickiness")
            }

            val confirmedSpeed = confirmedSpeedLimit!!
            val confirmedHighway = confirmedHighwayInfo!!
            val shouldSendTtl = shouldSendContinuousTtl(confirmedSpeed)

            return StableSpeedResult.Confirmed(
                speedLimit = confirmedSpeed,
                reason = "road_stickiness_${confirmedEnforcementReason}",
                timeSinceConfirmed = System.currentTimeMillis() - roadConfirmedAt,
                altitude = smoothLocation.third,
                highwayInfo = confirmedHighway,
                enforcementReason = confirmedEnforcementReason ?: "unknown",
                sendToTtl = shouldSendTtl,
                ttlReason = "stable_continuous"
            )
        }

        val currentConfirmed = confirmedSpeedLimit
        val currentEnforcementReason = confirmedEnforcementReason

        if (currentConfirmed != null && (enforcedSpeedLimit != currentConfirmed || enforcementReason != currentEnforcementReason)) {

            // Check for speed jumps
            if (isLargeSpeedJump(currentConfirmed, enforcedSpeedLimit)) {
                return handleSpeedJumpVerification(
                    currentConfirmed, enforcedSpeedLimit, currentSpeed,
                    smoothLocation, highwayInfo, enforcementReason
                )
            }

            // Check highway stickiness for high speeds
            if (shouldStickToHighwaySpeed(currentConfirmed, enforcedSpeedLimit, currentSpeed, System.currentTimeMillis() - roadConfirmedAt)) {
                val shouldSendTtl = shouldSendContinuousTtl(currentConfirmed)

                return StableSpeedResult.Confirmed(
                    speedLimit = currentConfirmed,
                    reason = "highway_stickiness_${currentEnforcementReason}",
                    timeSinceConfirmed = System.currentTimeMillis() - roadConfirmedAt,
                    altitude = smoothLocation.third,
                    highwayInfo = confirmedHighwayInfo!!,
                    enforcementReason = currentEnforcementReason ?: "unknown",
                    sendToTtl = shouldSendTtl,
                    ttlReason = "highway_stickiness_continuous"
                )
            }

            // Normal verification
            return handleSmartHighwayVerification(
                enforcedSpeedLimit, currentConfirmed,
                enforcementReason, currentEnforcementReason ?: "unknown",
                smoothLocation, currentSpeed, highwayInfo
            )
        } else {
            if (isVerifying) {
                cancelVerification("same_speed_and_reason_detected")
            }

            val votingResult = addToVotingAndCheck(enforcedSpeedLimit, currentSpeed)
            return handleVotingResult(votingResult, smoothLocation, highwayInfo, enforcementReason)
        }
    }

    /**
     * FIXED SMART HIGHWAY VERIFICATION HANDLER
     */
    private fun handleSmartHighwayVerification(
        newSpeedLimit: Int,
        currentSpeedLimit: Int,
        newEnforcementReason: String,
        currentEnforcementReason: String,
        location: Triple<Double, Double, Double>,
        currentSpeed: Float,
        highwayInfo: HighwayInfo
    ): StableSpeedResult {
        val currentTime = System.currentTimeMillis()

        when {
            !isVerifying || pendingSpeedLimit != newSpeedLimit || pendingEnforcementReason != newEnforcementReason -> {
                val (reqVerifications, verifyInterval) = getSmartVerificationSettings(
                    currentSpeedLimit, newSpeedLimit,
                    currentEnforcementReason, newEnforcementReason,
                    highwayInfo
                )
                startSmartVerification(newSpeedLimit, newEnforcementReason, reqVerifications, verifyInterval)

                val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                return StableSpeedResult.Verifying(
                    currentSpeedLimit = currentSpeedLimit,
                    newSpeedLimit = newSpeedLimit,
                    verificationCount = 1,
                    requiredVerifications = reqVerifications,
                    nextCheckIn = verifyInterval / 1000,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = newEnforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "highway_verification_continuous"
                )
            }

            isVerifying && currentTime - lastVerificationTime >= verificationInterval -> {
                verificationCount++
                lastVerificationTime = currentTime

                if (verificationCount >= requiredVerifications) {
                    completeVerificationWithHighway(newSpeedLimit, newEnforcementReason, location, highwayInfo)

                    return StableSpeedResult.VerificationComplete(
                        speedLimit = newSpeedLimit,
                        reason = "highway_verification_complete_${newEnforcementReason}",
                        checksPerformed = requiredVerifications,
                        altitude = location.third,
                        highwayInfo = highwayInfo,
                        enforcementReason = newEnforcementReason,
                        sendToTtl = true,
                        ttlReason = "highway_verification_complete"
                    )
                } else {
                    val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                    return StableSpeedResult.Verifying(
                        currentSpeedLimit = currentSpeedLimit,
                        newSpeedLimit = newSpeedLimit,
                        verificationCount = verificationCount,
                        requiredVerifications = requiredVerifications,
                        nextCheckIn = verificationInterval / 1000,
                        altitude = location.third,
                        highwayInfo = highwayInfo,
                        enforcementReason = newEnforcementReason,
                        sendToTtl = shouldSendTtl,
                        ttlReason = "highway_verification_continuous"
                    )
                }
            }

            else -> {
                val timeRemaining = verificationInterval - (currentTime - lastVerificationTime)
                val secondsRemaining = (timeRemaining / 1000).coerceAtLeast(0)
                val shouldSendTtl = shouldSendContinuousTtl(currentSpeedLimit)

                return StableSpeedResult.Verifying(
                    currentSpeedLimit = currentSpeedLimit,
                    newSpeedLimit = newSpeedLimit,
                    verificationCount = verificationCount,
                    requiredVerifications = requiredVerifications,
                    nextCheckIn = secondsRemaining,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = newEnforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "highway_verification_waiting"
                )
            }
        }
    }

    private fun startSmartVerification(speedLimit: Int, enforcementReason: String, reqVerifications: Int, verifyInterval: Long) {
        pendingSpeedLimit = speedLimit
        pendingEnforcementReason = enforcementReason
        verificationCount = 1
        lastVerificationTime = System.currentTimeMillis()
        isVerifying = true
        requiredVerifications = reqVerifications
        verificationInterval = verifyInterval
    }

    private fun completeVerificationWithHighway(speedLimit: Int, enforcementReason: String, location: Triple<Double, Double, Double>, highwayInfo: HighwayInfo) {
        confirmedSpeedLimit = speedLimit
        confirmedRoadCenter = Pair(location.first, location.second)
        confirmedAltitude = location.third
        confirmedHighwayInfo = highwayInfo
        confirmedEnforcementReason = enforcementReason
        roadConfirmedAt = System.currentTimeMillis()
        consecutiveSameReadings = 1

        storeLastKnownSpeedWithHighway(speedLimit, highwayInfo, enforcementReason)

        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L

        recentReadings.clear()
        votingStartTime.clear()
    }

    private fun cancelVerification(reason: String) {
        if (isVerifying) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "❌ HIGHWAY VERIFICATION CANCELLED: ${pendingSpeedLimit}km/h (${pendingEnforcementReason}) (reason: $reason)"
            )
        }

        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L
    }

    private fun handleVotingResult(votingResult: VotingStatus, location: Triple<Double, Double, Double>, highwayInfo: HighwayInfo, enforcementReason: String): StableSpeedResult {
        return when (votingResult) {
            is VotingStatus.Winner -> {
                confirmNewRoadWithHighway(votingResult.speedLimit, enforcementReason, location, highwayInfo)
                storeLastKnownSpeedWithHighway(votingResult.speedLimit, highwayInfo, enforcementReason)

                StableSpeedResult.NewConfirmed(
                    speedLimit = votingResult.speedLimit,
                    reason = "voting_winner_highway_${enforcementReason}",
                    votesUsed = votingResult.totalVotes,
                    timeTaken = votingResult.timeTaken,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = true,
                    ttlReason = "voting_winner"
                )
            }

            is VotingStatus.Collecting -> {
                val shouldSendTtl = shouldSendContinuousTtl(votingResult.leadingSpeedLimit)

                StableSpeedResult.Voting(
                    progress = "${votingResult.currentVotes}/5",
                    timeElapsed = votingResult.timeElapsed,
                    leadingCandidate = votingResult.leadingSpeedLimit,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = shouldSendTtl,
                    ttlReason = "voting_continuous"
                )
            }
        }
    }

    private fun storeLastKnownSpeedWithHighway(speedLimit: Int, highwayInfo: HighwayInfo, enforcementReason: String) {
        lastKnownSpeedLimit = speedLimit
        lastKnownHighwayInfo = highwayInfo
        lastKnownEnforcementReason = enforcementReason
        lastKnownLimitTime = System.currentTimeMillis()
    }

    private fun smoothGPSCoordinates(lat: Double, lon: Double, altitude: Double): Triple<Double, Double, Double> {
        gpsHistory.addToEnd(Triple(lat, lon, altitude))
        val avgLat = gpsHistory.map { it.first }.average()
        val avgLon = gpsHistory.map { it.second }.average()
        val avgAlt = gpsHistory.map { it.third }.average()
        return Triple(avgLat, avgLon, avgAlt)
    }

    private fun shouldStickToConfirmedRoad(
        currentLocation: Triple<Double, Double, Double>,
        newSpeedLimit: Int,
        newEnforcementReason: String,
        highwayInfo: HighwayInfo
    ): Boolean {
        val confirmed = confirmedSpeedLimit ?: return false
        val confirmedHighway = confirmedHighwayInfo ?: return false
        val confirmedReason = confirmedEnforcementReason ?: return false
        val roadCenter = confirmedRoadCenter ?: return false

        val timeSinceConfirmed = System.currentTimeMillis() - roadConfirmedAt
        val distanceFromRoad = calculateDistance(
            roadCenter.first, roadCenter.second,
            currentLocation.first, currentLocation.second
        )

        val stickDistance = if (confirmedHighway.isHighway) 200.0 else 100.0
        val stickTime = if (confirmedHighway.isHighway) 60000L else 30000L

        val reasonChanged = confirmedReason != newEnforcementReason
        if (reasonChanged) {
            return false
        }

        return when {
            timeSinceConfirmed < stickTime && distanceFromRoad < 50.0 -> true
            consecutiveSameReadings >= 5 && distanceFromRoad < stickDistance -> true
            abs(newSpeedLimit - confirmed) <= 10 && distanceFromRoad < stickDistance -> true
            confirmedHighway.type == highwayInfo.type && distanceFromRoad < 100.0 -> true
            else -> false
        }
    }

    private fun addToVotingAndCheck(speedLimit: Int, currentSpeed: Float): VotingStatus {
        val currentTime = System.currentTimeMillis()
        recentReadings.addToEnd(speedLimit)

        if (!votingStartTime.containsKey(speedLimit)) {
            votingStartTime[speedLimit] = currentTime
        }

        val votes = mutableMapOf<Int, Int>()
        recentReadings.forEach { reading ->
            votes[reading] = (votes[reading] ?: 0) + 1
        }

        val totalVotes = votes.values.sum()
        val winner = votes.maxByOrNull { it.value }
        val winnerLimit = winner?.key ?: speedLimit
        val winnerCount = winner?.value ?: 0
        val winnerPercentage = winnerCount.toFloat() / totalVotes

        val oldestVoteTime = votingStartTime.values.minOrNull() ?: currentTime
        val timeElapsed = currentTime - oldestVoteTime

        val (minVotes, minPercentage, maxTime) = when {
            currentSpeed > 80f -> Triple(3, 0.6f, 4000L)
            currentSpeed > 40f -> Triple(4, 0.7f, 8000L)
            else -> Triple(5, 0.75f, 12000L)
        }

        return if ((totalVotes >= minVotes && winnerPercentage >= minPercentage) ||
            (totalVotes >= 3 && timeElapsed >= maxTime)) {
            VotingStatus.Winner(winnerLimit, totalVotes, timeElapsed)
        } else {
            VotingStatus.Collecting(totalVotes, timeElapsed, winnerLimit)
        }
    }

    private fun confirmNewRoadWithHighway(speedLimit: Int, enforcementReason: String, location: Triple<Double, Double, Double>, highwayInfo: HighwayInfo) {
        if (confirmedSpeedLimit == speedLimit && confirmedEnforcementReason == enforcementReason) {
            consecutiveSameReadings++
        } else {
            consecutiveSameReadings = 1
            roadConfirmedAt = System.currentTimeMillis()
            confirmedRoadCenter = Pair(location.first, location.second)
            confirmedAltitude = location.third
            confirmedHighwayInfo = highwayInfo
            confirmedEnforcementReason = enforcementReason
            recentReadings.clear()
            votingStartTime.clear()
        }
        confirmedSpeedLimit = speedLimit
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(toRadians(lat1)) * cos(toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // Public functions
    fun getCurrentStatus(): Map<String, String> {
        val confirmedHighway = confirmedHighwayInfo
        val lastKnownHighway = lastKnownHighwayInfo
        val lastKnown = getLastKnownSpeedLimit()
        val confirmedReason = confirmedEnforcementReason
        val lastKnownReason = lastKnownEnforcementReason

        val lastKnownTime = if (lastKnown != null) {
            val timeSince = System.currentTimeMillis() - lastKnownLimitTime
            "${timeSince/1000}s ago"
        } else "None"

        val lastSentTime = if (lastSentSpeedLimit != null) {
            val timeSince = System.currentTimeMillis() - lastTtlSendTime
            "${timeSince/1000}s ago"
        } else "Never"

        return mapOf(
            "System" to "FIXED: All Complex Systems Working + IMPROVED Bridge Detection",
            "Confirmed Speed" to "${confirmedSpeedLimit ?: "None"}km/h${if (confirmedReason != null) " ($confirmedReason)" else ""}",
            "Confirmed Highway" to (confirmedHighway?.description ?: "None"),
            "Confirmed Altitude" to "${confirmedAltitude?.let { String.format("%.1f", it) } ?: "None"}m",
            "Last Known Speed" to "${lastKnown ?: "None"}km/h ($lastKnownTime)${if (lastKnownReason != null) " ($lastKnownReason)" else ""}",
            "Last Known Highway" to (lastKnownHighway?.description ?: "None"),
            "Last TTL Sent" to "${lastSentSpeedLimit ?: "None"}km/h ($lastSentTime)",
            "Verification Mode" to "FIXED: Smart verification with proper reason codes",
            "Speed Enforcement" to "FIXED: OSM direct or highway defaults",
            "Speed Jump Detection" to "FIXED: Prevents rapid changes between any speeds",
            "Bridge Detection" to "IMPROVED: Works for UAE and India OSM data",
            "TTL Mode" to "Continuous All States (20s interval)"
        )
    }

    fun clearAll() {
        confirmedSpeedLimit = null
        confirmedRoadCenter = null
        confirmedAltitude = null
        confirmedHighwayInfo = null
        confirmedEnforcementReason = null
        roadConfirmedAt = 0L
        consecutiveSameReadings = 0
        recentReadings.clear()
        votingStartTime.clear()
        gpsHistory.clear()
        lastKnownSpeedLimit = null
        lastKnownHighwayInfo = null
        lastKnownEnforcementReason = null
        lastKnownLimitTime = 0L
        noDataCount = 0

        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L

        lastTtlSendTime = 0L
        lastSentSpeedLimit = null
    }

    fun hasStableSpeedLimit(): Boolean = confirmedSpeedLimit != null
    fun getConfirmedSpeedLimit(): Int? = confirmedSpeedLimit
    fun getConfirmedHighwayInfo(): HighwayInfo? = confirmedHighwayInfo
    fun getConfirmedAltitude(): Double? = confirmedAltitude
    fun getConfirmedEnforcementReason(): String? = confirmedEnforcementReason
    fun getLastKnownSpeedLimit(): Int? = lastKnownSpeedLimit
    fun getLastKnownHighwayInfo(): HighwayInfo? = lastKnownHighwayInfo
    fun getLastKnownEnforcementReason(): String? = lastKnownEnforcementReason
    fun canUseLastKnown(): Boolean = lastKnownSpeedLimit != null

    fun setLastKnownSpeedLimit(speedLimit: Int) {
        lastKnownSpeedLimit = speedLimit
        lastKnownLimitTime = System.currentTimeMillis()
    }

    fun getPersistenceConfig(): Map<String, String> {
        return mapOf(
            "System Type" to "FIXED: All Complex Systems Working + IMPROVED Bridge Detection",
            "TTL Interval" to "${TTL_SEND_INTERVAL/1000}s (20s)",
            "Enforcement Logic" to "FIXED: OSM direct or highway defaults",
            "Speed Jump Detection" to "FIXED: Prevents rapid changes between any speeds",
            "Highway Stickiness" to "FIXED: High speeds stick for 15s during transitions",
            "Smart Verification" to "FIXED: Different speeds for OSM vs default transitions",
            "Reason Codes" to "FIXED: osm_speed, default_speed (consistent)",
            "Highway Defaults" to "Motorway:80, Trunk:80, Primary:60, Secondary:40, Residential:30",
            "Bridge Detection" to "IMPROVED: Works for UAE and India (bridge=yes, layer, name-based)",
            "All Features" to "WORKING: Jump detection, stickiness, verification, TTL, bridge detection"
        )
    }

    fun getVerificationStatus(): VerificationStatus {
        return if (isVerifying) {
            val timeRemaining = verificationInterval - (System.currentTimeMillis() - lastVerificationTime)
            val secondsRemaining = (timeRemaining / 1000).coerceAtLeast(0)

            VerificationStatus.Active(
                currentSpeedLimit = confirmedSpeedLimit ?: 0,
                pendingSpeedLimit = pendingSpeedLimit ?: 0,
                checksCompleted = verificationCount,
                checksRequired = requiredVerifications,
                nextCheckIn = secondsRemaining
            )
        } else {
            VerificationStatus.Inactive
        }
    }
}

// RESULT TYPES (unchanged but working properly)
sealed class StableSpeedResult {
    object NoData : StableSpeedResult()

    data class Confirmed(
        val speedLimit: Int,
        val reason: String,
        val timeSinceConfirmed: Long,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "stable_continuous"
    ) : StableSpeedResult()

    data class NewConfirmed(
        val speedLimit: Int,
        val reason: String,
        val votesUsed: Int,
        val timeTaken: Long,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "new_confirmed"
    ) : StableSpeedResult()

    data class Voting(
        val progress: String,
        val timeElapsed: Long,
        val leadingCandidate: Int,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "voting_continuous"
    ) : StableSpeedResult()

    data class UsingLastKnown(
        val speedLimit: Int,
        val reason: String,
        val timeSinceLastKnown: Long,
        val noDataCount: Int,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "no_osm_continuous"
    ) : StableSpeedResult()

    data class Verifying(
        val currentSpeedLimit: Int,
        val newSpeedLimit: Int,
        val verificationCount: Int,
        val requiredVerifications: Int,
        val nextCheckIn: Long,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "verification_continuous"
    ) : StableSpeedResult()

    data class VerificationComplete(
        val speedLimit: Int,
        val reason: String,
        val checksPerformed: Int,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "verification_complete"
    ) : StableSpeedResult()

    data class SpeedJumpVerification(
        val currentSpeedLimit: Int,
        val newSpeedLimit: Int,
        val verificationCount: Int,
        val requiredVerifications: Int,
        val jumpSize: Int,
        val drivingSpeed: Float,
        val nextCheckIn: Long,
        val altitude: Double = 0.0,
        val highwayInfo: StableSpeedLimitManager.HighwayInfo? = null,
        val enforcementReason: String = "unknown",
        val sendToTtl: Boolean = false,
        val ttlReason: String = "speed_jump_verification"
    ) : StableSpeedResult()
}

sealed class VotingStatus {
    data class Winner(val speedLimit: Int, val totalVotes: Int, val timeTaken: Long) : VotingStatus()
    data class Collecting(val currentVotes: Int, val timeElapsed: Long, val leadingSpeedLimit: Int) : VotingStatus()
}

sealed class VerificationStatus {
    object Inactive : VerificationStatus()
    data class Active(
        val currentSpeedLimit: Int,
        val pendingSpeedLimit: Int,
        val checksCompleted: Int,
        val checksRequired: Int,
        val nextCheckIn: Long
    ) : VerificationStatus()
}