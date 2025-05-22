package com.gpstracker.msldapp.uis



import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object HereApiManager {

    private const val TAG = "HereAPI"
    private const val CLIENT_ID = "L7tvWlgQEscYWBCO23QqYA"
    private const val CLIENT_SECRET = "K-mv-j4q-3zDTYtfrSbhxSBXar7lULggsB4a2PBuG-B60D1g-5vc2QxNjkgvStnmSMEgAMPTi-9FkMboRWsYTA"
    private const val TOKEN_URL = "https://account.api.here.com/oauth2/token"
    private const val ROUTE_MATCH_URL = "https://rme.api.here.com/v8/match/routes?attributes=SPEED_LIMITS_FC1,ROAD_GEOM_FC1"

    private var accessToken: String? = null

    fun matchRoute(gpsPoints: List<Pair<Double, Double>>) {
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
                Log.e(TAG, "Token fetch failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                if (response.isSuccessful && json != null) {
                    val accessToken = JSONObject(json).getString("access_token")
                    Log.i(TAG, "✅ Access Token Acquired")
                    onTokenReady(accessToken)
                } else {
                    Log.e(TAG, "Token error: ${response.code} ${response.message}")
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
                Log.e(TAG, "Route match failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                if (response.isSuccessful && json != null) {
                    Log.i(TAG, "✅ Route Match Success")
                    parseMatchResponse(JSONObject(json))
                } else {
                    Log.e(TAG, "❌ Match Error: ${response.code} ${response.message}")
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

                Log.i(TAG, """
                    Link ID: $linkId
                    Speed: $fromSpeed ➝ $toSpeed $unit
                """.trimIndent())
            }
        }
    }

    fun matchRouteWithCallback(
        gpsPoints: List<Pair<Double, Double>>,
        onSpeedLimitFetched: (String) -> Unit
    ) {
        if (accessToken == null) {
            fetchAccessToken { token ->
                accessToken = token
                sendRouteMatchRequest(gpsPoints, onSpeedLimitFetched)
            }
        } else {
            sendRouteMatchRequest(gpsPoints, onSpeedLimitFetched)
        }
    }

    private fun sendRouteMatchRequest(
        gpsPoints: List<Pair<Double, Double>>,
        onSpeedLimitFetched: (String) -> Unit
    ) {
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
                Log.e(TAG, "Route match failed: ${e.message}")
                onSpeedLimitFetched("Error")
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                if (response.isSuccessful && json != null) {
                    val speed = parseSpeedLimit(JSONObject(json))
                    onSpeedLimitFetched(speed)
                } else {
                    Log.e(TAG, "❌ Match Error: ${response.code} ${response.message}")
                    onSpeedLimitFetched("No Data")
                }
            }
        })
    }

    private fun parseSpeedLimit(json: JSONObject): String {
        val matches = json.optJSONArray("match") ?: return "--"
        val firstMatch = matches.optJSONObject(0) ?: return "--"
        val links = firstMatch.optJSONArray("routeLinks") ?: return "--"
        val firstLink = links.optJSONObject(0) ?: return "--"
        val attr = firstLink.optJSONObject("attributes") ?: return "--"

        val fromSpeed = attr.optInt("FROM_REF_SPEED_LIMIT", -1)
        val toSpeed = attr.optInt("TO_REF_SPEED_LIMIT", -1)
        val unit = attr.optString("UNIT", "K")

        return if (fromSpeed >= 0 || toSpeed >= 0)
            "$fromSpeed ➝ $toSpeed $unit"
        else
            "--"
    }

}
