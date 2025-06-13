package com.gpstracker.msldapp.uis
/**
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OnlineVsOfflineComparison {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class SpeedLimitComparison(
        val latitude: Double,
        val longitude: Double,
        val onlineResult: String,
        val offlineResult: String,
        val onlineRoads: List<String>,
        val offlineRoads: List<String>,
        val onlineDataAge: String,
        val offlineDataAge: String
    )


     * Compare online OSM API with offline map file for the same coordinates

    suspend fun compareSpeedLimits(context: Context, lat: Double, lon: Double): SpeedLimitComparison {
        Log.i("OSMComparison", "🔍 ===== ONLINE vs OFFLINE OSM COMPARISON START =====")
        Log.i("OSMComparison", "📍 Testing coordinates: ${"%.6f".format(lat)}, ${"%.6f".format(lon)}")

        return withContext(Dispatchers.IO) {
            // Get online result
            Log.i("OSMComparison", "🌐 Fetching ONLINE OSM data...")
            val onlineResult = getOnlineSpeedLimit(lat, lon)

            // Get offline result
            Log.i("OSMComparison", "💾 Fetching OFFLINE map data...")
            val offlineResult = OsmOfflineSpeedLookup.getSpeedLimit(context, lat, lon)

            // Get detailed road information
            Log.i("OSMComparison", "🔍 Analyzing road data differences...")
            val onlineRoads = getOnlineRoadDetails(lat, lon)
            val offlineRoads = getOfflineRoadDetails(context, lat, lon)

            val comparison = SpeedLimitComparison(
                latitude = lat,
                longitude = lon,
                onlineResult = onlineResult,
                offlineResult = offlineResult,
                onlineRoads = onlineRoads,
                offlineRoads = offlineRoads,
                onlineDataAge = "Live OSM Database",
                offlineDataAge = "Map file from assets"
            )

            logComparison(comparison)
            comparison
        }
    }

    /**
     * Get speed limit from online OSM Overpass API

    private suspend fun getOnlineSpeedLimit(lat: Double, lon: Double): String {
        return try {
            Log.d("OSMComparison", "🌐 Calling Overpass API for speed limits...")

            // Overpass API query to find roads with speed limits near coordinates
            val query = """
                [out:json][timeout:25];
                (
                  way(around:150,$lat,$lon)["highway"]["maxspeed"];
                );
                out geom;
            """.trimIndent()

            val url = "https://overpass-api.de/api/interpreter"
            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(
                    "text/plain".toMediaTypeOrNull(),
                    query
                ))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("OSMComparison", "❌ Online API error: ${response.code}")
                return "❌ Online API Error"
            }

            val jsonResponse = response.body?.string() ?: ""
            Log.d("OSMComparison", "🌐 Online API response received: ${jsonResponse.length} chars")

            parseOnlineSpeedLimit(jsonResponse, lat, lon)

        } catch (e: Exception) {
            Log.e("OSMComparison", "❌ Online API exception: ${e.message}")
            "❌ Online Error: ${e.message}"
        }
    }

    /**
     * Parse speed limit from Overpass API response
     */
    private fun parseOnlineSpeedLimit(jsonResponse: String, lat: Double, lon: Double): String {
        return try {
            val json = JSONObject(jsonResponse)
            val elements = json.getJSONArray("elements")

            Log.d("OSMComparison", "🌐 Online API found ${elements.length()} roads with speed limits")

            var closestSpeedLimit: String? = null
            var closestDistance = Double.MAX_VALUE

            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val tags = element.optJSONObject("tags")
                val maxspeed = tags?.optString("maxspeed")
                val highway = tags?.optString("highway")
                val name = tags?.optString("name", "unnamed")

                if (!maxspeed.isNullOrBlank() && maxspeed != "none") {
                    // Calculate distance to this road
                    val geometry = element.optJSONArray("geometry")
                    if (geometry != null && geometry.length() > 0) {
                        val firstPoint = geometry.getJSONObject(0)
                        val roadLat = firstPoint.getDouble("lat")
                        val roadLon = firstPoint.getDouble("lon")

                        val distance = calculateDistance(lat, lon, roadLat, roadLon)
                        Log.d("OSMComparison", "🌐 Online road: $name ($highway) maxspeed=$maxspeed at ${distance.toInt()}m")

                        if (distance < closestDistance) {
                            closestDistance = distance
                            closestSpeedLimit = maxspeed
                        }
                    }
                }
            }

            if (closestSpeedLimit != null) {
                val formatted = formatSpeedLimit(closestSpeedLimit)
                Log.i("OSMComparison", "✅ Online result: $formatted at ${closestDistance.toInt()}m")
                formatted
            } else {
                Log.w("OSMComparison", "❌ Online: No speed limits found in API response")
                "❓ No Online Speed Data"
            }

        } catch (e: Exception) {
            Log.e("OSMComparison", "❌ Error parsing online response: ${e.message}")
            "❌ Online Parse Error"
        }
    }

    /**
     * Get detailed road information from online API
     */
    private suspend fun getOnlineRoadDetails(lat: Double, lon: Double): List<String> {
        return try {
            val query = """
                [out:json][timeout:25];
                (
                  way(around:150,$lat,$lon)["highway"];
                );
                out geom;
            """.trimIndent()

            val url = "https://overpass-api.de/api/interpreter"
            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(
                    "text/plain".toMediaTypeOrNull(),
                    query
                ))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return listOf("❌ API Error")

            val jsonResponse = response.body?.string() ?: ""
            val json = JSONObject(jsonResponse)
            val elements = json.getJSONArray("elements")

            val roads = mutableListOf<String>()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val tags = element.optJSONObject("tags")
                val highway = tags?.optString("highway", "unknown")
                val name = tags?.optString("name", "unnamed")
                val maxspeed = tags?.optString("maxspeed", "no limit")

                roads.add("$name ($highway) - maxspeed: $maxspeed")
            }

            Log.i("OSMComparison", "🌐 Online found ${roads.size} total roads")
            roads

        } catch (e: Exception) {
            Log.e("OSMComparison", "❌ Error getting online road details: ${e.message}")
            listOf("❌ Error: ${e.message}")
        }
    }

    /**
     * Get detailed road information from offline map
     */
    private fun getOfflineRoadDetails(context: Context, lat: Double, lon: Double): List<String> {
        return try {
            Log.d("OSMComparison", "💾 Analyzing offline map file...")

            // This would need to be implemented in your OsmOfflineSpeedLookup class
            // For now, we'll return a placeholder
            listOf(
                "Offline road analysis would go here",
                "Need to modify OsmOfflineSpeedLookup to return road details",
                "Check LogCat for detailed offline analysis"
            )

        } catch (e: Exception) {
            Log.e("OSMComparison", "❌ Error getting offline road details: ${e.message}")
            listOf("❌ Error: ${e.message}")
        }
    }

    /**
     * Log detailed comparison results
     */
    private fun logComparison(comparison: SpeedLimitComparison) {
        Log.i("OSMComparison", "📊 ===== COMPARISON RESULTS =====")
        Log.i("OSMComparison", "📍 Location: ${comparison.latitude}, ${comparison.longitude}")
        Log.i("OSMComparison", "🌐 ONLINE result:  '${comparison.onlineResult}'")
        Log.i("OSMComparison", "💾 OFFLINE result: '${comparison.offlineResult}'")

        val match = comparison.onlineResult == comparison.offlineResult
        Log.i("OSMComparison", "🎯 Results match: $match")

        if (!match) {
            Log.w("OSMComparison", "⚠️ RESULTS DIFFER! Possible reasons:")
            Log.w("OSMComparison", "   1. Offline map file is older than online data")
            Log.w("OSMComparison", "   2. Different search algorithms")
            Log.w("OSMComparison", "   3. Different data sources")
            Log.w("OSMComparison", "   4. Map file coverage limitations")
        }

        Log.i("OSMComparison", "🌐 Online roads found: ${comparison.onlineRoads.size}")
        comparison.onlineRoads.forEach { road ->
            Log.d("OSMComparison", "   🌐 $road")
        }

        Log.i("OSMComparison", "💾 Offline roads found: ${comparison.offlineRoads.size}")
        comparison.offlineRoads.forEach { road ->
            Log.d("OSMComparison", "   💾 $road")
        }

        Log.i("OSMComparison", "📊 ===== COMPARISON END =====")
    }

    /**
     * Calculate distance between two points
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    /**
     * Format speed limit consistently
     */
    private fun formatSpeedLimit(rawSpeed: String): String {
        return when {
            rawSpeed.contains("mph", ignoreCase = true) -> {
                val mph = rawSpeed.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (mph != null) {
                    "${(mph * 1.60934).toInt()} km/h"
                } else {
                    rawSpeed
                }
            }
            rawSpeed.matches(Regex("\\d+")) -> {
                "$rawSpeed km/h"
            }
            rawSpeed.contains("kmh", ignoreCase = true) ||
                    rawSpeed.contains("km/h", ignoreCase = true) -> {
                rawSpeed
            }
            else -> {
                val number = rawSpeed.replace(Regex("[^0-9]"), "")
                if (number.isNotEmpty()) {
                    "$number km/h"
                } else {
                    rawSpeed
                }
            }
        }
    }
}

