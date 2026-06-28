package com.pedometer.bt

import org.junit.Assert.*
import org.junit.Test

class PacketV1Test {

    @Test
    fun `decode valid packet`() {
        val raw = byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
            0x02, 0x80.toByte(), 0x06, 0x00,
            0x02, 0x00, 0x01,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xEF.toByte(),
        )
        val packet = PacketV1.decode(raw)
        assertNotNull(packet)
        packet!!
        assertEquals(Channel.ProtobufCommand, packet.channel)
        assertTrue(packet.flag)
        assertFalse(packet.needsResponse)
        assertEquals(0x02, packet.opCode)
        assertEquals(0x00, packet.frameSerial)
        assertEquals(PacketV1.DATA_TYPE_ENCRYPTED, packet.dataType)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), packet.payload)
    }

    @Test
    fun `decode returns null for bad preamble`() {
        val raw = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x80.toByte(), 0x06, 0x00, 0x02, 0x00, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xEF.toByte())
        assertNull(PacketV1.decode(raw))
    }

    @Test
    fun `decode returns null for truncated packet`() {
        val raw = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 0x02, 0x80.toByte())
        assertNull(PacketV1.decode(raw))
    }

    @Test
    fun `encode then decode roundtrip`() {
        val original = PacketV1(
            channel = Channel.ProtobufCommand, flag = true, needsResponse = false,
            opCode = PacketV1.OPCODE_SEND, frameSerial = 5,
            dataType = PacketV1.DATA_TYPE_PLAIN, payload = byteArrayOf(0x01, 0x02, 0x03),
        )
        val decoded = PacketV1.decode(original.encode())
        assertNotNull(decoded)
        decoded!!
        assertEquals(original.channel, decoded.channel)
        assertEquals(original.opCode, decoded.opCode)
        assertEquals(original.frameSerial, decoded.frameSerial)
        assertEquals(original.dataType, decoded.dataType)
        assertArrayEquals(original.payload, decoded.payload)
    }
}
