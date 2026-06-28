package com.pedometer.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectReader(private val context: Context) {
    companion object {
        private const val TAG = "HealthConnectReader"

        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )
    }

    private var client: HealthConnectClient? = null

    fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    fun getClient(): HealthConnectClient? {
        if (client == null && isAvailable()) {
            client = HealthConnectClient.getOrCreate(context)
        }
        return client
    }

    suspend fun readTodaySteps(): Long {
        val hc = getClient() ?: return 0
        return try {
            val now = Instant.now()
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val response = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                )
            )
            val steps = response[StepsRecord.COUNT_TOTAL] ?: 0
            Log.i(TAG, "Health Connect today steps: $steps")
            steps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read steps from Health Connect", e)
            0
        }
    }

    suspend fun readLatestHeartRate(): Int {
        val hc = getClient() ?: return 0
        return try {
            val now = Instant.now()
            val oneHourAgo = now.minusSeconds(3600)
            val response = hc.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneHourAgo, now),
                )
            )
            val latest = response.records.lastOrNull()?.samples?.lastOrNull()
            val hr = latest?.beatsPerMinute?.toInt() ?: 0
            if (hr > 0) Log.i(TAG, "Health Connect heart rate: $hr")
            hr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read heart rate from Health Connect", e)
            0
        }
    }

    suspend fun writeSteps(steps: Long, startTime: Instant, endTime: Instant): Boolean {
        val hc = getClient() ?: return false
        return try {
            val zone = ZoneId.systemDefault()
            val record = StepsRecord(
                count = steps,
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = zone.rules.getOffset(startTime),
                endZoneOffset = zone.rules.getOffset(endTime),
            )
            hc.insertRecords(listOf(record))
            Log.i(TAG, "Wrote $steps steps to Health Connect")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write steps to Health Connect", e)
            false
        }
    }

    suspend fun readWeekSteps(): List<Pair<String, Long>> {
        val hc = getClient() ?: return emptyList()
        return try {
            val today = LocalDate.now()
            val result = mutableListOf<Pair<String, Long>>()
            for (i in 6 downTo 0) {
                val date = today.minusDays(i.toLong())
                val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
                val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                val response = hc.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                    )
                )
                val steps = response[StepsRecord.COUNT_TOTAL] ?: 0
                result.add(date.toString() to steps)
            }
            Log.i(TAG, "Health Connect week: ${result.map { "${it.first}=${it.second}" }}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read week steps", e)
            emptyList()
        }
    }
}
