package com.pedometer.health

import android.content.Context
import android.net.Uri
import android.util.Log
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DayStepData(
    val date: String,
    val totalSteps: Int,
    val walkSteps: Int,
    val runSteps: Int,
    val walkMinutes: Int,
    val runMinutes: Int,
)

object StepProviderReader {
    private const val TAG = "StepProviderReader"
    private const val AUTHORITY = "com.oplus.healthservice.stepprovider"
    private val URI_DAY = Uri.parse("content://$AUTHORITY/day_statistic")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun readToday(context: Context): DayStepData? {
        return readDay(context, LocalDate.now())
    }

    fun readDay(context: Context, date: LocalDate): DayStepData? {
        val dateStr = date.format(DATE_FMT)

        // Try ContentProvider call() method first (how system uses it)
        try {
            val extras = android.os.Bundle().apply {
                putString("day_date", dateStr)
            }
            val result = context.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                "method_call_by_assistantscreen_and_health_from_table_day_step_data",
                null,
                extras,
            )
            if (result != null) {
                val json = result.getString("result") ?: result.getString("data")
                Log.i(TAG, "Call result for $dateStr: $json (keys: ${result.keySet()})")
                if (json != null) {
                    return parseDayJson(dateStr, json)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Call method failed for $dateStr: ${e.message}")
        }

        // Fallback: query
        try {
            val cursor = context.contentResolver.query(
                URI_DAY, null, "day_date=?", arrayOf(dateStr), null
            )
            if (cursor != null) {
                var data: DayStepData? = null
                Log.i(TAG, "Query $dateStr: ${cursor.count} rows, cols=${cursor.columnNames.joinToString()}")
                if (cursor.moveToFirst()) {
                    data = DayStepData(
                        date = dateStr,
                        totalSteps = getIntSafe(cursor, "day_step"),
                        walkSteps = getIntSafe(cursor, "day_step_walk"),
                        runSteps = getIntSafe(cursor, "day_step_run"),
                        walkMinutes = getIntSafe(cursor, "day_offset_walk"),
                        runMinutes = getIntSafe(cursor, "day_offset_run"),
                    )
                }
                cursor.close()
                return data
            }
        } catch (e: Exception) {
            Log.d(TAG, "Query failed for $dateStr: ${e.message}")
        }

        return null
    }

    private fun parseDayJson(dateStr: String, json: String): DayStepData? {
        return try {
            // Parse JSON like: [{"date":"2026-06-28","step":10752,...}]
            val clean = json.trimStart('[').trimEnd(']')
            val map = mutableMapOf<String, String>()
            clean.split(",").forEach { pair ->
                val kv = pair.split(":", limit = 2)
                if (kv.size == 2) {
                    map[kv[0].trim().trim('"')] = kv[1].trim().trim('"')
                }
            }
            DayStepData(
                date = dateStr,
                totalSteps = map["step"]?.toIntOrNull() ?: 0,
                walkSteps = map["stepWalk"]?.toIntOrNull() ?: 0,
                runSteps = map["stepRun"]?.toIntOrNull() ?: 0,
                walkMinutes = map["offsetWalk"]?.toIntOrNull() ?: 0,
                runMinutes = map["offsetRun"]?.toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: $json", e)
            null
        }
    }

    fun readHistory(context: Context, days: Int = 7): List<DayStepData> {
        val today = LocalDate.now()
        return (0 until days).mapNotNull { offset ->
            readDay(context, today.minusDays(offset.toLong()))
        }
    }

    private fun getIntSafe(cursor: android.database.Cursor, col: String): Int {
        val idx = cursor.getColumnIndex(col)
        return if (idx >= 0) cursor.getInt(idx) else 0
    }
}
