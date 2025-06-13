package com.gpstracker.msldapp.uis

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

object LogCollector {
    private val logs = mutableStateListOf<String>()
    private val maxLogs = 500 // Keep more logs for detailed debugging
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Log categories for filtering
    enum class LogCategory {
        GPS, OSM, SPEED, PERMISSION, ERROR, INFO, DEBUG, JSON, BACKEND
    }

    data class LogEntry(
        val timestamp: Long,
        val category: LogCategory,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    )

    private val detailedLogs = mutableStateListOf<LogEntry>()

    /**
     * Add a simple log message (backward compatibility)
     */
    fun addLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val formattedTime = dateFormat.format(Date(timestamp))
        val logLine = "[$formattedTime] $message"

        logs.add(logLine)

        // Auto-categorize based on message content
        val category = categorizeMessage(message)
        detailedLogs.add(LogEntry(timestamp, category, message))

        // Keep only recent logs
        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
        if (detailedLogs.size > maxLogs) {
            detailedLogs.removeAt(0)
        }
    }

    /**
     * Add detailed log with category and additional data
     */
    fun addDetailedLog(
        category: LogCategory,
        message: String,
        details: Map<String, Any> = emptyMap()
    ) {
        val timestamp = System.currentTimeMillis()
        val formattedTime = dateFormat.format(Date(timestamp))
        val categoryIcon = getCategoryIcon(category)
        val logLine = "[$formattedTime] $categoryIcon $message"

        logs.add(logLine)
        detailedLogs.add(LogEntry(timestamp, category, message, details))

        // Add detailed info if available
        if (details.isNotEmpty()) {
            details.forEach { (key, value) ->
                logs.add("    └─ $key: $value")
            }
        }

        // Keep only recent logs
        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
        if (detailedLogs.size > maxLogs) {
            detailedLogs.removeAt(0)
        }
    }

    /**
     * Log GPS location with full details
     */
    fun logGPSLocation(location: LocationData) {
        addDetailedLog(
            LogCategory.GPS,
            "Location Update",
            mapOf(
                "Latitude" to String.format("%.7f", location.latitude),
                "Longitude" to String.format("%.7f", location.longitude),
                "Accuracy" to "${String.format("%.1f", location.accuracy)}m",
                "Speed" to "${String.format("%.1f", location.speedKmh)} km/h",
                "Altitude" to "${location.altitude.toInt()}m",
                "Bearing" to "${location.bearing.toInt()}°",
                "Provider" to location.provider,
                "Timestamp" to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.timestamp))
            )
        )
    }

    /**
     * Log OSM speed limit lookup with detailed results
     */
    fun logOSMSpeedLookup(
        lat: Double,
        lon: Double,
        result: OsmOfflineSpeedLookup.SpeedLimitInfo?
    ) {
        if (result != null) {
            addDetailedLog(
                LogCategory.OSM,
                "OSM Speed Limit Found",
                mapOf(
                    "Coordinates" to "${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}",
                    "Speed Limit" to "${result.speedLimit} ${result.unit}",
                    "Road Name" to (result.roadName ?: "Unknown"),
                    "Road Type" to (result.roadType ?: "Unknown"),
                    "Source" to result.source
                )
            )
        } else {
            addDetailedLog(
                LogCategory.OSM,
                "No OSM Speed Limit Data",
                mapOf(
                    "Coordinates" to "${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}",
                    "Reason" to "No speed limit data in OSM for this area"
                )
            )
        }
    }

    /**
     * Log JSON data operations
     */
    fun logJSONOperation(operation: String, details: Map<String, Any> = emptyMap()) {
        addDetailedLog(LogCategory.JSON, operation, details)
    }

    /**
     * Log backend system operations
     */
    fun logBackendOperation(operation: String, details: Map<String, Any> = emptyMap()) {
        addDetailedLog(LogCategory.BACKEND, operation, details)
    }

    /**
     * Log speed comparison results
     */
    fun logSpeedComparison(
        currentSpeed: Float,
        speedLimit: Int?,
        isOverLimit: Boolean
    ) {
        speedLimit?.let { limit ->
            val speedDiff = currentSpeed - limit
            addDetailedLog(
                LogCategory.SPEED,
                if (isOverLimit) "Speed Limit Exceeded" else "Speed Within Limit",
                mapOf(
                    "Current Speed" to "${String.format("%.1f", currentSpeed)} km/h",
                    "Speed Limit" to "$limit km/h",
                    "Difference" to "${String.format("%.1f", speedDiff)} km/h",
                    "Status" to if (isOverLimit) "⚠️ OVER LIMIT" else "✅ WITHIN LIMIT"
                )
            )
        }
    }

    /**
     * Log system errors with stack traces
     */
    fun logError(message: String, exception: Exception? = null) {
        val details = mutableMapOf<String, Any>(
            "Error Message" to message
        )

        exception?.let { e ->
            details["Exception Type"] = e.javaClass.simpleName
            details["Exception Message"] = e.message ?: "No message"
            details["Stack Trace"] = e.stackTrace.take(3).joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
        }

        addDetailedLog(LogCategory.ERROR, message, details)
    }

    /**
     * Get all logs (backward compatibility)
     */
    fun getLogs(): List<String> = logs.toList()

    /**
     * Get detailed logs with filtering
     */
    fun getDetailedLogs(category: LogCategory? = null): List<LogEntry> {
        return if (category != null) {
            detailedLogs.filter { it.category == category }
        } else {
            detailedLogs.toList()
        }
    }

    /**
     * Get logs by category as formatted strings
     */
    fun getLogsByCategory(category: LogCategory): List<String> {
        return detailedLogs
            .filter { it.category == category }
            .map { entry ->
                val time = dateFormat.format(Date(entry.timestamp))
                val icon = getCategoryIcon(entry.category)
                val details = if (entry.details.isNotEmpty()) {
                    "\n" + entry.details.map { "    ${it.key}: ${it.value}" }.joinToString("\n")
                } else ""
                "[$time] $icon ${entry.message}$details"
            }
    }

    /**
     * Get system statistics
     */
    fun getSystemStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val recentLogs = detailedLogs.filter { now - it.timestamp < 60000 } // Last minute

        return mapOf(
            "Total Logs" to detailedLogs.size,
            "Recent Logs (1min)" to recentLogs.size,
            "GPS Updates" to detailedLogs.count { it.category == LogCategory.GPS },
            "OSM Lookups" to detailedLogs.count { it.category == LogCategory.OSM },
            "Speed Checks" to detailedLogs.count { it.category == LogCategory.SPEED },
            "Errors" to detailedLogs.count { it.category == LogCategory.ERROR },
            "JSON Operations" to detailedLogs.count { it.category == LogCategory.JSON },
            "Backend Operations" to detailedLogs.count { it.category == LogCategory.BACKEND },
            "Uptime" to "${(now - (detailedLogs.firstOrNull()?.timestamp ?: now)) / 1000}s"
        )
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        logs.clear()
        detailedLogs.clear()
        addLog("📝 Logs cleared")
    }

    /**
     * Export logs as formatted string
     */
    fun exportLogs(): String {
        return buildString {
            appendLine("🚗 GPS Speed Limit Tracker - Log Export")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Total Entries: ${logs.size}")
            appendLine("=".repeat(50))
            appendLine()

            logs.forEach { log ->
                appendLine(log)
            }

            appendLine()
            appendLine("=".repeat(50))
            appendLine("📊 System Statistics:")
            getSystemStats().forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
    }

    // Helper functions
    private fun categorizeMessage(message: String): LogCategory {
        return when {
            message.contains("GPS", ignoreCase = true) ||
                    message.contains("Location", ignoreCase = true) -> LogCategory.GPS
            message.contains("OSM", ignoreCase = true) ||
                    message.contains("speed limit", ignoreCase = true) -> LogCategory.OSM
            message.contains("Speed:", ignoreCase = true) ||
                    message.contains("over limit", ignoreCase = true) -> LogCategory.SPEED
            message.contains("permission", ignoreCase = true) -> LogCategory.PERMISSION
            message.contains("Error", ignoreCase = true) ||
                    message.contains("Failed", ignoreCase = true) -> LogCategory.ERROR
            message.contains("JSON", ignoreCase = true) -> LogCategory.JSON
            message.contains("Backend", ignoreCase = true) ||
                    message.contains("System", ignoreCase = true) -> LogCategory.BACKEND
            else -> LogCategory.INFO
        }
    }

    private fun getCategoryIcon(category: LogCategory): String {
        return when (category) {
            LogCategory.GPS -> "📍"
            LogCategory.OSM -> "🗺️"
            LogCategory.SPEED -> "🚀"
            LogCategory.PERMISSION -> "🔐"
            LogCategory.ERROR -> "❌"
            LogCategory.INFO -> "ℹ️"
            LogCategory.DEBUG -> "🔧"
            LogCategory.JSON -> "📄"
            LogCategory.BACKEND -> "⚙️"
        }
    }

    /**
     * Legacy method for compatibility
     */
    fun log(message: String) {
        addLog(message)
    }
}