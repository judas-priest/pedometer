package com.pedometer.bt

import android.os.Build
import android.util.Log
import com.pedometer.auth.AuthService
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ProtocolHandler(
    private val authService: AuthService,
    private val connection: SppConnection,
    private val onAuthenticated: () -> Unit,
    private val onCommand: (XiaomiProto.Command) -> Unit,
) {
    companion object {
        private const val TAG = "ProtocolHandler"
    }

    private val buffer = ByteArrayOutputStream()
    private var useV2 = false
    private val frameCounter = AtomicInteger(0)
    private val encryptionCounter = AtomicInteger(0)
    private val packetSeqV2 = AtomicInteger(0)

    fun start() {
        val versionPacket = PacketV1(
            channel = Channel.Version,
            flag = true,
            needsResponse = true,
            opCode = PacketV1.OPCODE_READ,
            frameSerial = 0,
            dataType = PacketV1.DATA_TYPE_PLAIN,
            payload = ByteArray(0),
        ).encode()
        connection.write(versionPacket)
    }

    fun onDataReceived(data: ByteArray) {
        buffer.write(data)
        processBuffer()
    }

    private fun processBuffer() {
        while (true) {
            val bytes = buffer.toByteArray()
            if (bytes.size < 8) return
            if (useV2) {
                processV2(bytes) ?: return
            } else {
                processV1(bytes) ?: return
            }
        }
    }

    private fun processV1(bytes: ByteArray): Unit? {
        if (bytes.size < 11) return null
        if (bytes[0] != PacketV1.PREAMBLE[0]) {
            val offset = (1 until bytes.size).firstOrNull { bytes[it] == PacketV1.PREAMBLE[0] }
            skipBuffer(offset ?: bytes.size)
            return Unit
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(5)
        val payloadSize = buf.short.toInt() and 0xFFFF
        val packetSize = payloadSize + 8
        if (bytes.size < packetSize) return null

        val packet = PacketV1.decode(bytes)
        skipBuffer(packetSize)
        if (packet == null) return Unit

        when (packet.channel) {
            Channel.Version -> handleVersionResponse(packet.payload)
            Channel.ProtobufCommand -> {
                val decrypted = if (packet.dataType == PacketV1.DATA_TYPE_ENCRYPTED && authService.isInitialized) {
                    authService.decrypt(packet.payload)
                } else {
                    packet.payload
                }
                handleProtobufPayload(decrypted)
            }
            else -> Log.d(TAG, "Unhandled channel: ${packet.channel}")
        }
        return Unit
    }

    private fun processV2(bytes: ByteArray): Unit? {
        if (bytes.size < 8) return null
        if (bytes[0] != PacketV2.PREAMBLE[0]) {
            val offset = (1 until bytes.size).firstOrNull { bytes[it] == PacketV2.PREAMBLE[0] }
            skipBuffer(offset ?: bytes.size)
            return Unit
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val payloadLen = buf.short.toInt() and 0xFFFF
        val packetSize = 8 + payloadLen
        if (bytes.size < packetSize) return null

        val packet = PacketV2.decode(bytes)
        skipBuffer(packetSize)
        if (packet == null) return Unit

        when (packet) {
            is PacketV2.SessionConfigPacket -> {
                Log.i(TAG, "Session config received, opcode=${packet.opCode}")
                startAuth()
            }
            is PacketV2.DataPacket -> {
                val decrypted = if (packet.opCode == PacketV2.OPCODE_ENCRYPTED && authService.isInitialized) {
                    authService.decrypt(packet.payload)
                } else {
                    packet.payload
                }
                connection.write(PacketV2.encodeAck(packet.sequenceNumber))
                handleProtobufPayload(decrypted)
            }
            is PacketV2.AckPacket -> Log.d(TAG, "ACK for ${packet.sequenceNumber}")
        }
        return Unit
    }

    private fun handleVersionResponse(payload: ByteArray) {
        if (payload.isNotEmpty() && payload[0] >= 2) {
            Log.i(TAG, "Protocol V2 detected")
            useV2 = true
            authService.useV2Crypto = true
            connection.write(PacketV2.encodeSessionConfig(0, PacketV2.SESSION_START_REQUEST))
        } else {
            Log.i(TAG, "Protocol V1 detected")
            startAuth()
        }
    }

    private fun startAuth() {
        val cmd = CommandHelper.buildNonceCommand(authService.phoneNonce)
        sendCommand(cmd, forAuth = true)
    }

    private fun handleProtobufPayload(data: ByteArray) {
        try {
            val cmd = XiaomiProto.Command.parseFrom(data)
            Log.d(TAG, "Command type=${cmd.type} subtype=${cmd.subtype}")
            if (cmd.type == CommandHelper.TYPE_AUTH) {
                handleAuthCommand(cmd)
            } else {
                onCommand(cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse protobuf", e)
        }
    }

    private fun handleAuthCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.AUTH_NONCE -> {
                val watchNonce = cmd.auth.watchNonce.nonce.toByteArray()
                val watchHmac = cmd.auth.watchNonce.hmac.toByteArray()
                if (!authService.processWatchNonce(watchNonce, watchHmac)) {
                    Log.e(TAG, "Auth failed: HMAC mismatch. Check auth key.")
                    return
                }
                val encryptedNonces = authService.getEncryptedNonces(watchNonce)
                val deviceInfo = CommandHelper.buildAuthDeviceInfo(
                    phoneModel = Build.MODEL,
                    apiLevel = Build.VERSION.SDK_INT,
                    region = Locale.getDefault().language.take(2),
                )
                val encryptedDeviceInfo = authService.encrypt(deviceInfo.toByteArray(), 0)
                val step3 = CommandHelper.buildAuthStep3(encryptedNonces, encryptedDeviceInfo)
                sendCommand(step3, forAuth = true)
            }
            CommandHelper.AUTH_STEP3 -> {
                Log.i(TAG, "Authentication successful!")
                onAuthenticated()
            }
            else -> Log.w(TAG, "Unknown auth subtype: ${cmd.subtype}")
        }
    }

    fun sendCommand(command: XiaomiProto.Command, forAuth: Boolean = false) {
        val data = command.toByteArray()
        val channel = if (forAuth) Channel.Authentication else Channel.ProtobufCommand

        val packet: ByteArray = if (useV2) {
            val encrypt: ((ByteArray) -> ByteArray)? = if (!forAuth && authService.isInitialized) {
                { authService.encrypt(it, 0) }
            } else null
            PacketV2.encodeDataPacket(channel, packetSeqV2.getAndIncrement(), data, encrypt)
        } else {
            val dataType = if (forAuth) PacketV1.DATA_TYPE_AUTH else PacketV1.DATA_TYPE_ENCRYPTED
            var payload = data
            if (dataType == PacketV1.DATA_TYPE_ENCRYPTED && authService.isInitialized) {
                val counter = encryptionCounter.incrementAndGet()
                payload = authService.encrypt(data, counter)
                payload = ByteBuffer.allocate(payload.size + 2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(counter.toShort()).put(payload).array()
            }
            PacketV1(
                channel = channel, flag = true, needsResponse = false,
                opCode = PacketV1.OPCODE_SEND, frameSerial = frameCounter.getAndIncrement(),
                dataType = dataType, payload = payload,
            ).encode()
        }
        connection.write(packet)
    }

    private fun skipBuffer(count: Int) {
        val bytes = buffer.toByteArray()
        buffer.reset()
        if (count < bytes.size) {
            buffer.write(bytes, count, bytes.size - count)
        }
    }
}
