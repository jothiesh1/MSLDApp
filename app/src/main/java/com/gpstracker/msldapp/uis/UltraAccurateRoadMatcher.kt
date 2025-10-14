// ULTRA ACCURATE ROAD MATCHING SYSTEM
// Combines GPS Track Analysis + Enhanced JSON Data + Map Matching
// File: UltraAccurateRoadMatcher.kt

package com.gpstracker.msldapp.uis

import kotlin.math.*
import java.util.ArrayDeque

/**
 * ULTRA ACCURATE ROAD MATCHING SYSTEM
 * Combines 3 approaches for maximum accuracy:
 * 1. GPS Track Analysis - Movement pattern matching
 * 2. Enhanced JSON Data - Detailed road geometry
 * 3. Map Matching - Snap to road network
 */
class UltraAccurateRoadMatcher {

    // GPS Track Analysis (Option 1)
    private val gpsHistory = ArrayDeque<GPSPoint>()
    private val maxHistorySize = 10
    private var currentMovementVector: MovementVector? = null

    // Enhanced JSON Data (Option 2)
    data class EnhancedRoadData(
        val element: OverpassElement,
        val detailedGeometry: List<RoadSegment>,
        val roadBearing: Float,
        val speedProfile: SpeedProfile,
        val roadCharacteristics: RoadCharacteristics
    )

    // Map Matching (Option 3)
    private var lastMatchedRoad: EnhancedRoadData? = null
    private var matchConfidence = 0.0
    private val snapDistance = 15.0 // meters

    data class GPSPoint(
        val lat: Double,
        val lon: Double,
        val timestamp: Long,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float
    )

    data class MovementVector(
        val bearing: Float,
        val speed: Float,
        val consistency: Float // How consistent the movement is
    )

    data class RoadSegment(
        val startLat: Double,
        val startLon: Double,
        val endLat: Double,
        val endLon: Double,
        val bearing: Float,
        val length: Double
    )

    data class SpeedProfile(
        val expectedMinSpeed: Float,
        val expectedMaxSpeed: Float,
        val typicalSpeed: Float
    )

    data class RoadCharacteristics(
        val width: Float,
        val lanes: Int,
        val curvature: Float, // How curved the road is
        val accessibility: Float // How accessible for current vehicle type
    )

    data class MatchResult(
        val road: EnhancedRoadData,
        val confidence: Double,
        val snapPoint: Pair<Double, Double>,
        val distanceFromRoad: Double,
        val matchReason: String
    )

    /**
     * MAIN FUNCTION: Find the most accurate road match
     */
    fun findMostAccurateRoad(
        currentLat: Double,
        currentLon: Double,
        currentSpeed: Float,
        currentBearing: Float,
        accuracy: Float,
        nearbyRoads: List<OverpassElement>
    ): MatchResult? {

        // Step 1: GPS Track Analysis - Update movement pattern
        updateGPSHistory(currentLat, currentLon, currentSpeed, currentBearing, accuracy)

        // Step 2: Enhanced JSON Data - Process roads with detailed analysis
        val enhancedRoads = nearbyRoads.map { enhanceRoadData(it) }

        // Step 3: Map Matching - Find best match using all data
        return findBestMatch(currentLat, currentLon, enhancedRoads)
    }

    /**
     * OPTION 1: GPS Track Analysis
     * Analyze GPS movement pattern for road matching
     */
    fun updateGPSHistory(
        lat: Double,
        lon: Double,
        speed: Float,
        bearing: Float,
        accuracy: Float
    ) {
        val gpsPoint = GPSPoint(lat, lon, System.currentTimeMillis(), accuracy, speed, bearing)

        gpsHistory.addLast(gpsPoint)
        if (gpsHistory.size > maxHistorySize) {
            gpsHistory.removeFirst()
        }

        // Calculate movement vector from GPS history
        currentMovementVector = calculateMovementVector()
    }

    private fun calculateMovementVector(): MovementVector? {
        if (gpsHistory.size < 3) return null

        val historyList = gpsHistory.toList()
        val recentPoints = historyList.takeLast(5)

        // Calculate average bearing and speed
        val bearings = mutableListOf<Float>()
        val speeds = mutableListOf<Float>()

        for (i in 1 until recentPoints.size) {
            val prev = recentPoints[i-1]
            val curr = recentPoints[i]

            // Calculate bearing between consecutive points
            val bearing = calculateBearing(prev.lat, prev.lon, curr.lat, curr.lon)
            bearings.add(bearing)
            speeds.add(curr.speed)
        }

        val avgBearing = averageBearing(bearings)
        val avgSpeed = speeds.average().toFloat()

        // Calculate consistency (how stable is the movement)
        val bearingVariance = bearings.map { abs(normalizeAngle(it - avgBearing)) }.average()
        val consistency = (90f - bearingVariance.toFloat()) / 90f // 0-1 scale

        return MovementVector(avgBearing, avgSpeed, consistency.coerceIn(0f, 1f))
    }

