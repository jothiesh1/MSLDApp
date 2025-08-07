// COMPLETE: Highway Classification + Continuous TTL + Smart Default Enforcement
// File: app/src/main/java/com/gpstracker/msldapp/uis/StableSpeedLimitManager.kt

package com.gpstracker.msldapp.uis

import java.lang.Math.toRadians
import kotlin.math.*
import java.util.ArrayDeque
import kotlinx.coroutines.*

/**
 * 🛣️ COMPLETE HIGHWAY CLASSIFICATION + CONTINUOUS TTL + SMART DEFAULT ENFORCEMENT
 * 1. Highway classification (motorway, trunk, primary, etc.)
 * 2. Ground level detection (bridge, tunnel, ground)
 * 3. Smart verification timing (fast low→high, slow high→low)
 * 4. SMART DEFAULT enforcement (send defaults for violations, actual speeds for proper)
 * 5. Altitude collection and display
 * 6. Continuous TTL for all states
 * 7. Smooth handling of speed jumping between proper/violation
 */
class StableSpeedLimitManager {

    // Current stable state with highway info
    private var confirmedSpeedLimit: Int? = null
    private var confirmedRoadCenter: Pair<Double, Double>? = null
    private var confirmedAltitude: Double? = null
    private var confirmedHighwayInfo: HighwayInfo? = null
    private var confirmedEnforcementReason: String? = null  // Track if using default or actual
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
    private var requiredVerifications = 3  // Dynamic based on speed change
    private var verificationInterval = 1000L  // 1 second

    // 🛣️ HIGHWAY CLASSIFICATION SYSTEM
    data class HighwayInfo(
        val type: String,           // motorway, trunk, primary, etc.
        val isHighway: Boolean,     // true for motorway/trunk
        val priority: Int,          // priority score
        val description: String,    // display text with ground level
        val icon: String,          // emoji icon
        val layer: Int,            // bridge/tunnel layer
        val levelType: String,     // GROUND/BRIDGE/TUNNEL
        val minSpeedLimit: Int,    // minimum speed for this highway type
        val maxSpeedLimit: Int,    // maximum speed for this highway type
        val defaultSpeed: Int      // default speed when violations occur
    )

    /**
     * 🛣️ ANALYZE HIGHWAY TYPE FROM OSM DATA
     */
    private fun analyzeHighway(tags: Map<String, String>): HighwayInfo {
        val highway = tags["highway"] ?: "unknown"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val bridge = tags["bridge"] == "yes"
        val tunnel = tags["tunnel"] == "yes"

        // Ground level detection
        val levelType = when {
            bridge && layer > 0 -> "BRIDGE"
            tunnel && layer < 0 -> "TUNNEL"
            else -> "GROUND"
        }

        return when (highway) {
            "motorway" -> HighwayInfo(
                type = "MOTORWAY",
                isHighway = true,
                priority = 100,
                description = "🛣️ MOTORWAY ($levelType)",
                icon = "🛣️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 80,    // Never below 80
                maxSpeedLimit = 140,   // Never above 140
                defaultSpeed = 120     // Default when violations occur
            )

            "trunk" -> HighwayInfo(
                type = "TRUNK",
                isHighway = true,
                priority = 90,
                description = "🛣️ TRUNK HIGHWAY ($levelType)",
                icon = "🛣️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 80,    // Never below 80
                maxSpeedLimit = 120,   // Never above 120
                defaultSpeed = 100     // Default when violations occur
            )

            "primary" -> HighwayInfo(
                type = "PRIMARY",
                isHighway = false,
                priority = 80,
                description = "🛤️ PRIMARY ROAD ($levelType)",
                icon = "🛤️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 50,    // Minimum
                maxSpeedLimit = 90,    // Maximum
                defaultSpeed = 70      // Manager: "make it to 70 until new speed from map"
            )

            "secondary" -> HighwayInfo(
                type = "SECONDARY",
                isHighway = false,
                priority = 70,
                description = "🛤️ SECONDARY ROAD ($levelType)",
                icon = "🛤️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 30,    // Minimum
                maxSpeedLimit = 70,    // Never above 70
                defaultSpeed = 50      // Default when violations occur
            )

            "tertiary" -> HighwayInfo(
                type = "TERTIARY",
                isHighway = false,
                priority = 60,
                description = "🛤️ LOCAL ROAD ($levelType)",
                icon = "🛤️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 20,
                maxSpeedLimit = 60,
                defaultSpeed = 40
            )

            "residential" -> HighwayInfo(
                type = "RESIDENTIAL",
                isHighway = false,
                priority = 40,
                description = "🏘️ RESIDENTIAL ($levelType)",
                icon = "🏘️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 20,    // Minimum
                maxSpeedLimit = 60,    // Never above 60
                defaultSpeed = 30      // Default when violations occur
            )

            "service" -> HighwayInfo(
                type = "SERVICE",
                isHighway = false,
                priority = 30,
                description = "🅿️ SERVICE ROAD ($levelType)",
                icon = "🅿️",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 20,
                maxSpeedLimit = 50,
                defaultSpeed = 30
            )

            else -> HighwayInfo(
                type = "UNKNOWN",
                isHighway = false,
                priority = 20,
                description = "❓ UNKNOWN ROAD ($levelType)",
                icon = "❓",
                layer = layer,
                levelType = levelType,
                minSpeedLimit = 30,
                maxSpeedLimit = 60,
                defaultSpeed = 50
            )
        }
    }

