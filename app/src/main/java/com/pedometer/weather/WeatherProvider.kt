package com.pedometer.weather

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherData(
    val temperature: Int,       // celsius
    val humidity: Int,          // %
    val windSpeed: Double,      // km/h
    val windDirection: Int,     // degrees
    val weatherCode: Int,       // WMO code
    val pressure: Float,        // hPa
    val cityName: String = "",
)

object WeatherProvider {
    private const val TAG = "WeatherProvider"

    // WMO weather code → Xiaomi condition code mapping
    private fun wmoToXiaomiCondition(wmo: Int): Int = when (wmo) {
        0 -> 0          // Clear sky → Sunny
        1, 2 -> 1       // Mainly clear, partly cloudy → Cloudy
        3 -> 2          // Overcast → Overcast
        45, 48 -> 18    // Fog → Fog
        51, 53, 55 -> 7 // Drizzle → Light rain
        61 -> 7         // Slight rain → Light rain
        63 -> 8         // Moderate rain → Rain
        65 -> 10        // Heavy rain → Heavy rain
        66, 67 -> 15    // Freezing rain → Sleet
        71, 73 -> 13    // Snow → Light snow
        75, 77 -> 16    // Heavy snow → Heavy snow
        80, 81 -> 8     // Rain showers → Rain
        82 -> 10        // Violent rain → Heavy rain
        85, 86 -> 14    // Snow showers → Snow
        95, 96, 99 -> 11 // Thunderstorm → Thunderstorm
        else -> 0
    }

    // Wind speed (km/h) → Beaufort scale
    private fun toBeaufort(kmh: Double): Int = when {
        kmh < 1 -> 0
        kmh < 6 -> 1
        kmh < 12 -> 2
        kmh < 20 -> 3
        kmh < 29 -> 4
        kmh < 39 -> 5
        kmh < 50 -> 6
        kmh < 62 -> 7
        kmh < 75 -> 8
        kmh < 89 -> 9
        kmh < 103 -> 10
        kmh < 118 -> 11
        else -> 12
    }

    fun fetch(lat: Double, lon: Double, cityName: String = ""): WeatherData? {
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&current=relative_humidity_2m,pressure_msl")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val current = json.getJSONObject("current_weather")
            val currentVars = json.optJSONObject("current")

            WeatherData(
                temperature = current.getDouble("temperature").toInt(),
                humidity = currentVars?.optInt("relative_humidity_2m", 50) ?: 50,
                windSpeed = current.getDouble("windspeed"),
                windDirection = current.getInt("winddirection"),
                weatherCode = current.getInt("weathercode"),
                pressure = currentVars?.optDouble("pressure_msl", 1013.0)?.toFloat() ?: 1013f,
                cityName = cityName.ifBlank { "%.2f,%.2f".format(lat, lon) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            null
        }
    }
}
