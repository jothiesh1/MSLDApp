// File: app/src/main/java/com/gpstracker/msldapp/uis/SpeedDashboard.kt

package com.gpstracker.msldapp.uis

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/**
 * Enhanced Speed Dashboard for High-Speed GPS Tracking
 * Features: Animated speedometer, speed zones, accurate readings
 */
@Composable
fun SpeedDashboard(
    currentSpeed: Float,
    speedLimit: Int? = null,
    maxSpeed: Float = 200f, // Default max speed for display
    accuracy: Float = 0f,
    modifier: Modifier = Modifier
) {
    // Animated speed value for smooth transitions
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "speed_animation"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Draw the enhanced speedometer
        Canvas(
            modifier = Modifier
                .size(280.dp)
                .padding(16.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2, canvasHeight / 2)
            val radius = canvasWidth / 2 - 20.dp.toPx()

            // Background arc
            drawArc(
                color = Color.Gray.copy(alpha = 0.2f),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 25.dp.toPx(), cap = StrokeCap.Round)
            )

            // Speed zones
            val speedPercentage = (animatedSpeed / maxSpeed).coerceIn(0f, 1f)
            val sweepAngle = 260f * speedPercentage

            // Determine color based on speed and speed limit
            val arcColor = when {
                speedLimit != null && currentSpeed > speedLimit + 10 -> Color(0xFFD32F2F) // Red - over limit
                speedLimit != null && currentSpeed > speedLimit -> Color(0xFFFF9800) // Orange - slightly over
                speedPercentage > 0.8f -> Color(0xFFFFC107) // Yellow - high speed
                speedPercentage > 0.5f -> Color(0xFF2196F3) // Blue - moderate speed
                else -> Color(0xFF4CAF50) // Green - low speed
            }

            // Main speed arc
            drawArc(
                color = arcColor,
                startAngle = 140f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 25.dp.toPx(), cap = StrokeCap.Round)
            )

            // Speed limit indicator (if available)
            speedLimit?.let { limit ->
                val limitPercentage = (limit.toFloat() / maxSpeed).coerceIn(0f, 1f)
                val limitAngle = 140f + (260f * limitPercentage)

                // Draw speed limit marker
                val markerStart = Offset(
                    center.x + (radius - 15.dp.toPx()) * cos(Math.toRadians(limitAngle.toDouble())).toFloat(),
                    center.y + (radius - 15.dp.toPx()) * sin(Math.toRadians(limitAngle.toDouble())).toFloat()
                )
                val markerEnd = Offset(
                    center.x + (radius + 15.dp.toPx()) * cos(Math.toRadians(limitAngle.toDouble())).toFloat(),
                    center.y + (radius + 15.dp.toPx()) * sin(Math.toRadians(limitAngle.toDouble())).toFloat()
                )

                drawLine(
                    color = Color.Red,
                    start = markerStart,
                    end = markerEnd,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Speed markings (every 20 km/h)
            for (speed in 0..maxSpeed.toInt() step 20) {
                val percentage = speed / maxSpeed
                val angle = 140f + (260f * percentage)
                val markStart = Offset(
                    center.x + (radius - 10.dp.toPx()) * cos(Math.toRadians(angle.toDouble())).toFloat(),
                    center.y + (radius - 10.dp.toPx()) * sin(Math.toRadians(angle.toDouble())).toFloat()
                )
                val markEnd = Offset(
                    center.x + radius * cos(Math.toRadians(angle.toDouble())).toFloat(),
                    center.y + radius * sin(Math.toRadians(angle.toDouble())).toFloat()
                )

                drawLine(
                    color = Color.Gray.copy(alpha = 0.6f),
                    start = markStart,
                    end = markEnd,
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Speed needle
            val needleAngle = 140f + (260f * speedPercentage)
            rotate(needleAngle, center) {
                drawLine(
                    color = Color.Black,
                    start = center,
                    end = Offset(center.x, center.y - radius * 0.7f),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Center circle
            drawCircle(
                color = Color.Black,
                radius = 8.dp.toPx(),
                center = center
            )
        }

        // Speed display overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = 40.dp)
        ) {
            // Current speed
            Text(
                text = "${currentSpeed.toInt()}",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "km/h",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Speed limit display
            speedLimit?.let { limit ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (currentSpeed > limit)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Limit: $limit km/h",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Accuracy indicator
            if (accuracy > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "±${accuracy.toInt()}m",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // Speed zone indicator (top-right)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        currentSpeed > 100f -> MaterialTheme.colorScheme.errorContainer
                        currentSpeed > 60f -> Color(0xFFFFF3E0) // Light orange
                        currentSpeed > 30f -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Text(
                    text = when {
                        currentSpeed > 100f -> "🏎️ HIGHWAY"
                        currentSpeed > 60f -> "🚗 FAST"
                        currentSpeed > 30f -> "🚙 MODERATE"
                        currentSpeed > 5f -> "🐌 SLOW"
                        else -> "⏸️ STOPPED"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Compact speed display for smaller spaces
 */
@Composable
fun CompactSpeedDisplay(
    currentSpeed: Float,
    speedLimit: Int? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (speedLimit != null && currentSpeed > speedLimit)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${currentSpeed.toInt()}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "km/h",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            speedLimit?.let { limit ->
                Text(
                    text = "/ $limit",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Enhanced logging function using your LogCollector system
 */
fun logSpeedDashboardData(
    speed: Float,
    speedLimit: Int?,
    accuracy: Float,
    latitude: Double,
    longitude: Double
) {
    LogCollector.addDetailedLog(
        LogCollector.LogCategory.GPS,
        "🏎️ Speed Dashboard Update",
        mapOf(
            "Current Speed" to "${String.format("%.1f", speed)} km/h",
            "Speed Limit" to (speedLimit?.toString() ?: "None"),
            "Accuracy" to "${String.format("%.1f", accuracy)}m",
            "Location" to "${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)}",
            "Status" to when {
                speedLimit != null && speed > speedLimit + 10 -> "OVER LIMIT"
                speedLimit != null && speed > speedLimit -> "SLIGHTLY OVER"
                speed > 100f -> "HIGH SPEED"
                speed > 60f -> "FAST"
                speed > 30f -> "MODERATE"
                speed > 5f -> "SLOW"
                else -> "STOPPED"
            }
        )
    )
}