    /**
     * OPTION 2: Enhanced JSON Data
     * Process road with detailed geometry and characteristics
     */
    private fun enhanceRoadData(element: OverpassElement): EnhancedRoadData {
        val geometry = element.geometry ?: emptyList()

        // Create detailed road segments
        val segments = mutableListOf<RoadSegment>()
        for (i in 0 until geometry.size - 1) {
            val start = geometry[i]
            val end = geometry[i + 1]

            val bearing = calculateBearing(start.lat, start.lon, end.lat, end.lon)
            val length = calculateDistance(start.lat, start.lon, end.lat, end.lon)

            segments.add(RoadSegment(start.lat, start.lon, end.lat, end.lon, bearing, length))
        }

        // Calculate overall road bearing (weighted average)
        val roadBearing = if (segments.isNotEmpty()) {
            val weightedBearings = segments.map { it.bearing * it.length.toFloat() }
            val totalLength = segments.sumOf { it.length }.toFloat()
            weightedBearings.sum() / totalLength
        } else 0f

        // Create speed profile based on road type
        val speedProfile = createSpeedProfile(element)

        // Analyze road characteristics
        val characteristics = analyzeRoadCharacteristics(element, segments)

        return EnhancedRoadData(element, segments, roadBearing, speedProfile, characteristics)
    }

    private fun createSpeedProfile(element: OverpassElement): SpeedProfile {
        val highway = element.tags?.get("highway") ?: "unknown"

        return when (highway) {
            "motorway" -> SpeedProfile(60f, 140f, 100f)
            "trunk" -> SpeedProfile(50f, 120f, 80f)
            "primary" -> SpeedProfile(30f, 80f, 60f)
            "secondary" -> SpeedProfile(20f, 60f, 40f)
            "tertiary" -> SpeedProfile(15f, 50f, 30f)
            "residential" -> SpeedProfile(5f, 40f, 25f)
            "service" -> SpeedProfile(5f, 30f, 20f)
            else -> SpeedProfile(10f, 50f, 30f)
        }
    }

    private fun analyzeRoadCharacteristics(
        element: OverpassElement,
        segments: List<RoadSegment>
    ): RoadCharacteristics {
        val tags = element.tags ?: emptyMap()

        // Estimate road width
        val lanes = tags["lanes"]?.toIntOrNull() ?: when (tags["highway"]) {
            "motorway" -> 4
            "trunk" -> 3
            "primary" -> 2
            else -> 1
        }
        val width = lanes * 3.5f // Approximate lane width

        // Calculate curvature from segments
        val curvature = if (segments.size > 2) {
            val bearingChanges = mutableListOf<Float>()
            for (i in 1 until segments.size) {
                val change = abs(normalizeAngle(segments[i].bearing - segments[i-1].bearing))
                bearingChanges.add(change)
            }
            bearingChanges.average().toFloat()
        } else 0f

        // Accessibility based on road type
        val accessibility = when (tags["highway"]) {
            "motorway", "trunk" -> 1.0f
            "primary", "secondary" -> 0.9f
            "tertiary" -> 0.8f
            "residential" -> 0.7f
            "service" -> 0.6f
            else -> 0.5f
        }

        return RoadCharacteristics(width, lanes, curvature, accessibility)
    }

    /**
     * OPTION 3: Map Matching
     * Snap GPS position to most likely road
     */
    private fun findBestMatch(
        lat: Double,
        lon: Double,
        enhancedRoads: List<EnhancedRoadData>
    ): MatchResult? {

        if (enhancedRoads.isEmpty()) return null

        val candidates = enhancedRoads.mapNotNull { road ->
            val snapResult = snapToRoad(lat, lon, road)
            if (snapResult != null && snapResult.second <= snapDistance) {
                val confidence = calculateMatchConfidence(lat, lon, road, snapResult)
                MatchResult(
                    road = road,
                    confidence = confidence,
                    snapPoint = snapResult.first,
                    distanceFromRoad = snapResult.second,
                    matchReason = getMatchReason(confidence)
                )
            } else null
        }

        // Return highest confidence match
        val bestMatch = candidates.maxByOrNull { it.confidence }

        // Update tracking
        if (bestMatch != null && bestMatch.confidence > 0.7) {
            lastMatchedRoad = bestMatch.road
            matchConfidence = bestMatch.confidence
        }

        return bestMatch
    }

    /**
     * Snap GPS point to nearest point on road
     */
    private fun snapToRoad(
        lat: Double,
        lon: Double,
        road: EnhancedRoadData
    ): Pair<Pair<Double, Double>, Double>? {

        var closestPoint: Pair<Double, Double>? = null
        var minDistance = Double.MAX_VALUE

        // Check each segment
        for (segment in road.detailedGeometry) {
            val snapPoint = snapToSegment(lat, lon, segment)
            val distance = calculateDistance(lat, lon, snapPoint.first, snapPoint.second)

            if (distance < minDistance) {
                minDistance = distance
                closestPoint = snapPoint
            }
        }

        return if (closestPoint != null) {
            Pair(closestPoint, minDistance)
        } else null
    }

