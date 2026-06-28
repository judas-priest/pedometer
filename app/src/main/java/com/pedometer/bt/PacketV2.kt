package com.pedometer.bt

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class PacketV2(val packetType: Int, val sequenceNumber: Int) {

    class AckPacket(sequenceNumber: Int) : PacketV2(TYPE_ACK, sequenceNumber)

    class SessionConfigPacket(sequenceNumber: Int, val opCode: Int) : PacketV2(TYPE_SESSION_CONFIG, sequenceNumber)

    class DataPacket(
        sequenceNumber: Int,
        val channel: Channel,
        val opCode: Int,
        val payload: ByteArray,
    ) : PacketV2(TYPE_DATA, sequenceNumber)

    companion object {
        val PREAMBLE = byteArrayOf(0xA5.toByte(), 0xA5.toByte())

        const val TYPE_ACK = 1
        const val TYPE_SESSION_CONFIG = 2
        const val TYPE_DATA = 3

        const val OPCODE_PLAINTEXT = 1
        const val OPCODE_ENCRYPTED = 2

        const val SESSION_START_REQUEST = 1

        private const val CHANNEL_PROTOBUF = 1
        private const val CHANNEL_DATA = 2
        private const val CHANNEL_ACTIVITY = 5

        fun channelToRaw(channel: Channel): Int = when (channel) {
            Channel.Authentication, Channel.ProtobufCommand -> CHANNEL_PROTOBUF
            Channel.Data -> CHANNEL_DATA
            Channel.Activity -> CHANNEL_ACTIVITY
            else -> -1
        }

        fun rawToChannel(raw: Int): Channel = when (raw) {
            CHANNEL_PROTOBUF -> Channel.ProtobufCommand
            CHANNEL_DATA -> Channel.Data
            CHANNEL_ACTIVITY -> Channel.Activity
            else -> Channel.Unknown
        }

        fun opCodeForChannel(channel: Channel): Int = when (channel) {
            Channel.Authentication -> OPCODE_PLAINTEXT
            Channel.Data -> OPCODE_PLAINTEXT
            Channel.ProtobufCommand, Channel.Activity -> OPCODE_ENCRYPTED
            else -> OPCODE_PLAINTEXT
        }

        // Force plaintext for debugging — change back after fix
        fun opCodeForChannelPlaintext(channel: Channel): Int = OPCODE_PLAINTEXT

        fun crc16arc(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                for (j in 0 until 8) {
                    crc = crc shl 1
                    if ((((crc shr 16) and 1) xor ((b.toInt() shr j) and 1)) == 1) {
                        crc = crc xor 0x8005
                    }
                }
            }
            return Integer.reverse(crc).ushr(16)
        }

        fun encodeAck(sequenceNumber: Int): ByteArray {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_ACK and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(0)
            buf.putShort(0)
            return buf.array()
        }

        fun encodeSessionConfig(sequenceNumber: Int, opCode: Int): ByteArray {
            val payload = byteArrayOf(
                opCode.toByte(),
                0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
                0x02, 0x02, 0x00, 0x00, 0xFC.toByte(),
                0x03, 0x02, 0x00, 0x20, 0x00,
                0x04, 0x02, 0x00, 0x10, 0x27,
            )
            val checksum = crc16arc(payload)
            val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_SESSION_CONFIG and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(payload.size.toShort())
            buf.putShort(checksum.toShort())
            buf.put(payload)
            return buf.array()
        }

        fun encodeDataPacket(
            channel: Channel,
            sequenceNumber: Int,
            payload: ByteArray,
            encrypt: ((ByteArray) -> ByteArray)?,
        ): ByteArray {
            val opCode = opCodeForChannel(channel)
            val processedPayload = if (opCode == OPCODE_ENCRYPTED && encrypt != null) {
                encrypt(payload)
            } else {
                payload
            }
            val packetPayload = ByteBuffer.allocate(2 + processedPayload.size)
                .put((channelToRaw(channel) and 0x0F).toByte())
                .put((opCode and 0xFF).toByte())
                .put(processedPayload)
                .array()

            val checksum = crc16arc(packetPayload)
            val buf = ByteBuffer.allocate(8 + packetPayload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_DATA and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(packetPayload.size.toShort())
            buf.putShort(checksum.toShort())
            buf.put(packetPayload)
            return buf.array()
        }

        fun decode(bytes: ByteArray): PacketV2? {
            if (bytes.size < 8) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(2)
            buf.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            val packetType = buf.get().toInt() and 0x0F
            val sequenceNumber = buf.get().toInt() and 0xFF
            val payloadLength = buf.short.toInt() and 0xFFFF
            val givenChecksum = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < payloadLength) return null

            val payloadBytes = ByteArray(payloadLength)
            buf.get(payloadBytes)

            if (payloadLength > 0) {
                val calcChecksum = crc16arc(payloadBytes)
                if (calcChecksum != givenChecksum) return null
            }

            return when (packetType) {
                TYPE_ACK -> AckPacket(sequenceNumber)
                TYPE_SESSION_CONFIG -> {
                    val opCode = if (payloadBytes.isNotEmpty()) payloadBytes[0].toInt() and 0xFF else 0
                    SessionConfigPacket(sequenceNumber, opCode)
                }
                TYPE_DATA -> {
                    if (payloadBytes.size < 2) return null
                    val rawChannel = payloadBytes[0].toInt() and 0x0F
                    val opCode = payloadBytes[1].toInt() and 0xFF
                    val data = payloadBytes.copyOfRange(2, payloadBytes.size)
                    DataPacket(sequenceNumber, rawToChannel(rawChannel), opCode, data)
                }
                else -> null
            }
        }
    }
}
