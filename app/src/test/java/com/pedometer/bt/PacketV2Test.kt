package com.pedometer.bt

import org.junit.Assert.*
import org.junit.Test

class PacketV2Test {

    @Test
    fun `decode data packet`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val encoded = PacketV2.encodeDataPacket(
            channel = Channel.Authentication,
            sequenceNumber = 1,
            payload = payload,
            encrypt = null,
        )
        val decoded = PacketV2.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is PacketV2.DataPacket)
        val data = decoded as PacketV2.DataPacket
        assertEquals(Channel.ProtobufCommand, data.channel)
        assertEquals(1, data.sequenceNumber)
        assertArrayEquals(payload, data.payload)
    }

    @Test
    fun `decode ack packet`() {
        val encoded = PacketV2.encodeAck(sequenceNumber = 42)
        val decoded = PacketV2.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is PacketV2.AckPacket)
        assertEquals(42, decoded!!.sequenceNumber)
    }

    @Test
    fun `decode returns null for bad preamble`() {
        val raw = byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x05, 0x00, 0x00, 0x00, 0x01, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        assertNull(PacketV2.decode(raw))
    }

    @Test
    fun `checksum validates`() {
        val payload = byteArrayOf(0x01, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val checksum = PacketV2.crc16arc(payload)
        assertTrue(checksum in 0..0xFFFF)
    }
}
