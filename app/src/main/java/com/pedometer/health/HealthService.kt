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
        const val CMD_CONFIG_SPO2_GET = 8
        const val CMD_CONFIG_HEART_RATE_GET = 10
        const val CMD_CONFIG_STANDING_REMINDER_GET = 12
        const val CMD_CONFIG_STRESS_GET = 14
        const val CMD_CONFIG_GOAL_NOTIFICATION_GET = 21
        const val CMD_CONFIG_GOALS_GET = 42
        const val CMD_CONFIG_VITALITY_SCORE_GET = 35
    }

    private var realtimeStarted = false

    fun initialize() {
        Log.i(TAG, "Initializing health configs")
        sendSimpleCommand(CMD_CONFIG_SPO2_GET)
        sendSimpleCommand(CMD_CONFIG_HEART_RATE_GET)
        sendSimpleCommand(CMD_CONFIG_STANDING_REMINDER_GET)
        sendSimpleCommand(CMD_CONFIG_STRESS_GET)
        sendSimpleCommand(CMD_CONFIG_GOAL_NOTIFICATION_GET)
        sendSimpleCommand(CMD_CONFIG_GOALS_GET)
        sendSimpleCommand(CMD_CONFIG_VITALITY_SCORE_GET)

        // Enable SpO2 all-day tracking (value=2, not boolean true)
        val spo2Cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_HEALTH)
            .setSubtype(9) // CMD_CONFIG_SPO2_SET
            .setHealth(XiaomiProto.Health.newBuilder()
                .setSpo2(XiaomiProto.SpO2.newBuilder()
                    .setUnknown1(1)
                    .setAllDayTracking(true)))
            .build()
        protocolHandler.sendCommand(spo2Cmd)

        // Enable stress all-day tracking
        val stressCmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_HEALTH)
            .setSubtype(15) // CMD_CONFIG_STRESS_SET
            .setHealth(XiaomiProto.Health.newBuilder()
                .setStress(XiaomiProto.Stress.newBuilder()
                    .setAllDayTracking(true)))
            .build()
        protocolHandler.sendCommand(stressCmd)
        Log.i(TAG, "Enabled stress all-day tracking")

        // Fetch today's activity data
        protocolHandler.sendCommand(CommandHelper.buildActivityFetchToday())
    }

    private fun sendSimpleCommand(subtype: Int) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_HEALTH)
            .setSubtype(subtype)
            .build()
        protocolHandler.sendCommand(cmd)
    }

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
                    onHealthUpdate(data)
                }
            }
            CMD_CONFIG_SPO2_GET -> {
                Log.i(TAG, "SpO2 config: ${cmd.health}")
            }
            CMD_CONFIG_STRESS_GET -> {
                Log.i(TAG, "Stress config: ${cmd.health}")
            }
            CMD_CONFIG_HEART_RATE_GET,
            CMD_CONFIG_STANDING_REMINDER_GET,
            CMD_CONFIG_GOAL_NOTIFICATION_GET, CMD_CONFIG_GOALS_GET,
            CMD_CONFIG_VITALITY_SCORE_GET -> {
                Log.d(TAG, "Health config response: subtype=${cmd.subtype}")
            }
            else -> Log.d(TAG, "Health subtype: ${cmd.subtype}")
        }
    }
}
