package com.pedometer.health

import java.time.LocalDate

/** Supplement intake slot. key is stored in DB. */
enum class SupplementSlot(val key: String, val title: String) {
    FASTING("fasting", "Натощак"),
    BREAKFAST("breakfast", "Завтрак"),
    FLEX("flex", "Обед или ужин");

    companion object {
        fun byKey(key: String): SupplementSlot = entries.first { it.key == key }
    }
}

/** Window of day in minutes, [startMin, endMin). */
data class Window(val startMin: Int, val endMin: Int) {
    fun contains(minOfDay: Int): Boolean = minOfDay in startMin until endMin
}

object SupplementWindows {
    // Defaults: HOME breakfast 13:00–16:00, fasting 12:00–13:00;
    // OFFICE breakfast 08:00–09:30, fasting 07:00–08:00; flex 13:00–22:00 in both regimes.
    private val DEFAULTS = mapOf(
        false to mapOf( // false = home
            "fasting" to Window(720, 780),
            "breakfast" to Window(780, 960),
            "flex" to Window(780, 1320),
        ),
        true to mapOf(
            "fasting" to Window(420, 480),
            "breakfast" to Window(480, 570),
            "flex" to Window(780, 1320),
        ),
    )

    /** Effective window for a slot. Prefs (minutes of day) override the HOME regime only. */
    fun windowFor(slot: String, office: Boolean, prefs: Map<String, Int> = emptyMap()): Window {
        val def = DEFAULTS.getValue(office).getValue(slot)
        if (office) return def
        val start = prefs["supp_win_${slot}_start"] ?: def.startMin
        val end = prefs["supp_win_${slot}_end"] ?: def.endMin
        return Window(start, end)
    }

    /** Swipe within 30 s of posting is accidental (shade clear-all protection). */
    fun isAccidentalDismissal(postedAtMs: Long, dismissedAtMs: Long, thresholdMs: Long = 30_000L): Boolean =
        dismissedAtMs - postedAtMs < thresholdMs

    /** Manual «Сегодня в офисе» toggle: stored as ISO date string, expires next day. */
    fun officeOverrideActive(storedDate: String?, today: LocalDate): Boolean =
        storedDate == today.toString()
}

object SupplementStreak {
    /**
     * Consecutive days where every required slot has an intake. The chain is anchored on
     * yesterday; today is counted as a bonus only on top of an existing chain — a streak
     * cannot be started by today alone (missing yesterday resets it to zero).
     */
    fun streak(loggedByDate: Map<String, Set<String>>, requiredSlots: Set<String>, today: LocalDate): Int {
        if (requiredSlots.isEmpty()) return 0
        var day = today.minusDays(1)
        var count = 0
        while (loggedByDate[day.toString()]?.containsAll(requiredSlots) == true) {
            count++
            day = day.minusDays(1)
        }
        if (count > 0 && loggedByDate[today.toString()]?.containsAll(requiredSlots) == true) count++
        return count
    }
}
