package com.pedometer.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `delays grow exponentially from the base delay`() {
        val policy = ReconnectPolicy(maxAttempts = 10, baseDelayMs = 2_000L, maxDelayMs = 300_000L)
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        val policy = ReconnectPolicy(maxAttempts = 20, baseDelayMs = 2_000L, maxDelayMs = 10_000L)
        repeat(5) { policy.nextDelayMs() }
        assertEquals(10_000L, policy.nextDelayMs())
    }

    @Test
    fun `gives up after maxAttempts`() {
        val policy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun `onConnected resets the attempt counter`() {
        val policy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.onConnected()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `suspendRetries stops retrying until resumed`() {
        val policy = ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        policy.suspendRetries()
        assertNull(policy.nextDelayMs())
        policy.resumeRetries()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `attempts is observable for logging`() {
        val policy = ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        assertEquals(0, policy.attempts)
        policy.nextDelayMs()
        assertEquals(1, policy.attempts)
    }
}
