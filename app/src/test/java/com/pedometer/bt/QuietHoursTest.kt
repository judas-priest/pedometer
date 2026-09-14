package com.pedometer.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    @Test
    fun `disabled window is never quiet`() {
        val qh = QuietHours(enabled = false, startHour = 0, endHour = 7)
        assertFalse(qh.isQuiet(3))
    }

    @Test
    fun `simple window inside one day`() {
        val qh = QuietHours(enabled = true, startHour = 13, endHour = 15)
        assertTrue(qh.isQuiet(13))
        assertTrue(qh.isQuiet(14))
        assertFalse(qh.isQuiet(15))
        assertFalse(qh.isQuiet(12))
    }

    @Test
    fun `default window 0 to 7 covers early morning`() {
        val qh = QuietHours.DEFAULT
        assertTrue(qh.isQuiet(0))
        assertTrue(qh.isQuiet(6))
        assertFalse(qh.isQuiet(7))
    }

    @Test
    fun `evening to morning window wraps midnight`() {
        val qh = QuietHours(enabled = true, startHour = 22, endHour = 8)
        assertTrue(qh.isQuiet(23))
        assertTrue(qh.isQuiet(3))
        assertTrue(qh.isQuiet(7))
        assertFalse(qh.isQuiet(8))
        assertFalse(qh.isQuiet(21))
    }

    @Test
    fun `start equals end means never quiet`() {
        val qh = QuietHours(enabled = true, startHour = 5, endHour = 5)
        assertFalse(qh.isQuiet(5))
        assertFalse(qh.isQuiet(6))
    }
}
