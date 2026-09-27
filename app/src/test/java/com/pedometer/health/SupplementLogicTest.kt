package com.pedometer.health

import org.junit.Assert.*
import org.junit.Test

class SupplementLogicTest {

    @Test
    fun `four slots exist with right keys`() {
        assertEquals(
            listOf("fasting", "breakfast", "lunch", "dinner"),
            SupplementSlot.entries.map { it.key },
        )
        assertEquals("Натощак", SupplementSlot.byKey("fasting").title)
        assertEquals("Ужин", SupplementSlot.byKey("dinner").title)
    }

    @Test
    fun `home windows defaults`() {
        assertEquals(Window(720, 780), SupplementWindows.windowFor("fasting", regime = "home"))
        assertEquals(Window(780, 960), SupplementWindows.windowFor("breakfast", regime = "home"))
        assertEquals(Window(960, 1140), SupplementWindows.windowFor("lunch", regime = "home"))
        assertEquals(Window(1140, 1320), SupplementWindows.windowFor("dinner", regime = "home"))
    }

    @Test
    fun `office windows defaults`() {
        assertEquals(Window(420, 480), SupplementWindows.windowFor("fasting", regime = "office"))
        assertEquals(Window(480, 570), SupplementWindows.windowFor("breakfast", regime = "office"))
        assertEquals(Window(780, 870), SupplementWindows.windowFor("lunch", regime = "office"))
        assertEquals(Window(1140, 1320), SupplementWindows.windowFor("dinner", regime = "office"))
    }

    @Test
    fun `prefs override both regimes`() {
        val prefs = mapOf(
            "supp_win_lunch_home_start" to 900, "supp_win_lunch_home_end" to 1000,
            "supp_win_lunch_office_start" to 780, "supp_win_lunch_office_end" to 830,
        )
        assertEquals(Window(900, 1000), SupplementWindows.windowFor("lunch", regime = "home", prefs = prefs))
        assertEquals(Window(780, 830), SupplementWindows.windowFor("lunch", regime = "office", prefs = prefs))
        assertEquals(Window(1140, 1320), SupplementWindows.windowFor("dinner", regime = "home", prefs = prefs))
    }

    @Test
    fun `time parsing roundtrip`() {
        assertEquals(780, SupplementWindows.parseHhMm("13:00"))
        assertEquals(0, SupplementWindows.parseHhMm("00:00"))
        assertEquals(1439, SupplementWindows.parseHhMm("23:59"))
        assertNull(SupplementWindows.parseHhMm("25:00"))
        assertNull(SupplementWindows.parseHhMm("abc"))
        assertEquals("13:00", SupplementWindows.formatHhMm(780))
        assertEquals("00:05", SupplementWindows.formatHhMm(5))
    }

    @Test
    fun `dismissal within 30s is accidental`() {
        assertTrue(SupplementWindows.isAccidentalDismissal(1_000_000L, 1_020_000L))
        assertFalse(SupplementWindows.isAccidentalDismissal(1_000_000L, 1_031_000L))
    }

    @Test
    fun `streak anchors on yesterday and adds today`() {
        val required = setOf("breakfast", "dinner")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast", "dinner"),
            "2026-09-25" to setOf("breakfast", "dinner"),
            "2026-09-24" to setOf("breakfast"),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(2, SupplementStreak.streak(logged, required, today))
    }
}
