package com.pedometer.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.util.TimeZone

class CalendarService(
    private val protocolHandler: ProtocolHandler,
    private val context: Context,
) {
    companion object {
        private const val TAG = "CalendarService"
        const val COMMAND_TYPE = 12
        const val CMD_SET = 1
        const val MAX_EVENTS = 20

        fun addToSystemCalendar(context: Context, title: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No WRITE_CALENDAR permission")
                return
            }
            val calId = getCalendarId(context)
            if (calId < 0) {
                Log.w(TAG, "No calendar account found")
                return
            }
            val cal = java.util.GregorianCalendar(year, month - 1, day, hour, minute)
            val startMs = cal.timeInMillis
            val endMs = startMs + 60 * 60 * 1000

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.i(TAG, "Added to system calendar: $title → $uri")
        }

        fun readUpcomingEventsUI(context: Context): List<com.pedometer.ui.CalendarEventUI> {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) return emptyList()
            val now = System.currentTimeMillis()
            val weekLater = now + 7 * 24 * 60 * 60 * 1000L
            val events = mutableListOf<com.pedometer.ui.CalendarEventUI>()
            try {
                val cursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                        CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                        CalendarContract.Events.ALL_DAY),
                    "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                    arrayOf(now.toString(), weekLater.toString()),
                    "${CalendarContract.Events.DTSTART} ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        events.add(com.pedometer.ui.CalendarEventUI(
                            id = it.getLong(0),
                            title = it.getString(1) ?: "",
                            startMs = it.getLong(2),
                            endMs = it.getLong(3),
                            allDay = it.getInt(4) == 1,
                        ))
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Read calendar failed: ${e.message}") }
            return events
        }

        fun deleteFromSystemCalendar(context: Context, eventId: Long) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) return
            try {
                val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.delete(uri, null, null)
                Log.i(TAG, "Deleted calendar event $eventId")
            } catch (e: Exception) { Log.e(TAG, "Delete failed: ${e.message}") }
        }

        private fun getCalendarId(context: Context): Long {
            try {
                val cursor = context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID),
                    null, null, null
                )
                cursor?.use { if (it.moveToFirst()) return it.getLong(0) }
            } catch (_: Exception) {}
            return -1
        }
    }

    fun syncCalendar() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_CALENDAR permission")
            return
        }

        val events = readUpcomingEvents()
        val calendarSync = XiaomiProto.CalendarSync.newBuilder()
            .setDisabled(false)

        for (event in events.take(MAX_EVENTS)) {
            calendarSync.addEvent(
                XiaomiProto.CalendarEvent.newBuilder()
                    .setTitle(event.title)
                    .setDescription(event.description)
                    .setLocation(event.location)
                    .setStart((event.startMs / 1000).toInt())
                    .setEnd((event.endMs / 1000).toInt())
                    .setAllDay(event.allDay)
                    .setNotifyMinutesBefore(event.reminderMin)
            )
        }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_SET)
            .setCalendar(XiaomiProto.Calendar.newBuilder().setCalendarSync(calendarSync))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Synced ${events.size} calendar events")
    }

    data class CalEvent(
        val title: String,
        val description: String,
        val location: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val reminderMin: Int,
    )

    private fun readUpcomingEvents(): List<CalEvent> {
        val now = System.currentTimeMillis()
        val weekLater = now + 7 * 24 * 60 * 60 * 1000L
        val events = mutableListOf<CalEvent>()

        try {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
            )

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), weekLater.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(1) ?: ""
                    val desc = it.getString(2) ?: ""
                    val loc = it.getString(3) ?: ""
                    val start = it.getLong(4)
                    val end = it.getLong(5)
                    val allDay = it.getInt(6) == 1

                    events.add(CalEvent(title, desc, loc, start, end, allDay, 15))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read calendar: ${e.message}")
        }

        return events
    }
}
