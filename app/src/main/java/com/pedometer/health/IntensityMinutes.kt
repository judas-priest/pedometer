package com.pedometer.health

/**
 * WHO-style intensity minutes from heart-rate samples.
 * Zones are percentages of the user's estimated max HR (220 - age):
 * moderate 50-69%, intense 70-84%. Intense minutes count double toward the weekly goal.
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

    fun compute(samples: List<Pair<Long, Int>>, maxHr: Int): Result {
        val moderateLo = maxHr * 50 / 100
        val moderateHi = maxHr * 69 / 100
        val intenseLo = maxHr * 70 / 100
        val intenseHi = maxHr * 84 / 100
        val byMinute = samples.groupBy { it.first / 60_000L }
        var moderate = 0
        var intense = 0
        for ((_, minuteSamples) in byMinute) {
            val best = minuteSamples.maxOf { it.second }
            when {
                best in intenseLo..intenseHi -> intense++
                best in moderateLo..moderateHi -> moderate++
            }
        }
        return Result(moderate, intense, moderate + intense * 2)
    }

    fun maxHrFor(age: Int): Int = (220 - age).coerceIn(120, 220)
}
