package com.pedometer.auth

import org.junit.Assert.*
import org.junit.Test

class AuthServiceTest {

    @Test
    fun `computeAuthKeys produces 64 bytes`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce = ByteArray(16) { (it + 16).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val result = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        assertEquals(64, result.size)
    }

    @Test
    fun `computeAuthKeys is deterministic`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce = ByteArray(16) { (it + 16).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val r1 = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        val r2 = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        assertArrayEquals(r1, r2)
    }

    @Test
    fun `computeAuthKeys changes with different nonces`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce1 = ByteArray(16) { (it + 16).toByte() }
        val phoneNonce2 = ByteArray(16) { (it + 48).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val r1 = AuthService.computeAuthKeys(secretKey, phoneNonce1, watchNonce)
        val r2 = AuthService.computeAuthKeys(secretKey, phoneNonce2, watchNonce)
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun `hmacSha256 produces 32 bytes`() {
        val key = ByteArray(16) { 0x42 }
        val input = ByteArray(32) { 0x01 }
        val result = AuthService.hmacSha256(key, input)
        assertEquals(32, result.size)
    }

    @Test
    fun `encryptCcm then decryptCcm roundtrip`() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val plaintext = "hello watch".toByteArray()
        val encrypted = AuthService.encryptCcm(key, nonce, plaintext)
        val decrypted = AuthService.decryptCcm(key, nonce, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encryptCtr then decryptCtr roundtrip`() {
        val key = ByteArray(16) { (it * 5).toByte() }
        val plaintext = "hello watch v2".toByteArray()
        val encrypted = AuthService.encryptCtr(key, key, plaintext)
        val decrypted = AuthService.decryptCtr(key, key, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `parseAuthKey handles 0x prefix`() {
        val hex = "0xba2ae13b1dba45e4f53e28e98b6b5846"
        val bytes = AuthService.parseAuthKey(hex)
        assertEquals(16, bytes.size)
        assertEquals(0xBA.toByte(), bytes[0])
        assertEquals(0x46.toByte(), bytes[15])
    }

    @Test
    fun `parseAuthKey handles no prefix`() {
        val hex = "ba2ae13b1dba45e4f53e28e98b6b5846"
        val bytes = AuthService.parseAuthKey(hex)
        assertEquals(16, bytes.size)
        assertEquals(0xBA.toByte(), bytes[0])
    }
}
