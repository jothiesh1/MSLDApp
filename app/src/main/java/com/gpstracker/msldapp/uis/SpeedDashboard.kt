package com.gpstracker.msldapp.ui.theme

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Speed Dashboard component
 * Shows current speed with a speedometer-like visual
 */
@Composable
fun SpeedDashboard(
    currentSpeed: Float,
    maxSpeed: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Draw the speedometer arc
        Canvas(
            modifier = Modifier
                .size(250.dp)
                .padding(16.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2, canvasHeight / 2)
            val radius = canvasWidth / 2

            // Background arc (0-100%)
            drawArc(
                color = Color.LightGray.copy(alpha = 0.3f),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 30f, cap = StrokeCap.Round)
            )

            // Speed arc (0-current%)
            val speedPercentage = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
            val sweepAngle = 240f * speedPercentage

            val arcColor = when {
                speedPercentage < 0.4f -> Color(0xFF4CAF50) // Green
                speedPercentage < 0.7f -> Color(0xFFFFC107) // Yellow
                else -> Color(0xFFF44336) // Red
            }

            drawArc(
                color = arcColor,
                startAngle = 150f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 30f, cap = StrokeCap.Round)
            )
        }

        // Speed text in the center
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${currentSpeed.toInt()}",
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "km/h",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Utility function to log current location data
 */
fun logCurrentLocationDetails(latitude: Double, longitude: Double, speed: Double) {
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    Log.i("CurrentLocation", "🕒 Time: $currentTime")
    Log.i("CurrentLocation", "📍 Latitude: $latitude")
    Log.i("CurrentLocation", "📍 Longitude: $longitude")
    Log.i("CurrentLocation", "🏎️ Speed: ${"%.2f".format(speed)} km/h")
}
