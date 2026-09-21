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
}
