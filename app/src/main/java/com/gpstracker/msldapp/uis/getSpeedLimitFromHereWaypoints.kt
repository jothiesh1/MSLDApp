package com.gpstracker.msldapp.uis

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

private const val TAG = "HereSpeedLimit"

fun getHereSpeedLimit(
    lat0: Double,
    lon0: Double,
    lat1: Double,
    lon1: Double,
    onSpeedLimitUpdate: (String) -> Unit
) {
    val apiKey = "jN79jbOxhzLclkj-h1y1YFuxH0XEGNxchXnf7aY6oaM"
    val url = "https://routematching.hereapi.com/v8/match/routelinks" +
            "?apikey=$apiKey" +
            "&waypoint0=$lat0,$lon0" +
            "&waypoint1=$lat1,$lon1" +
            "&mode=fastest;car" +
            "&routeMatch=1" +
            "&attributes=SPEED_LIMITS_FCn(*)"

    Log.d(TAG, "Request URL: $url")

    val request = Request.Builder()
        .url(url)
        .get()
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e(TAG, "API call failed: ${e.message}", e)
            onSpeedLimitUpdate("Speed Limit: Unknown")
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.body?.string()
            Log.d(TAG, "HTTP Status Code: ${response.code}")
            Log.d(TAG, "Response Body: $body")

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Unsuccessful response or null body")
                onSpeedLimitUpdate("Speed Limit: Unknown")
                return
            }

            try {
                val json = JSONObject(body)
                val attr = json.getJSONArray("routes")
                    .getJSONObject(0)
                    .getJSONArray("sections")
                    .getJSONObject(0)
                    .getJSONArray("spans")
                    .getJSONObject(0)
                    .getJSONObject("speedLimit")

                val speed = attr.optString("value", "0")
                val unit = if (attr.optString("unit", "KILOMETERS_PER_HOUR") == "MILES_PER_HOUR") "MPH" else "KMH"

                Log.i(TAG, "Extracted Speed Limit: $speed $unit")
                onSpeedLimitUpdate("$speed $unit")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing speed limit response", e)
                onSpeedLimitUpdate("Speed Limit: Unknown")
            }
        }
    })
}

fun callHereSpeedLimitFromDashboard(
    lastLat: Double,
    lastLon: Double,
    currentLat: Double,
    currentLon: Double,
    onSpeedLimit: (String) -> Unit
) {
    Log.d(TAG, "Calling HERE API with: last=($lastLat, $lastLon), current=($currentLat, $currentLon)")
    getHereSpeedLimit(
        lat0 = lastLat,
        lon0 = lastLon,
        lat1 = currentLat,
        lon1 = currentLon,
        onSpeedLimitUpdate = onSpeedLimit
    )
}
