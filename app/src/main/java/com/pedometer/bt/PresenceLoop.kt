package com.pedometer.bt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the presence-scan cadence. Radio-free and Android-free so the cadence rules
 * (quiet hours, home-Wi-Fi suppression, wake-on-demand, backoff) are unit-testable;
 * [WatchPresenceMonitor] owns the actual BLE scan.
 *
 * Home Wi-Fi with the gate on: the connection policy will not connect, so scanning is
 * pure waste — the loop sleeps in short ticks and relies on [scanNow] (fired by the
 * service's onLost callback) to resume the instant Wi-Fi drops.
 */
internal class PresenceLoop(
    private val scope: CoroutineScope,
    private val quietHours: () -> QuietHours,
    private val onQuietChanged: () -> Unit,
    private val onPresenceChanged: (present: Boolean) -> Unit,
    private val connected: () -> Boolean,
    private val scanSuppressed: () -> Boolean,
    private val scanOnce: suspend () -> Boolean?,
    private val now: () -> Int = { java.time.LocalTime.now().hour },
) {
    companion object {
        internal const val SCAN_WINDOW_MS = 3_000L
        internal const val BASE_INTERVAL_MS = 60_000L
    }

    private var loopJob: Job? = null
    private var lastQuiet: Boolean? = null
    private val intervalPolicy = ScanIntervalPolicy()

    /** Conflated wake signal: a scanNow() while the loop sleeps ends the sleep at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch { run() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Request an immediate scan — fires even while the loop is sleeping. */
    fun scanNow() {
        wake.trySend(Unit)
    }

    private suspend fun sleepMs(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
        // Drain stale tokens: a conflated channel hands the first token straight to a
        // waiting receiver and buffers the rest — without the drain, a second scanNow()
        // during the same wake would schedule one phantom extra scan.
        while (wake.tryReceive().isSuccess) { /* discard */ }
    }

    private suspend fun run() {
        while (true) {
            val quiet = quietHours().isQuiet(now())
            if (quiet != lastQuiet) {
                lastQuiet = quiet
                onQuietChanged()
            }
            if (quiet) {
                // Night window: no scan at all. Re-assert the policy every tick so any
                // connect that slipped past the gate is torn down within a minute.
                onQuietChanged()
                sleepMs(BASE_INTERVAL_MS)
                continue
            }
            if (connected()) {
                // Watch already linked — scanning is pure radio waste.
                sleepMs(BASE_INTERVAL_MS)
                continue
            }
            if (scanSuppressed()) {
                sleepMs(BASE_INTERVAL_MS)
                continue
            }
            val found = scanOnce()
            if (found != null) {
                intervalPolicy.onScanResult(found)
                onPresenceChanged(found)
            }
            sleepMs((intervalPolicy.nextIntervalMs() - SCAN_WINDOW_MS).coerceAtLeast(BASE_INTERVAL_MS / 2))
        }
    }
}
