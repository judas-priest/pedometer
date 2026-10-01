package com.pedometer.health

import com.pedometer.data.GpsPointRecord
import com.pedometer.data.HeartRateRecord
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationAnalyticsTest {

    // ── Minetti 2002 energy cost ──────────────────────────────────────────────

    @Test
    fun `minettiCost at flat is 3_6 J per kg per m`() {
        assertEquals(3.6, ElevationAnalytics.minettiCost(0.0), 1e-9)
    }

    @Test
    fun `minettiCost at +10 percent matches the polynomial`() {
        // 155.4·1e-5 − 30.4·1e-4 − 43.3·1e-3 + 46.3·1e-2 + 19.5·0.1 + 3.6 = 5.9682
        // (Minetti chart: ~1.7x flat cost at +10%)
        assertEquals(5.97, ElevationAnalytics.minettiCost(0.1), 0.05)
    }

    @Test
    fun `minettiCost downhill is cheaper than flat`() {
        assertTrue(ElevationAnalytics.minettiCost(-0.1) < 3.6)
        assertTrue(ElevationAnalytics.minettiCost(-0.2) < ElevationAnalytics.minettiCost(-0.1))
    }

    @Test
    fun `minettiCost clamps slope beyond validity range`() {
        assertEquals(ElevationAnalytics.minettiCost(0.45), ElevationAnalytics.minettiCost(2.0), 1e-9)
        assertEquals(ElevationAnalytics.minettiCost(-0.45), ElevationAnalytics.minettiCost(-2.0), 1e-9)
    }

    // ── GAP direction (Strava convention: uphill GAP faster, downhill slower) ──

    @Test
    fun `gapKmh at zero slope equals actual speed`() {
        assertEquals(10.0, ElevationAnalytics.gapKmh(10.0, 0.0), 1e-9)
    }

    @Test
    fun `gapKmh uphill is faster than actual`() {
        val gap = ElevationAnalytics.gapKmh(6.0, 0.1)
        assertEquals(6.0 * ElevationAnalytics.minettiCost(0.1) / 3.6, gap, 1e-9)
        assertTrue(gap > 6.0)
    }

    @Test
    fun `gapKmh steep downhill is slower than actual`() {
        assertTrue(ElevationAnalytics.gapKmh(12.0, -0.2) < 12.0)
    }

    // ── Smoothing ─────────────────────────────────────────────────────────────

    @Test
    fun `smooth leaves constant series unchanged`() {
        val series = List(20) { 150.0 }
        assertEquals(series, ElevationAnalytics.smooth(series))
    }

    @Test
    fun `smooth centered window averages neighbors`() {
        val smoothed = ElevationAnalytics.smooth(listOf(1.0, 2.0, 3.0, 4.0, 5.0), window = 5)
        // idx2 averages the whole window: 3.0
        assertEquals(3.0, smoothed[2], 1e-9)
        // idx0 has only indices 0..2 available: (1+2+3)/3
        assertEquals(2.0, smoothed[0], 1e-9)
        // idx4 has only indices 2..4: (3+4+5)/3
        assertEquals(4.0, smoothed[4], 1e-9)
    }

    @Test
    fun `smooth passes through tiny lists`() {
        assertEquals(listOf(7.0), ElevationAnalytics.smooth(listOf(7.0)))
        assertTrue(ElevationAnalytics.smooth(emptyList()).isEmpty())
    }

    // ── Profile assembly ──────────────────────────────────────────────────────

    private fun pt(t: Long, lat: Double, lon: Double, speed: Float) =
        GpsPointRecord(workoutStart = 0L, timestamp = t, lat = lat, lon = lon, speed = speed)

    @Test
    fun `buildProfile computes ascent and uphill GAP`() {
        // 5 points heading north, 100 s apart; altitudes climb linearly 0 -> 40 m.
        // Smoothing (window 5) turns that into 10..30 — ascent 20 m.
        val points = listOf(
            pt(0L, 55.0, 37.0, 1.5f),          // 5.4 km/h
            pt(100_000L, 55.0009, 37.0, 1.5f), // ~100 m north
            pt(200_000L, 55.0018, 37.0, 1.5f),
            pt(300_000L, 55.0027, 37.0, 1.5f),
            pt(400_000L, 55.0036, 37.0, 1.5f),
        )
        val profile = ElevationAnalytics.buildProfile(
            points, listOf(0.0, 10.0, 20.0, 30.0, 40.0),
            hrSamples = emptyList(),
        )!!

        assertEquals(5, profile.points.size)
        assertEquals(20.0, profile.ascentM, 0.5)
        // ~5 m rise per ~100 m run → slope ≈ 0.05, GAP above the actual 5.4 km/h
        assertTrue(profile.avgGapKmh!! > 5.4)
        assertEquals(0.05, profile.points[1].slope, 0.01)
    }

    @Test
    fun `buildProfile aligns hr within 60 seconds only`() {
        val points = listOf(
            pt(0L, 55.0, 37.0, 1.5f),
            pt(60_000L, 55.0009, 37.0, 1.5f),
            pt(120_000L, 55.0018, 37.0, 1.5f),
        )
        val hr = listOf(
            HeartRateRecord(timestamp = 5_000L, bpm = 110),
            HeartRateRecord(timestamp = 300_000L, bpm = 150), // too far from all points
        )
        val profile = ElevationAnalytics.buildProfile(points, List(3) { 0.0 }, hr)!!
        assertEquals(110, profile.points[0].bpm)
        assertEquals(110, profile.points[1].bpm)
        assertEquals(0, profile.points[2].bpm)
    }

    @Test
    fun `buildProfile returns null on mismatched or tiny input`() {
        val points = listOf(pt(0L, 55.0, 37.0, 1.0f))
        assertNull(ElevationAnalytics.buildProfile(points, listOf(0.0, 1.0), emptyList()))
        assertNull(ElevationAnalytics.buildProfile(points, listOf(0.0), emptyList()))
    }

    @Test
    fun `buildProfile avgGap ignores non-moving points`() {
        val points = listOf(
            pt(0L, 55.0, 37.0, 0.0f),          // standing
            pt(60_000L, 55.0009, 37.0, 0.2f),  // still standing
            pt(120_000L, 55.0018, 37.0, 1.5f),
        )
        val profile = ElevationAnalytics.buildProfile(points, List(3) { 100.0 }, emptyList())!!
        assertEquals(5.4, profile.avgGapKmh!!, 0.1) // flat 1.5 m/s
        assertEquals(0.0, profile.points[0].gapKmh, 1e-9)
        assertEquals(0.0, profile.points[1].gapKmh, 1e-9)
    }

    // ── GAP calibration: slope over a 40 m track window (Strava smooths before grading) ──

    @Test
    fun `flat route gives GAP equal to actual speed`() {
        val pts = (0 until 100).map { i ->
            GpsPointRecord(0L, i * 5000L, 55.70 + i * 0.0001, 37.60, 1.5f) // 1.5 m/s = 5.4 km/h, ~7.8 m apart
        }
        val alts = List(100) { 150.0 }
        val p = ElevationAnalytics.buildProfile(pts, alts, emptyList())!!
        assertTrue("avgGap=${p.avgGapKmh}", p.avgGapKmh!! in 5.2..5.6)
        assertTrue("ascent=${p.ascentM}", p.ascentM < 0.5)
    }

    @Test
    fun `steady climb gives GAP faster than actual and correct ascent`() {
        // ~8% grade: +2 m altitude every ~27.8 m of track
        val pts = (0 until 200).map { i ->
            GpsPointRecord(0L, i * 20000L, 55.70 + i * 0.00025, 37.60, 1.2f) // 4.32 km/h actual
        }
        val alts = pts.mapIndexed { i, _ -> 100.0 + i * 2.0 }
        val p = ElevationAnalytics.buildProfile(pts, alts, emptyList())!!
        assertTrue("ascent=${p.ascentM}", p.ascentM in 380.0..400.0)
        assertTrue("gap=${p.avgGapKmh}", p.avgGapKmh!! > 4.6)
    }
}
