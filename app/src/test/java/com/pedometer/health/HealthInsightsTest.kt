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

    @Test
    fun `keytelSum is keytelKcal for uniform hr`() {
        val sum = HealthInsights.keytelSum(List(98) { 131 }, 93, 33)
        assertEquals(HealthInsights.keytelKcal(hrAvg = 131, weightKg = 93, age = 33, durationMin = 98), sum)
    }

    // ── MET ceiling for Keytel calories ──

    @Test
    fun `metFromSpeed interpolation over the compendium table`() {
        assertEquals(1.0, HealthInsights.metFromSpeed(0.0), 1e-9)
        assertEquals(3.5, HealthInsights.metFromSpeed(4.8), 1e-9)
        assertEquals(4.3, HealthInsights.metFromSpeed(5.6), 1e-9)
        assertEquals(3.9, HealthInsights.metFromSpeed(5.2), 0.1) // midpoint of 3.5..4.3
        assertEquals(8.0, HealthInsights.metFromSpeed(10.0), 1e-9)
    }

    @Test
    fun `metFromSpeed clamps below table and above running`() {
        assertEquals(1.0, HealthInsights.metFromSpeed(-2.0), 1e-9)
        assertEquals(8.0, HealthInsights.metFromSpeed(50.0), 1e-9)
    }

    @Test
    fun `walkKcalPerMinute never exceeds keytel`() {
        val keytel = HealthInsights.keytelPerMinute(150, 93, 33)
        val v = HealthInsights.walkKcalPerMinute(hr = 150, cadenceStepsPerMin = 110, weightKg = 93, age = 33, stepLenM = 0.81)
        // speed 5.35 km/h → MET 4.05 → cap ≈ 4.96, well below Keytel ≈ 14.1 at 150 bpm
        val cap = (HealthInsights.metFromSpeed(110 * 0.81 * 60.0 / 1000.0) - 1.0) * 3.5 * 93 / 200.0
        assertEquals(cap, v, 1e-6)
        assertTrue(v <= keytel)
    }

    @Test
    fun `walkKcalPerMinute returns keytel when cap is not binding`() {
        val keytel = HealthInsights.keytelPerMinute(80, 93, 33)
        val v = HealthInsights.walkKcalPerMinute(hr = 80, cadenceStepsPerMin = 110, weightKg = 93, age = 33, stepLenM = 0.81)
        assertEquals(keytel, v, 1e-6)
    }

    @Test
    fun `tonight walk scenario is capped near 5 kcal per minute`() {
        // 106-min GPS walk: cadence ≈112 steps/min, GPS-derived step length 0.81 m
        val speed = 112 * 0.81 * 60.0 / 1000.0 // ≈ 5.44 km/h
        val cap = (HealthInsights.metFromSpeed(speed) - 1.0) * 3.5 * 93 / 200.0
        val v = HealthInsights.walkKcalPerMinute(hr = 150, cadenceStepsPerMin = 112, weightKg = 93, age = 33, stepLenM = 0.81)
        assertEquals(cap, v, 1e-6)
        assertTrue("expected cap ≈5.1 kcal/min, got $v", v in 4.5..6.0)
        // Keytel alone would give ≈14 kcal/min — the point of the cap
        assertTrue(v < HealthInsights.keytelPerMinute(150, 93, 33) / 2)
    }

    @Test
    fun `walkKcalPerMinute is zero for standing minute`() {
        // cadence 0 → MET 1.0 → cap 0 regardless of HR
        val v = HealthInsights.walkKcalPerMinute(hr = 150, cadenceStepsPerMin = 0, weightKg = 93, age = 33, stepLenM = 0.81)
        assertEquals(0.0, v, 1e-9)
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
