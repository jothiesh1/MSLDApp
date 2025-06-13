// ========================================
// FILE 1: SerialTtlManager.kt
// ========================================

package com.gpstracker.msldapp.uis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object SerialTtlManager {
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val ttlLogs = mutableStateListOf<String>()
    var readCallback: (String) -> Unit = {}

    // 🔍 Connection status tracking
    var isConnected: Boolean = false
        private set

    // 🔍 Send attempt counter for debugging
    private var sendAttempts = 0
    private var successfulSends = 0

    // USB permission handling
    private const val ACTION_USB_PERMISSION = "com.gpstracker.msldapp.USB_PERMISSION"
    private var context: Context? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.i("SerialTtl", "✅ USB permission granted")
                            continueInit(context)
                        }
                    } else {
                        Log.d("SerialTtl", "❌ USB permission denied")
                        Toast.makeText(context, "❌ USB permission denied", Toast.LENGTH_SHORT).show()
                        isConnected = false
                        addLog("❌ USB permission denied")
                    }
                }
            }
        }
    }

    fun init(context: Context): Boolean {
        Log.i("SerialTtl", "🔍 ===== TTL INITIALIZATION START =====")
        this.context = context
        isConnected = false

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        Log.i("SerialTtl", "🔍 Scanning for TTL USB devices...")
        addLog("🔍 Scanning for TTL USB devices...")

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.i("SerialTtl", "🔍 Found ${drivers.size} USB drivers")

        if (drivers.isEmpty()) {
            Log.e("SerialTtl", "❌ No USB serial drivers found")
            Toast.makeText(context, "❌ No TTL device found", Toast.LENGTH_SHORT).show()
            addLog("❌ No USB serial drivers found")
            return false
        }

        val driver = drivers[0]
        val device = driver.device

        Log.i("SerialTtl", "🔍 Found USB device: ${device.deviceName}")
        Log.i("SerialTtl", "🔍 Device VID: ${device.vendorId}, PID: ${device.productId}")
        addLog("🔍 Found USB device: ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId})")

        if (!usbManager.hasPermission(device)) {
            Log.w("SerialTtl", "⚠️ USB permission required, requesting...")

            // Register receiver and request permission
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            try {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } catch (e: Exception) {
                // Fallback for older Android versions
                context.registerReceiver(usbReceiver, filter)
            }

            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)

            Log.w("SerialTtl", "⚠️ Waiting for USB permission...")
            Toast.makeText(context, "⚠️ Requesting USB permission...", Toast.LENGTH_SHORT).show()
            addLog("⚠️ Waiting for USB permission...")
            return false
        }

        return continueInit(context)
    }

    private fun continueInit(context: Context): Boolean {
        Log.i("SerialTtl", "🔍 Continuing TTL initialization...")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            val errorMsg = "❌ No drivers found during continue init"
            Log.e("SerialTtl", errorMsg)
            addLog(errorMsg)
            return false
        }

        val driver = drivers[0]
        val device = driver.device

        Log.i("SerialTtl", "🔍 Opening USB device connection...")
        val connection = usbManager.openDevice(device)

        if (connection == null) {
            val errorMsg = "❌ Failed to open USB device connection"
            Log.e("SerialTtl", errorMsg)
            Toast.makeText(context, "❌ Failed to open TTL connection", Toast.LENGTH_SHORT).show()
            addLog(errorMsg)
            isConnected = false
            return false
        }

        Log.i("SerialTtl", "🔍 Getting serial port...")
        port = driver.ports.firstOrNull()

        if (port == null) {
            val errorMsg = "❌ No ports found on the TTL device"
            Log.e("SerialTtl", errorMsg)
            Toast.makeText(context, "❌ No TTL port available", Toast.LENGTH_SHORT).show()
            addLog(errorMsg)
            isConnected = false
            return false
        }

        return try {
            Log.i("SerialTtl", "🔍 Configuring serial port...")
            port?.apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                setDTR(true)
                setRTS(true)
            }

            isConnected = true
            Log.i("SerialTtl", "✅ TTL Port opened successfully")
            Toast.makeText(context, "✅ TTL Connected", Toast.LENGTH_SHORT).show()
            addLog("✅ TTL Connected - Port ready for communication")

            // Send test message to verify connection
            Log.i("SerialTtl", "🔍 Sending test message...")
            sendSpeed(54, context) // Send visible test message

            // Start reading data from TTL
            startReading()

            Log.i("SerialTtl", "🔍 ===== TTL INITIALIZATION COMPLETE =====")
            true

        } catch (e: Exception) {
            isConnected = false
            val errorMsg = "❌ Error opening TTL port: ${e.message}"
            Log.e("SerialTtl", errorMsg, e)
            Toast.makeText(context, "❌ TTL open error: ${e.message}", Toast.LENGTH_LONG).show()
            addLog("$errorMsg - Stack: ${e.stackTraceToString()}")
            false
        }
    }

    // 🔍 Enhanced sendSpeed with comprehensive debugging
    fun sendSpeed(speed: Int, context: Context? = null) {
        sendAttempts++

        try {
            // Log start of sending process
            Log.d("SerialTtl", "🔍 ===== SEND SPEED START =====")
            Log.d("SerialTtl", "🔍 Send attempt #$sendAttempts")
            Log.d("SerialTtl", "🔍 Input speed: $speed")

            // Validate speed range
            if (speed < 0 || speed > 255) {
                throw IllegalArgumentException("Speed must be between 0 and 255, got: $speed")
            }

            // Check connection status
            if (!isConnected || port == null) {
                throw IllegalStateException("TTL device not connected")
            }

            // Prepare data packet and send it
            val packet = byteArrayOf(speed.toByte())
            Log.d("SerialTtl", "🔍 Sending byte: 0x${String.format("%02X", speed)} (${speed.toByte()})")

            val bytesWritten = port?.write(packet, 1000) ?: 0
            if (bytesWritten != 1) {
                throw IOException("Expected to write 1 byte, actually wrote $bytesWritten")
            }

            successfulSends++
            val logMsg = "📤 TTL SUCCESS #$successfulSends: HEX=0x${String.format("%02X", speed)} DEC=$speed"
            Log.i("SerialTtl", logMsg)

            // Send confirmation to UI
            context?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, "✅ TTL Sent: $speed", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            // Handle errors
            val errorMsg = "❌ TTL SEND FAILED (attempt #$sendAttempts): ${e.message}"
            Log.e("SerialTtl", errorMsg, e)
            context?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, "❌ TTL Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    fun startReading() {
        if (port == null) {
            val errorMsg = "❌ Cannot start reading: port is null"
            Log.e("SerialTtl", errorMsg)
            addLog(errorMsg)
            return
        }

        try {
            Log.i("SerialTtl", "🔍 Starting IO Manager...")

            ioManager = SerialInputOutputManager(port!!, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val output = data.joinToString(" ") { "0x${String.format("%02X", it)} (${it.toUByte()})" }
                    Log.d("SerialTtl", "📥 TTL Read: $output")
                    addLog("📥 Read: $output")
                    readCallback(output)
                }

                override fun onRunError(e: Exception) {
                    val errorMsg = "❌ IO Manager error: ${e.message}"
                    Log.e("SerialTtl", errorMsg, e)
                    addLog(errorMsg)
                    isConnected = false // Mark as disconnected on read error
                }
            })

            executor.submit {
                try {
                    ioManager?.start()
                    Log.i("SerialTtl", "✅ IO Manager started successfully")
                } catch (e: Exception) {
                    Log.e("SerialTtl", "❌ IO Manager start error: ${e.message}", e)
                    addLog("❌ IO Manager start error: ${e.message}")
                }
            }

            addLog("✅ TTL IO Manager started")

        } catch (e: Exception) {
            val errorMsg = "❌ Failed to start IO Manager: ${e.message}"
            Log.e("SerialTtl", errorMsg, e)
            addLog("$errorMsg - Stack: ${e.stackTraceToString()}")
            isConnected = false
        }
    }

    fun close() {
        try {
            Log.i("SerialTtl", "🔍 Closing TTL connection...")

            isConnected = false

            // Stop IO Manager
            ioManager?.stop()
            ioManager = null

            // Close port
            port?.close()
            port = null

            // Unregister receiver
            context?.let {
                try {
                    it.unregisterReceiver(usbReceiver)
                    Log.d("SerialTtl", "🔍 USB receiver unregistered")
                } catch (e: Exception) {
                    Log.d("SerialTtl", "🔍 Receiver not registered or already unregistered: ${e.message}")
                }
            }

            val closeMsg = "🔌 TTL connection closed - Total sends: $successfulSends/$sendAttempts"
            Log.d("SerialTtl", closeMsg)
            addLog(closeMsg)

        } catch (e: Exception) {
            val errorMsg = "❌ Failed to close TTL: ${e.message}"
            Log.e("SerialTtl", errorMsg, e)
            addLog("$errorMsg - Stack: ${e.stackTraceToString()}")
        }
    }

    // Debug status function
    fun getDebugStatus(): String {
        return """
            |🔍 TTL Debug Status:
            |• Connected: $isConnected
            |• Port: ${if (port != null) "Available" else "NULL"}
            |• Send Attempts: $sendAttempts
            |• Successful Sends: $successfulSends
            |• Success Rate: ${if (sendAttempts > 0) "${(successfulSends * 100 / sendAttempts)}%" else "N/A"}
            |• Total Logs: ${ttlLogs.size}
        """.trimMargin()
    }

    fun getCacheStats(): String = "TTL sends: $successfulSends/$sendAttempts (${if (sendAttempts > 0) "${(successfulSends * 100 / sendAttempts)}%" else "0%"})"

    private fun addLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val logMsg = "$timestamp ➤ $message"
        Log.d("SerialTtl", logMsg)
        ttlLogs.add(logMsg)

        // Prevent memory leaks by limiting log size
        if (ttlLogs.size > 1000) {
            ttlLogs.removeRange(0, 100)
            Log.d("SerialTtl", "🔍 TTL logs trimmed to prevent memory issues")
        }
    }
}

