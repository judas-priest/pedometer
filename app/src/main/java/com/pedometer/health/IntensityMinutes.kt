package com.pedometer.health

/**
 * WHO-style intensity minutes from heart-rate samples, Karvonen zones.
 * Max HR comes from the Tanaka equation (208 - 0.7 * age); zone thresholds are
 * percentages of heart-rate reserve (max HR minus resting HR): moderate 50-69% HRR,
 * intense >=70% HRR. Intense minutes count double toward the weekly goal.
 *
 * Pure: the caller reads the database and owns the clock.
 */
object IntensityMinutes {

    data class Result(
        val moderateMinutes: Int,
        val intenseMinutes: Int,
        /** Moderate ×1 + intense ×2 — the number compared against the weekly goal. */
        val earnedMinutes: Int,
    )

    /** Karvonen zone thresholds in bpm: moderate [moderateLo, intenseLo), intense >= intenseLo. */
    data class Zones(val moderateLo: Int, val intenseLo: Int)

    enum class HrZone { LIGHT, MODERATE, INTENSE }

    /**
     * Karvonen thresholds: percentages of heart-rate reserve (max HR minus resting HR) —
     * moderate floor at 50% HRR, intense floor at 70% HRR. Single source of truth for the
     * intensity-minute classifier and the day-detail HR-zone widget.
     */
    fun zones(maxHr: Int, restingHr: Int = 60): Zones {
        val hrr = (maxHr - restingHr).coerceAtLeast(1)
        return Zones(restingHr + hrr * 50 / 100, restingHr + hrr * 70 / 100)
    }

    /** Classify one bpm sample into the three display zones. */
    fun zoneOf(bpm: Int, maxHr: Int, restingHr: Int = 60): HrZone {
        val z = zones(maxHr, restingHr)
        return when {
            bpm >= z.intenseLo -> HrZone.INTENSE
            bpm >= z.moderateLo -> HrZone.MODERATE
            else -> HrZone.LIGHT
        }
    }

    /**
     * WHO-style intensity minutes from heart-rate samples, Karvonen zones.
     * Zone thresholds are percentages of heart-rate reserve (max HR minus resting HR):
     * moderate 50-69% HRR, intense >=70% HRR (open top). Intense minutes count double.
     *
     * Pure: the caller reads the database and owns the clock.
     */
    fun compute(samples: List<Pair<Long, Int>>, maxHr: Int, restingHr: Int = 60): Result {
        val z = zones(maxHr, restingHr)
        val byMinute = samples.groupBy { it.first / 60_000L }
        var moderate = 0
        var intense = 0
        for ((_, minuteSamples) in byMinute) {
            val best = minuteSamples.maxOf { it.second }
            when {
                best >= z.intenseLo -> intense++
                best >= z.moderateLo -> moderate++
            }
        }
        return Result(moderate, intense, moderate + intense * 2)
    }

    /** Tanaka equation — more accurate than the classic 220-age, especially over 40. */
    fun maxHrFor(age: Int): Int = Math.round(208 - 0.7 * age).toInt().coerceIn(120, 220)
}
