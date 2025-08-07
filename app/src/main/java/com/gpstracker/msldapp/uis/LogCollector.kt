// File: app/src/main/java/com/gpstracker/msldapp/uis/LogCollector.kt

package com.gpstracker.msldapp.uis

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe log collector for high-speed GPS tracking
 * Optimized for performance and memory efficiency
 */
object LogCollector {

    enum class LogCategory {
        GPS, OSM, BACKEND, PERMISSION, JSON, DEBUG, ERROR, INFO
    }

    // Thread-safe list for concurrent access
    private val logs = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 200 // Increased for debugging

    // Performance counters
    private var gpsUpdateCount = 0
    private var osmLookupCount = 0
    private var ttlSendCount = 0
    private var errorCount = 0

    /**
     * Add a simple log message
     */
    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMsg = "[$timestamp] $message"

        synchronized(logs) {
            logs.add(logMsg)

            // Keep only recent logs for memory efficiency
            if (logs.size > MAX_LOGS) {
                repeat(logs.size - (MAX_LOGS * 3 / 4)) {
                    if (logs.isNotEmpty()) {
                        logs.removeAt(0)
                    }
                }
            }
        }

        // Also log to Android logcat for debugging
        Log.d("MSLD_Tracker", message)
    }

    /**
     * Add detailed log with category and metadata
     */
    fun addDetailedLog(
        category: LogCategory,
        message: String,
        details: Map<String, String> = emptyMap()
    ) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val logMessage = if (details.isNotEmpty()) {
            val detailsStr = details.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            "$message [$detailsStr]"
        } else {
            message
        }

        val fullMessage = "[$timestamp] [${category.name}] $logMessage"

        synchronized(logs) {
            logs.add(fullMessage)

            // Update counters
            when (category) {
                LogCategory.GPS -> gpsUpdateCount++
                LogCategory.OSM -> osmLookupCount++
                LogCategory.BACKEND -> if (message.contains("sent")) ttlSendCount++
                LogCategory.ERROR -> errorCount++
                else -> {}
            }

            // Memory management
            if (logs.size > MAX_LOGS) {
                repeat(logs.size - (MAX_LOGS * 3 / 4)) {
                    if (logs.isNotEmpty()) {
                        logs.removeAt(0)
                    }
                }
            }
        }

        // Log to Android logcat with appropriate level
        when (category) {
            LogCategory.ERROR -> Log.e("MSLD_${category.name}", logMessage)
            LogCategory.GPS, LogCategory.OSM -> Log.d("MSLD_${category.name}", logMessage)
            else -> Log.i("MSLD_${category.name}", logMessage)
        }
    }

    /**
     * Log errors with exception details
     */
    fun logError(message: String, exception: Exception? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val errorMessage = if (exception != null) {
            "$message: ${exception.javaClass.simpleName} - ${exception.message}"
        } else {
            message
        }

        val fullMessage = "[$timestamp] [ERROR] $errorMessage"

        synchronized(logs) {
            logs.add(fullMessage)
            errorCount++

            if (logs.size > MAX_LOGS) {
                repeat(logs.size - (MAX_LOGS * 3 / 4)) {
                    if (logs.isNotEmpty()) {
                        logs.removeAt(0)
                    }
                }
            }
        }

        // Log to Android logcat as error
        Log.e("MSLD_ERROR", errorMessage, exception)
    }

    /**
     * Log GPS location updates (optimized for high frequency)
     */
    fun logGPSLocation(location: LocationData) {
        // Only log every 5th GPS update to reduce spam
        if (gpsUpdateCount % 5 == 0 || location.speedKmh > 80f) {
            addDetailedLog(
                LogCategory.GPS,
                "GPS Update #${gpsUpdateCount + 1}",
                mapOf(
                    "Speed" to "${String.format("%.1f", location.speedKmh)} km/h",
                    "Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                    "Provider" to location.provider
                )
            )
        }
        gpsUpdateCount++
    }

    /**
     * Log OSM speed limit lookups
     */
    fun logOSMSpeedLookup(lat: Double, lon: Double, result: Any?) {
        osmLookupCount++

        when (result) {
            is OsmOfflineSpeedLookup.SpeedLimitInfo -> {
                addDetailedLog(
                    LogCategory.OSM,
                    "OSM Lookup #$osmLookupCount",
                    mapOf(
                        "Location" to "${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}",
                        "Result" to "${result.speedLimit ?: "No data"} km/h",
                        "Road" to (result.roadName ?: "Unknown"),
                        "Source" to result.source
                    )
                )
            }
            else -> {
                addDetailedLog(
                    LogCategory.OSM,
                    "OSM Lookup #$osmLookupCount",
                    mapOf(
                        "Location" to "${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}",
                        "Result" to "No data"
                    )
                )
            }
        }
    }

    /**
     * Log backend operations (TTL, etc.)
     */
    fun logBackendOperation(operation: String, details: Map<String, String>) {
        addDetailedLog(LogCategory.BACKEND, operation, details)
    }

    /**
     * Get all logs as a list
     */
    fun getLogs(): List<String> {
        return logs.toList()
    }

    /**
     * Get recent logs (last N entries)
     */
    fun getRecentLogs(count: Int = 20): List<String> {
        return logs.takeLast(count)
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
            gpsUpdateCount = 0
            osmLookupCount = 0
            ttlSendCount = 0
            errorCount = 0
        }
        Log.i("MSLD_LogCollector", "Logs cleared")
    }

    /**
     * Get system statistics
     */
    fun getSystemStats(): Map<String, String> {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val memoryPercent = (usedMemory * 100 / maxMemory)

        return mapOf(
            "Total Logs" to logs.size.toString(),
            "GPS Updates" to gpsUpdateCount.toString(),
            "OSM Lookups" to osmLookupCount.toString(),
            "TTL Sends" to ttlSendCount.toString(),
            "Errors" to errorCount.toString(),
            "Memory Usage" to "${usedMemory}MB/${maxMemory}MB (${memoryPercent}%)"
        )
    }

    /**
     * Get performance summary
     */
    fun getPerformanceSummary(): String {
        val stats = getSystemStats()
        return buildString {
            appendLine("📊 PERFORMANCE SUMMARY:")
            appendLine("• GPS Updates: ${stats["GPS Updates"]}")
            appendLine("• OSM Lookups: ${stats["OSM Lookups"]}")
            appendLine("• TTL Sends: ${stats["TTL Sends"]}")
            appendLine("• Errors: ${stats["Errors"]}")
            appendLine("• Memory: ${stats["Memory Usage"]}")
            appendLine("• Log Entries: ${stats["Total Logs"]}")
        }
    }

    /**
     * Get logs by category
     */
    fun getLogsByCategory(category: LogCategory): List<String> {
        return logs.filter { it.contains("[${category.name}]") }
    }

    /**
     * Export logs as text (for sharing/debugging)
     */
    fun exportLogs(): String {
        val stats = getSystemStats()
        return buildString {
            appendLine("=== MSLD HIGH-SPEED TRACKER LOGS ===")
            appendLine("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("STATISTICS:")
            stats.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine()
            appendLine("LOGS:")
            logs.forEach { log ->
                appendLine(log)
            }
            appendLine()
            appendLine("=== END OF LOGS ===")
        }
    }
}