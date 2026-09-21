package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthInsightsTest {

    // ── Keytel (male, 93 kg, 33 y): per-minute coefficient at HR 131 ≈ 12.6 gross ──

    @Test
    fun `keytel net kcal for a real walk`() {
        // 98 min walk at avg 131 bpm, 93 kg, 33 y (user-measured walk)
        val kcal = HealthInsights.keytelKcal(hrAvg = 131, weightKg = 93, age = 33, durationMin = 98)
        // gross/min = (−55.0969 + 82.6479 + 18.4884 + 6.6561)/4.184 = 52.6955/4.184 = 12.5946
        // rmr/min (Mifflin 93/179/33 male) = 1888.75/1440 = 1.3116
        // net/min = 11.2830 → ×98 = 1105.7 → 1105
        assertEquals(1105, kcal)
    }

    @Test
    fun `keytel returns zero without hr`() {
        assertEquals(0, HealthInsights.keytelKcal(hrAvg = 0, weightKg = 93, age = 33, durationMin = 60))
    }

    // ── Banister TRIMP (max 185, rest 60) ──

    @Test
    fun `trimp of an easy walk is small`() {
        // fraction = (117−60)/125 = 0.456
        // 63 × 0.456 × 0.64 × e^(1.92×0.456) = 63 × 0.456 × 0.64 × 2.4003 = 44.13
        val t = HealthInsights.trimp(hrAvg = 117, durationMin = 63, maxHr = 185, restingHr = 60)
        assertEquals(44, t)
    }

    @Test
    fun `trimp of a hard walk is much larger`() {
        // fraction = (155−60)/125 = 0.76
        // 98 × 0.76 × 0.64 × e^(1.92×0.76) = 98 × 0.76 × 0.64 × 4.3030 = 205.1
        val t = HealthInsights.trimp(hrAvg = 155, durationMin = 98, maxHr = 185, restingHr = 60)
        assertEquals(205, t)
    }

    @Test
    fun `trimp is zero without hr`() {
        assertEquals(0, HealthInsights.trimp(hrAvg = 0, durationMin = 60, maxHr = 185, restingHr = 60))
    }

    // ── Resting-HR baseline flag ──

    @Test
    fun `no flag without baseline`() {
        val r = HealthInsights.restingHrInsight(baseline = emptyList(), today = 74)
        assertFalse(r.elevated)
        assertEquals(0, r.delta)
    }

    @Test
    fun `no flag with too short baseline`() {
        val r = HealthInsights.restingHrInsight(baseline = listOf(58, 60), today = 74)
        assertFalse(r.elevated)
    }

    @Test
    fun `elevated when today exceeds baseline by 3 or more`() {
        val base = List(14) { 60 }
        val r = HealthInsights.restingHrInsight(baseline = base, today = 63)
        assertTrue(r.elevated)
        assertEquals(3, r.delta)
    }

    @Test
    fun `normal when within baseline noise`() {
        // avg of this baseline = 418/7 = 59; today 61 → delta 2 < 3 → not elevated
        val base = listOf(58, 60, 62, 59, 61, 60, 58)
        val r = HealthInsights.restingHrInsight(baseline = base, today = 61)
        assertFalse(r.elevated)
    }

    @Test
    fun `no today value means no insight`() {
        val r = HealthInsights.restingHrInsight(baseline = List(20) { 60 }, today = 0)
        assertFalse(r.elevated)
        assertEquals(0, r.today)
    }
}
