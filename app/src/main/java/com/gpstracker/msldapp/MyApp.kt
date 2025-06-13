package com.gpstracker.msldapp

import android.app.Application
import android.util.Log
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import java.util.Properties

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            val properties = Properties()
            assets.open("credentials.properties").use { inputStream ->
                properties.load(inputStream)
            }

            val accessKeyId = properties.getProperty("access.key.id")
            val accessKeySecret = properties.getProperty("access.key.secret")

            val options = SDKOptions(accessKeyId, accessKeySecret)
            SDKNativeEngine.makeSharedInstance(applicationContext, options)

            Log.d("HERE-SDK", "✅ SDKNativeEngine initialized")
        } catch (e: Exception) {
            Log.e("HERE-SDK", "❌ Failed to initialize HERE SDK", e)
        }
    }
}
