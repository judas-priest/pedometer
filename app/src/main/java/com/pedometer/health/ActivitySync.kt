package com.pedometer.health

import android.content.Context
import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.io.File
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
    private val onHourlySteps: (String, List<Pair<Int, Int>>, List<Triple<Long, Int, Int>>) -> Unit = { _, _, _ -> }, // date, (hour, steps), per-minute rows (tsMs, steps, distanceCm)
    private val onGpsTrack: ((Long, List<GpsPoint>) -> Unit)? = null, // workoutStartMs, points
    private val context: Context? = null, // for debug dumps into filesDir
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

    data class GpsPoint(val timestamp: Long, val lat: Double, val lon: Double, val speed: Float = 0f)

    data class DailySummary(
        val date: Long, // epoch seconds
        val steps: Int = 0,
        val distanceM: Int = 0,
        val activeMinutes: Int = 0,
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
                // Don't ACK manual samples (subtype=6) so they come again on next fetch
                if (info.type == 0 && info.subtype == 6) {
                    Log.i(TAG, "Skipping ACK for manual sample — will re-fetch next time")
                } else {
                    ackFile(fileId)
                }
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

        val info = com.pedometer.health.parsers.ParserUtils.decodeFileId(fileId)
        Log.i(TAG, "Processing: $info")

        try {
            when {
                info.type == 0 && info.subtype == 0 && info.detailType == 0 -> {
                    val result = com.pedometer.health.parsers.DailyDetailsParser.parse(fileId, data)
                    if (result != null) {
                        if (result.hrSamples.isNotEmpty()) onHeartRateSamples(result.hrSamples)
                        if (result.hourlySteps.isNotEmpty()) {
                            val dateStr = java.time.Instant.ofEpochSecond(info.timestamp)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                            onHourlySteps(dateStr, result.hourlySteps.map { (h, s) -> Pair(h, s) }.sortedBy { it.first }, result.minuteRows)
                        }
                        if (result.totalDistanceM > 0 || result.activeMinutes > 0 || result.lastSpo2 > 0 || result.lastStress > 0) {
                            onDailySummary(DailySummary(
                                date = info.timestamp, distanceM = result.totalDistanceM,
                                activeMinutes = result.activeMinutes, spo2Avg = result.lastSpo2, stressAvg = result.lastStress,
                            ))
                        }
                    }
                }
                info.type == 0 && info.subtype == 0 && info.detailType == 1 -> {
                    val summary = com.pedometer.health.parsers.DailySummaryParser.parse(fileId, data)
                    if (summary != null) onDailySummary(summary)
                }
                info.type == 0 && info.subtype == 3 && info.detailType == 0 -> {
                    val sleep = com.pedometer.health.parsers.SleepParser.parseSleepStages(fileId, data)
                    if (sleep != null) onSleepData(sleep)
                }
                info.type == 0 && info.subtype == 8 -> {
                    val result = com.pedometer.health.parsers.SleepParser.parseSleepDetails(fileId, data)
                    if (result != null) {
                        onSleepData(result.first)
                        if (result.second.isNotEmpty()) onHeartRateSamples(result.second)
                    }
                }
                info.type == 0 && info.subtype == 6 ->
                    parseManualSamples(fileId, data)
                info.type == 1 && info.detailType == 1 ->
                    parseWorkoutSummary(fileId, data)
                info.type == 1 && info.detailType == 2 ->
                    parseGpsTrack(fileId, data)
                else -> Log.d(TAG, "Skipping file type=${info.type} sub=${info.subtype} detail=${info.detailType}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse file: ${fileId.toHex()}", e)
        }
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
        if (data.size < 30) return

        val info = decodeFileId(fileId)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8) // skip fileId + padding

        // Header size from Gadgetbridge WorkoutSummaryParser
        val headerSize = when (info.subtype) {
            0x08 -> when { // freestyle
                info.version <= 5 -> 3
                info.version <= 7 -> 5
                else -> 6
            }
            0x07 -> when { // indoor cycling
                info.version <= 8 -> 7
                else -> 8
            }
            0x01 -> 4 // outdoor running v1
            0x02 -> 4 // outdoor walking v1
            0x16 -> when { // outdoor walking v2
                info.version <= 1 -> 5
                info.version <= 4 -> 7
                info.version <= 6 -> 9
                else -> 13
            }
            0x03 -> when { // treadmill
                info.version <= 5 -> 4
                info.version <= 10 -> 8
                else -> 9
            }
            0x10 -> 4 // HIIT
            0x0B -> 4 // elliptical
            0x0D -> when { // rowing
                info.version <= 4 -> 4
                else -> 5
            }
            0x0E -> 5 // jump roping
            0x06, 0x17 -> when { // outdoor cycling
                info.version <= 5 -> 6
                else -> 7
            }
            else -> 4
        }
        if (bb.remaining() < headerSize) return
        bb.position(bb.position() + headerSize)

        // TEMP DEBUG: hex dump of the workout body so the HR-field offset can be
        // verified against a real payload. Layout for 0x16 v5 is now confirmed
        // against Gadgetbridge WorkoutSummaryParser.getOutdoorWalkingV2Parser +
        // the 2026-09-27 capture (HR at body offset 56: 103/119/92, matches the
        // workout). Keep dumping until ONE more real workout also shows correct
        // HR in the UI, then remove. Full dump goes to <filesDir>/workout_dumps/
        // (logcat rotates in minutes).
        run {
            try {
                val dup = bb.duplicate().apply { position(bb.position()) }
                val len = dup.remaining()
                val hex = StringBuilder()
                while (dup.hasRemaining()) hex.append("%02x ".format(dup.get()))

                // Mirror the parser below: V2 types have a leading workout-type short
                val start = run {
                    val b = bb.duplicate().apply { position(bb.position()) }
                    if (info.subtype in listOf(0x16, 0x17, 0x06) && b.remaining() >= 2) b.short
                    if (b.remaining() >= 4) b.int.toLong() and 0xFFFFFFFFL else 0L
                }

                context?.let { ctx ->
                    val dir = File(ctx.filesDir, "workout_dumps")
                    dir.mkdirs()
                    File(dir, "dump_$start.txt").writeText(
                        "subtype=0x%02x v=%d len=%d\n%s".format(info.subtype, info.version, len, hex)
                    )
                    dir.listFiles()
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(5)
                        ?.forEach { it.delete() }
                }
                Log.w(TAG, "WorkoutBody subtype=0x%02x v=%d len=%d: %s"
                    .format(info.subtype, info.version, len, hex.take(48 * 3).toString().trim()))
            } catch (e: Exception) {
                Log.w(TAG, "WorkoutBody dump failed", e)
            }
        }

        try {
            val fields = parseWorkoutBody(info.subtype, info.version, bb)
            if (fields != null) {
                var hrAvg = fields.hrAvg
                var hrMax = fields.hrMax
                var hrMin = fields.hrMin
                if (hrAvg == 0 || hrAvg > 220) {
                    Log.w(TAG, "Implausible workout HR avg=$hrAvg max=$hrMax min=$hrMin — zeroing (layout mismatch for 0x%02x v%d?)"
                        .format(info.subtype, info.version))
                    hrAvg = 0; hrMax = 0; hrMin = 0
                }

                val workout = WorkoutSummary(
                    startTime = fields.startTime * 1000,
                    endTime = fields.endTime * 1000,
                    sportType = info.subtype,
                    sportName = sportName(info.subtype),
                    durationSec = fields.durationSec,
                    distanceM = fields.distanceM,
                    calories = fields.calories,
                    hrAvg = hrAvg,
                    hrMax = hrMax,
                    hrMin = hrMin,
                )
                Log.i(TAG, "Workout: ${workout.sportName} ${fields.durationSec / 60}min dist=${fields.distanceM}m cal=${fields.calories} HR=$hrAvg/$hrMin-$hrMax")
                onWorkout(workout)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse workout: ${e.message}")
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

    private fun parseGpsTrack(fileId: ByteArray, data: ByteArray) {
        val info = decodeFileId(fileId)
        val sampleSize = if (info.version >= 2) 18 else 12
        val headerSize = 1

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8 + headerSize) // skip fileId + padding + header
        val dataEnd = data.size - 4 // exclude CRC32

        val points = mutableListOf<GpsPoint>()
        while (bb.position() + sampleSize <= dataEnd) {
            val ts = bb.int.toLong() and 0xFFFFFFFFL
            val lon = bb.float.toDouble()
            val lat = bb.float.toDouble()
            var speed = 0f
            if (info.version >= 2) {
                bb.float // hdop
                speed = (bb.short.toInt() shr 2) / 10.0f
            }
            if (lat != 0.0 && lon != 0.0) {
                points.add(GpsPoint(ts * 1000, lat, lon, speed))
            }
        }

        Log.i(TAG, "GPS track: ${points.size} points for sport=${info.subtype}")
        if (points.isNotEmpty()) {
            onGpsTrack?.invoke(info.timestamp * 1000, points)
        }
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

/** Raw fields as laid out in a workout-summary payload (the body after the
 *  8-byte file header + per-type headerSize). Pure java.nio — unit-testable. */
internal data class ParsedWorkoutBody(
    val startTime: Long,
    val endTime: Long,
    val durationSec: Int,
    val distanceM: Int,
    val calories: Int,
    val steps: Int,
    val hrAvg: Int,
    val hrMax: Int,
    val hrMin: Int,
)

/**
 * Byte-layout of the workout-summary body. Reads from [body]'s current position
 * (i.e. after fileId+padding and the per-type headerSize already consumed by
 * [ActivitySync.parseWorkoutSummary]). Returns null when start/end timestamps
 * are missing. No android.* dependencies so it can be pinned by unit tests
 * against real captured payloads (WorkoutParseOffsetTest).
 */
internal fun parseWorkoutBody(subtype: Int, version: Int, body: ByteBuffer): ParsedWorkoutBody? {
    // V2 types start with workout type short
    val isV2 = subtype in listOf(0x16, 0x17, 0x06)
    if (isV2 && body.remaining() >= 2) body.short

    val startTime = if (body.remaining() >= 4) body.int.toLong() and 0xFFFFFFFFL else 0L
    val endTime = if (body.remaining() >= 4) body.int.toLong() and 0xFFFFFFFFL else 0L
    val duration = if (body.remaining() >= 4) body.int else 0

    // Distance/unknown4 depends on sport type
    var distance = 0
    val hasDistance = subtype in listOf(0x01, 0x02, 0x03, 0x06, 0x09, 0x16, 0x17)
    if (hasDistance && body.remaining() >= 4) {
        if (subtype in listOf(0x16, 0x17)) {
            body.int // unknown4
            distance = if (body.remaining() >= 4) body.int else 0
        } else {
            distance = body.int
        }
    }

    // Calories — short for v2 types, int for v1
    val calories = when (subtype) {
        0x16, 0x17, 0x06 -> {
            // v2: totalCal(short) + activeCal(short)
            if (body.remaining() >= 4) {
                body.short.toInt() and 0xFFFF // total
                // body.short // active — skip
            } else 0
        }
        0x01, 0x02 -> {
            // v1: calories as int
            if (body.remaining() >= 4) body.int else 0
        }
        else -> {
            if (body.remaining() >= 2) body.short.toInt() and 0xFFFF else 0
        }
    }

    // Skip pace/speed fields to reach HR
    // Most types: some pace/speed fields, then HR avg(1), max(1), min(1)
    // Offsets cross-checked against Gadgetbridge WorkoutSummaryParser
    // (service/devices/xiaomi/activity/impl/) and real 0x16 v5 captures
    // (byte-for-byte pinned in WorkoutParseOffsetTest).
    val skipToHr = when (subtype) {
        0x16 -> { // outdoor walking v2, verified on real v5 payloads (2026-09-27/28).
            // The calories block above consumes only totalCal (2 bytes); activeCal
            // (2 bytes, 434/20 kcal in the captures) stays unconsumed, so it is
            // part of this skip: activeCal(2) + pace_avg s/km i16 (739) + reserved
            // i16 (0) + pace_max i32 (537) + pace_min i32 (2800) + speed_avg km/h
            // f32 (4.93) + speed_max f32 (6.73) + steps u32 (10097) + step_len cm
            // u16 (76) + step_rate_avg u16 (101) + step_rate_max u16 (137) = 32
            // bytes, then HR avg/max/min (114/145/85). Pinned by
            // WorkoutParseOffsetTest on real dumps — do NOT change without one.
            if (version >= 5) 32 else 18
        }
        // walking v1 (GB getOutdoorWalkingV1Parser): pace_max(4)+pace_min(4)
        // +unk(4)+steps(4)+unk(2) = 18 (was 16 — off by 2)
        0x02 -> 18
        0x01 -> 16 // running v1: no GB reference parser, keep legacy value
        0x03 -> { // treadmill (GB getTreadmillParser)
            if (version >= 10) 22 else 14
        }
        0x08, 0x10 -> 0 // freestyle/HIIT: HR right after calories (matches GB)
        0x07 -> 8 // indoor cycling: unk(4)+unk(4) (GB covers only v8/9 — unverified)
        0x0B -> { // elliptical (GB getEllipticalParser): steps(4)+[cadAvg(2)]+cadMax(2)
            if (version >= 6) 8 else 6
        }
        else -> 0
    }
    // Skip at most remaining-3 so a truncated payload still yields HR bytes
    if (body.remaining() >= 3) {
        body.position(body.position() + minOf(skipToHr, body.remaining() - 3))
    }

    // HR: avg(1), max(1), min(1)
    val hrStart = body.position()
    val hrAvg = if (body.remaining() >= 1) body.get().toInt() and 0xFF else 0
    val hrMax = if (body.remaining() >= 1) body.get().toInt() and 0xFF else 0
    val hrMin = if (body.remaining() >= 1) body.get().toInt() and 0xFF else 0

    // steps: read at the verified offset for 0x16 v5+ only (other subtypes
    // unverified — leave 0 rather than store garbage). steps u32 sits 10 bytes
    // before HR (step_len(2)+step_rate_avg(2)+step_rate_max(2) in between).
    val steps = if (subtype == 0x16 && version >= 5 && hrStart >= 10) {
        val b = body.duplicate().apply { position(hrStart - 10) }
        if (b.remaining() >= 4) b.int else 0
    } else 0

    if (startTime <= 0 || endTime <= 0) return null
    return ParsedWorkoutBody(
        startTime = startTime,
        endTime = endTime,
        durationSec = duration,
        distanceM = distance,
        calories = calories,
        steps = steps,
        hrAvg = hrAvg,
        hrMax = hrMax,
        hrMin = hrMin,
    )
}
