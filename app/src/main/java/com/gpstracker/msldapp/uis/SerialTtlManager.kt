package com.gpstracker.msldapp.uis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object SerialTtlManager {
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val ttlLogs = mutableStateListOf<String>()
    var readCallback: (String) -> Unit = {}

    private val _isConnected = AtomicBoolean(false)
    var isConnected: Boolean
        get() = _isConnected.get()
        private set(value) = _isConnected.set(value)

    private val sendAttempts = AtomicInteger(0)
    private val successfulSends = AtomicInteger(0)

    private var connectionRetryCount = AtomicInteger(0)
    private val maxRetries = 5
    private val baseRetryDelay = 1000L

    private val initializationInProgress = AtomicBoolean(false)
    private val permissionRequested = AtomicBoolean(false)
    private val reconnectionInProgress = AtomicBoolean(false)
    private val lastReconnectAttempt = AtomicLong(0)

    private val lastToastTime = AtomicLong(0)
    private val toastCooldown = 2000L

    const val ACTION_USB_PERMISSION = "com.gpstracker.msldapp.USB_PERMISSION"
    private var context: Context? = null
    private var isReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionEstablishedTime = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    private val lastSentSpeedLimit = AtomicInteger(-1)
    private val lastSentTime = AtomicLong(0)

    private fun showToast(context: Context, message: String, force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (force || currentTime - lastToastTime.get() > toastCooldown) {
            lastToastTime.set(currentTime)
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> handleUsbPermissionResult(context, intent)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbDeviceAttached(context, intent)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDeviceDetached(context, intent)
                }
            } catch (e: Exception) {
                Log.e("SerialTtl", "Error in USB receiver: ${e.message}", e)
                addLog("USB receiver error: ${e.message}")
            }
        }
    }

    private fun handleUsbPermissionResult(context: Context, intent: Intent) {
        synchronized(this) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            addLog("USB Permission: ${if (granted) "GRANTED" else "DENIED"}")
            permissionRequested.set(false)

            if (granted && device != null) {
                addLog("Permission granted for ${device.deviceName}")
                continueInit(context)
            } else {
                addLog("Permission denied")
                isConnected = false
                initializationInProgress.set(false)
                showToast(context, "USB permission required for TTL", true)
            }
        }
    }

    private fun handleUsbDeviceAttached(context: Context, intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        Log.i("SerialTtl", "USB device attached: ${device?.deviceName}")
        addLog("USB device attached: ${device?.deviceName}")

        if (!isConnected && !initializationInProgress.get() && !reconnectionInProgress.get()) {
            mainHandler.postDelayed({
                if (!isConnected) {
                    init(context)
                }
            }, 500)
        }
    }

    private fun handleUsbDeviceDetached(context: Context, intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        Log.i("SerialTtl", "USB device detached: ${device?.deviceName}")
        addLog("USB device detached: ${device?.deviceName}")

        if (isConnected) {
            cleanupConnection()
            showToast(context, "TTL device disconnected")
        }
    }

    fun init(context: Context): Boolean {
        Log.i("SerialTtl", "===== TTL INITIALIZATION START =====")

        if (!initializationInProgress.compareAndSet(false, true)) {
            Log.w("SerialTtl", "Initialization already in progress, skipping")
            return false
        }

        try {
            this.context = context
            isConnected = false
            reconnectionInProgress.set(false)

            cleanupConnection()
            registerUsbReceivers(context)

            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            addLog("Scanning for USB serial devices...")

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            Log.i("SerialTtl", "Found ${drivers.size} USB serial drivers")

            if (drivers.isEmpty()) {
                addLog("No USB serial drivers found")
                initializationInProgress.set(false)
                showToast(context, "No TTL device found. Connect CP2102 device.")
                return false
            }

            val driver = drivers[0]
            val device = driver.device

            addLog("Found USB device: ${device.deviceName} (VID:0x${String.format("%04X", device.vendorId)}, PID:0x${String.format("%04X", device.productId)})")

            if (!usbManager.hasPermission(device)) {
                return requestUsbPermission(context, usbManager, device)
            }

            return continueInit(context)

        } catch (e: Exception) {
            Log.e("SerialTtl", "Init error: ${e.message}", e)
            addLog("Init error: ${e.message}")
            initializationInProgress.set(false)
            showToast(context, "TTL init error: ${e.message}")
            return false
        }
    }

    private fun requestUsbPermission(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (!permissionRequested.compareAndSet(false, true)) {
            Log.w("SerialTtl", "Permission request already in progress")
            return false
        }

        try {
            addLog("Requesting USB permission for ${device.deviceName}")

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            usbManager.requestPermission(device, permissionIntent)
            addLog("Waiting for USB permission...")
            showToast(context, "Please allow USB permission for TTL")
            return false

        } catch (e: Exception) {
            Log.e("SerialTtl", "Error requesting USB permission: ${e.message}", e)
            addLog("Error requesting USB permission: ${e.message}")
            permissionRequested.set(false)
            initializationInProgress.set(false)
            return false
        }
    }

    private fun continueInit(context: Context): Boolean {
        Log.i("SerialTtl", "Continuing TTL initialization...")
        addLog("Continuing TTL initialization...")

        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (drivers.isEmpty()) {
                addLog("No drivers found during continue init")
                initializationInProgress.set(false)
                return false
            }

            val driver = drivers[0]
            val device = driver.device

            addLog("Opening USB device connection...")

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                addLog("Failed to open USB device connection")
                initializationInProgress.set(false)
                showToast(context, "Could not open TTL device")
                return false
            }

            port = driver.ports.firstOrNull()
            if (port == null) {
                addLog("No ports found on the TTL device")
                connection.close()
                initializationInProgress.set(false)
                showToast(context, "No TTL port available")
                return false
            }

            return configureAndTestPort(context, connection)

        } catch (e: Exception) {
            addLog("Error in continueInit: ${e.message}")
            initializationInProgress.set(false)
            showToast(context, "TTL connection failed: ${e.message}")
            return false
        }
    }

    private fun configureAndTestPort(context: Context, connection: android.hardware.usb.UsbDeviceConnection): Boolean {
        return try {
            addLog("Configuring serial port parameters...")

            port?.apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                setDTR(true)
                setRTS(false)
                Thread.sleep(50)
                setRTS(true)
            }

            isConnected = true
            connectionRetryCount.set(0)
            consecutiveFailures.set(0)
            connectionEstablishedTime.set(System.currentTimeMillis())
            initializationInProgress.set(false)

            addLog("TTL Port configured successfully")
            showToast(context, "TTL Connected Successfully")

            startReading()

            true

        } catch (e: Exception) {
            isConnected = false
            initializationInProgress.set(false)
            addLog("Error configuring TTL port: ${e.message}")
            showToast(context, "TTL configuration failed")
            false
        }
    }

    private fun attemptReconnection(context: Context, reason: String) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastReconnectAttempt.get() < 2000) {
            Log.d("SerialTtl", "Reconnection attempt too soon, skipping")
            return
        }

        if (!reconnectionInProgress.compareAndSet(false, true)) {
            Log.d("SerialTtl", "Reconnection already in progress")
            return
        }

        lastReconnectAttempt.set(currentTime)
        val currentRetryCount = connectionRetryCount.incrementAndGet()

        if (currentRetryCount > maxRetries) {
            addLog("Max reconnection attempts reached")
            reconnectionInProgress.set(false)
            showToast(context, "TTL connection failed after $maxRetries attempts")
            return
        }

        addLog("Attempting reconnection ($currentRetryCount/$maxRetries) - Reason: $reason")

        val delay = Math.min(baseRetryDelay * currentRetryCount, 5000L)
        mainHandler.postDelayed({
            cleanupConnection()

            mainHandler.postDelayed({
                if (!isConnected) {
                    init(context)
                }
                reconnectionInProgress.set(false)
            }, 200)
        }, delay)
    }

    /**
     * FIXED: Send speed to TTL device
     * Returns: true if successful, false if failed
     */
    fun sendSpeed(speed: Int, context: Context? = null): Boolean {
        val attempt = sendAttempts.incrementAndGet()
        val currentTime = System.currentTimeMillis()

        try {
            Log.d("SerialTtl", "Sending speed: $speed (attempt #$attempt)")

            if (speed < 0 || speed > 255) {
                throw IllegalArgumentException("Speed must be between 0 and 255, got: $speed")
            }

            // Check for duplicate sends within 1 second
            if (lastSentSpeedLimit.get() == speed && currentTime - lastSentTime.get() < 1000) {
                Log.d("SerialTtl", "Duplicate speed send skipped: $speed")
                return true // Already sent, count as success
            }

            // Connection validation
            if (!isConnected || port == null) {
                throw IllegalStateException("TTL device not connected")
            }

            // Check connection stability
            val connectionAge = currentTime - connectionEstablishedTime.get()
            if (connectionAge < 500) {
                throw IllegalStateException("Connection too new, waiting for stability")
            }

            val packet = byteArrayOf(speed.toByte())
            addLog("Sending: 0x${String.format("%02X", speed)} ($speed)")

            val startTime = System.currentTimeMillis()

            // FIXED: Properly handle write operation
            try {
                port?.write(packet, 1000)
                // If we reach here, write succeeded

                val endTime = System.currentTimeMillis()

                // Update tracking
                lastSentSpeedLimit.set(speed)
                lastSentTime.set(currentTime)

                val success = successfulSends.incrementAndGet()
                consecutiveFailures.set(0)

                addLog("TTL SUCCESS #$success: $speed (${endTime - startTime}ms)")
                context?.let { showToast(it, "TTL: $speed km/h", true) }

                Log.d("SerialTtl", "Successfully sent speed: $speed")
                return true

            } catch (writeException: Exception) {
                throw IOException("Write failed: ${writeException.message}")
            }

        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            addLog("TTL SEND FAILED #$attempt: ${e.message}")
            Log.e("SerialTtl", "Failed to send speed: ${e.message}", e)

            // Reconnect on IO errors with multiple failures
            if (e is IOException && failures >= 2) {
                isConnected = false
                context?.let { ctx ->
                    showToast(ctx, "TTL error, reconnecting...")
                    attemptReconnection(ctx, "IO error after $failures failures")
                }
            } else {
                context?.let { showToast(it, "TTL error: ${e.message}") }
            }

            return false
        }
    }

    fun startReading() {
        if (port == null) {
            addLog("Cannot start reading: port is null")
            return
        }

        try {
            addLog("Starting TTL data reader...")

            ioManager = SerialInputOutputManager(port!!, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    try {
                        val output = data.joinToString(" ") { "0x${String.format("%02X", it)} (${it.toUByte()})" }
                        addLog("Read: $output")
                        readCallback(output)
                    } catch (e: Exception) {
                        Log.e("SerialTtl", "Error processing received data: ${e.message}")
                    }
                }

                override fun onRunError(e: Exception) {
                    addLog("IO Manager error: ${e.message}")

                    val failures = consecutiveFailures.incrementAndGet()
                    if (failures >= 3) {
                        isConnected = false
                        context?.let { ctx ->
                            attemptReconnection(ctx, "Multiple IO read errors")
                        }
                    }
                }
            })

            executor.submit {
                try {
                    ioManager?.start()
                    addLog("TTL reader started")
                } catch (e: Exception) {
                    addLog("TTL reader start error: ${e.message}")
                    isConnected = false
                }
            }

        } catch (e: Exception) {
            addLog("Failed to start IO Manager: ${e.message}")
            isConnected = false
        }
    }

    private fun cleanupConnection() {
        try {
            isConnected = false

            ioManager?.stop()
            ioManager = null

            port?.close()
            port = null

            lastSentSpeedLimit.set(-1)
            lastSentTime.set(0)

            Log.d("SerialTtl", "Connection cleaned up")
        } catch (e: Exception) {
            Log.w("SerialTtl", "Warning during cleanup: ${e.message}")
        }
    }

    private fun registerUsbReceivers(context: Context) {
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }

            isReceiverRegistered = true
            Log.d("SerialTtl", "USB receivers registered")
        } catch (e: Exception) {
            Log.e("SerialTtl", "Error registering USB receivers: ${e.message}")
        }
    }

    fun close() {
        try {
            Log.i("SerialTtl", "Closing TTL connection...")

            cleanupConnection()
            initializationInProgress.set(false)
            permissionRequested.set(false)
            reconnectionInProgress.set(false)
            connectionRetryCount.set(0)

            context?.let { ctx ->
                if (isReceiverRegistered) {
                    try {
                        ctx.unregisterReceiver(usbReceiver)
                        isReceiverRegistered = false
                    } catch (e: Exception) {
                        Log.d("SerialTtl", "Receiver already unregistered")
                    }
                }
            }

            val attempts = sendAttempts.get()
            val successes = successfulSends.get()
            addLog("TTL connection closed - Sends: $successes/$attempts")

        } catch (e: Exception) {
            addLog("Error during TTL close: ${e.message}")
        }
    }

    fun getDebugStatus(): String {
        val attempts = sendAttempts.get()
        val successes = successfulSends.get()
        val connectionAge = if (isConnected)
            "${(System.currentTimeMillis() - connectionEstablishedTime.get()) / 1000}s"
        else "N/A"

        return """
            TTL Debug Status:
            • Connected: $isConnected (for $connectionAge)
            • Port: ${if (port != null) "Available" else "NULL"}
            • Init in Progress: ${initializationInProgress.get()}
            • Reconnection in Progress: ${reconnectionInProgress.get()}
            • Retry Count: ${connectionRetryCount.get()}/$maxRetries
            • Consecutive Failures: ${consecutiveFailures.get()}
            • Send Success Rate: ${if (attempts > 0) "${(successes * 100 / attempts)}%" else "N/A"}
            • Last Sent: ${lastSentSpeedLimit.get()} (${(System.currentTimeMillis() - lastSentTime.get()) / 1000}s ago)
        """.trimIndent()
    }

    fun getCacheStats(): String {
        val attempts = sendAttempts.get()
        val successes = successfulSends.get()
        return "TTL: $successes/$attempts (${if (attempts > 0) "${(successes * 100 / attempts)}%" else "0%"})"
    }

    private fun addLog(message: String) {
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val logMsg = "[$timestamp] $message"

            synchronized(ttlLogs) {
                ttlLogs.add(logMsg)
                if (ttlLogs.size > 100) {
                    repeat(ttlLogs.size - 80) {
                        if (ttlLogs.isNotEmpty()) {
                            ttlLogs.removeAt(0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SerialTtl", "Error adding log: ${e.message}")
        }
    }

    fun getHealthStatus(): Map<String, Any> {
        val attempts = sendAttempts.get()
        val successes = successfulSends.get()

        return mapOf(
            "connected" to isConnected,
            "connection_stable" to (isConnected && consecutiveFailures.get() == 0),
            "port_available" to (port != null),
            "retry_count" to connectionRetryCount.get(),
            "consecutive_failures" to consecutiveFailures.get(),
            "success_rate" to if (attempts > 0) (successes * 100 / attempts) else 0,
            "connection_age_seconds" to if (isConnected)
                (System.currentTimeMillis() - connectionEstablishedTime.get()) / 1000
            else 0,
            "last_sent_speed" to lastSentSpeedLimit.get(),
            "last_sent_seconds_ago" to if (lastSentTime.get() > 0)
                (System.currentTimeMillis() - lastSentTime.get()) / 1000
            else -1
        )
    }
}