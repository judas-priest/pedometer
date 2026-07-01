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
    val activeMinutes: Int = 0,
)

data class WorkoutEvent(val sport: Int, val status: Int, val sportName: String)
// status: 0=started, 1=resumed, 2=paused, 3=finished

class HealthService(
    private val protocolHandler: ProtocolHandler,
    private val onHealthUpdate: (HealthData) -> Unit,
) {
    var onWorkoutEvent: ((WorkoutEvent) -> Unit)? = null
    var onGpsNeeded: ((Boolean) -> Unit)? = null
    private var workoutStarted = false
    private var gpsStarted = false
    private var gpsFixSent = false

    fun sendGpsLocation(lat: Double, lon: Double, alt: Double, speed: Float, bearing: Float) {
        if (!gpsFixSent) {
            gpsFixSent = true
            // Tell watch we have GPS fix
            val reply = XiaomiProto.Command.newBuilder()
                .setType(CommandHelper.TYPE_HEALTH)
                .setSubtype(CMD_WORKOUT_OPEN)
                .setHealth(XiaomiProto.Health.newBuilder()
                    .setWorkoutOpenReply(XiaomiProto.WorkoutOpenReply.newBuilder()
                        .setUnknown1(0).setUnknown2(2).setUnknown3(2)))
                .build()
            protocolHandler.sendCommand(reply)
            Log.i(TAG, "Sent GPS fix notification to watch")
        }

        if (workoutStarted) {
            val loc = XiaomiProto.WorkoutLocation.newBuilder()
                .setUnknown1(2)
                .setTimestamp((System.currentTimeMillis() / 1000).toInt())
                .setLongitude(lon)
                .setLatitude(lat)
                .setAltitude(alt)
                .setSpeed(speed)
                .setBearing(bearing)

            val cmd = XiaomiProto.Command.newBuilder()
                .setType(CommandHelper.TYPE_HEALTH)
                .setSubtype(CMD_WORKOUT_LOCATION)
                .setHealth(XiaomiProto.Health.newBuilder().setWorkoutLocation(loc))
                .build()
            protocolHandler.sendCommand(cmd)
        }
    }
    companion object {
        private const val TAG = "HealthService"
        const val CMD_CONFIG_SPO2_GET = 8
        const val CMD_CONFIG_HEART_RATE_GET = 10
        const val CMD_CONFIG_STANDING_REMINDER_GET = 12
        const val CMD_CONFIG_STRESS_GET = 14
        const val CMD_CONFIG_GOAL_NOTIFICATION_GET = 21
        const val CMD_CONFIG_GOALS_GET = 42
        const val CMD_CONFIG_VITALITY_SCORE_GET = 35
        const val CMD_WORKOUT_STATUS = 26
        const val CMD_WORKOUT_OPEN = 30
        const val CMD_WORKOUT_LOCATION = 48
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

        // Fetch today's + past activity data
        protocolHandler.sendCommand(CommandHelper.buildActivityFetchToday())
        // Past will be requested by ActivitySync after today's files are processed
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
                    Log.v(TAG, "RT: steps=${stats.steps} cal=${stats.calories} u3=${stats.unknown3} hr=${stats.heartRate} u5=${stats.unknown5} standing=${stats.standingHours}")
                    val data = HealthData(
                        steps = stats.steps,
                        calories = stats.calories,
                        heartRate = stats.heartRate,
                        standingHours = stats.standingHours,
                        activeMinutes = stats.unknown5, // likely active/moving minutes
                    )
                    onHealthUpdate(data)
                }
            }
            CMD_WORKOUT_OPEN -> {
                if (cmd.hasHealth() && cmd.health.hasWorkoutOpenWatch()) {
                    val sport = cmd.health.workoutOpenWatch.sport
                    Log.i(TAG, "Workout open: sport=$sport workoutStarted=$workoutStarted gpsStarted=$gpsStarted")
                    // Start GPS relay from phone
                    if (!gpsStarted) {
                        gpsStarted = true
                        onGpsNeeded?.invoke(true)
                    }
                }
            }
            CMD_WORKOUT_STATUS -> {
                if (cmd.hasHealth() && cmd.health.hasWorkoutStatusWatch()) {
                    val ws = cmd.health.workoutStatusWatch
                    val status = ws.status
                    val sport = ws.sport
                    val sportName = when (sport) {
                        1 -> "Бег"; 2, 22 -> "Ходьба"; 3 -> "Беговая дорожка"
                        6, 23 -> "Велосипед"; 8 -> "Свободная"; 9 -> "Плавание"
                        else -> "Тренировка"
                    }
                    Log.i(TAG, "Workout status: $status sport=$sport ($sportName)")
                    when (status) {
                        0, 1 -> workoutStarted = true // started/resumed
                        3 -> { // finished
                            workoutStarted = false
                            gpsStarted = false
                            onGpsNeeded?.invoke(false)
                        }
                    }
                    onWorkoutEvent?.invoke(WorkoutEvent(sport, status, sportName))
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
