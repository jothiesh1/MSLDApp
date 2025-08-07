package com.gpstracker.msldapp.uis

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gpstracker.msldapp.ui.theme.MSLDAppTheme
import com.gpstracker.msldapp.utils.AppNavigator
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var usbPermissionReceiver: BroadcastReceiver? = null
    private var usbAttachReceiver: BroadcastReceiver? = null
    private var usbDetachReceiver: BroadcastReceiver? = null

    // Use unique action string to avoid conflicts
    private val ACTION_USB_PERMISSION = "${packageName}.USB_PERMISSION"

    // Retry mechanism
    private var ttlInitRetryCount = 0
    private val maxTtlRetries = 3
    private val ttlRetryDelayMs = 2000L

    // Reference to OSM lookup for cleanup
    private var osmJsonSpeedLookup: OsmJsonSpeedLookup? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Better error handling for uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CrashHandler", "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            LogCollector.logError("App crashed in thread ${thread.name}", throwable as? Exception)
        }

        setupUsbReceivers()
        registerUsbReceivers()

        setContent {
            MSLDAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }

        // Initialize TTL with retry mechanism
        initializeTtlWithRetry()

        // Start memory monitoring
        startMemoryMonitoring()
    }

    // Memory monitoring function
    private fun startMemoryMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30000) // Check every 30 seconds

                val runtime = Runtime.getRuntime()
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val maxMemory = runtime.maxMemory() / 1024 / 1024
                val percentage = (usedMemory * 100 / maxMemory)

                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "Memory Status",
                    mapOf(
                        "Used" to "${usedMemory}MB",
                        "Max" to "${maxMemory}MB",
                        "Usage" to "$percentage%",
                        "Free" to "${maxMemory - usedMemory}MB"
                    )
                )

                // Warning if memory usage is high
                if (percentage > 80) {
                    LogCollector.logError("High memory usage: $percentage%")

                    // Try to clear OSM cache if available
                    osmJsonSpeedLookup?.clearCache()

                    // Force garbage collection
                    System.gc()

                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "Attempted memory cleanup due to high usage"
                    )
                }
            }
        }
    }

    private fun setupUsbReceivers() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        // USB permission receiver
        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    if (intent?.action == ACTION_USB_PERMISSION) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "USB Permission Response",
                            mapOf(
                                "Device" to (device?.deviceName ?: "Unknown"),
                                "Granted" to granted.toString()
                            )
                        )

                        if (granted && device != null) {
                            Log.i("SerialTtl", "✅ USB permission granted for ${device.deviceName}")

                            // Add delay and retry mechanism
                            Handler(Looper.getMainLooper()).postDelayed({
                                initializeTtlConnection()
                            }, 1000) // Wait 1 second before trying to connect
                        } else {
                            Log.e("SerialTtl", "❌ USB permission denied")
                            LogCollector.addDetailedLog(
                                LogCollector.LogCategory.ERROR,
                                "USB permission denied"
                            )
                            Toast.makeText(this@MainActivity, "❌ USB permission denied", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.logError("USB permission receiver error", e)
                    Log.e("SerialTtl", "Error in USB permission receiver", e)
                }
            }
        }

        // USB attach receiver
        usbAttachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }

                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "USB Device Attached",
                            mapOf(
                                "Device" to (device?.deviceName ?: "Unknown"),
                                "Product ID" to (device?.productId?.toString() ?: "Unknown"),
                                "Vendor ID" to (device?.vendorId?.toString() ?: "Unknown")
                            )
                        )

                        Log.i("SerialTtl", "🔌 USB device attached: ${device?.deviceName}")

                        if (device != null) {
                            if (!usbManager.hasPermission(device)) {
                                requestUsbPermission(device)
                            } else {
                                // Add delay before trying to connect
                                Handler(Looper.getMainLooper()).postDelayed({
                                    initializeTtlConnection()
                                }, 1500)
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.logError("USB attach receiver error", e)
                    Log.e("SerialTtl", "Error in USB attach receiver", e)
                }
            }
        }

        // USB detach receiver
        usbDetachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }

                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "USB Device Detached",
                            mapOf("Device" to (device?.deviceName ?: "Unknown"))
                        )

                        Log.i("SerialTtl", "🔌 USB device detached: ${device?.deviceName}")

                        // Clean up TTL connection
                        try {
                            SerialTtlManager.close()
                            Toast.makeText(this@MainActivity, "📱 TTL device disconnected", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LogCollector.logError("Error closing TTL on detach", e)
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.logError("USB detach receiver error", e)
                    Log.e("SerialTtl", "Error in USB detach receiver", e)
                }
            }
        }
    }

    private fun registerUsbReceivers() {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_EXPORTED
            } else {
                0
            }

            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)
            registerReceiver(usbAttachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), flags)
            registerReceiver(usbDetachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), flags)

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "USB receivers registered successfully"
            )
        } catch (e: Exception) {
            LogCollector.logError("Error registering USB receivers", e)
        }
    }

    private fun unregisterUsbReceivers() {
        try {
            usbPermissionReceiver?.let { unregisterReceiver(it) }
            usbAttachReceiver?.let { unregisterReceiver(it) }
            usbDetachReceiver?.let { unregisterReceiver(it) }

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "USB receivers unregistered"
            )
        } catch (e: Exception) {
            LogCollector.logError("Error unregistering USB receivers", e)
        }
    }

    // Improved TTL initialization with retry
    private fun initializeTtlWithRetry() {
        lifecycleScope.launch {
            repeat(maxTtlRetries) { attempt ->
                try {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "TTL initialization attempt ${attempt + 1}/$maxTtlRetries"
                    )

                    delay(1000) // Wait before attempting

                    val success = initializeTtlConnection()
                    if (success) {
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "TTL initialized successfully on attempt ${attempt + 1}"
                        )
                        return@launch
                    }

                    if (attempt < maxTtlRetries - 1) {
                        delay(ttlRetryDelayMs)
                    }
                } catch (e: Exception) {
                    LogCollector.logError("TTL init attempt ${attempt + 1} failed", e)
                    if (attempt < maxTtlRetries - 1) {
                        delay(ttlRetryDelayMs)
                    }
                }
            }

            // All attempts failed
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.ERROR,
                "TTL initialization failed after $maxTtlRetries attempts"
            )
        }
    }

    // Improved TTL connection initialization
    // Fixed TTL test in MainActivity.kt - initializeTtlConnection method

    private fun initializeTtlConnection(): Boolean {
        return try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (drivers.isEmpty()) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "No USB serial devices found"
                )
                return false
            }

            val device = drivers[0].device

            if (!usbManager.hasPermission(device)) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "USB permission required for device: ${device.deviceName}"
                )
                requestUsbPermission(device)
                return false
            }

            val result = SerialTtlManager.init(this)

            if (result) {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "TTL connection established",
                    mapOf(
                        "Device" to device.deviceName,
                        "Status" to "Connected"
                    )
                )

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "✅ TTL Connected", Toast.LENGTH_SHORT).show()
                }

                // Test connection with a simple command
                try {
                    val bytesWritten = SerialTtlManager.sendSpeed(0, this) // Send 0 as test

                    // Check the return value to confirm bytes were written
                    if (bytesWritten == 1) {
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "TTL test command sent successfully: $bytesWritten byte written"
                        )
                    } else {
                        LogCollector.addDetailedLog(
                            LogCollector.LogCategory.BACKEND,
                            "TTL test command partially successful: $bytesWritten bytes written"
                        )
                    }
                } catch (e: Exception) {
                    LogCollector.logError("TTL test command failed", e)
                }
            } else {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.ERROR,
                    "TTL initialization failed"
                )

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "❌ TTL connection failed", Toast.LENGTH_SHORT).show()
                }
            }

            result
        } catch (e: Exception) {
            LogCollector.logError("TTL connection error", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "❌ TTL error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }
    // Improved USB permission request
    private fun requestUsbPermission(device: UsbDevice) {
        try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            usbManager.requestPermission(device, permissionIntent)

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "USB permission requested",
                mapOf("Device" to device.deviceName)
            )

            Log.w("SerialTtl", "⚠️ Requested USB permission for ${device.deviceName}")
        } catch (e: Exception) {
            LogCollector.logError("Error requesting USB permission", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Clean up resources
            unregisterUsbReceivers()
            SerialTtlManager.close()

            // Clean up OSM JSON cache
            osmJsonSpeedLookup?.cleanup()

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "MainActivity destroyed, all resources cleaned"
            )
        } catch (e: Exception) {
            LogCollector.logError("Error in onDestroy", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "MainActivity resumed"
            )

            // Check TTL connection status on resume
            checkTtlConnectionStatus()

            // Only request permission if needed and not already requested recently
            tryRequestUsbPermissionIfNeeded()
        } catch (e: Exception) {
            LogCollector.logError("Error in onResume", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "MainActivity paused"
            )
        } catch (e: Exception) {
            LogCollector.logError("Error in onPause", e)
        }
    }

    // Better connection status checking
    private fun checkTtlConnectionStatus() {
        try {
            val isConnected = SerialTtlManager.isConnected

            // 🔧 FIXED: Use getSystemStats() instead of getHealthStatus()
            val healthStatus = SerialTtlManager.getHealthStatus() // This exists in SerialTtlManager

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.BACKEND,
                "TTL Connection Status Check",
                mapOf(
                    "Connected" to isConnected.toString(),
                    "Health" to healthStatus.toString()
                )
            )

            if (!isConnected) {
                // Try to reconnect after a short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    initializeTtlWithRetry()
                }, 2000)
            }
        } catch (e: Exception) {
            LogCollector.logError("Error checking TTL status", e)
        }
    }

    // Improved USB permission checking
    private fun tryRequestUsbPermissionIfNeeded() {
        try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (drivers.isNotEmpty()) {
                val device = drivers[0].device
                if (!usbManager.hasPermission(device)) {
                    LogCollector.addDetailedLog(
                        LogCollector.LogCategory.BACKEND,
                        "Re-requesting USB permission on resume",
                        mapOf("Device" to device.deviceName)
                    )

                    // Add delay to avoid immediate re-request
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestUsbPermission(device)
                    }, 1000)
                } else {
                    // Permission already granted, check if we need to reconnect
                    if (!SerialTtlManager.isConnected) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            initializeTtlConnection()
                        }, 500)
                    }
                }
            } else {
                LogCollector.addDetailedLog(
                    LogCollector.LogCategory.BACKEND,
                    "No USB serial devices found on resume"
                )
            }
        } catch (e: Exception) {
            LogCollector.logError("Error checking USB permission on resume", e)
        }
    }

    // Set OSM lookup reference for cleanup
    fun setOsmJsonSpeedLookup(lookup: OsmJsonSpeedLookup) {
        osmJsonSpeedLookup = lookup
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        try {
            Log.d("AppNavigator", "✅ Setting navController")
            AppNavigator.setController(navController)

            LogCollector.addDetailedLog(
                LogCollector.LogCategory.INFO,
                "App navigation initialized"
            )
        } catch (e: Exception) {
            LogCollector.logError("Error setting up navigation", e)
        }
    }

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen()
        }
        composable("map") {
            // LiveHereMapWithAutoLocation()
        }
    }
}