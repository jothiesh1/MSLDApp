package com.gpstracker.msldapp.uis

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object SerialTtlManager {
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var readCallback: (String) -> Unit = {}

    /**
     * Initialize TTL USB port connection
     */
    fun init(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            Log.e("SerialTtl", "❌ No USB serial drivers found")
            return false
        }

        val connection = usbManager.openDevice(drivers[0].device)
        if (connection == null) {
            Log.e("SerialTtl", "❌ Failed to open USB device connection")
            return false
        }

        port = drivers[0].ports[0]
        return try {
            port?.apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }
            Log.i("SerialTtl", "✅ TTL Port opened successfully")
            true
        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Error opening TTL port: ${e.message}", e)
            false
        }
    }

    /**
     * Send speed limit byte to TTL
     */
    fun sendSpeed(speed: Int) {
        try {
            val safeSpeed = speed.coerceIn(0, 255)
            port?.write(byteArrayOf(safeSpeed.toByte()), 1000)
            Log.d("SerialTtl", "📤 TTL Sent: $safeSpeed")
        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Failed to send TTL: ${e.message}", e)
        }
    }

    /**
     * Start reading from TTL in background thread
     */
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
                    readCallback(output)
                }

                override fun onRunError(e: Exception) {
                    Log.e("SerialTtl", "❌ Read error: ${e.message}", e)
                }
            })

            ioManager?.let { manager ->
                executor.submit { manager.start() } // ✅ use start() instead of submit(it)
                Log.i("SerialTtl", "✅ TTL IO Manager started")
            }

        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Failed to start IO Manager: ${e.message}", e)
        }
    }

    /**
     * Cleanly close the TTL port and stop manager
     */
    fun close() {
        try {
            ioManager?.stop()
            ioManager = null
            port?.close()
            port = null
            Log.d("SerialTtl", "🔌 TTL connection closed")
        } catch (e: Exception) {
            Log.e("SerialTtl", "❌ Failed to close TTL: ${e.message}", e)
        }
    }
}
