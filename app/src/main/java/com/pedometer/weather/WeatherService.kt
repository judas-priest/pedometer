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
        const val COMMAND_TYPE = 12
        const val CMD_CURRENT = 1
        const val CMD_FORECAST = 2
    }

    fun sendCurrentWeather(
        cityName: String,
        temperature: Int,
        condition: Int,
        humidity: Int,
    ) {
        val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())

        val current = XiaomiProto.WeatherCurrent.newBuilder()
            .setMetadata(
                XiaomiProto.WeatherMetadata.newBuilder()
                    .setPublicationTimestamp(timestamp)
                    .setCityName(cityName)
                    .setLocationName(cityName)
            )
            .setWeatherCondition(condition)
            .setTemperature(
                XiaomiProto.WeatherUnitValue.newBuilder()
                    .setUnit("C")
                    .setValue(temperature)
            )
            .setHumidity(
                XiaomiProto.WeatherUnitValue.newBuilder()
                    .setUnit("%")
                    .setValue(humidity)
            )

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_CURRENT)
            .setWeather(XiaomiProto.Weather.newBuilder().setCurrent(current))
            .build()

        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Sent weather: $cityName ${temperature}C condition=$condition")
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            3 -> {
                // Watch requesting weather update
                Log.d(TAG, "Watch requested weather update")
                // TODO: trigger weather fetch from API
            }
            else -> Log.d(TAG, "Unhandled weather subtype: ${cmd.subtype}")
        }
    }
}