// 🔍 DEBUGGING: Enhanced cache section for your composable
/*
Add this enhanced logging to your cache hit section:

if (cachedLimit != null) {
    val cleanCachedSpeed = cachedLimit.speedLimit.replace(Regex("[^0-9]"), "")
    Log.d("CacheDebug", "🔍 Cache hit - Original: '${cachedLimit.speedLimit}', Cleaned: '$cleanCachedSpeed'")

    if (cleanCachedSpeed.isNotEmpty() && cleanCachedSpeed != "0") {
        // ✅ Valid cached speed found
        speedLimitText.value = "📋 ${cachedLimit.speedLimit}"
        lastKnownValidSpeed.value = cleanCachedSpeed
        lastKnownValidSource.value = "Cache"
        lastSpeedFound.value = true
        currentCheckInterval.value = 20000L
        cacheHits.value++

        // 🔍 ENHANCED: Send cached speed limit to TTL with debugging
        val speedValue = cleanCachedSpeed.toIntOrNull()
        Log.d("CacheDebug", "🔍 Converted speed value: $speedValue")
        Log.d("CacheDebug", "🔍 TTL Manager status: ${SerialTtlManager.getDebugStatus()}")

        if (speedValue != null && speedValue > 0) {
            Log.d("CacheDebug", "🔍 About to call SerialTtlManager.sendSpeed($speedValue)")
            SerialTtlManager.sendSpeed(speedValue, context)
            Log.d("CacheDebug", "🔍 SerialTtlManager.sendSpeed() call completed")
        } else {
            Log.w("CacheDebug", "❌ Invalid speed value for TTL: speedValue=$speedValue, original='$cleanCachedSpeed'")
        }

        Log.i("CACHE", "✅ Cache hit: ${cachedLimit.speedLimit}")
        Toast.makeText(context, "✅ Speed limit found: $cleanCachedSpeed km/h (Cache) - checking every 20s", Toast.LENGTH_SHORT).show()
    }
    // ... rest of cache logic
}
*/
/*
package com.gpstracker.msldapp.uis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object SerialTtlManager {
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val ttlLogs = mutableStateListOf<String>()
    var readCallback: (String) -> Unit = {}

    fun init(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        Log.i("SerialTtl", "🔍 Scanning for TTL USB devices...")
        Toast.makeText(context, "🔍 Scanning TTL device...", Toast.LENGTH_SHORT).show()

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Log.e("SerialTtl", "❌ No USB serial drivers found")
            Toast.makeText(context, "❌ No TTL device found", Toast.LENGTH_SHORT).show()
            return false
        }

        val driver = drivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent("com.android.example.USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            Log.w("SerialTtl", "⚠️ Waiting for USB permission...")
            Toast.makeText(context, "⚠️ Waiting for USB permission", Toast.LENGTH_SHORT).show()
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e("SerialTtl", "❌ Failed to open USB device connection")
            Toast.makeText(context, "❌ Failed to open TTL connection", Toast.LENGTH_SHORT).show()
            return false
        }

        port = driver.ports.firstOrNull()
        if (port == null) {
            Log.e("SerialTtl", "❌ No ports found on the TTL device")
            Toast.makeText(context, "❌ No TTL port available", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            port?.apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                setDTR(true)
                setRTS(true)
            }
            Log.i("SerialTtl", "✅ TTL Port opened successfully")
            Toast.makeText(context, "✅ TTL Connected", Toast.LENGTH_SHORT).show()
            addLog("✅ TTL Connected")

            sendSpeed(5, context) // Send visible message
            startReading()
            true
        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Error opening TTL port: ${e.message}", e)
            Toast.makeText(context, "❌ TTL open error: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun sendSpeed(speed: Int, context: Context? = null) {
        try {
            if (speed < 0 || speed > 255) {
                throw IllegalArgumentException("Speed must be between 0 and 255")
            }
            val packet = byteArrayOf(speed.toByte()) // Send raw hex byte
            port?.write(packet, 1000)

            val logMsg = "📤 TTL Sent HEX Byte: 0x${String.format("%02X", speed)} (DEC: $speed)"
            Log.d("SerialTtl", logMsg)
            addLog(logMsg)

            context?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, logMsg, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            val errorMsg = "❌ Failed to send TTL: ${e.message}"
            Log.e("SerialTtl", errorMsg, e)
            addLog(errorMsg)
            context?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startReading() {
        if (port == null) {
            Log.e("SerialTtl", "❌ Cannot start reading: port is null")
            return
        }

        try {
            ioManager = SerialInputOutputManager(port!!, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val output = data.joinToString(" ") { it.toUByte().toString() }
                    Log.d("SerialTtl", "📥 TTL Read: $output")
                    addLog("📥 Read: $output")
                    readCallback(output)
                }

                override fun onRunError(e: Exception) {
                    Log.e("SerialTtl", "❌ Read error: ${e.message}", e)
                    addLog("❌ Read error: ${e.message}")
                }
            })

            executor.submit {
                ioManager?.start()
            }

            Log.i("SerialTtl", "✅ TTL IO Manager started")
            addLog("✅ TTL IO Manager started")

        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Failed to start IO Manager: ${e.message}", e)
            addLog("❌ IO Manager start failed: ${e.message}")
        }
    }

    fun close() {
        try {
            ioManager?.stop()
            ioManager = null
            port?.close()
            port = null
            Log.d("SerialTtl", "🔌 TTL connection closed")
            addLog("🔌 TTL connection closed")
        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Failed to close TTL: ${e.message}", e)
            addLog("❌ TTL close failed: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val logMsg = "${System.currentTimeMillis()} ➤ $message"
        Log.d("SerialTtl", logMsg)
        ttlLogs.add(logMsg)
    }
}
*/