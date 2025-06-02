package com.gpstracker.msldapp.uis
data class TrackingEntry(
    val latitude: Double,
    val longitude: Double,
    val speed: Double,
    val speedLimit: Int,
    val timestamp: Long
)
