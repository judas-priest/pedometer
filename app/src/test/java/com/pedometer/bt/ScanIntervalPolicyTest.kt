package com.pedometer.bt

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanIntervalPolicyTest {

    @Test
    fun `first interval is one minute`() {
        val policy = ScanIntervalPolicy()
        assertEquals(60_000L, policy.nextIntervalMs())
    }

    @Test
    fun `interval grows after consecutive absences`() {
        val policy = ScanIntervalPolicy()
        policy.onScanResult(false)
        assertEquals(5 * 60_000L, policy.nextIntervalMs())
        policy.onScanResult(false)
        assertEquals(10 * 60_000L, policy.nextIntervalMs())
    }

    @Test
    fun `interval is capped at ten minutes`() {
        val policy = ScanIntervalPolicy()
        repeat(10) { policy.onScanResult(false) }
        assertEquals(10 * 60_000L, policy.nextIntervalMs())
    }

    @Test
    fun `watching the watch resets the progression`() {
        val policy = ScanIntervalPolicy()
        policy.onScanResult(false)
        policy.onScanResult(false)
        policy.onScanResult(true)
        assertEquals(60_000L, policy.nextIntervalMs())
    }

    @Test
    fun `unknown scan result does not change the progression`() {
        val policy = ScanIntervalPolicy()
        policy.onScanResult(false)
        policy.onScanResult(null)
        assertEquals(5 * 60_000L, policy.nextIntervalMs())
    }
}
