package com.pedometer.health

enum class SupplementSlot(val key: String, val title: String) {
    FASTING("fasting", "Натощак"),
    BREAKFAST("breakfast", "Завтрак"),
    LUNCH("lunch", "Обед"),
    DINNER("dinner", "Ужин");

    companion object {
        fun byKey(key: String): SupplementSlot = entries.first { it.key == key }
    }
}

/** Window of day in minutes, [startMin, endMin). */
data class Window(val startMin: Int, val endMin: Int) {
    fun contains(minOfDay: Int): Boolean = minOfDay in startMin until endMin
}

object SupplementWindows {
    // [regime][slot] — minutes of day. Overridable via prefs
    // "supp_win_<slot>_<regime>_start/_end".
    private val DEFAULTS = mapOf(
        "home" to mapOf(
            "fasting" to Window(720, 780),
            "breakfast" to Window(780, 960),
            "lunch" to Window(960, 1140),
            "dinner" to Window(1140, 1320),
        ),
        "office" to mapOf(
            "fasting" to Window(420, 480),
            "breakfast" to Window(480, 570),
            "lunch" to Window(780, 870),
            "dinner" to Window(1140, 1320),
        ),
    )

    fun regimeOf(prefs: Map<String, String>): String =
        if (prefs["supp_regime"] == "office") "office" else "home"

    /** Effective window; prefs override per regime. */
    fun windowFor(slot: String, regime: String, prefs: Map<String, Int> = emptyMap()): Window {
        val def = DEFAULTS.getValue(regime).getValue(slot)
        val start = prefs["supp_win_${slot}_${regime}_start"] ?: def.startMin
        val end = prefs["supp_win_${slot}_${regime}_end"] ?: def.endMin
        return Window(start, end)
    }

    fun isAccidentalDismissal(postedAtMs: Long, dismissedAtMs: Long, thresholdMs: Long = 30_000L): Boolean =
        dismissedAtMs - postedAtMs < thresholdMs

    /** "13:00" → 780. Null on garbage or out-of-range. */
    fun parseHhMm(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 780 → "13:00". */
    fun formatHhMm(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}

object SupplementStreak {
    fun streak(loggedByDate: Map<String, Set<String>>, requiredSlots: Set<String>, today: java.time.LocalDate): Int {
        if (requiredSlots.isEmpty()) return 0
        var day = today
        if (loggedByDate[day.toString()]?.containsAll(requiredSlots) != true) day = day.minusDays(1)
        var count = 0
        while (loggedByDate[day.toString()]?.containsAll(requiredSlots) == true) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
