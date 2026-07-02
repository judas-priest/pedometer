package com.pedometer.health.parsers

import android.util.Log
import com.pedometer.health.ActivitySync
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses daily summary files (subtype=0, detailType=1).
 */
object DailySummaryParser {
    private const val TAG = "DailySummaryParser"

    fun parse(fileId: ByteArray, data: ByteArray): ActivitySync.DailySummary? {
        val info = ParserUtils.decodeFileId(fileId)
        val headerSize = when {
            info.version >= 5 -> 4
            else -> 3
        }
        val dataStart = 8 + headerSize
        if (data.size < dataStart + 41 + 4) return null

        val bb = ByteBuffer.wrap(data, dataStart, data.size - dataStart - 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        val steps = bb.int
        bb.get(); bb.get(); bb.get() // unknowns
        val hrResting = bb.get().toInt() and 0xFF
        val hrMax = bb.get().toInt() and 0xFF
        bb.int // hrMaxTs
        val hrMin = bb.get().toInt() and 0xFF
        bb.int // hrMinTs
        val hrAvg = bb.get().toInt() and 0xFF
        val stressAvg = bb.get().toInt() and 0xFF
        val stressMax = bb.get().toInt() and 0xFF
        val stressMin = bb.get().toInt() and 0xFF
        bb.get(); bb.get(); bb.get() // standing hours
        val calories = bb.short.toInt() and 0xFFFF
        bb.get(); bb.get(); bb.get() // unknowns
        val spo2Max = bb.get().toInt() and 0xFF
        bb.int // spo2MaxTs
        val spo2Min = bb.get().toInt() and 0xFF
        bb.int // spo2MinTs
        val spo2Avg = bb.get().toInt() and 0xFF

        Log.i(TAG, "steps=$steps HR=$hrAvg/$hrMin-$hrMax SpO2=$spo2Avg stress=$stressAvg cal=$calories")

        return ActivitySync.DailySummary(
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
    }
}
