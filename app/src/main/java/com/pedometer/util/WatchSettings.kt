package com.pedometer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto

/**
 * Watch settings: contacts, reminders, DND, wearing mode.
 */
class WatchSettings(
    private val protocolHandler: ProtocolHandler,
    private val context: Context,
) {
    companion object {
        private const val TAG = "WatchSettings"
        const val SCHEDULE_TYPE = 17
        const val PHONEBOOK_TYPE = 23
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    fun syncContacts(limit: Int = 50) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_CONTACTS permission")
            return
        }

        val contacts = readPhoneContacts(limit)
        val contactList = XiaomiProto.ContactList.newBuilder()
        contacts.forEach { (name, phone) ->
            contactList.addContactInfo(
                XiaomiProto.ContactInfo.newBuilder()
                    .setDisplayName(name)
                    .setPhoneNumber(phone)
            )
        }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(PHONEBOOK_TYPE)
            .setSubtype(0)
            .setPhonebook(XiaomiProto.Phonebook.newBuilder().setContactList(contactList))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Synced ${contacts.size} contacts to watch")
    }

    private fun readPhoneContacts(limit: Int): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val seen = mutableSetOf<String>()
                while (it.moveToNext() && contacts.size < limit) {
                    val name = it.getString(0) ?: continue
                    val phone = it.getString(1)?.replace(Regex("[\\s\\-()]"), "") ?: continue
                    if (seen.add(phone)) {
                        contacts.add(Pair(name, phone))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read contacts failed: ${e.message}")
        }
        return contacts
    }

    // ── Reminders ─────────────────────────────────────────────────────────

    fun createReminder(title: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(SCHEDULE_TYPE)
            .setSubtype(9) // create reminder
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setCreateReminder(XiaomiProto.ReminderDetails.newBuilder()
                    .setTitle(title)
                    .setDate(XiaomiProto.Date.newBuilder().setYear(year).setMonth(month).setDay(day))
                    .setTime(XiaomiProto.Time.newBuilder().setHour(hour).setMinute(minute))
                    .setRepeatMode(0) // once
                    .setRepeatFlags(64)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Created reminder: $title at $year-$month-$day $hour:$minute")
    }

    // ── DND ───────────────────────────────────────────────────────────────

    fun setDnd(enabled: Boolean) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(9) // misc setting set
            .setSystem(XiaomiProto.System.newBuilder()
                .setMiscSettingSet(XiaomiProto.MiscSettingSet.newBuilder()
                    .setDndSync(XiaomiProto.DndSync.newBuilder()
                        .setEnabled(if (enabled) 1 else 0))))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "DND sync: $enabled")
    }

    // ── Wearing Mode ──────────────────────────────────────────────────────

    fun setWearingMode(mode: Int) {
        // 0 = band (wristband), 1 = pebble (buckle), 2 = necklace
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(9) // misc setting set
            .setSystem(XiaomiProto.System.newBuilder()
                .setMiscSettingSet(XiaomiProto.MiscSettingSet.newBuilder()
                    .setWearingMode(XiaomiProto.WearingMode.newBuilder().setMode(mode))))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Wearing mode: $mode")
    }
}
