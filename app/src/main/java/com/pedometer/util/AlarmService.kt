package com.pedometer.util

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

data class WatchAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val repeatMode: Int = 0, // 0=once, 1=daily, 5=weekly
    val repeatFlags: Int = 0, // bitmask for weekly: 1=Mon, 2=Tue, 4=Wed...
    val smart: Int = 2, // 1=smart, 2=normal
)

class AlarmService(private val protocolHandler: ProtocolHandler) {
    companion object {
        private const val TAG = "AlarmService"
        const val COMMAND_TYPE = 17
        const val CMD_GET = 0
        const val CMD_CREATE = 1
        const val CMD_EDIT = 3
        const val CMD_DELETE = 4
        const val CMD_ACK = 5
    }

    var onAlarmsReceived: ((List<WatchAlarm>) -> Unit)? = null

    fun getAlarms() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_GET)
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Requesting alarms")
    }

    fun createAlarm(hour: Int, minute: Int, repeatMode: Int = 0, repeatFlags: Int = 0, smart: Int = 2) {
        val details = XiaomiProto.AlarmDetails.newBuilder()
            .setTime(XiaomiProto.HourMinute.newBuilder().setHour(hour).setMinute(minute))
            .setRepeatMode(repeatMode)
            .setEnabled(true)
            .setSmart(smart)
        if (repeatMode == 5) details.setRepeatFlags(repeatFlags)

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder().setCreateAlarm(details))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Creating alarm: $hour:$minute repeat=$repeatMode")
        getAlarms()
    }

    fun editAlarm(alarm: WatchAlarm) {
        val details = XiaomiProto.AlarmDetails.newBuilder()
            .setTime(XiaomiProto.HourMinute.newBuilder().setHour(alarm.hour).setMinute(alarm.minute))
            .setRepeatMode(alarm.repeatMode)
            .setEnabled(alarm.enabled)
            .setSmart(alarm.smart)
        if (alarm.repeatMode == 5) details.setRepeatFlags(alarm.repeatFlags)

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_EDIT)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setEditAlarm(XiaomiProto.Alarm.newBuilder()
                    .setId(alarm.id)
                    .setAlarmDetails(details)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Editing alarm ${alarm.id}: ${alarm.hour}:${alarm.minute} enabled=${alarm.enabled}")
        getAlarms()
    }

    fun deleteAlarm(id: Int) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_DELETE)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setDeleteAlarm(XiaomiProto.AlarmDelete.newBuilder().addId(id)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Deleting alarm $id")
        getAlarms()
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_GET -> {
                if (cmd.hasSchedule() && cmd.schedule.hasAlarms()) {
                    val alarms = cmd.schedule.alarms.alarmList.map { a ->
                        val d = a.alarmDetails
                        WatchAlarm(
                            id = a.id,
                            hour = d.time.hour,
                            minute = d.time.minute,
                            enabled = d.enabled,
                            repeatMode = d.repeatMode,
                            repeatFlags = d.repeatFlags,
                            smart = d.smart,
                        )
                    }
                    Log.i(TAG, "Got ${alarms.size} alarms")
                    onAlarmsReceived?.invoke(alarms)
                }
            }
            CMD_ACK -> {
                Log.i(TAG, "Alarm ACK received")
                // Re-fetch alarms to update list
                getAlarms()
            }
            else -> Log.d(TAG, "Alarm subtype: ${cmd.subtype}")
        }
    }
}
