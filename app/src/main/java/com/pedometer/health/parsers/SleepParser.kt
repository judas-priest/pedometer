package com.pedometer.health.parsers

import android.util.Log
import com.pedometer.health.ActivitySync
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses sleep files: subtype=8 (details with HR/stages) and subtype=3 (stages only).
 */
object SleepParser {
    private const val TAG = "SleepParser"

    fun parseSleepDetails(fileId: ByteArray, data: ByteArray): Pair<ActivitySync.SleepData, List<ActivitySync.HeartRateSample>>? {
        if (data.size < 20) return null
        val info = ParserUtils.decodeFileId(fileId)
        val headerSize = if (info.version >= 5) 2 else 1

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val header = ByteArray(headerSize)
        bb.get(header)

        val isAwake = bb.get().toInt() and 0xFF
        val bedTime = bb.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = bb.int.toLong() and 0xFFFFFFFFL

        if (bedTime == 0L || wakeupTime == 0L) return null
        val totalMinutes = ((wakeupTime - bedTime) / 60).toInt()

        if (info.version >= 4 && bb.remaining() > 0) bb.get()
        if (info.version >= 5 && bb.remaining() >= 17) {
            bb.position(bb.position() + 9)
            bb.int; bb.int
        }

        // HR samples
        val hrSamples = mutableListOf<ActivitySync.HeartRateSample>()
        if (bb.remaining() >= 4 && headerHasField(header, 0)) {
            val unit = bb.short.toInt() and 0xFFFF
            val count = bb.short.toInt() and 0xFFFF
            if (count > 0 && bb.remaining() >= 4 + count) {
                val firstRecordTime = if (info.version >= 2) bb.int.toLong() and 0xFFFFFFFFL else bedTime
                for (i in 0 until count) {
                    if (bb.remaining() < 1) break
                    val hr = bb.get().toInt() and 0xFF
                    if (hr in 1..254) {
                        hrSamples.add(ActivitySync.HeartRateSample((firstRecordTime + i * unit) * 1000, hr))
                    }
                }
            }
        }

        // Sleep stages from stage packets
        var deepMin = 0; var lightMin = 0; var remMin = 0; var awakeMin = 0
        try {
            // Skip SpO2
            if (bb.remaining() >= 4 && headerHasField(header, 1)) {
                val unit = bb.short.toInt() and 0xFFFF
                val count = bb.short.toInt() and 0xFFFF
                if (count > 0) {
                    if (info.version >= 2 && bb.remaining() >= 4) bb.int
                    if (bb.remaining() >= count) bb.position(bb.position() + count)
                }
            }
            // Skip snore
            if (info.version >= 3 && bb.remaining() >= 4 && headerHasField(header, 2)) {
                val unit = bb.short.toInt() and 0xFFFF
                val count = bb.short.toInt() and 0xFFFF
                if (count > 0) {
                    if (info.version >= 2 && bb.remaining() >= 4) bb.int
                    if (bb.remaining() >= count * 4) bb.position(bb.position() + count * 4)
                }
            }
            // Stage packets
            while (bb.remaining() >= 17) {
                val b1 = bb.get().toInt() and 0xFF
                if (b1 != 0x02) { bb.position(bb.position() - 1); break }
                bb.get() // b2
                bb.get() // headerLen
                bb.long // ts
                bb.get() // parity
                val type = bb.get().toInt() and 0xFF
                val dataLen = ((bb.get().toInt() and 0xFF) shl 8) or (bb.get().toInt() and 0xFF)

                if (type in listOf(0x2, 0x3, 0x9, 0xc, 0xd, 0xe, 0xf)) continue

                if (dataLen > 0 && bb.remaining() >= dataLen) {
                    if (type == 16 && dataLen >= 12) {
                        bb.get() // d0
                        bb.short // sleepDur
                        val wakeDur = bb.short.toInt() and 0xFFFF
                        val lightDur = bb.short.toInt() and 0xFFFF
                        val remDur = bb.short.toInt() and 0xFFFF
                        val deepDur = bb.short.toInt() and 0xFFFF
                        if (dataLen > 11) bb.position(bb.position() + dataLen - 11)
                        deepMin = deepDur; lightMin = lightDur; remMin = remDur; awakeMin = wakeDur
                        Log.i(TAG, "Stages: deep=$deepDur light=$lightDur REM=$remDur awake=$wakeDur")
                    } else {
                        bb.position(bb.position() + dataLen)
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Stage parsing failed: ${e.message}") }

        val sleep = ActivitySync.SleepData(bedTime * 1000, wakeupTime * 1000, totalMinutes, deepMin, lightMin, remMin, awakeMin)
        return Pair(sleep, hrSamples)
    }

    fun parseSleepStages(fileId: ByteArray, data: ByteArray): ActivitySync.SleepData? {
        if (data.size < 30) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)

        bb.get(ByteArray(7)) // unk1
        val sleepDuration = bb.short.toInt() and 0xFFFF
        val bedTime = bb.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = bb.int.toLong() and 0xFFFFFFFFL
        bb.get(ByteArray(3)) // unk2
        val deep = bb.short.toInt() and 0xFFFF
        val light = bb.short.toInt() and 0xFFFF
        val rem = bb.short.toInt() and 0xFFFF
        val awake = bb.short.toInt() and 0xFFFF

        if (bedTime == 0L || wakeupTime == 0L || sleepDuration == 0) return null

        return ActivitySync.SleepData(bedTime * 1000, wakeupTime * 1000, sleepDuration, deep, light, rem, awake)
    }

    private fun headerHasField(header: ByteArray, index: Int): Boolean {
        if (header.isEmpty()) return false
        val byteIdx = index / 8
        val bitIdx = index % 8
        return byteIdx < header.size && (header[byteIdx].toInt() and (1 shl bitIdx)) != 0
    }
}
