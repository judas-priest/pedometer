package com.pedometer.bt

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PacketV1(
    val channel: Channel,
    val flag: Boolean,
    val needsResponse: Boolean,
    val opCode: Int,
    val frameSerial: Int,
    val dataType: Int,
    val payload: ByteArray,
) {
    companion object {
        val PREAMBLE = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        val EPILOGUE = byteArrayOf(0xEF.toByte())

        const val DATA_TYPE_PLAIN = 0
        const val DATA_TYPE_ENCRYPTED = 1
        const val DATA_TYPE_AUTH = 2

        const val OPCODE_READ = 0
        const val OPCODE_SEND = 2

        const val CHANNEL_VERSION = 0
        const val CHANNEL_PROTO_RX = 1
        const val CHANNEL_PROTO_TX = 2
        const val CHANNEL_FITNESS = 3
        const val CHANNEL_MASS = 5

        fun rawChannelToChannel(raw: Int): Channel = when (raw) {
            CHANNEL_VERSION -> Channel.Version
            CHANNEL_PROTO_RX, CHANNEL_PROTO_TX -> Channel.ProtobufCommand
            CHANNEL_FITNESS -> Channel.Activity
            CHANNEL_MASS -> Channel.Data
            else -> Channel.Unknown
        }

        fun channelToRawTx(channel: Channel): Int = when (channel) {
            Channel.Version -> CHANNEL_VERSION
            Channel.Authentication, Channel.ProtobufCommand -> CHANNEL_PROTO_TX
            Channel.Activity -> CHANNEL_FITNESS
            Channel.Data -> CHANNEL_MASS
            else -> -1
        }

        fun dataTypeForChannel(channel: Channel): Int = when (channel) {
            Channel.Authentication -> DATA_TYPE_AUTH
            Channel.ProtobufCommand, Channel.Version, Channel.Data -> DATA_TYPE_ENCRYPTED
            Channel.Activity -> DATA_TYPE_PLAIN
            else -> DATA_TYPE_PLAIN
        }

        fun decode(bytes: ByteArray): PacketV1? {
            if (bytes.size < 11) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(3)
            buf.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            val rawChannel = buf.get().toInt() and 0x0F
            val flags = buf.get().toInt() and 0xFF
            val flag = (flags and 0x80) != 0
            val needsResponse = (flags and 0x40) != 0

            val payloadSizeWithHeader = buf.short.toInt() and 0xFFFF
            val payloadSize = payloadSizeWithHeader - 3
            if (payloadSize < 0 || bytes.size < payloadSize + 11) return null

            val opCode = buf.get().toInt() and 0xFF
            val frameSerial = buf.get().toInt() and 0xFF
            val dataType = buf.get().toInt() and 0xFF
            val payload = ByteArray(payloadSize)
            buf.get(payload)

            val epilogue = ByteArray(1)
            buf.get(epilogue)
            if (!epilogue.contentEquals(EPILOGUE)) return null

            return PacketV1(rawChannelToChannel(rawChannel), flag, needsResponse, opCode, frameSerial, dataType, payload)
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(11 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PREAMBLE)
        buf.put((channelToRawTx(channel) and 0x0F).toByte())
        buf.put(((if (flag) 0x80 else 0) or (if (needsResponse) 0x40 else 0)).toByte())
        buf.putShort((payload.size + 3).toShort())
        buf.put((opCode and 0xFF).toByte())
        buf.put((frameSerial and 0xFF).toByte())
        buf.put((dataType and 0xFF).toByte())
        buf.put(payload)
        buf.put(EPILOGUE)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketV1) return false
        return channel == other.channel && flag == other.flag && needsResponse == other.needsResponse &&
                opCode == other.opCode && frameSerial == other.frameSerial && dataType == other.dataType &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = payload.contentHashCode()
}
