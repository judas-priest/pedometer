package com.pedometer.health.parsers

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Common utilities for activity file parsers.
 */
object ParserUtils {
    fun decodeFileId(id: ByteArray): FileIdInfo {
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

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

data class FileIdInfo(
    val timestamp: Long,
    val timezone: Int,
    val version: Int,
    val type: Int,      // 0=Activity, 1=Sports
    val subtype: Int,
    val detailType: Int, // 0=Details, 1=Summary, 2=GPS
)
