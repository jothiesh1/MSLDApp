package com.gpstracker.msldapp.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HereTokenService {
    private val clientId = "kchQCnuLN6tpJ60e0siy"
    private val clientSecret = "K-mv-j4q-3zDTYtfrSbhxSBXar7lULggsB4a2PBuG-B60D1g-5vc2QxNjkgvStnmSMEgAMPTi-9FkMboRWsYTA"
    private val tokenUrl = "https://account.api.here.com/oauth2/token"

    private var cachedToken: String? = null
    private var expiryTime: Long = 0

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        if (cachedToken != null && System.currentTimeMillis() < expiryTime) {
            Log.d("HereTokenService", "🔁 Returning cached HERE token")
            return@withContext cachedToken!!
        }

        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(tokenUrl)
            .post(form)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("No response from HERE")
            val json = JSONObject(body)

            cachedToken = json.getString("access_token")
            val expiresIn = json.getInt("expires_in")
            expiryTime = System.currentTimeMillis() + expiresIn * 1000L

            Log.d("HereTokenService", "✅ Token received: $cachedToken")
            return@withContext cachedToken!!
        }
    }
}
