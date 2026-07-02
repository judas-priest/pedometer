package com.pedometer.health.parsers

import android.util.Log
import com.pedometer.health.ActivitySync

/**
 * Parses daily details files (subtype=0, detailType=0).
 * Contains per-minute: steps, HR, SpO2, stress, distance.
 */
object DailyDetailsParser {
    private const val TAG = "DailyDetailsParser"

    data class Result(
        val hrSamples: List<ActivitySync.HeartRateSample>,
        val hourlySteps: Map<Int, Int>, // hour -> steps
        val totalDistanceM: Int,
        val lastSpo2: Int,
        val lastStress: Int,
        val activeMinutes: Int,
    )

    fun parse(fileId: ByteArray, data: ByteArray): Result? {
        if (data.size < 12) return null
        val info = ParserUtils.decodeFileId(fileId)

        val headerSize = when {
            info.version >= 4 -> 6
            info.version >= 3 -> 5
            else -> 4
        }
        val headerStart = 8
        if (data.size < headerStart + headerSize + 4) return null

        val header = data.copyOfRange(headerStart, headerStart + headerSize)
        val dataStart = headerStart + headerSize
        val dataEnd = data.size - 4

        if (dataStart >= dataEnd) return null

        val groups = mutableListOf<Int>()
        for (b in header) {
            val hi = (b.toInt() shr 4) and 0x0F
            val lo = b.toInt() and 0x0F
            groups.add(hi)
            groups.add(lo)
        }

        val samples = mutableListOf<ActivitySync.HeartRateSample>()
        val stepsByHour = mutableMapOf<Int, Int>()
        var totalDistanceCm = 0L
        var lastSpo2 = 0
        var lastStress = 0
        var pos = dataStart
        var minuteOffset = 0
        val baseTimestamp = info.timestamp

        while (pos < dataEnd) {
            var hr = 0
            var steps = 0
            var distanceCm = 0
            var spo2 = 0
            var stress = 0
            var includeExtra = false

            try {
                for (gi in groups.indices) {
                    val g = groups[gi]
                    if (g and 0x8 == 0) continue

                    when (gi) {
                        0 -> { // steps (16-bit)
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
                            steps = v and 0x3FFF
                            includeExtra = (v and 0x4000) != 0
                            pos += 2
                        }
                        1 -> { if (pos >= dataEnd) { pos = dataEnd; break }; pos += 1 } // activity
                        2 -> { if (pos >= dataEnd) { pos = dataEnd; break }; pos += 1 } // unknown
                        3 -> { // distance (16-bit)
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            val d = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
                            distanceCm = d * 100
                            pos += 2
                        }
                        4 -> { // heart rate (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            hr = data[pos].toInt() and 0xFF
                            pos += 1
                        }
                        5 -> { if (pos >= dataEnd) { pos = dataEnd; break }; pos += 1 } // energy
                        6 -> { if (pos + 2 > dataEnd) { pos = dataEnd; break }; pos += 2 } // unknown
                        7 -> { // SpO2 (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            spo2 = data[pos].toInt() and 0xFF
                            pos += 1
                        }
                        8 -> { // stress (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            val s = data[pos].toInt() and 0xFF
                            if (s != 255) stress = s
                            pos += 1
                        }
                        9 -> { if (pos + 2 > dataEnd) { pos = dataEnd; break }; pos += 2 } // light
                        10 -> { if (pos + 2 > dataEnd) { pos = dataEnd; break }; pos += 2 } // momentum
                    }
                }
                if (includeExtra && pos < dataEnd) pos += 1
            } catch (_: Exception) { break }

            val sampleTs = baseTimestamp + minuteOffset * 60L
            if (hr > 0 && hr < 255) {
                samples.add(ActivitySync.HeartRateSample(timestamp = sampleTs * 1000, bpm = hr))
            }
            if (steps > 0) {
                val hour = java.time.Instant.ofEpochSecond(sampleTs)
                    .atZone(java.time.ZoneId.systemDefault()).hour
                stepsByHour[hour] = (stepsByHour[hour] ?: 0) + steps
            }
            totalDistanceCm += distanceCm
            if (spo2 in 70..100) lastSpo2 = spo2
            if (stress in 1..100) lastStress = stress
            minuteOffset++
        }

        val totalSteps = stepsByHour.values.sum()
        val totalDistanceM = (totalDistanceCm / 100).toInt()
        val activeMinutes = stepsByHour.entries.sumOf { (_, s) -> (s / 10).coerceAtMost(60) }

        Log.i(TAG, "Parsed ${samples.size} HR, $totalSteps steps, ${totalDistanceM}m, spo2=$lastSpo2, stress=$lastStress ($minuteOffset min)")

        return Result(
            hrSamples = samples,
            hourlySteps = stepsByHour,
            totalDistanceM = totalDistanceM,
            lastSpo2 = lastSpo2,
            lastStress = lastStress,
            activeMinutes = activeMinutes,
        )
    }
}
