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

    /** Net (above-resting) kcal burned during steady aerobic activity. 0 when no HR. */
    fun keytelKcal(hrAvg: Int, weightKg: Int, age: Int, durationMin: Int, heightCm: Int = 179): Int {
        if (hrAvg <= 0 || durationMin <= 0) return 0
        val grossPerMin = (-55.0969 + 0.6309 * hrAvg + 0.1988 * weightKg + 0.2017 * age) / 4.184
        // Mifflin-St Jeor RMR (male) per minute, so "net" excludes what he'd burn resting anyway
        val rmrPerMin = (10.0 * weightKg + 6.25 * heightCm - 5.0 * age + 5.0) / 1440.0
        val netPerMin = grossPerMin - rmrPerMin
        return (netPerMin * durationMin).toInt().coerceAtLeast(0)
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
