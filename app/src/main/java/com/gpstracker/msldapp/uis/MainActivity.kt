package com.gpstracker.msldapp.uis

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

//import com.gpstracker.msldapp.ui.LiveHereMapWithAutoLocation
import com.gpstracker.msldapp.ui.theme.MSLDAppTheme
import com.gpstracker.msldapp.utils.AppNavigator

class MainActivity : ComponentActivity() {

    private lateinit var usbPermissionReceiver: BroadcastReceiver
    private lateinit var usbAttachReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e("CrashHandler", "Uncaught exception: ${throwable.message}", throwable)
        }

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.android.example.USB_PERMISSION") {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && context != null && device != null) {
                        Log.i("SerialTtl", "✅ USB permission granted")
                        if (SerialTtlManager.init(context)) {
                            Toast.makeText(context, "✅ TTL Initialized", Toast.LENGTH_SHORT).show()
                            SerialTtlManager.sendSpeed(10, context) // Send only once
                        } else {
                            Toast.makeText(context, "❌ TTL init failed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e("SerialTtl", "❌ USB permission denied")
                        Toast.makeText(context, "❌ USB permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        usbAttachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && context != null) {
                    Log.i("SerialTtl", "🔌 TTL device attached")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && !usbManager.hasPermission(device)) {
                        val permissionIntent = PendingIntent.getBroadcast(
                            context, 0,
                            Intent("com.android.example.USB_PERMISSION"),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager.requestPermission(device, permissionIntent)
                        Log.w("SerialTtl", "⚠️ Requested USB permission")
                    } else {
                        if (SerialTtlManager.init(context)) {
                            Toast.makeText(context, "✅ TTL Initialized", Toast.LENGTH_SHORT).show()
                            SerialTtlManager.sendSpeed(10, context) // Send only once
                        } else {
                            Toast.makeText(context, "❌ TTL init failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        registerReceiver(
            usbPermissionReceiver,
            IntentFilter("com.android.example.USB_PERMISSION"),
            RECEIVER_EXPORTED
        )

        registerReceiver(
            usbAttachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            RECEIVER_EXPORTED
        )

        setContent {
            MSLDAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbAttachReceiver)
        SerialTtlManager.close()
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        Log.d("AppNavigator", "✅ Setting navController")
        AppNavigator.setController(navController)
    }

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen()
        }
        composable("map") {
          //  LiveHereMapWithAutoLocation()
        }
    }
}



