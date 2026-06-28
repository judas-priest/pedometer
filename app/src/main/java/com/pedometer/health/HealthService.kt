package com.pedometer.health

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto

data class HealthData(
    val steps: Int = 0,
    val calories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
)

class HealthService(
    private val protocolHandler: ProtocolHandler,
    private val onHealthUpdate: (HealthData) -> Unit,
) {
    companion object {
        private const val TAG = "HealthService"
    }

    private var realtimeStarted = false

    fun startRealtimeStats() {
        if (realtimeStarted) return
        realtimeStarted = true
        Log.i(TAG, "Starting realtime stats")
        protocolHandler.sendCommand(CommandHelper.buildRealtimeStatsStart())
    }

    fun stopRealtimeStats() {
        if (!realtimeStarted) return
        realtimeStarted = false
        Log.i(TAG, "Stopping realtime stats")
        protocolHandler.sendCommand(CommandHelper.buildRealtimeStatsStop())
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.HEALTH_REALTIME_STATS_EVENT -> {
                if (cmd.hasHealth() && cmd.health.hasRealTimeStats()) {
                    val stats = cmd.health.realTimeStats
                    val data = HealthData(
                        steps = stats.steps,
                        calories = stats.calories,
                        heartRate = stats.heartRate,
                        standingHours = stats.standingHours,
                    )
                    Log.d(TAG, "Realtime: steps=${data.steps} hr=${data.heartRate} cal=${data.calories}")
                    onHealthUpdate(data)
                }
            }
            else -> Log.d(TAG, "Unhandled health subtype: ${cmd.subtype}")
        }
    }
}
