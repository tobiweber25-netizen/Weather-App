package com.google.weatherapp.data.map

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.weatherapp.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

sealed interface WeatherGridFetchResult {
    data class Success(val grid: WeatherGridField, val fromCache: Boolean) : WeatherGridFetchResult
    data class RateLimited(val message: String) : WeatherGridFetchResult
    data class Failure(val message: String) : WeatherGridFetchResult
}

/**
 * Fetches a padded 7x7 (49-point) 48-hour real meteorological grid from Open-Meteo in one
 * gzipped request (~30 KB, ~0.8s) and maintains a 15-minute spatial viewport cache.
 * Never fabricates fallback weather data on offline or HTTP 429 states.
 */
class WeatherMapGridClient(
    private val httpClient: OkHttpClient = ApiClient.okHttpClient,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val cacheLock = Any()
    private val cachedGrids = ArrayDeque<WeatherGridField>()

    fun findCachedGrid(viewport: MapViewportBounds): WeatherGridField? {
        val now = clockMs()
        synchronized(cacheLock) {
            val iterator = cachedGrids.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.fetchEpochMs > CACHE_TTL_MS) {
                    iterator.remove()
                }
            }
            return cachedGrids.lastOrNull { it.toBounds().coversViewport(viewport) }
        }
    }

    suspend fun fetchGridForViewport(
        viewport: MapViewportBounds,
        forceRefresh: Boolean = false
    ): WeatherGridFetchResult = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            findCachedGrid(viewport)?.let { cached ->
                return@withContext WeatherGridFetchResult.Success(cached, fromCache = true)
            }
        }

        val padded = computePaddedFetchBounds(viewport)
        val url = buildOpenMeteoGridUrl(padded, GRID_ROWS, GRID_COLS, FORECAST_HOURS)
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
                    bounds = padded,
                    rows = GRID_ROWS,
                    cols = GRID_COLS,
                    fetchEpochMs = clockMs()
                ) ?: return@withContext WeatherGridFetchResult.Failure(
                    "Unable to read live meteorological grid."
                )

                synchronized(cacheLock) {
                    cachedGrids.addLast(parsed)
                    while (cachedGrids.size > MAX_CACHED_REGIONS) {
                        cachedGrids.removeFirst()
                    }
                }
                WeatherGridFetchResult.Success(parsed, fromCache = false)
            }
        } catch (e: Exception) {
            WeatherGridFetchResult.Failure(
                "Unable to reach live weather servers. Check your connection."
            )
        }
    }

    companion object {
        const val GRID_ROWS = 7
        const val GRID_COLS = 7
        const val FORECAST_HOURS = 48
        private const val CACHE_TTL_MS = 15L * 60L * 1000L
        private const val MAX_CACHED_REGIONS = 6
        private const val OPEN_METEO_FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        private const val HOURLY_VARIABLES =
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,precipitation,cloud_cover,pressure_msl,cape,weather_code"

        internal fun computePaddedFetchBounds(viewport: MapViewportBounds): MapViewportBounds {
            // Pad by 25% on each side so gentle pans reuse the cached grid seamlessly
            val targetLatSpan = (viewport.latSpan * 1.5).coerceIn(1.2, 42.0)
            val targetLonSpan = (viewport.lonSpan * 1.5).coerceIn(1.2, 54.0)
            val halfLat = targetLatSpan * 0.5
            val halfLon = targetLonSpan * 0.5
            val south = (viewport.centerLat - halfLat).coerceIn(-82.0, 80.0)
            val north = (viewport.centerLat + halfLat).coerceIn(south + 0.8, 82.0)
            val west = (viewport.centerLon - halfLon).coerceIn(-179.5, 178.5)
            val east = (viewport.centerLon + halfLon).coerceIn(west + 0.8, 179.5)
            return MapViewportBounds(
                south = south,
                west = west,
                north = north,
                east = east,
                zoom = viewport.zoom
            )
        }

        internal fun buildOpenMeteoGridUrl(
            bounds: MapViewportBounds,
            rows: Int = GRID_ROWS,
            cols: Int = GRID_COLS,
            forecastHours: Int = FORECAST_HOURS
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
            return "$OPEN_METEO_FORECAST_ENDPOINT?latitude=$lats&longitude=$lons" +
                "&hourly=$HOURLY_VARIABLES&forecast_hours=$forecastHours&timezone=UTC"
        }

        internal fun parseMultiPointResponse(
            rawJson: String,
            bounds: MapViewportBounds,
            rows: Int = GRID_ROWS,
            cols: Int = GRID_COLS,
            fetchEpochMs: Long = System.currentTimeMillis()
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
                    weatherCode = wmo
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
