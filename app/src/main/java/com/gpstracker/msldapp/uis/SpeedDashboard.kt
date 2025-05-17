package com.gpstracker.msldapp.uis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SpeedDashboard(speedLimit: String, ttl: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🚗 Speed Limit", fontSize = 26.sp)
        Text(speedLimit, fontSize = 36.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(30.dp))
        Text("⏱ TTL Countdown", fontSize = 22.sp)
        Text(ttl, fontSize = 28.sp, color = MaterialTheme.colorScheme.secondary)
    }
}
