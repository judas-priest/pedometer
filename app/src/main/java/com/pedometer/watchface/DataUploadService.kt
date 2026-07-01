package com.pedometer.watchface

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Data upload protocol for watchfaces, firmware, notification icons.
 *
 * Flow:
 * 1. Send DataUploadRequest (type, md5, size) via type=22, sub=0
 * 2. Watch responds with DataUploadAck (resumePosition, chunkSize)
 * 3. Send data in chunks: [0x00, type, md5(16), size(4), data...] + CRC32
 * 4. Each chunk: [totalParts(2), currentPart(2), data...]
 */
class DataUploadService(
    private val protocolHandler: ProtocolHandler,
) {
    companion object {
        private const val TAG = "DataUploadService"
        const val COMMAND_TYPE = 22
        const val CMD_UPLOAD_START = 0
        const val TYPE_WATCHFACE: Byte = 16
    }

    var onProgress: ((Int) -> Unit)? = null
    var onComplete: ((Boolean) -> Unit)? = null

    private var currentType: Byte = 0
    private var currentBytes: ByteArray? = null
    private var chunkSize = 2048

    fun uploadWatchface(data: ByteArray) {
        upload(TYPE_WATCHFACE, data)
    }

    private fun upload(type: Byte, data: ByteArray) {
        if (currentBytes != null) {
            Log.w(TAG, "Already uploading, refusing")
            return
        }

        currentType = type
        currentBytes = data
        val md5 = md5(data) ?: return

        Log.i(TAG, "Requesting upload: ${data.size} bytes, type=$type, md5=${md5.toHex()}")

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_UPLOAD_START)
            .setDataUpload(XiaomiProto.DataUpload.newBuilder()
                .setDataUploadRequest(XiaomiProto.DataUploadRequest.newBuilder()
                    .setType(type.toInt())
                    .setMd5Sum(com.google.protobuf.ByteString.copyFrom(md5))
                    .setSize(data.size)))
            .build()
        protocolHandler.sendCommand(cmd)
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_UPLOAD_START -> {
                if (!cmd.hasDataUpload() || !cmd.dataUpload.hasDataUploadAck()) return
                val ack = cmd.dataUpload.dataUploadAck

                if (ack.unknown2 != 0) {
                    Log.w(TAG, "Upload rejected: unknown2=${ack.unknown2}")
                    finish(false)
                    return
                }

                chunkSize = if (ack.hasChunkSize()) ack.chunkSize else 2048
                val resumePos = ack.resumePosition
                Log.i(TAG, "Upload ACK: chunkSize=$chunkSize, resume=$resumePos")

                val bytes = currentBytes ?: return
                doUpload(bytes, resumePos)
            }
            else -> Log.d(TAG, "Upload subtype: ${cmd.subtype}")
        }
    }

    private fun doUpload(bytes: ByteArray, resumePosition: Int) {
        val md5 = md5(bytes) ?: return

        // Build payload: [0x00, type, md5(16), size(4), data_from_resume...] + CRC32
        val dataToSend = bytes.copyOfRange(resumePosition, bytes.size)
        val buf1 = ByteBuffer.allocate(2 + 16 + 4 + dataToSend.size).order(ByteOrder.LITTLE_ENDIAN)
        buf1.put(0.toByte())
        buf1.put(currentType)
        buf1.put(md5)
        buf1.putInt(bytes.size)
        buf1.put(dataToSend)

        val payload = buf1.array()
        val crc = crc32(payload)
        val fullPayload = ByteBuffer.allocate(payload.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(payload).putInt(crc).array()

        val partSize = chunkSize - 4 // 4 bytes for header (totalParts + currentPart)
        val totalParts = (fullPayload.size + partSize - 1) / partSize

        Log.i(TAG, "Uploading ${fullPayload.size} bytes in $totalParts chunks of $partSize")

        Thread {
            for (i in 0 until totalParts) {
                val startIdx = i * partSize
                val endIdx = minOf((i + 1) * partSize, fullPayload.size)
                val chunkData = fullPayload.copyOfRange(startIdx, endIdx)

                val chunk = ByteBuffer.allocate(4 + chunkData.size).order(ByteOrder.LITTLE_ENDIAN)
                chunk.putShort(totalParts.toShort())
                chunk.putShort((i + 1).toShort())
                chunk.put(chunkData)

                protocolHandler.sendRawProtobuf(chunk.array())

                val progress = ((i + 1) * 100) / totalParts
                Log.d(TAG, "Chunk ${i + 1}/$totalParts ($progress%)")
                onProgress?.invoke(progress)

                // Small delay between chunks to not overwhelm
                Thread.sleep(50)
            }

            Log.i(TAG, "Upload complete!")
            finish(true)
        }.start()
    }

    private fun finish(success: Boolean) {
        currentType = 0
        currentBytes = null
        onComplete?.invoke(success)
    }

    private fun md5(data: ByteArray): ByteArray? {
        return try {
            MessageDigest.getInstance("MD5").digest(data)
        } catch (e: Exception) {
            Log.e(TAG, "MD5 failed", e)
            null
        }
    }

    private fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
