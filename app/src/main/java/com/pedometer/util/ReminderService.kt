package com.pedometer.util

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

data class WatchReminder(
    val id: Int,
    val title: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val repeatMode: Int = 0, // 0=once, 1=daily, 5=weekly, 7=monthly, 8=yearly
)

class ReminderService(private val protocolHandler: ProtocolHandler) {
    companion object {
        private const val TAG = "ReminderService"
        const val COMMAND_TYPE = 17
        const val CMD_GET = 14
        const val CMD_CREATE = 15
        const val CMD_EDIT = 17
        const val CMD_DELETE = 18
    }

    var onRemindersReceived: ((List<WatchReminder>) -> Unit)? = null

    fun getReminders() {
        protocolHandler.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_GET)
                .build()
        )
    }

    fun createReminder(title: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, repeatMode: Int = 0) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setCreateReminder(XiaomiProto.ReminderDetails.newBuilder()
                    .setTitle(title)
                    .setDate(XiaomiProto.Date.newBuilder().setYear(year).setMonth(month).setDay(day))
                    .setTime(XiaomiProto.Time.newBuilder().setHour(hour).setMinute(minute))
                    .setRepeatMode(repeatMode)
                    .setRepeatFlags(64)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Create reminder: $title $year-$month-$day $hour:$minute repeat=$repeatMode")
        getReminders()
    }

    fun editReminder(reminder: WatchReminder) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_EDIT)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setEditReminder(XiaomiProto.Reminder.newBuilder()
                    .setId(reminder.id)
                    .setReminderDetails(XiaomiProto.ReminderDetails.newBuilder()
                        .setTitle(reminder.title)
                        .setDate(XiaomiProto.Date.newBuilder()
                            .setYear(reminder.year).setMonth(reminder.month).setDay(reminder.day))
                        .setTime(XiaomiProto.Time.newBuilder()
                            .setHour(reminder.hour).setMinute(reminder.minute))
                        .setRepeatMode(reminder.repeatMode)
                        .setRepeatFlags(64))))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Edit reminder ${reminder.id}: ${reminder.title}")
        getReminders()
    }

    fun deleteReminder(id: Int) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_DELETE)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setDeleteReminder(XiaomiProto.ReminderDelete.newBuilder()
                    .addId(id)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Delete reminder $id")
        getReminders()
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_GET -> {
                if (cmd.hasSchedule() && cmd.schedule.hasReminders()) {
                    val reminders = cmd.schedule.reminders.reminderList.map { r ->
                        val d = r.reminderDetails
                        WatchReminder(
                            id = r.id,
                            title = d.title,
                            year = d.date.year,
                            month = d.date.month,
                            day = d.date.day,
                            hour = d.time.hour,
                            minute = d.time.minute,
                            repeatMode = d.repeatMode,
                        )
                    }
                    Log.i(TAG, "Got ${reminders.size} reminders (max=${cmd.schedule.reminders.maxReminders})")
                    onRemindersReceived?.invoke(reminders)
                }
            }
            CMD_CREATE, CMD_EDIT -> {
                val ackId = if (cmd.hasSchedule()) cmd.schedule.ackId else -1
                Log.i(TAG, "Reminder ack: id=$ackId subtype=${cmd.subtype}")
            }
            CMD_DELETE -> {
                Log.i(TAG, "Reminder deleted")
            }
        }
    }
}
