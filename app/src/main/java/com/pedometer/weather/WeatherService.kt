package com.pedometer.weather

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherService(
    private val protocolHandler: ProtocolHandler,
) {
    companion object {
        private const val TAG = "WeatherService"
        const val COMMAND_TYPE = 10
    }

    var onWeatherRequested: (() -> Unit)? = null

    private fun locationKey(cityName: String): String {
        return "accu:${Math.abs(cityName.hashCode()) % 1000000}"
    }

    fun setLocation(cityName: String) {
        val key = locationKey(cityName)
        // Use subtype 6 (SET_LOCATIONS) to REPLACE the entire list, not 7 (ADD) which duplicates
        val location = XiaomiProto.WeatherLocation.newBuilder()
            .setCode(key)
            .setName(cityName)
            .build()
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(6) // SET_LOCATIONS — replaces all locations with this one
            .setWeather(XiaomiProto.Weather.newBuilder()
                .setLocations(XiaomiProto.WeatherLocations.newBuilder()
                    .addLocation(location)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Set weather location (replace): $cityName (key=$key)")
    }

    fun sendWeather(data: WeatherData) {
        val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
        val key = locationKey(data.cityName)

        val current = XiaomiProto.WeatherCurrent.newBuilder()
            .setMetadata(
                XiaomiProto.WeatherMetadata.newBuilder()
                    .setPublicationTimestamp(timestamp)
                    .setCityName(data.cityName)
                    .setLocationName(data.cityName)
                    .setLocationKey(key)
            )
            .setWeatherCondition(data.weatherCode)
            .setTemperature(XiaomiProto.WeatherUnitValue.newBuilder().setUnit("℃").setValue(data.temperature))
            .setHumidity(XiaomiProto.WeatherUnitValue.newBuilder().setUnit("%").setValue(data.humidity))
            .setWind(XiaomiProto.WeatherUnitValue.newBuilder().setUnit(data.windDirection.toString()).setValue(data.windSpeed.toInt()))
            .setUv(XiaomiProto.WeatherUnitValue.newBuilder().setUnit("").setValue(0))
            .setAqi(XiaomiProto.WeatherUnitValue.newBuilder().setUnit("Unknown").setValue(0))
            .setWarning(XiaomiProto.WeatherWarnings.newBuilder())
            .setPressure(data.pressure * 100)

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(0) // set current weather
            .setWeather(XiaomiProto.Weather.newBuilder().setCurrent(current))
            .build()

        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Sent weather: ${data.cityName} ${data.temperature}°C humidity=${data.humidity}%")
    }

    fun sendForecast(cityName: String, forecasts: List<WeatherProvider.DayForecast>) {
        if (forecasts.isEmpty()) return
        val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())

        val entries = XiaomiProto.ForecastEntries.newBuilder()
        for (f in forecasts) {
            entries.addEntry(XiaomiProto.ForecastEntry.newBuilder()
                .setTemperatureRange(XiaomiProto.WeatherRange.newBuilder()
                    .setFrom(f.tempMax).setTo(f.tempMin))
                .setConditionRange(XiaomiProto.WeatherRange.newBuilder()
                    .setFrom(f.weatherCode).setTo(f.weatherCode))
                .setTemperatureSymbol("℃"))
        }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(1) // daily forecast
            .setWeather(XiaomiProto.Weather.newBuilder()
                .setForecast(XiaomiProto.WeatherForecast.newBuilder()
                    .setMetadata(XiaomiProto.WeatherMetadata.newBuilder()
                        .setPublicationTimestamp(timestamp)
                        .setCityName(cityName)
                        .setLocationName(cityName))
                    .setEntries(entries)))
            .build()

        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Sent ${forecasts.size}-day forecast for $cityName")
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            3 -> {
                Log.i(TAG, "Watch requested weather update")
                onWeatherRequested?.invoke()
            }
            else -> Log.d(TAG, "Weather subtype: ${cmd.subtype}")
        }
    }
}
