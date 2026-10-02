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

    /** Grade clamp in percent for the Pandolf metabolic model. */
    private const val GRADE_MIN_PCT = -6.0
    private const val GRADE_MAX_PCT = 15.0

    /** GAP segment length; slope = end-to-end height delta over the segment. */
    private const val GAP_SEGMENT_M = 100.0
    /** Pandolf ratio sanity band (Moscow-flat; raise to 2.5-3.0 for real mountains). */
    private const val RATIO_MIN = 0.70
    private const val RATIO_MAX = 2.00

    /** Elevation profile resampling step along the track. */
    private const val SAMPLE_STEP_M = 30.0

    /** Altitude smoothing window in track meters (±100 m around each sample). */
    private const val SMOOTH_WINDOW_M = 200.0

    /** A climb counts only when it rises this much above the last valley. */
    private const val ASCENT_MIN_DELTA_M = 5.0

    /** …and spans at least this much of track distance. */
    private const val ASCENT_MIN_RUN_M = 80.0

    /** Pandolf 1977 metabolic-power grade ratio for level walking (L=0, η=1):
     *  C(i)/C(0) = (1.5 + 1.5v² + 0.35·v·G) / (1.5 + 1.5v²), v in m/s,
     *  G in percent. Downhill floored at −6% (linear term would give negative
     *  power below that; Santee et al. 2001). Validated 4.5–5.5 km/h. */
    fun pandolfRatio(vMs: Double, gradePct: Double): Double {
        val g = gradePct.coerceIn(GRADE_MIN_PCT, GRADE_MAX_PCT)
        val base = 1.5 + 1.5 * vMs * vMs
        return (base + 0.35 * vMs * g) / base
    }

    /** GAP: flat-equivalent speed for the same metabolic cost. */
    fun gapKmh(speedKmh: Double, gradePct: Double, speedMs: Double): Double {
        if (gradePct == 0.0) return speedKmh
        val ratio = pandolfRatio(speedMs, gradePct).coerceIn(RATIO_MIN, RATIO_MAX)
        return speedKmh * ratio
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
                            // bilinear — server default is nearest, which makes the DEM staircase
                            val conn = URL("https://api.opentopodata.org/v1/srtm90m?locations=$locs&interpolation=bilinear")
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
        val speeds = gpsSpeeds(sorted)
        val dist = cumulativeDistance(sorted)
        val resampled = resampleByDistance(sorted, altitudes, speeds, dist, SAMPLE_STEP_M)
        val smoothed = distanceSmooth(resampled, SMOOTH_WINDOW_M)
        val rdist = resampled.map { it.dist }

        val hrSorted = hrSamples.sortedBy { it.timestamp }
        val out = ArrayList<ProfilePoint>(resampled.size)
        // Self-calibrating glitch cap: the watch relay speed field is corrupted
        // (~km/h parsed as m/s). Cap honest GPS-derived speeds instead:
        // anything much above the median moving speed is a glitch, not movement.
        val moving = resampled.map { it.speedMs * 3.6 }.filter { it > 1.0 }.sorted()
        val median = if (moving.isEmpty()) 0.0
        else (moving[(moving.size - 1) / 2] + moving[moving.size / 2]) / 2.0
        val speedCap = (median * 1.5).coerceIn(7.0, 15.0)
        // GAP per 100 m segment: slope from end-to-end smoothed heights, speed
        // = segment mean; average is distance-weighted (tails <50 m dropped)
        val segSlope = DoubleArray(resampled.size)
        var gapSum = 0.0
        var gapDist = 0.0
        var i0 = 0
        while (i0 < resampled.size - 1) {
            var i1 = i0
            while (i1 < resampled.size - 1 && rdist[i1 + 1] - rdist[i0] < GAP_SEGMENT_M) i1++
            val segDist = rdist[i1] - rdist[i0]
            if (segDist < GAP_SEGMENT_M / 2) break
            val slope = (smoothed[i1] - smoothed[i0]) / segDist
            for (k in i0..i1) segSlope[k] = slope
            val vMs = resampled.subList(i0, i1 + 1).map { it.speedMs }.average()
            val vKmh = vMs * 3.6
            if (vKmh in 1.0..speedCap) {
                gapSum += gapKmh(vKmh, slope * 100, vMs) * segDist
                gapDist += segDist
            }
            i0 = i1
        }
        for (i in resampled.indices) {
            val slope = segSlope[i]
            val s = resampled[i]
            val speedKmh = s.speedMs * 3.6
            out.add(
                ProfilePoint(
                    timestamp = s.ts,
                    altitudeM = smoothed[i],
                    slope = slope,
                    gapKmh = if (speedKmh > 1.0) gapKmh(speedKmh, slope * 100, s.speedMs) else 0.0,
                    bpm = nearestBpm(s.ts, hrSorted),
                    speedKmh = speedKmh,
                ),
            )
        }
        val ascent = hysteresisAscent(smoothed, rdist)
        return ElevationProfile(
            points = out,
            ascentM = ascent,
            avgGapKmh = if (gapDist > 0) gapSum / gapDist else null,
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

    /** One resampled profile point every SAMPLE_STEP_M of track. */
    private data class Sample(val ts: Long, val alt: Double, val dist: Double, val speedMs: Double)

    /** Heights/speeds every SAMPLE_STEP_M of track; linear interpolation between points. */
    private fun resampleByDistance(
        sorted: List<GpsPointRecord>,
        altitudes: List<Double>,
        speeds: List<Double>,
        dist: List<Double>,
        step: Double,
    ): List<Sample> {
        val out = ArrayList<Sample>()
        var target = 0.0
        var i = 1
        out.add(Sample(sorted[0].timestamp, altitudes[0], 0.0, speeds[0]))
        while (target + step <= dist.last()) {
            target += step
            while (dist[i] < target) i++
            val t0 = dist[i - 1]; val t1 = dist[i]
            val f = if (t1 > t0) (target - t0) / (t1 - t0) else 0.0
            val alt = altitudes[i - 1] + f * (altitudes[i] - altitudes[i - 1])
            val ts = sorted[i - 1].timestamp + (f * (sorted[i].timestamp - sorted[i - 1].timestamp)).toLong()
            val speed = speeds[i - 1] + f * (speeds[i] - speeds[i - 1])
            out.add(Sample(ts, alt, target, speed))
        }
        return out
    }

    /** Moving average over ±window/2 of TRACK DISTANCE (not points). */
    private fun distanceSmooth(resampled: List<Sample>, window: Double): List<Double> {
        val d = resampled.map { it.dist }
        val a = resampled.map { it.alt }
        val half = window / 2
        return a.indices.map { i ->
            var lo = i; var hi = i
            while (lo > 0 && d[i] - d[lo - 1] < half) lo--
            while (hi < d.size - 1 && d[hi + 1] - d[i] < half) hi++
            var sum = 0.0
            for (j in lo..hi) sum += a[j]
            sum / (hi - lo + 1)
        }
    }

    /** Hysteresis ascent: a climb counts when rise from the last valley reaches
     *  ASCENT_MIN_DELTA_M and spans >= ASCENT_MIN_RUN_M of track. Dips smaller
     *  than MIN_DELTA neither break the climb nor double-count it. */
    private fun hysteresisAscent(heights: List<Double>, dist: List<Double>): Double {
        if (heights.size < 2) return 0.0
        var ascent = 0.0
        var valley = heights[0]; var valleyDist = dist[0]
        var peak = heights[0]; var peakDist = 0.0
        var inClimb = false
        for (i in 1 until heights.size) {
            if (heights[i] > peak) { peak = heights[i]; peakDist = dist[i] - valleyDist }
            if (peak - heights[i] >= ASCENT_MIN_DELTA_M) {           // descent confirmed — close climb
                if (inClimb && peak - valley >= ASCENT_MIN_DELTA_M && peakDist >= ASCENT_MIN_RUN_M) {
                    ascent += peak - valley
                }
                valley = heights[i]; valleyDist = dist[i]; peak = heights[i]; inClimb = false
            }
            if (heights[i] < valley) { valley = heights[i]; valleyDist = dist[i]; peak = heights[i]; inClimb = false }
            if (peak - valley >= ASCENT_MIN_DELTA_M) inClimb = true
        }
        if (inClimb && peak - valley >= ASCENT_MIN_DELTA_M && peakDist >= ASCENT_MIN_RUN_M) ascent += peak - valley
        return ascent
    }

    private fun distanceMeters(a: GpsPointRecord, b: GpsPointRecord): Double {
        val dLat = (b.lat - a.lat) * 111_320.0
        val dLon = (b.lon - a.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.hypot(dLat, dLon)
    }

    /** Ground-truth speed from point geometry: haversine/dt, median over ±3
     *  points (median resists GPS outliers). The watch relay speed field is
     *  corrupted (~km/h parsed as m/s, 3.35x high) and is never used. */
    private fun gpsSpeeds(sorted: List<GpsPointRecord>): List<Double> {
        val raw = DoubleArray(sorted.size)
        for (i in 1 until sorted.size) {
            val dt = (sorted[i].timestamp - sorted[i - 1].timestamp) / 1000.0
            raw[i] = if (dt > 0) distanceMeters(sorted[i - 1], sorted[i]) / dt else 0.0
        }
        raw[0] = raw.getOrElse(1) { 0.0 }
        return raw.indices.map { i ->
            val w = (i - 3..i + 3).filter { it in raw.indices }
            w.map { raw[it] }.sorted()[w.size / 2]
        }
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
