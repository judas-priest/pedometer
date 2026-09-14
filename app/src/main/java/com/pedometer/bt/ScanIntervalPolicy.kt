package com.pedometer.bt

/**
 * Delay before the next presence-scan window. Scans every minute while the watch is around
 * or just went missing; the interval backs off the longer the watch stays gone, because the
 * probability of it returning in any given minute drops to zero.
 *
 * Pure: the caller runs the scans and owns the clock.
 */
class ScanIntervalPolicy(
    private val intervalsMs: List<Long> = listOf(60_000L, 5 * 60_000L, 10 * 60_000L),
) {
    private var consecutiveAbsences: Int = 0

    /** Delay to use before the next scan window. */
    fun nextIntervalMs(): Long = intervalsMs[consecutiveAbsences.coerceAtMost(intervalsMs.size - 1)]

    /** Feed every completed scan window: seen=true, absent=false, unknown=null (scan failed). */
    fun onScanResult(present: Boolean?) {
        if (present == true) consecutiveAbsences = 0
        else if (present == false) consecutiveAbsences++
    }
}
