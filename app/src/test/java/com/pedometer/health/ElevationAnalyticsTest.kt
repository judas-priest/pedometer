package com.pedometer.health

import com.pedometer.data.GpsPointRecord
import com.pedometer.data.HeartRateRecord
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationAnalyticsTest {

    // ── Pandolf 1977 metabolic grade ratio ────────────────────────────────────

    @Test
    fun `pandolf ratio flat is 1`() {
        assertEquals(1.0, ElevationAnalytics.pandolfRatio(1.472, 0.0), 1e-9)
    }

    @Test
    fun `pandolf ratio at +5 percent matches hand calculation`() {
        // (1.5 + 1.5*1.472^2 + 0.35*1.472*5) / (1.5 + 1.5*1.472^2) = 7.325/4.749
        assertEquals(1.5424, ElevationAnalytics.pandolfRatio(1.472, 5.0), 1e-3)
    }

    @Test
    fun `pandolf downhill floors at -6 percent`() {
        assertEquals(
            ElevationAnalytics.pandolfRatio(1.472, -6.0),
            ElevationAnalytics.pandolfRatio(1.472, -15.0), 1e-9)
        assertTrue(ElevationAnalytics.pandolfRatio(1.472, -6.0) < 1.0)
    }

    @Test
    fun `pandolf clamps grade at +15 percent`() {
        assertEquals(
            ElevationAnalytics.pandolfRatio(1.472, 15.0),
            ElevationAnalytics.pandolfRatio(1.472, 40.0), 1e-9)
    }

    @Test
    fun `gapKmh uphill is faster, downhill slower (Strava convention)`() {
        val v = 5.4; val vms = v / 3.6
        assertTrue(ElevationAnalytics.gapKmh(v, 5.0, vms) > v)   // +5% grade
        assertTrue(ElevationAnalytics.gapKmh(v, -5.0, vms) < v)  // -5% grade
        assertEquals(v, ElevationAnalytics.gapKmh(v, 0.0, vms), 1e-9)
    }

    // ── Profile assembly ──────────────────────────────────────────────────────

    private fun pt(t: Long, lat: Double, lon: Double, speed: Float) =
        GpsPointRecord(workoutStart = 0L, timestamp = t, lat = lat, lon = lon, speed = speed)

    @Test
    fun `buildProfile computes ascent and uphill GAP`() {
        // 5 points heading north, 100 s apart; altitudes climb linearly 0 -> 40 m.
        // Resampled every 30 m of the ~400 m track -> 14 samples; a linear ramp
        // survives 200 m distance smoothing, so hysteresis counts the full rise
        // between the (one-sided-window) first and last smoothed values.
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

        assertEquals(14, profile.points.size)
        assertEquals(30.0, profile.ascentM, 1.5)
        // ~10 m rise per ~100 m run → slope ≈ 0.05 over the 40 m slope window,
        // GAP above the actual 3.6 km/h (1 m/s geometry)
        assertTrue(profile.avgGapKmh!! > 3.6)
        // first segment slope: edge artifact of one-sided smoothing (see hysteresisAscent notes)
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
        // Resampled every 30 m of the ~200 m track -> 7 samples; the last one sits
        // at ~108 s, >60 s from the 5 s HR sample and ~192 s from the 300 s one.
        assertEquals(110, profile.points[0].bpm)
        assertEquals(110, profile.points[1].bpm)
        assertEquals(0, profile.points[6].bpm)
    }

    @Test
    fun `buildProfile returns null on mismatched or tiny input`() {
        val points = listOf(pt(0L, 55.0, 37.0, 1.0f))
        assertNull(ElevationAnalytics.buildProfile(points, listOf(0.0, 1.0), emptyList()))
        assertNull(ElevationAnalytics.buildProfile(points, listOf(0.0), emptyList()))
    }

    @Test
    fun `buildProfile avgGap ignores non-moving points`() {
        // 20 stationary points (zero track distance) then 20 walking points at
        // 1.5 m/s. Resampling collapses the standing part into sample 0; the
        // 30 m samples across the stand→walk transition carry interpolated
        // sub-walking speeds, the rest are flat 5.4 km/h.
        val points = ArrayList<GpsPointRecord>()
        var ts = 0L
        repeat(20) { points.add(pt(ts, 55.0, 37.0, 0.0f)); ts += 30_000L }
        repeat(20) {
            points.add(pt(ts, 55.0 + (it + 1) * 0.0009, 37.0, 1.5f)); ts += 67_000L
        }
        val profile = ElevationAnalytics.buildProfile(points, List(40) { 100.0 }, emptyList())!!
        assertEquals(0.0, profile.points[0].gapKmh, 1e-9)   // standing sample: no GAP
        assertEquals(5.4, profile.points.last().speedKmh, 0.05)
        assertTrue("avgGap=${profile.avgGapKmh}", profile.avgGapKmh!! in 5.2..5.5)
    }

    // ── GAP calibration: slope over a 40 m track window (Strava smooths before grading) ──

    @Test
    fun `flat route gives GAP equal to actual speed`() {
        val pts = (0 until 100).map { i ->
            GpsPointRecord(0L, i * 5000L, 55.70 + i * 0.0000674, 37.60, 1.5f) // geometry: 7.5 m / 5 s = 1.5 m/s; the speed field is ignored
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
            GpsPointRecord(0L, i * 20000L, 55.70 + i * 0.0002515, 37.60, 1.2f) // geometry: 28.0 m / 20 s = 1.40 m/s = 5.04 km/h
        }
        val alts = pts.mapIndexed { i, _ -> 100.0 + i * 2.0 }
        val p = ElevationAnalytics.buildProfile(pts, alts, emptyList())!!
        assertTrue("ascent=${p.ascentM}", p.ascentM in 380.0..400.0)
        assertTrue("gap=${p.avgGapKmh}", p.avgGapKmh!! in 8.5..9.5)
        assertEquals(5.04, p.points[50].speedKmh, 0.05) // 1.40 m/s carried into the profile
    }

    @Test
    fun `speed comes from GPS geometry, not the relay speed field`() {
        // geometry: 7.5 m per 5 s (1.5 m/s); relay field screams 28.8 km/h — ignored
        val pts = (0 until 100).map { i ->
            GpsPointRecord(0L, i * 5000L, 55.70 + i * 0.0000674, 37.60, 8f)
        }
        val p = ElevationAnalytics.buildProfile(pts, List(100) { 150.0 }, emptyList())!!
        assertTrue("avgGap=${p.avgGapKmh}", p.avgGapKmh!! in 5.2..5.6)
        // 742 m track / 30 m step -> 25 samples; index 12 is mid-track
        assertEquals(5.4, p.points[12].speedKmh, 0.1)
    }

    @Test
    fun `gps speed median filter absorbs a positional spike`() {
        // one 50 m jump in 5 s (10 m/s) at i=50; median ±3 must absorb it
        val pts = (0 until 100).map { i ->
            val lat = 55.70 + i * 0.0000674 + if (i > 50) 0.0003862 else 0.0
            GpsPointRecord(0L, i * 5000L, lat, 37.60, 1.5f)
        }
        val p = ElevationAnalytics.buildProfile(pts, List(100) { 150.0 }, emptyList())!!
        assertTrue("avgGap=${p.avgGapKmh}", p.avgGapKmh!! in 5.2..5.6)
        // sample 13 sits at 390 m — inside the spike segment (i=50 is at 375 m)
        assertTrue("speed13=${p.points[13].speedKmh}", p.points[13].speedKmh < 6.0)
    }

    @Test
    fun `sawtooth climb counts once, noise counts zero`() {
        // 100 points every 30 m; noise ±1 m on a plateau, then one 40 m hill over ~510 m
        val pts = ArrayList<GpsPointRecord>()
        val alts = ArrayList<Double>()
        var ts = 0L
        fun add(alt: Double) {
            val i = pts.size
            pts.add(GpsPointRecord(0L, ts, 55.70 + i * 0.00027, 37.60, 1.4f)) // ~30 m apart
            alts.add(alt); ts += 20000
        }
        repeat(20) { add(150.0 + if (it % 2 == 0) 1.0 else 0.0) }   // noisy plateau: 600 m
        repeat(17) { add(150.0 + (it + 1) * 2.35) }                  // +40 m over ~510 m
        repeat(30) { add(190.0 + if (it % 2 == 0) 1.0 else 0.0) }   // noisy top
        val p = ElevationAnalytics.buildProfile(pts, alts, emptyList())!!
        assertEquals(40.0, p.ascentM, 6.0)                            // hill counted once, noise not
    }

    @Test
    fun `GAP cap is median-based - mildly inflated speeds are cut too`() {
        // median moving speed 1.4 m/s (5.04 km/h) -> new cap 7.56 km/h;
        // last 10 points at 13 m per 5 s (9.36 km/h) are a geometry breakaway:
        // cap cuts them; the mixed segment [600,700] averages ~8.3 km/h — also
        // excluded -> avgGap = clean 5.04
        // cumulative lat: 7.0 m steps, last 10 segments 13.0 m (no discontinuity at the seam)
        val pts = ArrayList<GpsPointRecord>()
        var lat = 55.70
        for (i in 0 until 100) {
            pts.add(GpsPointRecord(0L, i * 5000L, lat, 37.60, if (i >= 90) 2.2f else 1.4f))
            lat += if (i >= 89) 0.0001168 else 0.0000629
        }
        val alts = List(100) { 150.0 }
        val p = ElevationAnalytics.buildProfile(pts, alts, emptyList())!!
        assertTrue("avgGap=${p.avgGapKmh}", p.avgGapKmh!! in 4.9..5.2)
    }
}
