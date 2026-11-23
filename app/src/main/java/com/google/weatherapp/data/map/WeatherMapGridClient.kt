package com.google.weatherapp.data.map

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.weatherapp.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round

sealed interface WeatherGridFetchResult {
    data class Success(val composite: WeatherGridComposite, val fromCache: Boolean) : WeatherGridFetchResult {
        val grid: WeatherGridField get() = composite.baseGrid
    }
    data class RateLimited(val message: String) : WeatherGridFetchResult
    data class Failure(val message: String) : WeatherGridFetchResult
}

/**
 * Fetches a world-locked canonical 10x8 (80-point) 50-hour (-2h past + 48h forecast) real
 * meteorological grid from Open-Meteo in one gzipped request (~47 KB, ~1.0s) and maintains
 * a 15-minute spatial cache.
 *
 * Zoom stability guarantees:
 * - Coordinates are snapped to canonical power-of-2 world lattices (2°, 4°, 8°, 16°), never
 *   floating arbitrarily on pinch-zoom centers.
 * - Zooming IN inside a covered regional grid NEVER triggers a refetch or shifts values.
 * - Zooming OUT builds a [WeatherGridComposite] that preserves cached finer grids seamlessly
 *   inside the wider grid so already-viewed regions never flip wind direction or shift temps.
 * - Never fabricates fallback weather data on offline or HTTP 429 states.
 */
