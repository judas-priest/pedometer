package com.pedometer.health

/**
 * Health insights derived from heart rate. Pure — the caller reads the database.
 *
 * Sources of the formulas:
 *  - Keytel et al. 2005 (J Sports Sciences): HR-based energy expenditure, validated r=0.913
 *    against indirect calorimetry. Male form; net of resting metabolic rate (Mifflin-St Jeor).
 *  - Banister TRIMP: training impulse = minutes × HRR-fraction × 0.64 × e^(1.92 × fraction)
 *    (male weighting). Deliberately NO acute:chronic ratio — methodologically criticized
 *    (Impellizzeri 2020); week-over-week is shown as a plain percentage instead.
 *  - Elevated resting HR: rolling personal baseline (Stanford/Scripps wearable practice),
 *    flag when today exceeds the baseline by >=3 bpm with >=7 baseline days available.
 */
object HealthInsights {

    /** Net kcal for ONE minute at the given HR. */
    fun keytelPerMinute(hr: Int, weightKg: Int, age: Int, heightCm: Int = 179): Double {
        val grossPerMin = (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age) / 4.184
        // Mifflin-St Jeor RMR (male) per minute, so "net" excludes what he'd burn resting anyway
        val rmrPerMin = (10.0 * weightKg + 6.25 * heightCm - 5.0 * age + 5.0) / 1440.0
        return (grossPerMin - rmrPerMin).coerceAtLeast(0.0)
    }

    /** Net kcal for a list of per-minute HR samples. 0 when the list is empty. */
    fun keytelSum(bpms: List<Int>, weightKg: Int, age: Int, heightCm: Int = 179): Int {
        if (bpms.isEmpty()) return 0
        return bpms.sumOf { keytelPerMinute(it, weightKg, age, heightCm) }.toInt().coerceAtLeast(0)
    }

    /**
     * Gross MET for walking at the given speed — Compendium of Physical Activities,
     * piecewise-linear between the anchor points (running territory from ~9.7 km/h).
     * Clamped to 1.0 (resting) .. 8.0: HR-only estimates for walking must not exceed
     * what the legs can physically spend at this speed.
     */
    fun metFromSpeed(speedKmh: Double): Double {
        val table = listOf(
            0.0 to 1.0, 4.0 to 3.0, 4.8 to 3.5, 5.6 to 4.3, 7.2 to 5.0, 9.7 to 8.0,
        )
        if (speedKmh <= table.first().first) return table.first().second
        if (speedKmh >= table.last().first) return table.last().second
        for ((lo, hi) in table.zipWithNext()) {
            if (speedKmh <= hi.first) {
                return lo.second + (speedKmh - lo.first) * (hi.second - lo.second) / (hi.first - lo.first)
            }
        }
        return table.last().second
    }

    /**
     * Net kcal for ONE minute of walking, capped by what moving at this cadence can
     * physically burn: Keytel (HR-driven) tends to overestimate for high-HR responders,
     * so the result is min(Keytel, MET-from-speed net budget).
     */
    fun walkKcalPerMinute(
        hr: Int,
        cadenceStepsPerMin: Int,
        weightKg: Int,
        age: Int,
        stepLenM: Double,
        heightCm: Int = 179,
    ): Double {
        val keytel = keytelPerMinute(hr, weightKg, age, heightCm)
        val speedKmh = cadenceStepsPerMin * stepLenM * 60.0 / 1000.0
        val cap = (metFromSpeed(speedKmh) - 1.0) * 3.5 * weightKg / 200.0
        return minOf(keytel, cap)
    }

    /** Net (above-resting) kcal burned during steady aerobic activity. 0 when no HR. */
    fun keytelKcal(hrAvg: Int, weightKg: Int, age: Int, durationMin: Int, heightCm: Int = 179): Int {
        if (hrAvg <= 0 || durationMin <= 0) return 0
        return keytelSum(List(durationMin) { hrAvg }, weightKg, age, heightCm)
    }

    /** Banister TRIMP training-load points. 0 when no HR. */
    fun trimp(hrAvg: Int, durationMin: Int, maxHr: Int, restingHr: Int): Int {
        if (hrAvg <= 0 || durationMin <= 0) return 0
        val hrr = (maxHr - restingHr).coerceAtLeast(1)
        val fraction = ((hrAvg - restingHr).toDouble() / hrr).coerceIn(0.0, 1.0)
        if (fraction <= 0.0) return 0
        return (durationMin * fraction * 0.64 * Math.exp(1.92 * fraction)).toInt()
    }

    data class RestingHrInsight(
        val baseline: Int,
        val today: Int,
        val delta: Int,      // today − baseline; 0 when no insight
        val elevated: Boolean,
    )

    /**
     * Flags today's resting HR against the personal baseline (avg of prior days' hrResting).
     * Requires >=7 baseline days (Stanford practice: sustained deviation, not one-off noise).
     * Elevation threshold: >=3 bpm above baseline.
     */
    fun restingHrInsight(baseline: List<Int>, today: Int): RestingHrInsight {
        if (today <= 0) return RestingHrInsight(0, 0, 0, false)
        val clean = baseline.filter { it in 30..120 }
        if (clean.size < 7) return RestingHrInsight(0, today, 0, false)
        val avg = clean.sum() / clean.size
        val delta = today - avg
        return RestingHrInsight(avg, today, delta, delta >= 3)
    }
}
