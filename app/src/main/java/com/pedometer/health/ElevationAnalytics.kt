package com.pedometer.health

import android.util.Log
import kotlinx.coroutines.delay
import com.pedometer.data.GpsPointRecord
import com.pedometer.data.HeartRateRecord
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * Post-hoc elevation profile + Minetti GAP for GPS workouts (Strava DEM-correction
 * method for watch models without a barometer).
 *
 * Altitudes come from the Open-Meteo Elevation API (SRTM 90 m grid) by lat/lon —
 * `gps_points` has no altitude column. GAP = gradient-adjusted pace: the flat speed
 * that would cost the same energy per second (Minetti 2002 energy-cost polynomial).
 */
object ElevationAnalytics {
    private const val TAG = "ElevationAnalytics"
    private const val BATCH = 100
    private const val METEO_BATCH = 25

    /** Minetti validity range for the cost polynomial (|gradient| <= 45%). */
    private const val MAX_SLOPE = 0.45

    /** Track distance over which the DEM slope is averaged (Strava smooths before grading). */
    private const val SLOPE_WINDOW_M = 40.0

    /** Flat-walking energy cost, J/(kg·m) — C(0) of the Minetti polynomial. */
    const val FLAT_COST = 3.6

    // Minetti 2002: energy cost J/kg/m at slope i (rise/run):
    // 155.4·i⁵ − 30.4·i⁴ − 43.3·i³ + 46.3·i² + 19.5·i + 3.6
    fun minettiCost(slopeRaw: Double): Double {
        val slope = slopeRaw.coerceIn(-MAX_SLOPE, MAX_SLOPE)
        return 155.4 * slope.pow(5) - 30.4 * slope.pow(4) - 43.3 * slope.pow(3) +
            46.3 * slope.pow(2) + 19.5 * slope + FLAT_COST
    }

    /** GAP: flat-equivalent speed — uphill costs more per km, so the SAME effort
     *  maps to a FASTER flat speed. v_gap = v * C(slope)/C(0). (Strava convention:
     *  uphill GAP pace is faster than actual.) */
    fun gapKmh(speedKmh: Double, slope: Double): Double =
        if (slope == 0.0) speedKmh else speedKmh * minettiCost(slope) / FLAT_COST

    /** Moving-average smoothing of noisy DEM/GPS altitude, window ~5 points (~1 min).
     *  Centered window, clipped at the series ends; short series pass through. */
    fun smooth(altitudes: List<Double>, window: Int = 5): List<Double> {
        if (altitudes.size < 2 || window <= 1) return altitudes
        val half = window / 2
        return altitudes.indices.map { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(altitudes.size - 1)
            var sum = 0.0
            for (j in from..to) sum += altitudes[j]
            sum / (to - from + 1)
        }
    }

