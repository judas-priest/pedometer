package com.pedometer.health

import org.junit.Assert.*
import org.junit.Test

class SupplementLogicTest {

    @Test
    fun `home windows are correct`() {
        assertEquals(Window(720, 780), SupplementWindows.windowFor("fasting", office = false))
        assertEquals(Window(780, 960), SupplementWindows.windowFor("breakfast", office = false))
        assertEquals(Window(780, 1320), SupplementWindows.windowFor("flex", office = false))
    }

    @Test
    fun `office windows are correct`() {
        assertEquals(Window(420, 480), SupplementWindows.windowFor("fasting", office = true))
        assertEquals(Window(480, 570), SupplementWindows.windowFor("breakfast", office = true))
        assertEquals(Window(780, 1320), SupplementWindows.windowFor("flex", office = true))
    }

    @Test
    fun `prefs override home window`() {
        val prefs = mapOf("supp_win_breakfast_start" to 800, "supp_win_breakfast_end" to 900)
        assertEquals(Window(800, 900), SupplementWindows.windowFor("breakfast", office = false, prefs = prefs))
    }

    @Test
    fun `prefs override does not affect office`() {
        val prefs = mapOf("supp_win_breakfast_start" to 800, "supp_win_breakfast_end" to 900)
        assertEquals(Window(480, 570), SupplementWindows.windowFor("breakfast", office = true, prefs = prefs))
    }

    @Test
    fun `dismissal within 30s is accidental`() {
        assertTrue(SupplementWindows.isAccidentalDismissal(postedAtMs = 1_000_000L, dismissedAtMs = 1_020_000L))
    }

    @Test
    fun `dismissal after 30s counts`() {
        assertFalse(SupplementWindows.isAccidentalDismissal(postedAtMs = 1_000_000L, dismissedAtMs = 1_031_000L))
    }

    @Test
    fun `office override expires next day`() {
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertTrue(SupplementWindows.officeOverrideActive("2026-09-26", today))
        assertFalse(SupplementWindows.officeOverrideActive("2026-09-25", today))
        assertFalse(SupplementWindows.officeOverrideActive(null, today))
    }

    @Test
    fun `streak counts back from yesterday when today incomplete`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast"),                        // today: incomplete → not counted
            "2026-09-25" to setOf("breakfast", "flex"),
            "2026-09-24" to setOf("breakfast", "flex"),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(2, SupplementStreak.streak(logged, required, today))
    }

    @Test
    fun `streak counts today when complete`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast", "flex"),
            "2026-09-25" to setOf("breakfast", "flex"),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(2, SupplementStreak.streak(logged, required, today))
    }

    @Test
    fun `streak is zero when yesterday missed`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast", "flex"),
            "2026-09-25" to emptySet(),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(0, SupplementStreak.streak(logged, required, today))
    }
}
