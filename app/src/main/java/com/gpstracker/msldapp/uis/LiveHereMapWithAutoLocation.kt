package com.gpstracker.msldapp.uis

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@Composable
fun LiveHereMapWithAutoLocation() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!hasLocationPermission(context)) {
            Log.e("HERE", "❌ Location permission not granted.")
            return@LaunchedEffect
        }

        getLastKnownLocation(context) { location ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val gpsTrace = listOf(lat to lon)
                Log.i("HERE", "📍 Current location: $lat, $lon")
                matchRoute(gpsTrace)
            } else {
                Log.e("HERE", "⚠️ Unable to fetch GPS location.")
            }
        }
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun getLastKnownLocation(context: android.content.Context, callback: (Location?) -> Unit) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation
        .addOnSuccessListener { location -> callback(location) }
        .addOnFailureListener {
            Log.e("HERE", "❌ Failed to get location: ${it.message}")
            callback(null)
        }
}

// === HERE API OAuth & Route Matching ===

private const val CLIENT_ID = "L7tvWlgQEscYWBCO23QqYA"
private const val CLIENT_SECRET = "K-mv-j4q-3zDTYtfrSbhxSBXar7lULggsB4a2PBuG-B60D1g-5vc2QxNjkgvStnmSMEgAMPTi-9FkMboRWsYTA"
private const val TOKEN_URL = "https://account.api.here.com/oauth2/token"
private const val ROUTE_MATCH_URL = "https://rme.api.here.com/v8/match/routes?attributes=SPEED_LIMITS_FC1,ROAD_GEOM_FC1"

private var accessToken: String? = null

private fun matchRoute(gpsPoints: List<Pair<Double, Double>>) {
    if (accessToken == null) {
        fetchAccessToken { token ->
            accessToken = token
            sendRouteMatchRequest(gpsPoints)
        }
    } else {
        sendRouteMatchRequest(gpsPoints)
    }
}

private fun fetchAccessToken(onTokenReady: (String) -> Unit) {
    val auth = "$CLIENT_ID:$CLIENT_SECRET"
    val encodedAuth = Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)

    val body = "grant_type=client_credentials"
        .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(TOKEN_URL)
        .addHeader("Authorization", "Basic $encodedAuth")
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .post(body)
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("HereAPI", "❌ Token fetch failed: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            val json = response.body?.string()
            if (response.isSuccessful && json != null) {
                val accessToken = JSONObject(json).getString("access_token")
                Log.i("HereAPI", "✅ Access Token Acquired")
                onTokenReady(accessToken)
            } else {
                Log.e("HereAPI", "Token error: ${response.code} ${response.message}")
            }
        }
    })
}

private fun sendRouteMatchRequest(gpsPoints: List<Pair<Double, Double>>) {
    val traceArray = JSONArray()
    gpsPoints.forEach { (lat, lon) ->
        traceArray.put(JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
        })
    }

    val jsonBody = JSONObject().put("trace", traceArray).toString()
        .toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(ROUTE_MATCH_URL)
        .addHeader("Authorization", "Bearer $accessToken")
        .post(jsonBody)
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("HereAPI", "❌ Route match failed: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            val json = response.body?.string()
            if (response.isSuccessful && json != null) {
                Log.i("HereAPI", "✅ Route Match Success")
                parseMatchResponse(JSONObject(json))
            } else {
                Log.e("HereAPI", "❌ Match Error: ${response.code} ${response.message}")
            }
        }
    })
}

private fun parseMatchResponse(json: JSONObject) {
    val matches = json.optJSONArray("match") ?: return
    for (i in 0 until matches.length()) {
        val matchObj = matches.getJSONObject(i)
        val links = matchObj.optJSONArray("routeLinks") ?: continue
        for (j in 0 until links.length()) {
            val link = links.getJSONObject(j)
            val linkId = link.optString("linkId")
            val attr = link.optJSONObject("attributes")
            val fromSpeed = attr?.optInt("FROM_REF_SPEED_LIMIT")
            val toSpeed = attr?.optInt("TO_REF_SPEED_LIMIT")
            val unit = attr?.optString("UNIT")

            Log.i("HereAPI", """
                🚦 Link ID: $linkId
                📏 Speed: $fromSpeed ➝ $toSpeed $unit
            """.trimIndent())
        }
    }
}