    /**
     * 🚨 SMART DEFAULT ENFORCEMENT - Manager's Exact Requirements
     *
     * LOGIC:
     * 1. If speed limit IS available and PROPER → Send actual speed
     * 2. If speed limit IS available but VIOLATES rules → Send DEFAULT until proper speed found
     * 3. If speed limit NOT available → Use highway default
     * 4. Handle jumping between proper/violation smoothly
     */
    private fun enforceHighwaySpeed(speedLimit: Int?, highwayInfo: HighwayInfo): Pair<Int, String> {
        if (speedLimit == null) {
            // No speed limit available - use highway default
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🚨 ${highwayInfo.description}: No speed → Default ${highwayInfo.defaultSpeed}km/h"
            )
            return Pair(highwayInfo.defaultSpeed, "no_data_default")
        }

        // Speed limit IS available - check if it violates highway rules
        val violatesRules = speedLimit < highwayInfo.minSpeedLimit || speedLimit > highwayInfo.maxSpeedLimit

        return if (violatesRules) {
            // VIOLATION: Send DEFAULT value until proper speed found
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🚨 ${highwayInfo.description}: Speed ${speedLimit}km/h VIOLATES rules (${highwayInfo.minSpeedLimit}-${highwayInfo.maxSpeedLimit}) → Default ${highwayInfo.defaultSpeed}km/h until proper speed"
            )
            Pair(highwayInfo.defaultSpeed, "violation_default")
        } else {
            // PROPER SPEED: Send the actual map speed
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "✅ ${highwayInfo.description}: Speed ${speedLimit}km/h is proper (${highwayInfo.minSpeedLimit}-${highwayInfo.maxSpeedLimit}) → Sending actual speed"
            )
            Pair(speedLimit, "proper_map_speed")
        }
    }

    /**
     * 🆕 SMART VERIFICATION SETTINGS - Manager Requirements
     */
    private fun getSmartVerificationSettings(currentSpeed: Int, newSpeed: Int, currentReason: String, newReason: String, highwayInfo: HighwayInfo): Pair<Int, Long> {
        // Handle transitions between proper speeds and defaults
        val transitionType = when {
            // Both are proper speeds
            currentReason == "proper_map_speed" && newReason == "proper_map_speed" -> {
                if (newSpeed > currentSpeed) "proper_increase" else "proper_decrease"
            }
            // From default to proper speed (found proper speed!)
            currentReason.contains("default") && newReason == "proper_map_speed" -> "default_to_proper"
            // From proper to default (speed violation detected)
            currentReason == "proper_map_speed" && newReason.contains("default") -> "proper_to_default"
            // Between defaults
            else -> "default_change"
        }

        return when (transitionType) {
            "proper_increase" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "📈 PROPER SPEED INCREASE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → FAST (2 checks)"
                )
                Pair(2, 1000L)  // Fast for proper speed increases
            }

            "proper_decrease" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "📉 PROPER SPEED DECREASE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → SLOW (4 checks)"
                )
                Pair(4, 1000L)  // Slow for proper speed decreases
            }

            "default_to_proper" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🎯 DEFAULT→PROPER: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → FAST (2 checks) - Found proper speed!"
                )
                Pair(2, 1000L)  // Fast when finding proper speed
            }

            "proper_to_default" -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "⚠️ PROPER→DEFAULT: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → IMMEDIATE (1 check) - Speed violation"
                )
                Pair(1, 500L)   // Immediate when speed violation detected
            }

            else -> {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔄 DEFAULT CHANGE: ${currentSpeed}→${newSpeed}km/h on ${highwayInfo.description} → NORMAL (3 checks)"
                )
                Pair(3, 1000L)  // Normal for other cases
            }
        }
    }

    /**
     * 🔄 CONTINUOUS TTL CHECKER - Should we send TTL now?
     */
    private fun shouldSendContinuousTtl(currentSpeed: Int): Boolean {
        val timeSinceLastSend = System.currentTimeMillis() - lastTtlSendTime

        return when {
            // First time sending
            lastSentSpeedLimit == null -> true
            // Different speed
            lastSentSpeedLimit != currentSpeed -> true
            // 20 seconds passed
            timeSinceLastSend >= TTL_SEND_INTERVAL -> true
            // Don't send
            else -> false
        }
    }

    /**
     * 🔄 RECORD TTL SEND
     */
    fun recordTtlSent(speedLimit: Int) {
        lastTtlSendTime = System.currentTimeMillis()
        lastSentSpeedLimit = speedLimit

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.BACKEND,
            "📤 Continuous TTL sent: ${speedLimit}km/h (20s interval)"
        )
    }

    // Compatibility methods
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
     * 🎯 MAIN FUNCTION: Complete highway-aware speed limit with smart default enforcement
     */
    fun getStableSpeedLimit(
        rawLat: Double,
        rawLon: Double,
        rawAltitude: Double,
        currentSpeed: Float,
        rawSpeedLimit: Int?,
        osmTags: Map<String, String> = emptyMap()
    ): StableSpeedResult {

        // 🛣️ ANALYZE HIGHWAY TYPE
        val highwayInfo = if (osmTags.isNotEmpty()) {
            analyzeHighway(osmTags)
        } else {
            HighwayInfo(
                type = "UNKNOWN",
                isHighway = false,
                priority = 50,
                description = "❓ UNKNOWN ROAD (GROUND)",
                icon = "❓",
                layer = 0,
                levelType = "GROUND",
                minSpeedLimit = 30,
                maxSpeedLimit = 60,
                defaultSpeed = 50
            )
        }

        // Case 1: No OSM data - CONTINUOUS TTL with last known + smart enforcement
        if (rawSpeedLimit == null) {
            noDataCount++

            if (isVerifying) {
                cancelVerification("no_osm_data")
            }

            val lastKnown = lastKnownSpeedLimit
            val lastHighway = lastKnownHighwayInfo ?: highwayInfo
            val timeSinceLastKnown = System.currentTimeMillis() - lastKnownLimitTime

            return if (lastKnown != null) {
                // Apply smart enforcement to last known speed
                val (enforcedLimit, enforcementReason) = enforceHighwaySpeed(lastKnown, lastHighway)
                val shouldSendTtl = shouldSendContinuousTtl(enforcedLimit)

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔄 NO OSM DATA → Last known processed: ${enforcedLimit}km/h ($enforcementReason) → TTL: ${if (shouldSendTtl) "SEND" else "WAIT"}"
                )

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
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🚫 No OSM data and no last known yet"
                )
                StableSpeedResult.NoData
            }
        }

        // Case 2: OSM works - highway-aware processing with smart enforcement
        noDataCount = 0
        val smoothLocation = smoothGPSCoordinates(rawLat, rawLon, rawAltitude)

        // 🚨 SMART DEFAULT ENFORCEMENT
        val (enforcedSpeedLimit, enforcementReason) = enforceHighwaySpeed(rawSpeedLimit, highwayInfo)

        // Check if we should stick to confirmed road - WITH CONTINUOUS TTL
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

        // 🆕 SMART VERIFICATION with enforcement reason tracking
        val currentConfirmed = confirmedSpeedLimit
        val currentEnforcementReason = confirmedEnforcementReason

        if (currentConfirmed != null && (enforcedSpeedLimit != currentConfirmed || enforcementReason != currentEnforcementReason)) {
            return handleSmartHighwayVerification(
                enforcedSpeedLimit,
                currentConfirmed,
                enforcementReason,
                currentEnforcementReason ?: "unknown",
                smoothLocation,
                currentSpeed,
                highwayInfo
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
     * 🆕 SMART HIGHWAY VERIFICATION HANDLER - WITH ENFORCEMENT REASON TRACKING
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
            // Start new verification with smart highway settings
            !isVerifying || pendingSpeedLimit != newSpeedLimit || pendingEnforcementReason != newEnforcementReason -> {
                val (reqVerifications, verifyInterval) = getSmartVerificationSettings(
                    currentSpeedLimit,
                    newSpeedLimit,
                    currentEnforcementReason,
                    newEnforcementReason,
                    highwayInfo
                )
                startSmartVerification(newSpeedLimit, newEnforcementReason, reqVerifications, verifyInterval)

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔍 HIGHWAY VERIFICATION STARTED: ${newSpeedLimit}km/h ($newEnforcementReason) on ${highwayInfo.description} (${reqVerifications} checks)"
                )

                // 🔄 CONTINUOUS TTL during verification
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

            // Continue existing verification
            isVerifying && currentTime - lastVerificationTime >= verificationInterval -> {
                verificationCount++
                lastVerificationTime = currentTime

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.OSM,
                    "🔍 HIGHWAY VERIFICATION: ${newSpeedLimit}km/h ($newEnforcementReason) on ${highwayInfo.description} (check ${verificationCount}/${requiredVerifications})"
                )

                if (verificationCount >= requiredVerifications) {
                    // Verification complete
                    completeVerificationWithHighway(newSpeedLimit, newEnforcementReason, location, highwayInfo)

                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.OSM,
                        "✅ HIGHWAY VERIFICATION COMPLETE: ${newSpeedLimit}km/h ($newEnforcementReason) on ${highwayInfo.description}"
                    )

                    // 🔄 SEND TTL after verification
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
                    // Continue verification - CONTINUOUS TTL
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

            // Waiting for next verification check
            else -> {
                val timeRemaining = verificationInterval - (currentTime - lastVerificationTime)
                val secondsRemaining = (timeRemaining / 1000).coerceAtLeast(0)

                // 🔄 CONTINUOUS TTL while waiting
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

    /**
     * Start smart verification process with enforcement reason
     */
    private fun startSmartVerification(speedLimit: Int, enforcementReason: String, reqVerifications: Int, verifyInterval: Long) {
        pendingSpeedLimit = speedLimit
        pendingEnforcementReason = enforcementReason
        verificationCount = 1
        lastVerificationTime = System.currentTimeMillis()
        isVerifying = true
        requiredVerifications = reqVerifications
        verificationInterval = verifyInterval
    }

    /**
     * Complete verification with highway info and enforcement reason
     */
    private fun completeVerificationWithHighway(speedLimit: Int, enforcementReason: String, location: Triple<Double, Double, Double>, highwayInfo: HighwayInfo) {
        confirmedSpeedLimit = speedLimit
        confirmedRoadCenter = Pair(location.first, location.second)
        confirmedAltitude = location.third
        confirmedHighwayInfo = highwayInfo
        confirmedEnforcementReason = enforcementReason
        roadConfirmedAt = System.currentTimeMillis()
        consecutiveSameReadings = 1

        storeLastKnownSpeedWithHighway(speedLimit, highwayInfo, enforcementReason)

        // Reset verification
        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L

        // Clear voting history
        recentReadings.clear()
        votingStartTime.clear()
    }

    /**
     * Cancel verification process
     */
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

    /**
     * 🔄 VOTING RESULT HANDLER - WITH HIGHWAY INFO AND ENFORCEMENT REASON
     */
    private fun handleVotingResult(votingResult: VotingStatus, location: Triple<Double, Double, Double>, highwayInfo: HighwayInfo, enforcementReason: String): StableSpeedResult {
        return when (votingResult) {
            is VotingStatus.Winner -> {
                confirmNewRoadWithHighway(votingResult.speedLimit, enforcementReason, location, highwayInfo)
                storeLastKnownSpeedWithHighway(votingResult.speedLimit, highwayInfo, enforcementReason)

                // 🔄 SEND TTL for voting winner
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
                // 🔄 CONTINUOUS TTL during voting
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

    /**
     * Store last known speed with highway info and enforcement reason
     */
    private fun storeLastKnownSpeedWithHighway(speedLimit: Int, highwayInfo: HighwayInfo, enforcementReason: String) {
        lastKnownSpeedLimit = speedLimit
        lastKnownHighwayInfo = highwayInfo
        lastKnownEnforcementReason = enforcementReason
        lastKnownLimitTime = System.currentTimeMillis()

        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "💾 Stored last known ${speedLimit}km/h ($enforcementReason) on ${highwayInfo.description}"
        )
    }

    /**
     * Smooth GPS coordinates with altitude
     */
    private fun smoothGPSCoordinates(lat: Double, lon: Double, altitude: Double): Triple<Double, Double, Double> {
        gpsHistory.addToEnd(Triple(lat, lon, altitude))
        val avgLat = gpsHistory.map { it.first }.average()
        val avgLon = gpsHistory.map { it.second }.average()
        val avgAlt = gpsHistory.map { it.third }.average()
        return Triple(avgLat, avgLon, avgAlt)
    }

    /**
     * Highway-aware road stickiness check with enforcement reason
     */
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

        // Highway-specific stickiness rules with enforcement reason consideration
        val stickDistance = if (confirmedHighway.isHighway) 200.0 else 100.0
        val stickTime = if (confirmedHighway.isHighway) 60000L else 30000L

        // Don't stick if enforcement reason changed (proper speed found or violation detected)
        val reasonChanged = confirmedReason != newEnforcementReason
        if (reasonChanged) {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.OSM,
                "🔄 Enforcement reason changed: $confirmedReason → $newEnforcementReason - Not sticking"
            )
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

    /**
     * Voting system
     */
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
        val winnerCount = winner?.value ?:
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

    /**
     * Confirm new road with highway info and enforcement reason
     */
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

    /**
     * Distance calculation
     */
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
            "System" to "Complete Highway + Smart Default Enforcement + Continuous TTL",
            "Confirmed Speed" to "${confirmedSpeedLimit ?: "None"}km/h${if (confirmedReason != null) " ($confirmedReason)" else ""}",
            "Confirmed Highway" to (confirmedHighway?.description ?: "None"),
            "Confirmed Altitude" to "${confirmedAltitude?.let { String.format("%.1f", it) } ?: "None"}m",
            "Last Known Speed" to "${lastKnown ?: "None"}km/h ($lastKnownTime)${if (lastKnownReason != null) " ($lastKnownReason)" else ""}",
            "Last Known Highway" to (lastKnownHighway?.description ?: "None"),
            "Last TTL Sent" to "${lastSentSpeedLimit ?: "None"}km/h ($lastSentTime)",
            "Verification Mode" to "Smart Highway + Enforcement Reason (Proper↔Default transitions)",
            "Speed Enforcement" to "Smart Defaults: Proper speeds → Send actual, Violations → Send defaults",
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

        // Clear verification state
        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L

        // Clear TTL tracking
        lastTtlSendTime = 0L
        lastSentSpeedLimit = null

        LogCollector.addDetailedLog(LogCollector.LogCategory.GPS, "🧹 System cleared (complete highway + smart default enforcement + continuous TTL)")
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
        LogCollector.addDetailedLog(
            LogCollector.LogCategory.OSM,
            "🎯 Manual: Set last known ${speedLimit}km/h"
        )
    }

    fun getPersistenceConfig(): Map<String, String> {
        return mapOf(
            "System Type" to "Complete Highway + Smart Default Enforcement + Continuous TTL",
            "TTL Interval" to "${TTL_SEND_INTERVAL/1000}s (20s)",
            "Enforcement Logic" to "Proper speeds → Send actual, Violations → Send defaults",
            "Default→Proper" to "FAST: 2 checks (found proper speed!)",
            "Proper→Default" to "IMMEDIATE: 1 check (violation detected)",
            "Proper Increase" to "FAST: 2 checks, 1s each",
            "Proper Decrease" to "SLOW: 4 checks, 1s each",
            "Highway Defaults" to "Motorway:120, Trunk:100, Primary:70, Secondary:50, Residential:30",
            "Classification" to "Motorway/Trunk/Primary/Secondary/Residential/Service",
            "Ground Level" to "Bridge/Tunnel/Ground detection",
            "Altitude Collection" to "ENABLED",
            "TTL for All States" to "ENABLED",
            "Jump Handling" to "Smooth transitions between proper/default speeds"
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

// 🛣️ COMPLETE RESULT TYPES WITH HIGHWAY INFO, ENFORCEMENT REASON AND CONTINUOUS TTL
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
}

sealed class VotingStatus {
    data class Winner(
        val speedLimit: Int,
        val totalVotes: Int,
        val timeTaken: Long
    ) : VotingStatus()

    data class Collecting(
        val currentVotes: Int,
        val timeElapsed: Long,
        val leadingSpeedLimit: Int
    ) : VotingStatus()
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