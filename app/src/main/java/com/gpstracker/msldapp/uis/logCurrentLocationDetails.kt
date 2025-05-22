import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

fun logCurrentLocationDetails(latitude: Double, longitude: Double, speed: Double) {
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    Log.i("CurrentLocation", "🕒 Time: $currentTime")
    Log.i("CurrentLocation", "📍 Latitude: $latitude")
    Log.i("CurrentLocation", "📍 Longitude: $longitude")
    Log.i("CurrentLocation", "🏎️ Speed: ${"%.2f".format(speed)} km/h")
}

