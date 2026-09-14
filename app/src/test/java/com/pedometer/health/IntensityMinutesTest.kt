package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Test

class IntensityMinutesTest {

    private val maxHr = 190 // zones: moderate 95..132, intense 133..159 (50-69%, 70-84%)

    @Test
    fun `resting hr earns nothing`() {
        val m = IntensityMinutes.compute(listOf(70L to 80, 71L to 90), maxHr)
        assertEquals(0, m.moderateMinutes)
        assertEquals(0, m.intenseMinutes)
        assertEquals(0, m.earnedMinutes)
    }

    @Test
    fun `moderate and intense minutes accumulate`() {
        val m = IntensityMinutes.compute(
            listOf(0L to 100, 60_000L to 110, 120_000L to 140, 180_000L to 150),
            maxHr,
        )
        assertEquals(2, m.moderateMinutes)
        assertEquals(2, m.intenseMinutes)
        assertEquals(2 + 2 * 2, m.earnedMinutes) // intense counts double
    }

    @Test
    fun `deduplicates samples within the same minute`() {
        val m = IntensityMinutes.compute(
            listOf(0L to 100, 30_000L to 105, 60_000L to 140), // two samples in minute 0
            maxHr,
        )
        assertEquals(1, m.moderateMinutes)
        assertEquals(1, m.intenseMinutes)
    }

    @Test
    fun `a minute counts once at the highest zone reached`() {
        // minute 0 has 100 (moderate) and 145 (intense) — counts once, as intense
        val m = IntensityMinutes.compute(
            listOf(0L to 100, 30_000L to 145),
            maxHr,
        )
        assertEquals(0, m.moderateMinutes)
        assertEquals(1, m.intenseMinutes)
    }
}
