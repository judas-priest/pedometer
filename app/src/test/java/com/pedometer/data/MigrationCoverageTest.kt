package com.pedometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationCoverageTest {

    @Test
    fun `no gaps when every step is covered`() {
        val steps = listOf(7 to 8, 8 to 9)
        assertEquals(emptyList<Int>(), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `reports the version that has no migration out of it`() {
        val steps = listOf(7 to 8)
        assertEquals(listOf(8), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `reports every missing step`() {
        assertEquals(listOf(7, 8), missingMigrationSteps(emptyList(), from = 7, to = 9))
    }

    @Test
    fun `multi version migrations cover the range they span`() {
        val steps = listOf(7 to 9)
        assertEquals(emptyList<Int>(), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `same version needs no migrations`() {
        assertEquals(emptyList<Int>(), missingMigrationSteps(emptyList(), from = 7, to = 7))
    }
}
