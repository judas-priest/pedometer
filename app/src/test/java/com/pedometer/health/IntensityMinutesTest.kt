package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Test

class IntensityMinutesTest {

    private val maxHr = 190
    private val rest = 60 // Karvonen, HRR=130: moderate 125..150, intense 151+ (70% of 130 = 91)

    @Test
    fun `resting hr earns nothing`() {
        val m = IntensityMinutes.compute(listOf(70L to 80, 71L to 90), maxHr, rest)
        assertEquals(0, m.moderateMinutes)
        assertEquals(0, m.intenseMinutes)
        assertEquals(0, m.earnedMinutes)
    }

    @Test
    fun `moderate and intense minutes accumulate`() {
        val m = IntensityMinutes.compute(
            listOf(0L to 130, 60_000L to 140, 120_000L to 155, 180_000L to 165),
            maxHr, rest,
        )
        assertEquals(2, m.moderateMinutes)
        assertEquals(2, m.intenseMinutes)
        assertEquals(2 + 2 * 2, m.earnedMinutes)
    }

    @Test
    fun `deduplicates samples within the same minute`() {
        val m = IntensityMinutes.compute(
            listOf(0L to 130, 30_000L to 135, 60_000L to 160),
            maxHr, rest,
        )
        assertEquals(1, m.moderateMinutes)
        assertEquals(1, m.intenseMinutes)
    }

    @Test
    fun `a minute counts once at the highest zone reached`() {
        val m = IntensityMinutes.compute(
            listOf(0L to 130, 30_000L to 160),
            maxHr, rest,
        )
        assertEquals(0, m.moderateMinutes)
        assertEquals(1, m.intenseMinutes)
    }

    @Test
    fun `zone boundaries are contiguous under karvonen`() {
        // 124 = below moderate, 125 = moderate floor, 150 = moderate top, 151 = intense floor
        val m = IntensityMinutes.compute(
            listOf(
                0L to 124, 60_000L to 125, 120_000L to 150,
                180_000L to 151, 240_000L to 165,
            ),
            maxHr, rest,
        )
        assertEquals(2, m.moderateMinutes)
        assertEquals(2, m.intenseMinutes)
    }

    @Test
    fun `tanaka max hr for age 33 is 185`() {
        assertEquals(185, IntensityMinutes.maxHrFor(33))
    }

    // ── Shared Karvonen zone thresholds (single source for widget + intensity) ──

    @Test
    fun `karvonen boundaries for age 33 resting 60 are 122 and 147`() {
        val z = IntensityMinutes.zones(IntensityMinutes.maxHrFor(33), 60)
        assertEquals(122, z.moderateLo)
        assertEquals(147, z.intenseLo)
    }

    @Test
    fun `zone classification at the boundaries`() {
        val maxHr = IntensityMinutes.maxHrFor(33) // 185
        assertEquals(IntensityMinutes.HrZone.LIGHT, IntensityMinutes.zoneOf(121, maxHr, 60))
        assertEquals(IntensityMinutes.HrZone.MODERATE, IntensityMinutes.zoneOf(122, maxHr, 60))
        assertEquals(IntensityMinutes.HrZone.MODERATE, IntensityMinutes.zoneOf(146, maxHr, 60))
        assertEquals(IntensityMinutes.HrZone.INTENSE, IntensityMinutes.zoneOf(147, maxHr, 60))
    }

    @Test
    fun `a minute at 130 bpm counts as moderate for age 33`() {
        assertEquals(IntensityMinutes.HrZone.MODERATE, IntensityMinutes.zoneOf(130, 185, 60))
        val m = IntensityMinutes.compute(listOf(0L to 130), 185, 60)
        assertEquals(1, m.moderateMinutes)
        assertEquals(0, m.intenseMinutes)
    }

    @Test
    fun `max hr override shifts boundaries up`() {
        // HRR = 200-60 = 140: moderate 60+70=130, intense 60+98=158
        val z = IntensityMinutes.zones(200, 60)
        assertEquals(130, z.moderateLo)
        assertEquals(158, z.intenseLo)
        // 147 bpm: intense at Tanaka 185, still moderate with the override
        assertEquals(IntensityMinutes.HrZone.MODERATE, IntensityMinutes.zoneOf(147, 200, 60))
    }

    @Test
    fun `effectiveMaxHr honors override then tanaka`() {
        assertEquals(185, UserProfile(age = 33).effectiveMaxHr())
        assertEquals(200, UserProfile(age = 33, maxHrOverride = 200).effectiveMaxHr())
    }
}