// Add this button to your DashboardScreen.kt to test the comparison
/*
Button(
    onClick = {
        currentLocation?.let { location ->
            scope.launch {
                try {
                    speedLimit = "🔍 Comparing Online vs Offline..."

                    val comparison = OnlineVsOfflineComparison.compareSpeedLimits(
                        context,
                        location.latitude,
                        location.longitude
                    )

                    val resultMessage = """
                        📍 Location: ${String.format("%.6f, %.6f", comparison.latitude, comparison.longitude)}
                        🌐 Online: ${comparison.onlineResult}
                        💾 Offline: ${comparison.offlineResult}
                        🎯 Match: ${comparison.onlineResult == comparison.offlineResult}
                    """.trimIndent()

                    speedLimit = if (comparison.onlineResult == comparison.offlineResult) {
                        "✅ Both: ${comparison.onlineResult}"
                    } else {
                        "⚠️ Online: ${comparison.onlineResult}\n💾 Offline: ${comparison.offlineResult}"
                    }

                    Toast.makeText(context, resultMessage, Toast.LENGTH_LONG).show()

                } catch (e: Exception) {
                    speedLimit = "❌ Comparison Error: ${e.message}"
                    Log.e("Dashboard", "❌ Comparison error: ${e.message}", e)
                }
            }
        }
    },
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
) {
    Text("🌐 Compare Online vs Offline OSM")
}
*/