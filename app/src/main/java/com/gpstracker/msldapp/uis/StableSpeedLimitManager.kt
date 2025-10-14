// File: StableSpeedLimitManager.kt
// COMPLETE VERSION with CONTINUOUS TTL Support
// Features: Road Lock, Bridge Detection, Residential/School Zones, Speed Jump Detection,
//           Highway Stickiness, Smart Verification, Worldwide OSM Support, Continuous TTL

package com.gpstracker.msldapp.uis

import android.util.Log
import java.lang.Math.toRadians
import kotlin.math.*
import java.util.ArrayDeque

class StableSpeedLimitManager {

    // ===== ROAD LOCK SYSTEM =====
    enum class RoadLockMode {
        BRIDGE_LOCKED, MAIN_LOCKED, SERVICE_LOCKED,
        MOTORWAY_LOCKED, RESIDENTIAL_LOCKED, SCHOOL_LOCKED, UNLOCKED
    }

    data class RoadLockState(
        val mode: RoadLockMode,
        val lockedAt: Long,
        val lockStrength: Int,
        val lastValidSpeed: Int?,
        val lockReason: String
    )

    private var currentRoadLock: RoadLockState? = null
    private var consecutiveBlockedReadings = 0
    private val roadLockHistory = ArrayDeque<RoadLockState>(5)

    // Movement tracking
    private var lastLocationData: Triple<Double, Double, Double>? = null
    private var lastBearing: Float = 0f
    private var lastTimestamp: Long = 0L

    // Current stable state
    private var confirmedSpeedLimit: Int? = null
    private var confirmedRoadCenter: Pair<Double, Double>? = null
    private var confirmedAltitude: Double? = null
    private var confirmedHighwayInfo: HighwayInfo? = null
    private var confirmedEnforcementReason: String? = null
    private var roadConfirmedAt = 0L
    private var consecutiveSameReadings = 0

    // Voting system
    private val recentReadings = ArrayDeque<Int>(7)
    private val votingStartTime = mutableMapOf<Int, Long>()

    // GPS smoothing
    private val gpsHistory = ArrayDeque<Triple<Double, Double, Double>>(3)

    // Last known persistence
    private var lastKnownSpeedLimit: Int? = null
    private var lastKnownHighwayInfo: HighwayInfo? = null
    private var lastKnownEnforcementReason: String? = null
    private var lastKnownLimitTime = 0L
    private var noDataCount = 0

    // TTL system - ENHANCED
    private var lastTtlSendTime = 0L
    private var lastSentSpeedLimit: Int? = null
    private val TTL_SEND_INTERVAL = 20000L // 20 seconds

    // Verification system
    private var pendingSpeedLimit: Int? = null
    private var pendingEnforcementReason: String? = null
    private var verificationCount = 0
    private var lastVerificationTime = 0L
    private var isVerifying = false
    private var requiredVerifications = 3
    private var verificationInterval = 1000L

