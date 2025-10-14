package com.gpstracker.msldapp

import android.app.Application
import android.util.Log

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.d("MyApp", "Application started")

        // Add any other app initialization here
    }
}