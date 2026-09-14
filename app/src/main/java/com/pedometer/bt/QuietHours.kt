package com.pedometer.bt

/**
 * A daily window during which the watch link is torn down and presence scans pause.
 * [startHour] may be greater than [endHour] — the window then wraps past midnight
 * (e.g. 22 → 8 covers 22:00–07:59).
 */
data class QuietHours(
    val enabled: Boolean,
    val startHour: Int,
    val endHour: Int,
) {
    /** @param hour current hour of day, 0..23. */
    fun isQuiet(hour: Int): Boolean {
        if (!enabled || startHour == endHour) return false
        return if (startHour < endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour
    }

    companion object {
        val DEFAULT = QuietHours(enabled = true, startHour = 0, endHour = 7)

        /** Used as the monitor's default supplier when no gating is wired. */
        val DISABLED = QuietHours(enabled = false, startHour = 0, endHour = 7)
    }
}
