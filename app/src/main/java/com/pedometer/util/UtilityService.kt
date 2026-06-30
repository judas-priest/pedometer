package com.pedometer.util

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

class UtilityService(
    private val protocolHandler: ProtocolHandler,
    private val onFindPhone: () -> Unit,
) {
    companion object {
        private const val TAG = "UtilityService"
        const val SYSTEM_TYPE = 2
        const val CALENDAR_TYPE = 14
        const val SCHEDULE_TYPE = 19
        const val PHONEBOOK_TYPE = 21
    }

    fun findWatch() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(SYSTEM_TYPE)
            .setSubtype(18)
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(1))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Find watch triggered")
    }

    fun sendCalendarEvent(title: String, description: String, startTimestamp: Long, endTimestamp: Long) {
        val event = XiaomiProto.CalendarEvent.newBuilder()
            .setTitle(title)
            .setDescription(description)
            .setStart((startTimestamp / 1000).toInt())
            .setEnd((endTimestamp / 1000).toInt())

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CALENDAR_TYPE)
            .setSubtype(1)
            .setCalendar(
                XiaomiProto.Calendar.newBuilder()
                    .setCalendarSync(
                        XiaomiProto.CalendarSync.newBuilder().addEvent(event)
                    )
            )
            .build()

        protocolHandler.sendCommand(cmd)
        Log.d(TAG, "Sent calendar event: $title")
    }

    fun sendContacts(contacts: List<Pair<String, String>>) {
        val contactList = XiaomiProto.ContactList.newBuilder()
        for ((name, number) in contacts) {
            contactList.addContactInfo(
                XiaomiProto.ContactInfo.newBuilder()
                    .setDisplayName(name)
                    .setPhoneNumber(number)
            )
        }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(PHONEBOOK_TYPE)
            .setSubtype(0)
            .setPhonebook(XiaomiProto.Phonebook.newBuilder().setContactList(contactList))
            .build()

        protocolHandler.sendCommand(cmd)
        Log.d(TAG, "Sent ${contacts.size} contacts")
    }

    fun setWorldClocks(clocks: List<String>) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(17) // Schedule type
            .setSubtype(11) // Create world clock
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setWorldClocks(XiaomiProto.WorldClocks.newBuilder()
                    .apply { clocks.forEach { addWorldClock(it) } }))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Set world clocks: $clocks")
    }

    fun sendBreathingVibration() {
        // 4-7-8 breathing pattern: inhale 4s, hold 7s, exhale 8s
        // Vibrate on transitions
        val pattern = listOf(
            XiaomiProto.Vibration.newBuilder().setVibrate(1).setMs(200).build(), // start inhale
            XiaomiProto.Vibration.newBuilder().setVibrate(0).setMs(3800).build(), // inhale 4s
            XiaomiProto.Vibration.newBuilder().setVibrate(1).setMs(200).build(), // start hold
            XiaomiProto.Vibration.newBuilder().setVibrate(0).setMs(6800).build(), // hold 7s
            XiaomiProto.Vibration.newBuilder().setVibrate(1).setMs(300).build(), // start exhale
            XiaomiProto.Vibration.newBuilder().setVibrate(0).setMs(7700).build(), // exhale 8s
            XiaomiProto.Vibration.newBuilder().setVibrate(1).setMs(500).build(), // cycle complete
        )

        val vibTest = XiaomiProto.VibrationTest.newBuilder()
        pattern.forEach { vibTest.addVibration(it) }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(SYSTEM_TYPE)
            .setSubtype(41) // vibration test custom
            .setSystem(XiaomiProto.System.newBuilder()
                .setVibrationTestCustom(vibTest))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Breathing vibration sent")
    }

    fun handleSystemCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            18 -> {
                if (cmd.hasSystem() && cmd.system.findDevice == 0) {
                    Log.i(TAG, "Find phone triggered by watch")
                    onFindPhone()
                }
            }
        }
    }
}