class WeatherMapGridClient(
    private val httpClient: OkHttpClient = ApiClient.okHttpClient,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val cacheLock = Any()
    private val cachedGrids = ArrayDeque<WeatherGridField>()

    fun findCachedGrid(viewport: MapViewportBounds): WeatherGridField? =
        findCachedComposite(viewport)?.baseGrid

    fun findCachedComposite(viewport: MapViewportBounds): WeatherGridComposite? {
        val now = clockMs()
        val targetBounds = computePaddedFetchBounds(viewport)
        val targetStep = (targetBounds.north - targetBounds.south) / (GRID_ROWS - 1).coerceAtLeast(1)
        val isMaxTier = targetStep >= MAX_CANONICAL_STEP_DEG - 0.1

        synchronized(cacheLock) {
            pruneExpiredLocked(now)
            // Prefer the FINEST (and freshest on tie) cached grid covering the viewport within its canonical domain
            val bestBase = cachedGrids
                .filter { entry ->
                    val stepOk = entry.latStepDeg <= targetStep + 0.05
                    if (!stepOk) return@filter false
                    val clampedViewport = clampViewportToCanonicalDomain(viewport, entry.latStepDeg)
                    entry.toBounds().coversViewport(clampedViewport) ||
                        (isMaxTier && boundsMatchApproximately(entry.toBounds(), targetBounds))
                }
                .minWithOrNull(
                    compareBy<WeatherGridField> { it.latStepDeg }
                        .thenByDescending { it.fetchEpochMs }
                )
                ?: return null

            return buildCompositeLocked(bestBase)
        }
    }

    /**
     * Used during tests or cache-priming to register an already-parsed real grid.
     */
    internal fun putCachedGrid(grid: WeatherGridField) {
        synchronized(cacheLock) {
            upsertGridLocked(grid)
        }
    }

    suspend fun fetchGridForViewport(
        viewport: MapViewportBounds,
        forceRefresh: Boolean = false
    ): WeatherGridFetchResult = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            findCachedComposite(viewport)?.let { cached ->
                return@withContext WeatherGridFetchResult.Success(cached, fromCache = true)
            }
        }

        val canonicalBounds = computePaddedFetchBounds(viewport)
        val url = buildOpenMeteoGridUrl(
            bounds = canonicalBounds,
            rows = GRID_ROWS,
            cols = GRID_COLS,
            forecastHours = FORECAST_HOURS,
            pastHours = PAST_HOURS
        )
        val request = Request.Builder().url(url).build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return@withContext WeatherGridFetchResult.RateLimited(
                        "Map weather limit reached — please wait a moment and retry."
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext WeatherGridFetchResult.Failure(
                        "Live weather data unavailable (HTTP ${response.code})."
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext WeatherGridFetchResult.Failure("Empty weather grid response.")
                val parsed = parseMultiPointResponse(
                    rawJson = body,
                    bounds = canonicalBounds,
                    rows = GRID_ROWS,
                    cols = GRID_COLS,
                    fetchEpochMs = clockMs(),
                    pastHours = PAST_HOURS
                ) ?: return@withContext WeatherGridFetchResult.Failure(
                    "Unable to read live meteorological grid."
                )

                val composite = synchronized(cacheLock) {
                    pruneExpiredLocked(clockMs())
                    upsertGridLocked(parsed)
                    buildCompositeLocked(parsed)
                }
                WeatherGridFetchResult.Success(composite, fromCache = false)
            }
        } catch (_: Exception) {
            WeatherGridFetchResult.Failure(
                "Unable to reach live weather servers. Check your connection."
            )
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val iterator = cachedGrids.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.fetchEpochMs > CACHE_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun upsertGridLocked(grid: WeatherGridField) {
        val target = grid.toBounds()
        val iterator = cachedGrids.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next()
            if (boundsMatchApproximately(existing.toBounds(), target) &&
                existing.rows == grid.rows &&
                existing.cols == grid.cols
            ) {
                iterator.remove()
            }
        }
        cachedGrids.addLast(grid)
        while (cachedGrids.size > MAX_CACHED_REGIONS) {
            cachedGrids.removeFirst()
        }
    }

    private fun buildCompositeLocked(base: WeatherGridField): WeatherGridComposite {
        // Attach strictly finer cached grids overlapping base, ordered coarsest-to-finest
        val details = cachedGrids
            .filter { candidate ->
                candidate !== base &&
                    candidate.latStepDeg < base.latStepDeg - 0.1 &&
                    gridsOverlap(base, candidate)
            }
            .sortedByDescending { it.latStepDeg }
        return WeatherGridComposite(baseGrid = base, detailGrids = details)
    }

    companion object {
        // Shared across map sessions so reopening Weather Map within 15 minutes renders in 0ms
        val sharedInstance: WeatherMapGridClient by lazy { WeatherMapGridClient() }

        // 10x8 (80 pts) matches portrait mobile viewports on a world-locked canonical lattice (~1.0s)
        const val GRID_ROWS = 10
        const val GRID_COLS = 8
        const val PAST_HOURS = 2
        const val FORECAST_HOURS = 48
        const val MIN_CANONICAL_STEP_DEG = 2.0
        const val MAX_CANONICAL_STEP_DEG = 16.0
        internal val CANONICAL_STEPS_DEG = doubleArrayOf(2.0, 4.0, 8.0, 16.0)

        private const val MAX_CANONICAL_LAT = 80.0
        private const val MAX_CANONICAL_LON = 180.0
        private const val CACHE_TTL_MS = 15L * 60L * 1000L
        private const val MAX_CACHED_REGIONS = 16
        private const val OPEN_METEO_FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        private const val HOURLY_VARIABLES =
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,precipitation,cloud_cover,pressure_msl,cape,weather_code"

        /**
         * Normalizes raw MapLibre visible bounds (including antimeridian-crossing Pacific viewports
         * where [west] >= [east]) into a valid non-wrapping geographic box around [centerLon].
         */
        fun normalizeVisibleViewport(
            south: Double,
            west: Double,
            north: Double,
            east: Double,
            centerLon: Double,
            zoom: Double
        ): MapViewportBounds? {
            if (!south.isFinite() || !west.isFinite() || !north.isFinite() || !east.isFinite()) {
                return null
            }
            val normSouth = south.coerceIn(-82.0, 82.0)
            val normNorth = north.coerceIn(-82.0, 82.0)
            if (normNorth <= normSouth) return null

            val normWest: Double
            val normEast: Double
            if (west < east) {
                normWest = west.coerceIn(-180.0, 180.0)
                normEast = east.coerceIn(-180.0, 180.0)
            } else {
                // Antimeridian crossing (e.g. west=172°, east=-174°): keep the camera's hemisphere
                val wrappedCenter = (((centerLon + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
                if (wrappedCenter >= 0.0) {
                    normWest = west.coerceIn(-180.0, 179.5)
                    normEast = 180.0
                } else {
                    normWest = -180.0
                    normEast = east.coerceIn(-179.5, 180.0)
                }
            }
            if (normEast <= normWest) return null
            return MapViewportBounds(
                south = normSouth,
                west = normWest,
                north = normNorth,
                east = normEast,
                zoom = zoom
            )
        }

        /**
         * Estimates the initial regional camera viewport around (latitude, longitude) so weather grid
         * fetching starts at millisecond 0 in parallel with MapLibre GPU initialization and lands
         * on the exact same canonical snapped grid box once MapLibre settles.
         */
        fun estimateInitialViewport(
            latitude: Double,
            longitude: Double,
            zoom: Double = 6.2
        ): MapViewportBounds {
            // MapLibre GL world width = 512 * 2^zoom dp; typical portrait phone ~390x820 dp
            val worldDp = 512.0 * Math.pow(2.0, zoom.coerceIn(2.0, 13.5))
            val cosLat = cos(Math.toRadians(latitude.coerceIn(-75.0, 75.0))).coerceAtLeast(0.25)
            val halfLon = ((390.0 / worldDp) * 180.0).coerceIn(0.4, 70.0)
            val halfLat = ((820.0 / worldDp) * 180.0 * cosLat).coerceIn(0.4, 55.0)
            val south = (latitude - halfLat).coerceIn(-80.0, 79.0)
            val north = (latitude + halfLat).coerceIn(south + 0.5, 80.0)
            val west = (longitude - halfLon).coerceIn(-180.0, 179.0)
            val east = (longitude + halfLon).coerceIn(west + 0.5, 180.0)
            return MapViewportBounds(south = south, west = west, north = north, east = east, zoom = zoom)
        }

        /**
         * Snaps [viewport] to the finest canonical power-of-2 world lattice tier whose 10x8 box
         * comfortably covers the viewport within the canonical meteorological domain. All grid
         * sample points lie on exact multiples of step, preventing viewport-center drift and
         * keeping data geographically locked across zooms (including near ±180° / ±80°).
         */
        internal fun computePaddedFetchBounds(viewport: MapViewportBounds): MapViewportBounds {
            for (step in CANONICAL_STEPS_DEG) {
                val gridLatSpan = (GRID_ROWS - 1) * step
                val gridLonSpan = (GRID_COLS - 1) * step
                val isMaxTier = step >= MAX_CANONICAL_STEP_DEG - 0.01
                val clampedViewport = clampViewportToCanonicalDomain(viewport, step)
                if (!isMaxTier &&
                    (gridLatSpan < clampedViewport.latSpan * 1.12 || gridLonSpan < clampedViewport.lonSpan * 1.12)
                ) {
                    continue
                }
                val candidate = snapBoundsToCanonicalStep(clampedViewport, step, gridLatSpan, gridLonSpan)
                if (isMaxTier || candidate.coversViewport(clampedViewport)) {
                    return candidate
                }
            }
            val maxStep = MAX_CANONICAL_STEP_DEG
            val clampedMax = clampViewportToCanonicalDomain(viewport, maxStep)
            return snapBoundsToCanonicalStep(
                viewport = clampedMax,
                step = maxStep,
                gridLatSpan = (GRID_ROWS - 1) * maxStep,
                gridLonSpan = (GRID_COLS - 1) * maxStep
            )
        }

        internal fun clampViewportToCanonicalDomain(
            viewport: MapViewportBounds,
            step: Double
        ): MapViewportBounds {
            val safeStep = step.coerceAtLeast(MIN_CANONICAL_STEP_DEG)
            val maxLat = floor(MAX_CANONICAL_LAT / safeStep) * safeStep
            val maxLon = floor(MAX_CANONICAL_LON / safeStep) * safeStep
            val south = viewport.south.coerceIn(-maxLat, maxLat)
            val north = viewport.north.coerceIn(south, maxLat)
            val west = viewport.west.coerceIn(-maxLon, maxLon)
            val east = viewport.east.coerceIn(west, maxLon)
            return MapViewportBounds(
                south = south,
                west = west,
                north = north,
                east = east,
                zoom = viewport.zoom
            )
        }

        private fun snapBoundsToCanonicalStep(
            viewport: MapViewportBounds,
            step: Double,
            gridLatSpan: Double,
            gridLonSpan: Double
        ): MapViewportBounds {
            val maxLat = floor(MAX_CANONICAL_LAT / step) * step
            val minSouth = -maxLat
            val maxSouth = maxLat - gridLatSpan
            var south = round((viewport.centerLat - gridLatSpan * 0.5) / step) * step
            if (viewport.south < south && south - step >= minSouth && (south - step + gridLatSpan) >= viewport.north) {
                south -= step
            } else if (viewport.north > south + gridLatSpan && south + step <= maxSouth && (south + step) <= viewport.south) {
                south += step
            }
            south = south.coerceIn(minSouth, maxSouth)
            val north = south + gridLatSpan

            val maxLon = floor(MAX_CANONICAL_LON / step) * step
            val minWest = -maxLon
            val maxWest = maxLon - gridLonSpan
            var west = round((viewport.centerLon - gridLonSpan * 0.5) / step) * step
            if (viewport.west < west && west - step >= minWest && (west - step + gridLonSpan) >= viewport.east) {
                west -= step
            } else if (viewport.east > west + gridLonSpan && west + step <= maxWest && (west + step) <= viewport.west) {
                west += step
            }
            west = west.coerceIn(minWest, maxWest)
            val east = west + gridLonSpan

            return MapViewportBounds(
                south = south,
                west = west,
                north = north,
                east = east,
                zoom = viewport.zoom
            )
        }

        private fun boundsMatchApproximately(a: MapViewportBounds, b: MapViewportBounds): Boolean =
            abs(a.south - b.south) < 0.05 &&
                abs(a.north - b.north) < 0.05 &&
                abs(a.west - b.west) < 0.05 &&
                abs(a.east - b.east) < 0.05

        private fun gridsOverlap(a: WeatherGridField, b: WeatherGridField): Boolean {
            val latOverlap = minOf(a.northLat, b.northLat) - maxOf(a.southLat, b.southLat)
            val lonOverlap = minOf(a.eastLon, b.eastLon) - maxOf(a.westLon, b.westLon)
            return latOverlap > 0.1 && lonOverlap > 0.1
        }

        internal fun buildOpenMeteoGridUrl(
            bounds: MapViewportBounds,
            rows: Int = GRID_ROWS,
            cols: Int = GRID_COLS,
            forecastHours: Int = FORECAST_HOURS,
            pastHours: Int = PAST_HOURS
        ): String {
            val lats = StringBuilder(rows * cols * 7)
            val lons = StringBuilder(rows * cols * 8)
            val latStep = (bounds.north - bounds.south) / (rows - 1).coerceAtLeast(1)
            val lonStep = (bounds.east - bounds.west) / (cols - 1).coerceAtLeast(1)
            var first = true
            for (r in 0 until rows) {
                val lat = bounds.south + r * latStep
                for (c in 0 until cols) {
                    val lon = bounds.west + c * lonStep
                    if (!first) {
                        lats.append(',')
                        lons.append(',')
                    }
                    first = false
                    lats.append(String.format(Locale.US, "%.3f", lat))
                    lons.append(String.format(Locale.US, "%.3f", lon))
                }
            }
            val safePastHours = pastHours.coerceAtLeast(0)
            val pastParam = if (safePastHours > 0) "&past_hours=$safePastHours" else ""
            return "$OPEN_METEO_FORECAST_ENDPOINT?latitude=$lats&longitude=$lons" +
                "&hourly=$HOURLY_VARIABLES$pastParam&forecast_hours=$forecastHours&timezone=UTC"
        }

        internal fun parseMultiPointResponse(
            rawJson: String,
            bounds: MapViewportBounds,
            rows: Int = GRID_ROWS,
            cols: Int = GRID_COLS,
            fetchEpochMs: Long = System.currentTimeMillis(),
            pastHours: Int = 0
        ): WeatherGridField? {
            return try {
                val parsed = JsonParser.parseString(rawJson)
                val pointArray: JsonArray = when {
                    parsed.isJsonArray -> parsed.asJsonArray
                    parsed.isJsonObject -> JsonArray().apply { add(parsed.asJsonObject) }
                    else -> return null
                }
                val expectedPoints = rows * cols
                if (pointArray.size() != expectedPoints) return null

                val firstHourly = pointArray[0].asJsonObject.getAsJsonObject("hourly") ?: return null
                val timeArr = firstHourly.getAsJsonArray("time") ?: return null
                val hours = timeArr.size()
                if (hours <= 0) return null

                val hourlyTimes = List(hours) { i -> timeArr[i].asString }
                val totalSlots = hours * expectedPoints

                val windSpeed = FloatArray(totalSlots)
                val windDir = FloatArray(totalSlots)
                val windU = FloatArray(totalSlots)
                val windV = FloatArray(totalSlots)
                val windGusts = FloatArray(totalSlots)
                val temp = FloatArray(totalSlots)
                val precip = FloatArray(totalSlots)
                val clouds = FloatArray(totalSlots)
                val pressure = FloatArray(totalSlots)
                val cape = FloatArray(totalSlots)
                val wmo = IntArray(totalSlots)

                for (pIdx in 0 until expectedPoints) {
                    val hourly = pointArray[pIdx].asJsonObject.getAsJsonObject("hourly") ?: return null
                    val wsArr = hourly.getAsJsonArray("wind_speed_10m") ?: return null
                    val wdArr = hourly.getAsJsonArray("wind_direction_10m") ?: return null
                    val wgArr = hourly.getAsJsonArray("wind_gusts_10m")
                    val tArr = hourly.getAsJsonArray("temperature_2m") ?: return null
                    val prArr = hourly.getAsJsonArray("precipitation")
                    val clArr = hourly.getAsJsonArray("cloud_cover")
                    val pArr = hourly.getAsJsonArray("pressure_msl")
                    val capeArr = hourly.getAsJsonArray("cape")
                    val wmoArr = hourly.getAsJsonArray("weather_code")

                    for (h in 0 until hours) {
                        val slot = h * expectedPoints + pIdx
                        val ws = readFloat(wsArr, h, 0f).coerceAtLeast(0f)
                        val wd = readFloat(wdArr, h, 0f)
                        val (u, v) = WeatherFieldMath.windFromToDownwindUV(ws, wd)
                        windSpeed[slot] = ws
                        windDir[slot] = wd
                        windU[slot] = u
                        windV[slot] = v
                        windGusts[slot] = readFloat(wgArr, h, ws).coerceAtLeast(ws)
                        temp[slot] = readFloat(tArr, h, 0f)
                        precip[slot] = readFloat(prArr, h, 0f).coerceAtLeast(0f)
                        clouds[slot] = readFloat(clArr, h, 0f).coerceIn(0f, 100f)
                        pressure[slot] = readFloat(pArr, h, 1013.25f)
                        cape[slot] = readFloat(capeArr, h, 0f).coerceAtLeast(0f)
                        wmo[slot] = readInt(wmoArr, h, 0)
                    }
                }

                WeatherGridField(
                    rows = rows,
                    cols = cols,
                    southLat = bounds.south,
                    northLat = bounds.north,
                    westLon = bounds.west,
                    eastLon = bounds.east,
                    zoomLevel = bounds.zoom,
                    fetchEpochMs = fetchEpochMs,
                    hourlyTimesUtc = hourlyTimes,
                    windSpeedKmH = windSpeed,
                    windDirectionDeg = windDir,
                    windU = windU,
                    windV = windV,
                    windGustsKmH = windGusts,
                    temperatureC = temp,
                    precipitationMm = precip,
                    cloudCoverPct = clouds,
                    pressureHpa = pressure,
                    capeJkg = cape,
                    weatherCode = wmo,
                    pastHours = pastHours.coerceIn(0, (hours - 1).coerceAtLeast(0))
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun readFloat(arr: JsonArray?, index: Int, default: Float): Float {
            if (arr == null || index >= arr.size()) return default
            val el = arr[index]
            return if (el == null || el.isJsonNull) default else el.asFloat
        }

        private fun readInt(arr: JsonArray?, index: Int, default: Int): Int {
            if (arr == null || index >= arr.size()) return default
            val el = arr[index]
            return if (el == null || el.isJsonNull) default else el.asInt
        }
    }
}