    /**
     * Snap point to road segment
     */
    private fun snapToSegment(
        lat: Double,
        lon: Double,
        segment: RoadSegment
    ): Pair<Double, Double> {

        // Vector from start to end of segment
        val segmentLat = segment.endLat - segment.startLat
        val segmentLon = segment.endLon - segment.startLon

        // Vector from start to point
        val pointLat = lat - segment.startLat
        val pointLon = lon - segment.startLon

        // Project point onto segment
        val segmentLengthSquared = segmentLat * segmentLat + segmentLon * segmentLon

        if (segmentLengthSquared == 0.0) {
            // Segment is a point
            return Pair(segment.startLat, segment.startLon)
        }

        val t = ((pointLat * segmentLat + pointLon * segmentLon) / segmentLengthSquared)
            .coerceIn(0.0, 1.0)

        val projectedLat = segment.startLat + t * segmentLat
        val projectedLon = segment.startLon + t * segmentLon

        return Pair(projectedLat, projectedLon)
    }

    /**
     * Calculate match confidence using all available data
     */
    private fun calculateMatchConfidence(
        lat: Double,
        lon: Double,
        road: EnhancedRoadData,
        snapResult: Pair<Pair<Double, Double>, Double>
    ): Double {

        var confidence = 0.0
        val maxScore = 100.0

        // 1. Distance score (40 points) - closer is better
        val distanceScore = maxOf(0.0, 40.0 * (1.0 - snapResult.second / snapDistance))
        confidence += distanceScore

        // 2. Movement direction score (30 points)
        currentMovementVector?.let { movement ->
            val bearingDiff = abs(normalizeAngle(movement.bearing - road.roadBearing))
            val directionScore = maxOf(0.0, 30.0 * (1.0 - bearingDiff / 90.0)) * movement.consistency
            confidence += directionScore
        }

        // 3. Speed compatibility score (20 points)
        currentMovementVector?.let { movement ->
            val speedProfile = road.speedProfile
            val speedCompatibility = when {
                movement.speed in speedProfile.expectedMinSpeed..speedProfile.expectedMaxSpeed -> 1.0
                movement.speed < speedProfile.expectedMinSpeed -> {
                    maxOf(0.0, 1.0 - (speedProfile.expectedMinSpeed - movement.speed) / speedProfile.expectedMinSpeed)
                }
                else -> {
                    maxOf(0.0, 1.0 - (movement.speed - speedProfile.expectedMaxSpeed) / speedProfile.expectedMaxSpeed)
                }
            }
            confidence += 20.0 * speedCompatibility
        } ?: run {
            confidence += 10.0 // Partial score if no movement data
        }

        // 4. Road characteristics score (10 points)
        val characteristicsScore = 10.0 * road.roadCharacteristics.accessibility
        confidence += characteristicsScore

        // 5. Continuity bonus - prefer roads we were already on
        if (lastMatchedRoad?.element?.id == road.element.id && matchConfidence > 0.6) {
            confidence += 15.0 // Bonus for staying on same road
        }

        return (confidence / maxScore).coerceIn(0.0, 1.0)
    }

    private fun getMatchReason(confidence: Double): String {
        return when {
            confidence > 0.9 -> "HIGH_CONFIDENCE_MATCH"
            confidence > 0.7 -> "GOOD_MATCH"
            confidence > 0.5 -> "MODERATE_MATCH"
            confidence > 0.3 -> "LOW_CONFIDENCE"
            else -> "POOR_MATCH"
        }
    }

    /**
     * Utility functions
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360
        return bearing.toFloat()
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat/2) * sin(dLat/2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon/2) * sin(dLon/2)

        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return R * c
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        if (normalized > 180f) normalized -= 360f
        return abs(normalized)
    }

    private fun averageBearing(bearings: List<Float>): Float {
        if (bearings.isEmpty()) return 0f

        var sinSum = 0.0
        var cosSum = 0.0

        bearings.forEach { bearing ->
            val radians = Math.toRadians(bearing.toDouble())
            sinSum += sin(radians)
            cosSum += cos(radians)
        }

        val avgRadians = atan2(sinSum, cosSum)
        var avgDegrees = Math.toDegrees(avgRadians).toFloat()

        if (avgDegrees < 0) avgDegrees += 360f
        return avgDegrees
    }

    /**
     * Get detailed matching information for debugging
     */
    fun getMatchingDetails(): Map<String, String> {
        return mapOf(
            "GPS_History_Points" to gpsHistory.size.toString(),
            "Current_Movement_Vector" to (currentMovementVector?.let {
                "Bearing: ${it.bearing.toInt()}°, Speed: ${it.speed}km/h, Consistency: ${(it.consistency * 100).toInt()}%"
            } ?: "None"),
            "Last_Matched_Road" to (lastMatchedRoad?.element?.tags?.get("name") ?: "None"),
            "Match_Confidence" to "${(matchConfidence * 100).toInt()}%",
            "Snap_Distance" to "${snapDistance}m"
        )
    }

    /**
     * Clear history for fresh start
     */
    fun reset() {
        gpsHistory.clear()
        currentMovementVector = null
        lastMatchedRoad = null
        matchConfidence = 0.0
    }
}