    /**
     * DEM altitudes for the points. Providers in fallback order:
     * 1) OpenTopoData GET — reachable from RU, no coord quota; dataset is a PATH
     *    segment (/v1/srtm90m), NOT a query param; 1 req/sec.
     * 2) Open-Elevation POST — body MUST be `{"locations":[...]}`; a bare array
     *    (the old format) now gets 400 invalid_locations even for valid points.
     * 3) Open-Meteo GET in sub-batches of 25 (blocked from some RU networks).
     * Returns null on any failure (no internet, bad response) — UI shows «нет данных».
     */
    suspend fun fetchElevations(points: List<GpsPointRecord>): List<Double>? {
        // GPS relay can emit NaN/garbage coords — one invalid pair makes the whole
        // batch 400 (invalid_locations). Sanitize before batching.
        val clean = points.filter {
            it.lat.isFinite() && it.lon.isFinite() && abs(it.lat) <= 90 && abs(it.lon) <= 180
        }.distinctBy { "%.6f|%.6f".format(java.util.Locale.US, it.lat, it.lon) }
        if (clean.isEmpty()) return null
        for (provider in listOf("opentopodata", "open-elevation", "open-meteo")) {
            val altitudes = ArrayList<Double>(clean.size)
            try {
                var failed = false
                val batch = if (provider == "open-meteo") METEO_BATCH else BATCH
                val chunks = clean.chunked(batch)
                for ((index, chunk) in chunks.withIndex()) {
                    val json = when (provider) {
                        "opentopodata" -> {
                            if (index > 0) delay(1100) // 1 req/sec limit
                            val locs = chunk.joinToString("%7C") {
                                String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon)
                            }
                            val conn = URL("https://api.opentopodata.org/v1/srtm90m?locations=$locs")
                                .openConnection() as HttpURLConnection
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 15_000
                            val text = conn.inputStream.bufferedReader().readText()
                            conn.disconnect()
                            JSONObject(text).getJSONArray("results")
                        }
                        "open-elevation" -> {
                            if (index > 0) delay(1500) // per-burst limit
                            val body = chunk.joinToString(",") {
                                String.format(java.util.Locale.US, "{\"latitude\":%.6f,\"longitude\":%.6f}", it.lat, it.lon)
                            }
                            val conn = URL("https://api.open-elevation.com/api/v1/lookup").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 15_000
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            Log.w(TAG, "REQ body head: ${body.take(120)}")
                            conn.outputStream.use { it.write("{\"locations\":[$body]}".toByteArray()) }
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                val err = conn.errorStream?.bufferedReader()?.readText()?.take(200)
                                throw java.io.IOException("HTTP $code: $err")
                            }
                            val text = conn.inputStream.bufferedReader().readText()
                            conn.disconnect()
                            JSONObject(text).getJSONArray("results")
                        }
                        else -> {
                            if (index > 0) delay(300)
                            val lats = chunk.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it.lat) }
                            val lons = chunk.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it.lon) }
                            val conn = URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons")
                                .openConnection() as HttpURLConnection
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 15_000
                            val text = conn.inputStream.bufferedReader().readText()
                            conn.disconnect()
                            JSONObject(text).getJSONArray("elevation")
                        }
                    }
                    if (json.length() != chunk.size) {
                        Log.w(TAG, "Elevation API returned ${json.length()} of ${chunk.size}")
                        failed = true
                        break
                    }
                    for (i in 0 until json.length()) {
                        val el = json.get(i)
                        altitudes.add(
                            if (el is Double) el else el.let { (it as org.json.JSONObject).getDouble("elevation") }
                        )
                    }
                }
                if (!failed && altitudes.size == clean.size) return altitudes
                Log.w(TAG, "Provider $provider failed, falling back")
            } catch (e: Exception) {
                Log.w(TAG, "Provider $provider error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
   }

    // ── Pure profile assembly (network injected for testability) ─────────────────

    data class ProfilePoint(
        val timestamp: Long,
        val altitudeM: Double,   // smoothed
        val slope: Double,
        val gapKmh: Double,      // from the recorded speed
        val bpm: Int,            // 0 = no HR sample within ±60 s
        val speedKmh: Double,    // recorded speed, for the analysis chip
    )

    data class ElevationProfile(
        val points: List<ProfilePoint>,
        val ascentM: Double,      // sum of positive smoothed deltas
        val avgGapKmh: Double?,   // mean GAP over moving points; null if no usable speeds
    )

    /**
     * Builds the per-point profile from raw gps_points + DEM altitudes + HR samples.
     * HR is aligned by nearest sample within ±60 s (watch samples are ~1 min apart).
     */
    fun buildProfile(
        points: List<GpsPointRecord>,
        altitudes: List<Double>,
        hrSamples: List<HeartRateRecord>,
    ): ElevationProfile? {
        if (points.size < 2 || points.size != altitudes.size) return null
        val sorted = points.sortedBy { it.timestamp }
        val smoothed = smooth(altitudes)
        val dist = cumulativeDistance(sorted)

        val hrSorted = hrSamples.sortedBy { it.timestamp }
        val out = ArrayList<ProfilePoint>(sorted.size)
        var ascent = 0.0
        var gapSum = 0.0
        var gapCount = 0
        // Self-calibrating glitch cap: GPS relay emits phantom speeds while walking.
        // Anything much above the median moving speed is a glitch, not movement.
        val moving = sorted.map { it.speed * 3.6 }.filter { it > 1.0 }.sorted()
        val speedCap = if (moving.isEmpty()) 15.0
        else (moving[moving.size / 2] * 1.8).coerceIn(8.0, 15.0)
        for (i in sorted.indices) {
            val slope = windowedSlope(dist, smoothed, i)
            if (i > 0) {
                val rise = smoothed[i] - smoothed[i - 1]
                if (rise > 0) ascent += rise
            }
            val speedKmh = sorted[i].speed * 3.6
            if (speedKmh in 1.0..speedCap) {
                gapSum += gapKmh(speedKmh, slope)
                gapCount++
            }
            out.add(
                ProfilePoint(
                    timestamp = sorted[i].timestamp,
                    altitudeM = smoothed[i],
                    slope = slope,
                    gapKmh = if (speedKmh > 1.0) gapKmh(speedKmh, slope) else 0.0,
                    bpm = nearestBpm(sorted[i].timestamp, hrSorted),
                    speedKmh = speedKmh,
                ),
            )
        }
        return ElevationProfile(
            points = out,
            ascentM = ascent,
            avgGapKmh = if (gapCount == 0) null else gapSum / gapCount,
        )
    }

    /** Cumulative horizontal track distance (m) for slope windows. */
    private fun cumulativeDistance(sorted: List<GpsPointRecord>): List<Double> {
        val d = ArrayList<Double>(sorted.size)
        var acc = 0.0
        d.add(0.0)
        for (i in 1 until sorted.size) {
            acc += distanceMeters(sorted[i - 1], sorted[i])
            d.add(acc)
        }
        return d
    }

    private fun distanceMeters(a: GpsPointRecord, b: GpsPointRecord): Double {
        val dLat = (b.lat - a.lat) * 111_320.0
        val dLon = (b.lon - a.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.hypot(dLat, dLon)
    }

    /** Slope over ±SLOPE_WINDOW_M of track: rise/run across the window. Sparse
     *  tracks (points farther apart than the window) still get one neighbour
     *  per side so the slope is never silently 0. */
    private fun windowedSlope(dist: List<Double>, smoothed: List<Double>, i: Int): Double {
        var lo = i
        var hi = i
        if (lo > 0) lo--
        if (hi < dist.size - 1) hi++
        while (lo > 0 && dist[i] - dist[lo - 1] < SLOPE_WINDOW_M) lo--
        while (hi < dist.size - 1 && dist[hi + 1] - dist[i] < SLOPE_WINDOW_M) hi++
        val run = dist[hi] - dist[lo]
        if (run < 1.0) return 0.0
        val rise = smoothed[hi] - smoothed[lo]
        return (rise / run).coerceIn(-MAX_SLOPE, MAX_SLOPE)
    }

    private fun nearestBpm(ts: Long, hrSorted: List<HeartRateRecord>): Int {
        if (hrSorted.isEmpty()) return 0
        var best: HeartRateRecord? = null
        var bestDiff = Long.MAX_VALUE
        for (r in hrSorted) {
            val d = abs(r.timestamp - ts)
            if (d < bestDiff) {
                bestDiff = d
                best = r
                if (d == 0L) break
            }
        }
        return if (bestDiff <= 60_000L) best!!.bpm else 0
    }
}