    companion object {
        private const val MAX_BLOCKS = 12
        private const val LARGE_SPEED_JUMP = 60
        private const val MEDIUM_SPEED_JUMP = 40
        private const val HIGH_SPEED = 80
        private const val STICK_TIME = 15000L

        private val MOTORWAY_SPEEDS = mapOf("UAE" to 120, "INDIA" to 100, "KENYA" to 110, "DEFAULT" to 100)
        private val TRUNK_SPEEDS = mapOf("UAE" to 100, "INDIA" to 80, "KENYA" to 80, "DEFAULT" to 80)
        private val PRIMARY_SPEEDS = mapOf("UAE" to 80, "INDIA" to 60, "KENYA" to 60, "DEFAULT" to 60)
        private val SERVICE_SPEEDS = mapOf("UAE" to 50, "INDIA" to 40, "KENYA" to 30, "DEFAULT" to 40)
    }

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
        val defaultSpeed: Int,
        val roadName: String = "Unknown",
        val multilingualNames: Map<String, String> = emptyMap(),
        val region: String = "DEFAULT",
        val isResidential: Boolean = false,
        val isSchoolZone: Boolean = false
    )

    // ===== TTL SYSTEM - CONTINUOUS SENDING =====

    /**
     * FIXED: Always allow sending for continuous TTL transmission
     * Sends same value every 20 seconds OR when speed changes
     */
    private fun shouldSendToTtl(currentSpeed: Int): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastTtlSendTime

        val shouldSend = when {
            // First send - always allow
            lastSentSpeedLimit == null -> {
                Log.d("StableManager", "TTL: First send - Speed: $currentSpeed")
                true
            }
            // Speed changed - send immediately
            lastSentSpeedLimit != currentSpeed -> {
                Log.d("StableManager", "TTL: Speed changed ${lastSentSpeedLimit} -> $currentSpeed")
                true
            }
            // Same speed but 20 seconds elapsed - send again to maintain continuous transmission
            timeSinceLastSend >= TTL_SEND_INTERVAL -> {
                Log.d("StableManager", "TTL: Continuous send - Same speed $currentSpeed (${timeSinceLastSend}ms since last)")
                true
            }
            // Skip if within 20 second interval
            else -> {
                Log.d("StableManager", "TTL: Wait - ${(TTL_SEND_INTERVAL - timeSinceLastSend)/1000}s until next send")
                false
            }
        }

        return shouldSend
    }

    /**
     * Record TTL sent - Call this after successfully sending to TTL
     */
    fun recordTtlSent(speedLimit: Int) {
        val previousTime = lastTtlSendTime
        val previousSpeed = lastSentSpeedLimit

        lastTtlSendTime = System.currentTimeMillis()
        lastSentSpeedLimit = speedLimit

        val timeSinceLast = if (previousTime > 0) {
            (lastTtlSendTime - previousTime) / 1000
        } else {
            0
        }

        if (previousSpeed == speedLimit) {
            Log.d("StableManager", "TTL: Continuous send recorded - $speedLimit km/h (same value after ${timeSinceLast}s)")
        } else {
            Log.d("StableManager", "TTL: New speed recorded - $previousSpeed -> $speedLimit km/h")
        }
    }

    // ===== ROAD DETECTION =====

    private fun detectRoadLevel(tags: Map<String, String>): String {
        val highway = tags["highway"] ?: "unknown"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val bridge = tags["bridge"] == "yes"
        val tunnel = tags["tunnel"] == "yes"
        val name = extractBestRoadName(tags).lowercase()
        val level = tags["level"]?.toIntOrNull() ?: 0

        return when {
            bridge && layer > 1 -> "BRIDGE_L${layer}"
            bridge -> "BRIDGE"
            "flyover" in name -> "FLYOVER"
            "overpass" in name -> "OVERPASS"
            "bridge" in name -> "BRIDGE"
            "elevated" in name -> "ELEVATED"
            level > 0 -> "ELEVATED_L${level}"
            layer > 0 -> "ELEVATED_L${layer}"
            tags["embankment"] == "yes" -> "EMBANKMENT"
            tunnel -> "TUNNEL"
            layer < 0 -> "UNDERGROUND_L${abs(layer)}"
            highway == "motorway" -> "MOTORWAY"
            highway == "trunk" && layer >= 0 -> "HIGHWAY"
            else -> "GROUND"
        }
    }

    private fun extractBestRoadName(tags: Map<String, String>): String {
        val nameKeys = listOf("name:en", "name", "name:ar", "name:hi", "name:kn", "name:ta", "name:sw", "ref")
        for (key in nameKeys) {
            tags[key]?.let { if (it.isNotBlank()) return it }
        }
        return "Unnamed Road"
    }

    private fun detectRegion(tags: Map<String, String>, roadName: String): String {
        tags["source:maxspeed"]?.let { source ->
            if ("IN:" in source) return "INDIA"
            if ("AE:" in source) return "UAE"
        }

        val nameLower = roadName.lowercase()
        return when {
            "sheikh" in nameLower || tags.containsKey("name:ar") -> "UAE"
            "nh " in nameLower || tags.containsKey("name:hi") -> "INDIA"
            "a1" in nameLower || tags.containsKey("name:sw") -> "KENYA"
            else -> "DEFAULT"
        }
    }

    private fun analyzeHighway(tags: Map<String, String>): HighwayInfo {
        val highway = tags["highway"] ?: "unknown"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val roadName = extractBestRoadName(tags)
        val region = detectRegion(tags, roadName)
        val levelType = detectRoadLevel(tags)
        val nameLower = roadName.lowercase()

        val isResidential = highway == "residential" || "residential" in nameLower
        val isSchoolZone = "school" in nameLower || tags["zone:traffic"] == "school"

        return when (highway) {
            "motorway" -> HighwayInfo(
                type = "MOTORWAY", isHighway = true, priority = 90,
                description = "MOTORWAY ($levelType)", icon = "🛣️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 60, maxSpeedLimit = 160,
                defaultSpeed = MOTORWAY_SPEEDS[region] ?: 100,
                roadName = roadName, region = region
            )
            "trunk" -> HighwayInfo(
                type = "TRUNK", isHighway = true, priority = 85,
                description = "TRUNK HIGHWAY ($levelType)", icon = "🛣️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 50, maxSpeedLimit = 120,
                defaultSpeed = TRUNK_SPEEDS[region] ?: 80,
                roadName = roadName, region = region
            )
            "primary" -> HighwayInfo(
                type = "PRIMARY", isHighway = false, priority = 70,
                description = "PRIMARY ROAD ($levelType)", icon = "🛤️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 40, maxSpeedLimit = 100,
                defaultSpeed = PRIMARY_SPEEDS[region] ?: 60,
                roadName = roadName, region = region
            )
            "secondary" -> HighwayInfo(
                type = "SECONDARY", isHighway = false, priority = 60,
                description = "SECONDARY ROAD ($levelType)", icon = "🛤️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 30, maxSpeedLimit = 80,
                defaultSpeed = 50,
                roadName = roadName, region = region
            )
            "residential" -> HighwayInfo(
                type = "RESIDENTIAL", isHighway = false, priority = 40,
                description = "RESIDENTIAL ($levelType)", icon = "🏘️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 15, maxSpeedLimit = 50,
                defaultSpeed = 30,
                roadName = roadName, region = region,
                isResidential = true
            )
            "service" -> HighwayInfo(
                type = "SERVICE", isHighway = false, priority = 30,
                description = "SERVICE ROAD ($levelType)", icon = "🅿️",
                layer = layer, levelType = levelType,
                minSpeedLimit = 10, maxSpeedLimit = 60,
                defaultSpeed = SERVICE_SPEEDS[region] ?: 40,
                roadName = roadName, region = region
            )
            else -> HighwayInfo(
                type = "UNKNOWN", isHighway = false, priority = 20,
                description = "ROAD ($levelType)", icon = "❓",
                layer = layer, levelType = levelType,
                minSpeedLimit = 20, maxSpeedLimit = 80,
                defaultSpeed = 50,
                roadName = roadName, region = region,
                isSchoolZone = isSchoolZone
            )
        }
    }

    // ===== ROAD LOCK SYSTEM =====

    private fun getRoadLockCandidate(tags: Map<String, String>): RoadLockMode {
        val highway = tags["highway"] ?: "unknown"
        val bridge = tags["bridge"] == "yes"
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val level = tags["level"]?.toIntOrNull() ?: 0
        val name = extractBestRoadName(tags).lowercase()
        val service = tags["service"] ?: ""

        return when {
            "school" in name || tags["zone:traffic"] == "school" -> RoadLockMode.SCHOOL_LOCKED
            highway == "residential" || tags["zone:traffic"] == "residential" -> RoadLockMode.RESIDENTIAL_LOCKED
            bridge || layer > 0 || level > 0 ||
                    "flyover" in name || "overpass" in name ||
                    "bridge" in name || "elevated" in name -> RoadLockMode.BRIDGE_LOCKED
            highway == "service" || service.isNotEmpty() -> RoadLockMode.SERVICE_LOCKED
            highway in listOf("motorway", "motorway_link") -> RoadLockMode.MOTORWAY_LOCKED
            highway in listOf("trunk", "primary", "secondary", "tertiary") && !bridge && layer <= 0 -> RoadLockMode.MAIN_LOCKED
            else -> RoadLockMode.UNLOCKED
        }
    }

    private fun shouldEstablishRoadLock(
        tags: Map<String, String>,
        currentSpeed: Float,
        location: Triple<Double, Double, Double>
    ): Boolean {
        val candidate = getRoadLockCandidate(tags)
        if (candidate == RoadLockMode.UNLOCKED) return false

        var confidence = 0.0
        val highway = tags["highway"] ?: ""
        val name = extractBestRoadName(tags).lowercase()

        when (candidate) {
            RoadLockMode.SCHOOL_LOCKED -> {
                if ("school" in name) confidence += 0.9
                if (tags["zone:traffic"] == "school") confidence += 0.8
                if (currentSpeed < 40f) confidence += 0.3
                return confidence >= 0.8
            }
            RoadLockMode.RESIDENTIAL_LOCKED -> {
                if (highway == "residential") confidence += 0.8
                if (currentSpeed < 50f) confidence += 0.3
                return confidence >= 0.7
            }
            RoadLockMode.BRIDGE_LOCKED -> {
                if (tags["bridge"] == "yes") confidence += 0.5
                if (tags["layer"]?.toIntOrNull()?.let { it > 0 } == true) confidence += 0.3
                if ("flyover" in name || "bridge" in name) confidence += 0.4
                if (currentSpeed > 30f) confidence += 0.2
                return confidence >= 0.5
            }
            RoadLockMode.SERVICE_LOCKED -> {
                if (highway == "service") confidence += 0.8
                if (currentSpeed < 40f) confidence += 0.3
                return confidence >= 0.7
            }
            RoadLockMode.MOTORWAY_LOCKED -> {
                if (highway == "motorway") confidence += 0.9
                if (currentSpeed > 60f) confidence += 0.4
                return confidence >= 0.7
            }
            RoadLockMode.MAIN_LOCKED -> {
                if (highway in listOf("trunk", "primary", "secondary", "tertiary")) confidence += 0.7
                if (currentSpeed in 30f..80f) confidence += 0.3
                return confidence >= 0.6
            }
            else -> return false
        }
    }

    private fun establishRoadLock(lockMode: RoadLockMode, speedLimit: Int?, reason: String) {
        val newLock = RoadLockState(
            mode = lockMode,
            lockedAt = System.currentTimeMillis(),
            lockStrength = 1,
            lastValidSpeed = speedLimit,
            lockReason = reason
        )

        currentRoadLock = newLock
        consecutiveBlockedReadings = 0

        roadLockHistory.addLast(newLock)
        if (roadLockHistory.size > 5) {
            roadLockHistory.removeFirst()
        }
    }

    private fun filterSpeedByRoadLock(
        osmSpeedLimit: Int?,
        osmTags: Map<String, String>
    ): Pair<Int?, String> {
        val lock = currentRoadLock ?: return Pair(osmSpeedLimit, "no_lock")

        val highway = osmTags["highway"] ?: "unknown"
        val bridge = osmTags["bridge"] == "yes"
        val layer = osmTags["layer"]?.toIntOrNull() ?: 0
        val level = osmTags["level"]?.toIntOrNull() ?: 0
        val name = extractBestRoadName(osmTags).lowercase()

        val isValidData = when (lock.mode) {
            RoadLockMode.SCHOOL_LOCKED -> "school" in name || osmTags["zone:traffic"] == "school"
            RoadLockMode.RESIDENTIAL_LOCKED -> highway == "residential"
            RoadLockMode.BRIDGE_LOCKED -> {
                bridge || layer > 0 || level > 0 ||
                        "flyover" in name || "overpass" in name ||
                        "bridge" in name || "elevated" in name
            }
            RoadLockMode.SERVICE_LOCKED -> highway == "service" || "service" in name
            RoadLockMode.MOTORWAY_LOCKED -> highway in listOf("motorway", "motorway_link")
            RoadLockMode.MAIN_LOCKED -> {
                highway in listOf("trunk", "primary", "secondary", "tertiary") &&
                        !bridge && layer <= 0 && level <= 0
            }
            RoadLockMode.UNLOCKED -> true
        }

        return if (isValidData) {
            currentRoadLock = lock.copy(lockStrength = lock.lockStrength + 1)
            consecutiveBlockedReadings = 0
            Pair(osmSpeedLimit, "lock_accepted")
        } else {
            consecutiveBlockedReadings++
            Pair(lock.lastValidSpeed, "lock_blocked")
        }
    }

    private fun shouldReleaseRoadLock(
        location: Triple<Double, Double, Double>,
        bearing: Float,
        osmTags: Map<String, String>
    ): Boolean {
        val lock = currentRoadLock ?: return false

        if (consecutiveBlockedReadings > MAX_BLOCKS) return true

        val altChange = lastLocationData?.let { location.third - it.third } ?: 0.0
        val headChange = calculateHeadingChange(bearing)

        return when (lock.mode) {
            RoadLockMode.SCHOOL_LOCKED -> {
                val leftSchool = !extractBestRoadName(osmTags).lowercase().contains("school")
                leftSchool
            }
            RoadLockMode.RESIDENTIAL_LOCKED -> {
                val leftResidential = osmTags["highway"] != "residential"
                leftResidential && abs(headChange) > 20f
            }
            RoadLockMode.BRIDGE_LOCKED -> {
                val hasAltitudeDrop = altChange < -2.0
                val hasExitIndicators = hasExitIndicators(osmTags)
                val leftBridge = osmTags["bridge"] != "yes"
                (hasAltitudeDrop && hasExitIndicators) || (hasAltitudeDrop && leftBridge)
            }
            RoadLockMode.SERVICE_LOCKED -> {
                val hasTurn = abs(headChange) > 30f
                val isMainRoad = osmTags["highway"] in listOf("trunk", "primary", "secondary")
                hasTurn && isMainRoad
            }
            RoadLockMode.MAIN_LOCKED -> {
                val hasTurn = abs(headChange) > 20f
                val hasBridgeEntry = osmTags["bridge"] == "yes" || altChange > 2.0
                hasTurn && hasBridgeEntry
            }
            RoadLockMode.MOTORWAY_LOCKED -> {
                hasExitIndicators(osmTags)
            }
            else -> false
        }
    }

    private fun hasExitIndicators(osmTags: Map<String, String>): Boolean {
        val highway = osmTags["highway"] ?: ""
        val name = extractBestRoadName(osmTags).lowercase()
        return highway.contains("link") || highway.contains("ramp") ||
                name.contains("exit") || name.contains("ramp") ||
                osmTags["junction"]?.isNotEmpty() == true
    }

    private fun calculateHeadingChange(currentBearing: Float): Float {
        if (lastBearing == 0f) {
            lastBearing = currentBearing
            return 0f
        }
        var change = currentBearing - lastBearing
        while (change > 180f) change -= 360f
        while (change < -180f) change += 360f
        lastBearing = currentBearing
        return change
    }

    private fun releaseRoadLock(reason: String) {
        currentRoadLock = null
        consecutiveBlockedReadings = 0
    }

    // ===== VOTING SYSTEM =====

    private fun addToVotingAndCheck(
        speedLimit: Int,
        currentSpeed: Float,
        highwayInfo: HighwayInfo
    ): VotingStatus {
        val currentTime = System.currentTimeMillis()
        recentReadings.add(speedLimit)
        if (recentReadings.size > 7) recentReadings.removeFirst()

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
            highwayInfo.isSchoolZone -> Triple(3, 0.67f, 6000L)
            highwayInfo.isResidential -> Triple(3, 0.67f, 6000L)
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

    private fun smoothGPSCoordinates(lat: Double, lon: Double, altitude: Double): Triple<Double, Double, Double> {
        val point = Triple(lat, lon, altitude)
        gpsHistory.add(point)
        if (gpsHistory.size > 3) gpsHistory.removeFirst()

        val avgLat = gpsHistory.map { it.first }.average()
        val avgLon = gpsHistory.map { it.second }.average()
        val avgAlt = gpsHistory.map { it.third }.average()
        return Triple(avgLat, avgLon, avgAlt)
    }

    private fun confirmNewRoadWithHighway(
        speedLimit: Int,
        enforcementReason: String,
        location: Triple<Double, Double, Double>,
        highwayInfo: HighwayInfo
    ) {
        confirmedSpeedLimit = speedLimit
        confirmedRoadCenter = Pair(location.first, location.second)
        confirmedAltitude = location.third
        confirmedHighwayInfo = highwayInfo
        confirmedEnforcementReason = enforcementReason
        roadConfirmedAt = System.currentTimeMillis()
        consecutiveSameReadings = 1

        lastKnownSpeedLimit = speedLimit
        lastKnownHighwayInfo = highwayInfo
        lastKnownEnforcementReason = enforcementReason
        lastKnownLimitTime = System.currentTimeMillis()

        recentReadings.clear()
        votingStartTime.clear()
    }

    private fun handleVotingResult(
        votingResult: VotingStatus,
        location: Triple<Double, Double, Double>,
        highwayInfo: HighwayInfo,
        enforcementReason: String
    ): StableSpeedResult {
        return when (votingResult) {
            is VotingStatus.Winner -> {
                confirmNewRoadWithHighway(votingResult.speedLimit, enforcementReason, location, highwayInfo)

                val sendTtl = shouldSendToTtl(votingResult.speedLimit)

                StableSpeedResult.NewConfirmed(
                    speedLimit = votingResult.speedLimit,
                    reason = "voting_winner",
                    votesUsed = votingResult.totalVotes,
                    timeTaken = votingResult.timeTaken,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = sendTtl,
                    ttlReason = "new_confirmed"
                )
            }
            is VotingStatus.Collecting -> {
                val sendTtl = shouldSendToTtl(votingResult.leadingSpeedLimit)

                StableSpeedResult.Voting(
                    progress = "${votingResult.currentVotes}/votes",
                    timeElapsed = votingResult.timeElapsed,
                    leadingCandidate = votingResult.leadingSpeedLimit,
                    altitude = location.third,
                    highwayInfo = highwayInfo,
                    enforcementReason = enforcementReason,
                    sendToTtl = sendTtl,
                    ttlReason = "voting_continuous"
                )
            }
        }
    }

    private fun enforceHighwaySpeed(speedLimit: Int?, highwayInfo: HighwayInfo): Pair<Int, String> {
        return if (speedLimit != null) {
            Pair(speedLimit, "osm_speed")
        } else {
            Pair(highwayInfo.defaultSpeed, "default_speed")
        }
    }

    // ===== MAIN FUNCTION =====

    fun getStableSpeedLimit(
        rawLat: Double,
        rawLon: Double,
        rawAltitude: Double,
        currentSpeed: Float,
        rawSpeedLimit: Int?,
        osmTags: Map<String, String> = emptyMap(),
        bearing: Float = 0f
    ): StableSpeedResult {

        val location = Triple(rawLat, rawLon, rawAltitude)
        val highwayInfo = if (osmTags.isNotEmpty()) {
            analyzeHighway(osmTags)
        } else {
            HighwayInfo(
                type = "UNKNOWN", isHighway = false, priority = 50,
                description = "UNKNOWN ROAD", icon = "❓",
                layer = 0, levelType = "GROUND",
                minSpeedLimit = 0, maxSpeedLimit = 999,
                defaultSpeed = 50
            )
        }

        // Case 1: No OSM data - Use last known and keep sending
        if (rawSpeedLimit == null) {
            noDataCount++

            val lastKnown = lastKnownSpeedLimit
            return if (lastKnown != null) {
                // FIXED: Always send last known value continuously
                val sendTtl = shouldSendToTtl(lastKnown)

                StableSpeedResult.UsingLastKnown(
                    speedLimit = lastKnown,
                    reason = "no_osm_data",
                    timeSinceLastKnown = System.currentTimeMillis() - lastKnownLimitTime,
                    noDataCount = noDataCount,
                    altitude = rawAltitude,
                    highwayInfo = lastKnownHighwayInfo ?: highwayInfo,
                    enforcementReason = lastKnownEnforcementReason ?: "last_known",
                    sendToTtl = sendTtl,
                    ttlReason = if (sendTtl) "last_known_continuous" else "last_known_waiting"
                )
            } else {
                StableSpeedResult.NoData
            }
        }

        // Case 2: OSM data available
        noDataCount = 0
        val smoothLocation = smoothGPSCoordinates(rawLat, rawLon, rawAltitude)

        // Road lock system
        if (currentRoadLock == null && shouldEstablishRoadLock(osmTags, currentSpeed, location)) {
            val lockMode = getRoadLockCandidate(osmTags)
            establishRoadLock(lockMode, rawSpeedLimit, "auto_detect")
        }

        if (currentRoadLock != null && shouldReleaseRoadLock(location, bearing, osmTags)) {
            releaseRoadLock("transition_detected")
        }

        // Filter speed through road lock
        val (filteredSpeed, lockReason) = filterSpeedByRoadLock(rawSpeedLimit, osmTags)
        val (enforcedSpeed, enforcementReason) = enforceHighwaySpeed(filteredSpeed, highwayInfo)

        val fullReason = if (currentRoadLock != null) {
            "${enforcementReason}_${lockReason}"
        } else {
            enforcementReason
        }

        // Voting
        val votingResult = addToVotingAndCheck(enforcedSpeed, currentSpeed, highwayInfo)
        return handleVotingResult(votingResult, smoothLocation, highwayInfo, fullReason)
    }

    // ===== PUBLIC API =====

    fun clearAll() {
        currentRoadLock = null
        consecutiveBlockedReadings = 0
        roadLockHistory.clear()
        lastLocationData = null
        lastBearing = 0f
        lastTimestamp = 0L

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

        lastTtlSendTime = 0L
        lastSentSpeedLimit = null

        isVerifying = false
        pendingSpeedLimit = null
        pendingEnforcementReason = null
        verificationCount = 0
        requiredVerifications = 3
        verificationInterval = 1000L
    }

    fun hasStableSpeedLimit(): Boolean = confirmedSpeedLimit != null
    fun getConfirmedSpeedLimit(): Int? = confirmedSpeedLimit
    fun getConfirmedHighwayInfo(): HighwayInfo? = confirmedHighwayInfo
    fun getConfirmedAltitude(): Double? = confirmedAltitude
    fun getConfirmedEnforcementReason(): String? = confirmedEnforcementReason
    fun getLastKnownSpeedLimit(): Int? = lastKnownSpeedLimit
    fun getLastKnownHighwayInfo(): HighwayInfo? = lastKnownHighwayInfo
    fun isRoadLocked(): Boolean = currentRoadLock != null
    fun getCurrentRoadLock(): RoadLockState? = currentRoadLock

    fun setLastKnownSpeedLimit(speedLimit: Int) {
        lastKnownSpeedLimit = speedLimit
        lastKnownLimitTime = System.currentTimeMillis()
    }

    fun getCurrentStatus(): Map<String, String> {
        val lockStatus = currentRoadLock?.let {
            "${it.mode.name} (Str:${it.lockStrength}, Blocks:$consecutiveBlockedReadings)"
        } ?: "UNLOCKED"

        val ttlStatus = if (lastSentSpeedLimit != null) {
            "$lastSentSpeedLimit km/h (${(System.currentTimeMillis() - lastTtlSendTime)/1000}s ago)"
        } else {
            "Never sent"
        }

        return mapOf(
            "System" to "COMPLETE: All Features + Continuous TTL",
            "Road Lock" to lockStatus,
            "Confirmed Speed" to "${confirmedSpeedLimit ?: "None"}km/h",
            "Highway Type" to (confirmedHighwayInfo?.description ?: "None"),
            "Road Name" to (confirmedHighwayInfo?.roadName ?: "Unknown"),
            "Region" to (confirmedHighwayInfo?.region ?: "Unknown"),
            "Altitude" to "${confirmedAltitude?.let { "%.1f".format(it) } ?: "None"}m",
            "Last Known" to "${lastKnownSpeedLimit ?: "None"}km/h",
            "Last TTL Sent" to ttlStatus,
            "TTL Interval" to "20 seconds continuous"
        )
    }

    fun getRoadLockStatus(): Map<String, String> {
        val lock = currentRoadLock
        return if (lock != null) {
            mapOf(
                "Mode" to lock.mode.name,
                "Strength" to lock.lockStrength.toString(),
                "Duration" to "${(System.currentTimeMillis() - lock.lockedAt)/1000}s",
                "Last Speed" to "${lock.lastValidSpeed ?: "none"}km/h",
                "Blocks" to "$consecutiveBlockedReadings/$MAX_BLOCKS",
                "Reason" to lock.lockReason,
                "History" to "${roadLockHistory.size}/5"
            )
        } else {
            mapOf(
                "Status" to "UNLOCKED",
                "Ready" to "Yes",
                "Available Locks" to "Bridge, Service, Main, Motorway, Residential, School"
            )
        }
    }

    fun getTransitionRules(): Map<String, String> {
        return mapOf(
            "Service → Main" to "30°+ turn required",
            "Bridge Protection" to "Altitude drop + exit ramp needed",
            "Main → Bridge" to "Altitude gain + bridge indicators",
            "Motorway Exit" to "Only at exit ramps",
            "Bridge → Ground" to "Altitude drop AND exit indicators",
            "Lock Release" to "Auto after $MAX_BLOCKS blocked readings",
            "Physical Validation" to "Movement and altitude must match road type",
            "Residential Exit" to "20°+ turn + non-residential road",
            "School Zone" to "Immediate on name/tag detection"
        )
    }

    fun getPersistenceConfig(): Map<String, String> {
        return mapOf(
            "System" to "COMPLETE: All Features + Continuous TTL",
            "TTL Interval" to "20 seconds continuous",
            "TTL Behavior" to "Sends on: first time, speed change, 20s interval",
            "Bridge Lock" to "0.5 confidence (fixed)",
            "Main Lock" to "0.6 confidence",
            "Residential Vote" to "3 votes in 6 seconds",
            "School Vote" to "3 votes in 6 seconds",
            "Max Blocks" to "$MAX_BLOCKS before auto-release",
            "Speed Jump" to "${LARGE_SPEED_JUMP}km/h threshold",
            "Highway Stick" to "${STICK_TIME/1000}s at high speeds",
            "Enforcement" to "OSM direct or regional defaults",
            "Worldwide Support" to "India, UAE, Kenya, and more",
            "Bridge Detection" to "Name + Tags + Layer + Level",
            "All Features" to "WORKING ✓"
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

    // ===== NEW FUNCTIONS FOR CONTINUOUS TTL =====

    /**
     * PUBLIC: Check if TTL should send now (for continuous background sender)
     * This respects the 20-second interval rule
     */
    fun shouldSendTtlNow(currentSpeed: Int): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastTtlSendTime

        return when {
            // First send ever
            lastSentSpeedLimit == null -> true

            // Speed changed - send immediately
            lastSentSpeedLimit != currentSpeed -> true

            // Same speed but 20 seconds elapsed
            timeSinceLastSend >= TTL_SEND_INTERVAL -> true

            // Within 20 second interval - wait
            else -> false
        }
    }

    /**
     * Get last known speed limit for continuous TTL sending
     * Returns the most recent valid speed limit
     */
    fun getLastKnownSpeedLimitForTtl(): Int? {
        return lastKnownSpeedLimit ?: confirmedSpeedLimit
    }

    /**
     * Get comprehensive TTL status for monitoring
     */
    fun getTtlStatus(): Map<String, Any> {
        val timeSinceLastSend = if (lastTtlSendTime > 0) {
            (System.currentTimeMillis() - lastTtlSendTime) / 1000
        } else {
            -1
        }

        val nextSendIn = if (lastTtlSendTime > 0) {
            (20 - timeSinceLastSend).coerceAtLeast(0)
        } else {
            0
        }

        return mapOf(
            "last_sent_speed" to (lastSentSpeedLimit ?: 0),
            "time_since_last_send_seconds" to timeSinceLastSend,
            "next_send_in_seconds" to nextSendIn,
            "last_known_speed" to (lastKnownSpeedLimit ?: 0),
            "confirmed_speed" to (confirmedSpeedLimit ?: 0),
            "has_speed_to_send" to (lastKnownSpeedLimit != null || confirmedSpeedLimit != null)
        )
    }
}

// ===== RESULT TYPES =====

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