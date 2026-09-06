package com.pedometer.bt

/**
 * Decides whether and how long to wait before retrying a dropped watch connection.
 *
 * Pure logic: no Android, no coroutines, no clock. The caller owns the waiting.
 */
class ReconnectPolicy(
    private val maxAttempts: Int = 12,
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 5 * 60_000L,
) {
    var attempts: Int = 0
        private set

    private var enabled = true

    /** Delay before the next attempt, or null if we should stop retrying. */
    fun nextDelayMs(): Long? {
        if (!enabled) return null
        if (attempts >= maxAttempts) return null
        attempts++
        val delay = baseDelayMs shl (attempts - 1)
        return if (delay <= 0L || delay > maxDelayMs) maxDelayMs else delay
    }

    /** Successful authentication — the next drop starts from the base delay again. */
    fun onConnected() {
        attempts = 0
        enabled = true
    }

    /** User asked for disconnect — do not fight them. */
    fun suspendRetries() {
        enabled = false
    }

    fun resumeRetries() {
        enabled = true
        attempts = 0
    }
}
