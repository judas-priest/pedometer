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

        // Try call() methods — how system/lockscreen reads steps
        val callMethods = listOf(
            "method_call_by_assistantscreen_and_health_from_table_day_step_data",
            "method_get_today_step",
            "method_get_day_step",
            "get_today_step_count",
            "get_step_count",
        )
        for (method in callMethods) {
            try {
                val extras = android.os.Bundle().apply {
                    putString("day_date", dateStr)
                    putString("date", dateStr)
                }
                val result = context.contentResolver.call(
                    Uri.parse("content://$AUTHORITY"),
                    method,
                    dateStr,
                    extras,
                )
                if (result != null && result.keySet().isNotEmpty()) {
                    Log.i(TAG, "Call '$method' for $dateStr: keys=${result.keySet()}")
                    for (key in result.keySet()) {
                        Log.i(TAG, "  $key = ${result.get(key)}")
                    }
                    val json = result.getString("result") ?: result.getString("data")
                    if (json != null) {
                        return parseDayJson(dateStr, json)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Call '$method' failed: ${e.message}")
            }
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
                    // Log ALL values for debugging
                    val allVals = (0 until cursor.columnCount).joinToString(" | ") { i ->
                        "${cursor.getColumnName(i)}=${cursor.getString(i)}"
                    }
                    Log.i(TAG, "  RAW: $allVals")
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
        // Read raw cumulative data for days+1 to compute daily diffs
        val rawDays = (0..days).mapNotNull { offset ->
            readDay(context, today.minusDays(offset.toLong()))
        }

        // Convert cumulative to daily by computing differences
        val result = mutableListOf<DayStepData>()
        for (i in 0 until rawDays.size - 1) {
            val curr = rawDays[i]
            val prev = rawDays[i + 1]
            val dailySteps = curr.totalSteps - prev.totalSteps
            if (dailySteps >= 0) {
                // Normal day (no reboot)
                result.add(curr.copy(
                    totalSteps = dailySteps,
                    walkSteps = (curr.walkSteps - prev.walkSteps).coerceAtLeast(0),
                    runSteps = (curr.runSteps - prev.runSteps).coerceAtLeast(0),
                ))
            } else {
                // Reboot happened — curr.totalSteps IS the daily count (from 0)
                result.add(curr)
            }
        }
        return result
    }

    fun readTodayActual(context: Context): DayStepData? {
        val today = LocalDate.now()
        val todayRaw = readDay(context, today) ?: return null
        val yesterdayRaw = readDay(context, today.minusDays(1))

        if (yesterdayRaw == null) return todayRaw

        val diff = todayRaw.totalSteps - yesterdayRaw.totalSteps
        return if (diff >= 0) {
            todayRaw.copy(
                totalSteps = diff,
                walkSteps = (todayRaw.walkSteps - yesterdayRaw.walkSteps).coerceAtLeast(0),
                runSteps = (todayRaw.runSteps - yesterdayRaw.runSteps).coerceAtLeast(0),
            )
        } else {
            // Reboot happened today — raw value is already daily
            todayRaw
        }
    }

    fun probeAllPaths(context: Context) {
        val paths = listOf(
            "/day_statistic", "/hour_statistic", "/minute_statistic",
            "/day_step", "/hour_step", "/minute_step",
            "/step_detail", "/step_summary", "/step_history",
            "/calorie", "/calories", "/day_calorie",
            "/sleep", "/sleep_data", "/heart_rate",
            "/activity", "/sport", "/workout",
        )
        for (path in paths) {
            try {
                val uri = Uri.parse("content://$AUTHORITY$path")
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    Log.i(TAG, "PROBE '$path': ${cursor.count} rows, cols=${cursor.columnNames.joinToString()}")
                    if (cursor.moveToFirst()) {
                        val row = (0 until cursor.columnCount).joinToString(" | ") { i ->
                            "${cursor.getColumnName(i)}=${cursor.getString(i)}"
                        }
                        Log.i(TAG, "  FIRST: $row")
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                // silent
            }
        }

        // Also try call methods
        val methods = listOf(
            "method_get_today_step_count",
            "method_get_current_step",
            "method_call_by_assistantscreen_and_health_from_table_day_step_data",
            "method_call_by_assistantscreen_and_health_from_table_hour_step_data",
            "method_call_by_assistantscreen_and_health_from_table_minute_step_data",
        )
        for (method in methods) {
            try {
                val extras = android.os.Bundle().apply {
                    putString("day_date", java.time.LocalDate.now().format(DATE_FMT))
                }
                val result = context.contentResolver.call(
                    Uri.parse("content://$AUTHORITY"), method, null, extras
                )
                if (result != null && result.keySet().isNotEmpty()) {
                    Log.i(TAG, "CALL '$method': keys=${result.keySet()}")
                    for (key in result.keySet()) {
                        val v = result.get(key)
                        Log.i(TAG, "  $key = $v (${v?.javaClass?.simpleName})")
                    }
                }
            } catch (e: Exception) {
                // silent
            }
        }
    }

    private fun getIntSafe(cursor: android.database.Cursor, col: String): Int {
        val idx = cursor.getColumnIndex(col)
        return if (idx >= 0) cursor.getInt(idx) else 0
    }
}
