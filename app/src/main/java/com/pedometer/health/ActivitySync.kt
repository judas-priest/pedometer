package com.pedometer.health

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Activity file sync — fetches historical health data from watch.
 * Files contain per-minute heart rate, steps, SpO2, stress, sleep, etc.
 *
 * Protocol:
 * 1. Request file list (type=8, sub=1 today / sub=2 past)
 * 2. Watch responds with 7-byte file IDs
 * 3. Request each file (type=8, sub=3)
 * 4. Watch sends chunks (total|num|data)
 * 5. ACK each file (type=8, sub=5)
 */
class ActivitySync(
    private val protocolHandler: ProtocolHandler,
    private val onDailySummary: (DailySummary) -> Unit = {},
    private val onHeartRateSamples: (List<HeartRateSample>) -> Unit = {},
    private val onSleepData: (SleepData) -> Unit = {},
    private val onWorkout: (WorkoutSummary) -> Unit = {},
    private val onHourlySteps: (String, List<Pair<Int, Int>>) -> Unit = { _, _ -> }, // date, list of (hour, steps)
) {
    companion object {
        private const val TAG = "ActivitySync"
        const val COMMAND_TYPE = 8
        const val CMD_FETCH_TODAY = 1
        const val CMD_FETCH_PAST = 2
        const val CMD_FETCH_FILE = 3
        const val CMD_FETCH_ACK = 5
    }

    data class HeartRateSample(val timestamp: Long, val bpm: Int)

    data class SleepStage(val timestamp: Long, val phase: Int) // 0=awake, 1=light, 2=deep, 3=REM

    data class SleepData(
        val bedTime: Long,       // epoch millis
        val wakeupTime: Long,    // epoch millis
        val totalMinutes: Int,
        val deepMinutes: Int,
        val lightMinutes: Int,
        val remMinutes: Int,
        val awakeMinutes: Int,
        val stages: List<SleepStage> = emptyList(),
    )

    data class WorkoutSummary(
        val startTime: Long,     // epoch millis
        val endTime: Long,       // epoch millis
        val sportType: Int,      // subtype from file ID
        val sportName: String,
        val durationSec: Int,
        val distanceM: Int = 0,
        val calories: Int = 0,
        val hrAvg: Int = 0,
        val hrMax: Int = 0,
        val hrMin: Int = 0,
        val steps: Int = 0,
    )

    data class DailySummary(
        val date: Long, // epoch seconds
        val steps: Int = 0,
        val hrResting: Int = 0,
        val hrMax: Int = 0,
        val hrMin: Int = 0,
        val hrAvg: Int = 0,
        val stressAvg: Int = 0,
        val stressMax: Int = 0,
        val stressMin: Int = 0,
        val spo2Avg: Int = 0,
        val spo2Max: Int = 0,
        val spo2Min: Int = 0,
        val calories: Int = 0,
    )

    // Pending file chunks
    private val pendingChunks = mutableMapOf<String, MutableList<ByteArray>>()
    private val pendingFileIds = mutableListOf<ByteArray>()
    private var expectedChunks = 0
    private var currentFileId: ByteArray? = null

    fun requestToday() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_FETCH_TODAY)
            .setHealth(XiaomiProto.Health.newBuilder()
                .setActivitySyncRequestToday(
                    XiaomiProto.ActivitySyncRequestToday.newBuilder()
                        .setUnknown1(0)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Requested today's activity files")
    }

    fun requestPast() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_FETCH_PAST)
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Requested past activity files")
    }

    private fun requestFile(fileId: ByteArray) {
        currentFileId = fileId
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_FETCH_FILE)
            .setHealth(XiaomiProto.Health.newBuilder()
                .setActivityRequestFileIds(com.google.protobuf.ByteString.copyFrom(fileId)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Requesting file: ${fileId.toHex()}")
    }

    private fun ackFile(fileId: ByteArray) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_FETCH_ACK)
            .setHealth(XiaomiProto.Health.newBuilder()
                .setActivitySyncAckFileIds(com.google.protobuf.ByteString.copyFrom(fileId)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "ACK file: ${fileId.toHex()}")
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_FETCH_TODAY, CMD_FETCH_PAST -> {
                // Response contains file IDs
                if (cmd.hasHealth()) {
                    val health = cmd.health
                    Log.i(TAG, "Activity fetch response sub=${cmd.subtype}: hasFileIds=${health.hasActivityRequestFileIds()}, health=$health")
                    val ids = health.activityRequestFileIds
                    if (ids != null && ids.size() > 0) {
                        parseFileIds(ids.toByteArray())
                    } else {
                        Log.i(TAG, "No activity files available (sub=${cmd.subtype})")
                    }
                }
            }
            CMD_FETCH_FILE -> {
                // File chunk received
                if (cmd.hasHealth() && cmd.health.activityRequestFileIds != null) {
                    handleRawData(cmd.health.activityRequestFileIds.toByteArray())
                }
            }
            else -> Log.d(TAG, "Activity sync subtype: ${cmd.subtype}")
        }
    }

    /**
     * Handle raw activity data that comes outside protobuf (on data channel).
     * Called from ProtocolHandler when it receives activity file chunks.
     */
    fun handleRawData(data: ByteArray) {
        if (data.size < 4) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val totalChunks = bb.short.toInt() and 0xFFFF
        val chunkNum = bb.short.toInt() and 0xFFFF
        val chunkData = ByteArray(data.size - 4)
        bb.get(chunkData)

        val fileKey = currentFileId?.toHex() ?: "unknown"
        Log.d(TAG, "Chunk $chunkNum/$totalChunks for $fileKey (${chunkData.size} bytes)")

        val chunks = pendingChunks.getOrPut(fileKey) { mutableListOf() }
        // Ensure list is right size
        while (chunks.size < chunkNum) chunks.add(ByteArray(0))
        if (chunkNum > 0 && chunkNum <= chunks.size) {
            chunks[chunkNum - 1] = chunkData
        } else {
            chunks.add(chunkData)
        }

        if (chunkNum == totalChunks) {
            // All chunks received, assemble file
            val fullData = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            pendingChunks.remove(fileKey)
            Log.i(TAG, "File complete: $fileKey (${fullData.size} bytes)")

            // Extract fileId from first 7 bytes of the data itself
            if (fullData.size >= 7) {
                val fileId = fullData.copyOfRange(0, 7)
                val info = decodeFileId(fileId)
                Log.i(TAG, "File ID from data: ${fileId.toHex()} → $info")
                processFile(fileId, fullData)
                ackFile(fileId)
            }

            // Request next file if any
            if (pendingFileIds.isNotEmpty()) {
                val nextId = pendingFileIds.removeAt(0)
                requestFile(nextId)
            }
        }
    }

    private fun parseFileIds(data: ByteArray) {
        val fileCount = data.size / 7
        Log.i(TAG, "Got $fileCount file IDs")

        pendingFileIds.clear()
        for (i in 0 until fileCount) {
            val fileId = data.copyOfRange(i * 7, (i + 1) * 7)
            val info = decodeFileId(fileId)
            Log.d(TAG, "  File $i: ${fileId.toHex()} → $info")
            pendingFileIds.add(fileId)
        }

        // Start fetching first file
        if (pendingFileIds.isNotEmpty()) {
            requestFile(pendingFileIds.removeAt(0))
        }
    }

    private fun processFile(fileId: ByteArray, data: ByteArray) {
        if (data.size < 12) {
            Log.w(TAG, "File too small: ${data.size}")
            return
        }

        val info = decodeFileId(fileId)
        Log.i(TAG, "Processing: $info")

        try {
            when {
                info.type == 0 && info.subtype == 0 && info.detailType == 0 ->
                    parseDailyDetails(fileId, data)
                info.type == 0 && info.subtype == 0 && info.detailType == 1 ->
                    parseDailySummary(fileId, data)
                info.type == 0 && info.subtype == 3 && info.detailType == 0 ->
                    parseSleepStages(fileId, data)
                info.type == 0 && info.subtype == 8 ->
                    parseSleepDetails(fileId, data)
                info.type == 0 && info.subtype == 6 ->
                    parseManualSamples(fileId, data)
                info.type == 1 && info.detailType == 1 ->
                    parseWorkoutSummary(fileId, data)
                else -> Log.d(TAG, "Skipping file type=${info.type} sub=${info.subtype} detail=${info.detailType}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse file: ${fileId.toHex()}", e)
        }
    }

    private fun parseDailyDetails(fileId: ByteArray, data: ByteArray) {
        // Skip 7-byte fileId + 1 padding
        if (data.size < 12) return

        val info = decodeFileId(fileId)
        val headerSize = when {
            info.version >= 4 -> 6
            info.version >= 3 -> 5
            else -> 4
        }

        val headerStart = 8
        if (data.size < headerStart + headerSize + 4) return // +4 for CRC

        // Parse header nibbles to determine which groups are present
        val header = data.copyOfRange(headerStart, headerStart + headerSize)
        val dataStart = headerStart + headerSize
        val dataEnd = data.size - 4 // exclude CRC32

        if (dataStart >= dataEnd) return

        // Determine groups from header
        val groups = mutableListOf<Int>() // group sizes in bits
        for (b in header) {
            val hi = (b.toInt() shr 4) and 0x0F
            val lo = b.toInt() and 0x0F
            groups.add(hi)
            groups.add(lo)
        }

        // Parse per-minute samples
        val samples = mutableListOf<HeartRateSample>()
        val stepsByHour = mutableMapOf<Int, Int>() // hour -> accumulated steps
        var pos = dataStart
        var minuteOffset = 0
        val baseTimestamp = info.timestamp

        while (pos < dataEnd) {
            var hr = 0
            var steps = 0
            var includeExtra = false

            try {
                for (gi in groups.indices) {
                    val g = groups[gi]
                    if (g and 0x8 == 0) continue // group not present

                    when (gi) {
                        0 -> { // Group 1: steps (16-bit)
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
                            steps = (v shr 1) and 0x3FFF
                            includeExtra = (v and 0x8000) != 0
                            pos += 2
                        }
                        1 -> { // Group 2: activity (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            pos += 1
                        }
                        2 -> { // Group 3: unknown (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            pos += 1
                        }
                        3 -> { // Group 4: distance (16-bit)
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            pos += 2
                        }
                        4 -> { // Group 5: heart rate (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            hr = data[pos].toInt() and 0xFF
                            pos += 1
                        }
                        5 -> { // Group 6: energy (8-bit)
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            pos += 1
                        }
                        6 -> { // Group 7: unknown (16-bit)
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            pos += 2
                        }
                        7 -> { // Group 8: SpO2 (8-bit) - version 3+
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            pos += 1
                        }
                        8 -> { // Group 9: stress (8-bit) - version 3+
                            if (pos >= dataEnd) { pos = dataEnd; break }
                            pos += 1
                        }
                        9 -> { // Group 10: light (16-bit) - version 4+
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            pos += 2
                        }
                        10 -> { // Group 11: body momentum (16-bit) - version 4+
                            if (pos + 2 > dataEnd) { pos = dataEnd; break }
                            pos += 2
                        }
                    }
                }

                if (includeExtra && pos < dataEnd) {
                    pos += 1 // skip extra byte
                }
            } catch (_: Exception) {
                break
            }

            val sampleTs = baseTimestamp + minuteOffset * 60L
            if (hr > 0 && hr < 255) {
                samples.add(HeartRateSample(timestamp = sampleTs * 1000, bpm = hr))
            }
            if (steps > 0) {
                val hour = java.time.Instant.ofEpochSecond(sampleTs)
                    .atZone(java.time.ZoneId.systemDefault()).hour
                stepsByHour[hour] = (stepsByHour[hour] ?: 0) + steps
            }
            minuteOffset++
        }

        val totalStepsFromDetails = stepsByHour.values.sum()
        Log.i(TAG, "Parsed ${samples.size} HR samples, $totalStepsFromDetails steps in ${stepsByHour.size} hours from daily details ($minuteOffset minutes)")
        if (samples.isNotEmpty()) {
            onHeartRateSamples(samples)
        }

        // Emit hourly steps
        if (stepsByHour.isNotEmpty()) {
            val dateStr = java.time.Instant.ofEpochSecond(baseTimestamp)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            val hourlyList = stepsByHour.map { (hour, s) -> Pair(hour, s) }.sortedBy { it.first }
            Log.i(TAG, "Hourly steps for $dateStr: ${hourlyList.map { "${it.first}h=${it.second}" }}")
            onHourlySteps(dateStr, hourlyList)
        }
    }

    private fun parseDailySummary(fileId: ByteArray, data: ByteArray) {
        val info = decodeFileId(fileId)
        // Header size depends on version (from Gadgetbridge DailySummaryParser)
        val headerSize = when {
            info.version >= 5 -> 4
            else -> 3
        }
        val dataStart = 8 + headerSize // 7 fileId + 1 padding + header
        if (data.size < dataStart + 41 + 4) {
            Log.w(TAG, "Daily summary too small: ${data.size} (need ${dataStart + 45})")
            return
        }

        // Dump raw bytes for debugging
        val rawHex = data.copyOfRange(dataStart, minOf(dataStart + 60, data.size - 4))
            .joinToString(" ") { "%02x".format(it) }
        Log.i(TAG, "Daily summary raw (offset=$dataStart, v=${info.version}): $rawHex")

        val bb = ByteBuffer.wrap(data, dataStart, data.size - dataStart - 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        val steps = bb.int
        bb.get() // unknown1
        bb.get() // unknown2
        bb.get() // unknown3
        val hrResting = bb.get().toInt() and 0xFF
        val hrMax = bb.get().toInt() and 0xFF
        val hrMaxTs = bb.int
        val hrMin = bb.get().toInt() and 0xFF
        val hrMinTs = bb.int
        val hrAvg = bb.get().toInt() and 0xFF
        val stressAvg = bb.get().toInt() and 0xFF
        val stressMax = bb.get().toInt() and 0xFF
        val stressMin = bb.get().toInt() and 0xFF
        bb.get(); bb.get(); bb.get() // standing hours bitfield
        val calories = bb.short.toInt() and 0xFFFF
        bb.get(); bb.get(); bb.get() // unknowns
        val spo2Max = bb.get().toInt() and 0xFF
        val spo2MaxTs = bb.int
        val spo2Min = bb.get().toInt() and 0xFF
        val spo2MinTs = bb.int
        val spo2Avg = bb.get().toInt() and 0xFF

        val summary = DailySummary(
            date = info.timestamp,
            steps = steps,
            hrResting = hrResting,
            hrMax = hrMax,
            hrMin = hrMin,
            hrAvg = hrAvg,
            stressAvg = stressAvg,
            stressMax = stressMax,
            stressMin = stressMin,
            spo2Avg = spo2Avg,
            spo2Max = spo2Max,
            spo2Min = spo2Min,
            calories = calories,
        )

        Log.i(TAG, "Daily summary: steps=$steps HR=$hrAvg/$hrMin-$hrMax SpO2=$spo2Avg stress=$stressAvg")
        onDailySummary(summary)
    }

    private fun sportName(subtype: Int): String = when (subtype) {
        0x01 -> "Бег"
        0x02, 0x16 -> "Ходьба"
        0x03 -> "Беговая дорожка"
        0x06, 0x17 -> "Велосипед"
        0x07 -> "Велотренажёр"
        0x08 -> "Свободная"
        0x09 -> "Плавание"
        0x0B -> "Эллиптический"
        0x0D -> "Гребля"
        0x0E -> "Скакалка"
        0x10 -> "HIIT"
        else -> "Тренировка #$subtype"
    }

    private fun parseWorkoutSummary(fileId: ByteArray, data: ByteArray) {
        if (data.size < 30) {
            Log.w(TAG, "Workout summary too small: ${data.size}")
            return
        }

        val info = decodeFileId(fileId)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8) // skip fileId + padding

        // Skip version-dependent header
        val headerSize = when {
            info.version >= 9 -> 13
            info.version >= 7 -> 8
            info.version >= 5 -> 5
            else -> 4
        }
        if (bb.remaining() < headerSize) return
        bb.position(bb.position() + headerSize)

        // Common fields across most workout types (may have sport type short first for v2)
        // Try to extract: startTime, endTime, duration, distance (if outdoor), calories, HR
        try {
            // Some formats have a short workout type before timestamps
            val hasWorkoutType = info.subtype in listOf(0x06, 0x16, 0x17)
            if (hasWorkoutType && bb.remaining() >= 2) {
                bb.short // skip workout type short
            }

            val startTime = if (bb.remaining() >= 4) bb.int.toLong() and 0xFFFFFFFFL else 0L
            val endTime = if (bb.remaining() >= 4) bb.int.toLong() and 0xFFFFFFFFL else 0L
            val duration = if (bb.remaining() >= 4) bb.int else 0

            // Distance (4 bytes) — present for outdoor sports
            val hasDistance = info.subtype in listOf(0x01, 0x02, 0x03, 0x06, 0x09, 0x16, 0x17)
            val distance = if (hasDistance && bb.remaining() >= 4) {
                if (info.subtype == 0x03 || info.subtype == 0x16 || info.subtype == 0x17) {
                    bb.int // skip unknown 4 bytes before distance
                    if (bb.remaining() >= 4) bb.int else 0
                } else {
                    bb.int
                }
            } else 0

            // Calories (2 bytes) — after distance or directly
            val calories = if (bb.remaining() >= 2) bb.short.toInt() and 0xFFFF else 0

            // Try to find HR bytes — scan forward for plausible HR values
            var hrAvg = 0; var hrMax = 0; var hrMin = 0
            // HR is typically 3 consecutive bytes between 30-220
            val remaining = ByteArray(bb.remaining().coerceAtMost(40))
            val savedPos = bb.position()
            if (remaining.isNotEmpty()) bb.get(remaining)

            for (i in 0 until remaining.size - 2) {
                val a = remaining[i].toInt() and 0xFF
                val b = remaining[i + 1].toInt() and 0xFF
                val c = remaining[i + 2].toInt() and 0xFF
                if (a in 30..220 && b in 30..220 && c in 30..220 && b >= a && a >= c) {
                    hrAvg = a; hrMax = b; hrMin = c
                    break
                }
            }

            if (startTime > 0 && endTime > 0) {
                val workout = WorkoutSummary(
                    startTime = startTime * 1000,
                    endTime = endTime * 1000,
                    sportType = info.subtype,
                    sportName = sportName(info.subtype),
                    durationSec = duration,
                    distanceM = distance,
                    calories = calories,
                    hrAvg = hrAvg,
                    hrMax = hrMax,
                    hrMin = hrMin,
                )
                Log.i(TAG, "Workout: ${workout.sportName} ${duration / 60}min dist=${distance}m cal=$calories HR=$hrAvg/$hrMin-$hrMax")
                onWorkout(workout)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse workout fields", e)
        }
    }

    private fun parseManualSamples(fileId: ByteArray, data: ByteArray) {
        // Manual SpO2/stress measurements — subtype=6
        // Format per record: timestamp(4) + heartRate(1) + type(1) + value(1) = 7 bytes
        // type: 2=SpO2, 1=stress
        if (data.size < 15) return // 8 header + 7 record min

        val rawHex = data.copyOfRange(8, minOf(data.size, 30))
            .joinToString(" ") { "%02x".format(it) }
        Log.i(TAG, "Manual samples raw: $rawHex (total ${data.size} bytes)")

        val dataEnd = data.size - 4 // exclude CRC32
        var pos = 8

        while (pos + 7 <= dataEnd) {
            val bb = ByteBuffer.wrap(data, pos, 7).order(ByteOrder.LITTLE_ENDIAN)
            val ts = bb.int.toLong() and 0xFFFFFFFFL
            val hr = bb.get().toInt() and 0xFF
            val type = bb.get().toInt() and 0xFF
            val value = bb.get().toInt() and 0xFF
            pos += 7

            Log.i(TAG, "Manual sample: ts=$ts hr=$hr type=$type value=$value")

            // Use current time for DB storage since file timestamps are non-standard
            val now = System.currentTimeMillis() / 1000

            when (type) {
                2 -> {
                    if (value in 70..100) {
                        Log.i(TAG, "SpO2: $value% (HR=$hr)")
                        onDailySummary(DailySummary(
                            date = now, spo2Avg = value, spo2Max = value, spo2Min = value,
                        ))
                    }
                }
                3 -> {
                    if (value in 1..100) {
                        Log.i(TAG, "Stress: $value (HR=$hr)")
                        onDailySummary(DailySummary(
                            date = now, stressAvg = value, stressMax = value, stressMin = value,
                        ))
                    }
                }
                else -> Log.d(TAG, "Manual type=$type value=$value")
            }
        }
    }

    private fun parseSleepDetails(fileId: ByteArray, data: ByteArray) {
        // ACTIVITY_SLEEP (subtype=8) — detailed sleep data with HR, SpO2, stages
        if (data.size < 20) return
        val info = decodeFileId(fileId)

        val headerSize = when {
            info.version >= 5 -> 2
            else -> 1
        }

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8) // skip fileId + padding

        val header = ByteArray(headerSize)
        bb.get(header)

        val isAwake = bb.get().toInt() and 0xFF
        val bedTime = bb.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = bb.int.toLong() and 0xFFFFFFFFL

        if (bedTime == 0L || wakeupTime == 0L) {
            Log.w(TAG, "Sleep details: empty bed/wake times")
            return
        }

        val totalMinutes = ((wakeupTime - bedTime) / 60).toInt()
        Log.i(TAG, "Sleep details: bed=${bedTime} wake=${wakeupTime} total=${totalMinutes}min isAwake=$isAwake")

        // Skip quality byte for v4+
        if (info.version >= 4 && bb.remaining() > 0) bb.get()

        // Skip extra bytes for v5
        if (info.version >= 5 && bb.remaining() >= 17) {
            bb.position(bb.position() + 9) // unknown
            bb.int // bedTime2
            bb.int // wakeupTime2
        }

        // Try to read HR samples
        val hrSamples = mutableListOf<HeartRateSample>()
        if (bb.remaining() >= 4 && headerHasField(header, 0)) {
            val unit = bb.short.toInt() and 0xFFFF // sample rate in seconds
            val count = bb.short.toInt() and 0xFFFF
            if (count > 0 && bb.remaining() >= 4 + count) {
                val firstRecordTime = if (info.version >= 2) bb.int.toLong() and 0xFFFFFFFFL else bedTime
                for (i in 0 until count) {
                    if (bb.remaining() < 1) break
                    val hr = bb.get().toInt() and 0xFF
                    if (hr > 0 && hr < 255) {
                        hrSamples.add(HeartRateSample(
                            timestamp = (firstRecordTime + i * unit) * 1000,
                            bpm = hr
                        ))
                    }
                }
                Log.i(TAG, "Sleep HR: ${hrSamples.size} samples (unit=${unit}s)")
            }
        }

        // Create sleep data (without detailed stage durations — those come from subtype=3)
        val sleep = SleepData(
            bedTime = bedTime * 1000,
            wakeupTime = wakeupTime * 1000,
            totalMinutes = totalMinutes,
            deepMinutes = 0,
            lightMinutes = 0,
            remMinutes = 0,
            awakeMinutes = 0,
        )

        onSleepData(sleep)
        if (hrSamples.isNotEmpty()) onHeartRateSamples(hrSamples)
    }

    private fun headerHasField(header: ByteArray, index: Int): Boolean {
        if (header.isEmpty()) return false
        val byteIdx = index / 8
        val bitIdx = index % 8
        return byteIdx < header.size && (header[byteIdx].toInt() and (1 shl bitIdx)) != 0
    }

    private fun parseSleepStages(fileId: ByteArray, data: ByteArray) {
        if (data.size < 30) {
            Log.w(TAG, "Sleep stages file too small: ${data.size}")
            return
        }

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8) // skip fileId + padding

        val unk1 = ByteArray(7)
        bb.get(unk1)

        val sleepDuration = bb.short.toInt() and 0xFFFF
        val bedTime = bb.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = bb.int.toLong() and 0xFFFFFFFFL

        val unk2 = ByteArray(3)
        bb.get(unk2)

        val deepDuration = bb.short.toInt() and 0xFFFF
        val lightDuration = bb.short.toInt() and 0xFFFF
        val remDuration = bb.short.toInt() and 0xFFFF
        val awakeDuration = bb.short.toInt() and 0xFFFF

        if (bedTime == 0L || wakeupTime == 0L || sleepDuration == 0) {
            Log.w(TAG, "Sleep data empty, skipping")
            return
        }

        val unk3 = if (bb.remaining() > 0) bb.get() else 0

        val stages = mutableListOf<SleepStage>()
        while (bb.remaining() >= 5) {
            val time = bb.int.toLong() and 0xFFFFFFFFL
            val phase = bb.get().toInt() and 0xFF
            stages.add(SleepStage(timestamp = time * 1000, phase = phase))
        }

        val sleep = SleepData(
            bedTime = bedTime * 1000,
            wakeupTime = wakeupTime * 1000,
            totalMinutes = sleepDuration,
            deepMinutes = deepDuration,
            lightMinutes = lightDuration,
            remMinutes = remDuration,
            awakeMinutes = awakeDuration,
            stages = stages,
        )

        Log.i(TAG, "Sleep: ${sleepDuration}min (deep=$deepDuration light=$lightDuration REM=$remDuration awake=$awakeDuration) ${stages.size} stages")
        onSleepData(sleep)
    }

    data class FileIdInfo(
        val timestamp: Long,
        val timezone: Int,
        val version: Int,
        val type: Int,      // 0=Activity, 1=Sports
        val subtype: Int,
        val detailType: Int, // 0=Details, 1=Summary, 2=GPS
    )

    private fun decodeFileId(id: ByteArray): FileIdInfo {
        val bb = ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = bb.int.toLong() and 0xFFFFFFFFL
        val tz = bb.get().toInt() and 0xFF
        val version = bb.get().toInt() and 0xFF
        val flags = bb.get().toInt() and 0xFF
        return FileIdInfo(
            timestamp = timestamp,
            timezone = tz,
            version = version,
            type = (flags shr 7) and 1,
            subtype = (flags shr 2) and 0x1F,
            detailType = flags and 0x03,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
