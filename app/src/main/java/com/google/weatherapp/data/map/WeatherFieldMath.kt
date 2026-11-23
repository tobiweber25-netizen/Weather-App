package com.google.weatherapp.data.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.theme.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure deterministic meteorological interpolation, color-field rendering,
 * Marching Squares isobar extraction, and High/Low pressure center detection.
 * Operates exclusively on real WeatherGridField readings with zero synthetic fallback data.
 */
object WeatherFieldMath {

    /**
     * Converts meteorological wind speed (km/h) and "blows FROM" direction (degrees clockwise
     * from North: 0° = N wind blowing South, 270° = W wind blowing East) to downwind (u, v) flow.
     */
    fun windFromToDownwindUV(speedKmH: Float, fromDegrees: Float): Pair<Float, Float> {
        val rad = fromDegrees.toDouble() * PI / 180.0
        val u = (-speedKmH * sin(rad)).toFloat() // eastward velocity
        val v = (-speedKmH * cos(rad)).toFloat() // northward velocity
        return u to v
    }

    /**
     * Converts downwind (u eastward, v northward) velocity back into meteorological
     * "blows FROM" bearing in degrees [0..359].
     */
    fun downwindUVToFromDegrees(u: Float, v: Float): Int {
        if (hypot(u.toDouble(), v.toDouble()) < 0.001) return 0
        val deg = (Math.toDegrees(atan2(-u.toDouble(), -v.toDouble())) + 360.0) % 360.0
        return deg.roundToInt() % 360
    }

    fun degreesToCompass(degrees: Int): String {
        val normalized = ((degrees % 360) + 360) % 360
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return labels[((normalized + 22.5) / 45.0).toInt() % 8]
    }

    fun classifyCape(capeJkg: Int): CapeRiskTier = when {
        capeJkg >= 2500 -> CapeRiskTier.EXTREME
        capeJkg >= 1200 -> CapeRiskTier.STRONG
        capeJkg >= 500 -> CapeRiskTier.MODERATE
        capeJkg >= 150 -> CapeRiskTier.MARGINAL
        else -> CapeRiskTier.CALM
    }

    fun sampleWind(
        grid: WeatherGridField,
        hourIndex: Int,
        latitude: Double,
        longitude: Double
    ): WindVectorSample? {
        if (grid.hourCount <= 0 || grid.rows < 2 || grid.cols < 2) return null
        if (latitude < grid.southLat || latitude > grid.northLat ||
            longitude < grid.westLon || longitude > grid.eastLon
        ) {
            return null
        }
        val u = sampleScalarBilinear(grid, grid.windU, hourIndex, latitude, longitude, smooth = false)
        val v = sampleScalarBilinear(grid, grid.windV, hourIndex, latitude, longitude, smooth = false)
        val gust = sampleScalarBilinear(grid, grid.windGustsKmH, hourIndex, latitude, longitude, smooth = false)
        val vectorSpeed = hypot(u.toDouble(), v.toDouble()).toFloat()
        val scalarSpeed = sampleScalarBilinear(grid, grid.windSpeedKmH, hourIndex, latitude, longitude, smooth = false)
        // Preserve speed magnitude smoothly when opposing winds meet near a convergence boundary
        val effectiveSpeed = maxOf(vectorSpeed, scalarSpeed * 0.85f)
        val fromDeg = downwindUVToFromDegrees(u, v)
        return WindVectorSample(
            uKmH = u,
            vKmH = v,
            speedKmH = effectiveSpeed,
            gustKmH = maxOf(gust, effectiveSpeed),
            fromDegrees = fromDeg,
            compassFrom = degreesToCompass(fromDeg)
        )
    }

    fun samplePointReading(
        grid: WeatherGridField,
        hourIndex: Int,
        latitude: Double,
        longitude: Double,
        timeLabel: String,
        placeLabel: String? = null
    ): MapPointReading? {
        if (grid.hourCount <= 0 || grid.rows < 2 || grid.cols < 2) return null
        val clampedLat = latitude.coerceIn(grid.southLat, grid.northLat)
        val clampedLon = longitude.coerceIn(grid.westLon, grid.eastLon)
        val wind = sampleWind(grid, hourIndex, clampedLat, clampedLon) ?: return null
        val temp = sampleScalarBilinear(grid, grid.temperatureC, hourIndex, clampedLat, clampedLon, smooth = true)
        val precip = sampleScalarBilinear(grid, grid.precipitationMm, hourIndex, clampedLat, clampedLon, smooth = true)
            .coerceAtLeast(0f)
        val clouds = sampleScalarBilinear(grid, grid.cloudCoverPct, hourIndex, clampedLat, clampedLon, smooth = true)
            .roundToInt().coerceIn(0, 100)
        val pressure = sampleScalarBilinear(grid, grid.pressureHpa, hourIndex, clampedLat, clampedLon, smooth = true)
            .roundToInt()
        val cape = sampleScalarBilinear(grid, grid.capeJkg, hourIndex, clampedLat, clampedLon, smooth = true)
            .roundToInt().coerceAtLeast(0)

        val nearestRow = (((clampedLat - grid.southLat) / (grid.northLat - grid.southLat).coerceAtLeast(1e-6)) *
            (grid.rows - 1)).roundToInt().coerceIn(0, grid.rows - 1)
        val nearestCol = (((clampedLon - grid.westLon) / (grid.eastLon - grid.westLon).coerceAtLeast(1e-6)) *
            (grid.cols - 1)).roundToInt().coerceIn(0, grid.cols - 1)
        val wmo = grid.weatherCode[grid.index(hourIndex, nearestRow, nearestCol)]

        return MapPointReading(
            latitude = latitude,
            longitude = longitude,
            placeLabel = placeLabel,
            windSpeedKmH = wind.speedKmH.roundToInt(),
            windGustKmH = wind.gustKmH.roundToInt(),
            windFromDegrees = wind.fromDegrees,
            windCompassFrom = wind.compassFrom,
            temperatureC = temp.toDouble(),
            precipitationMmH = precip.toDouble(),
            cloudCoverPct = clouds,
            pressureHpa = pressure,
            capeJkg = cape,
            capeTier = classifyCape(cape),
            isThunderstormCode = wmo in 95..99,
            timeLabel = timeLabel
        )
    }

    internal fun sampleScalarBilinear(
        grid: WeatherGridField,
        values: FloatArray,
        hourIndex: Int,
        latitude: Double,
        longitude: Double,
        smooth: Boolean = true
    ): Float {
        val normY = ((latitude - grid.southLat) / (grid.northLat - grid.southLat).coerceAtLeast(1e-6))
            .coerceIn(0.0, 1.0)
        val normX = ((longitude - grid.westLon) / (grid.eastLon - grid.westLon).coerceAtLeast(1e-6))
            .coerceIn(0.0, 1.0)
        return sampleNormalized(grid, values, hourIndex, normX, normY, smooth)
    }

    private fun sampleNormalized(
        grid: WeatherGridField,
        values: FloatArray,
        hourIndex: Int,
        normX: Double,
        normY: Double,
        smooth: Boolean
    ): Float {
        val gx = normX * (grid.cols - 1)
        val gy = normY * (grid.rows - 1)
        val c0 = floor(gx).toInt().coerceIn(0, grid.cols - 2)
        val r0 = floor(gy).toInt().coerceIn(0, grid.rows - 2)
        val c1 = c0 + 1
        val r1 = r0 + 1
        var tx = (gx - c0).toFloat().coerceIn(0f, 1f)
        var ty = (gy - r0).toFloat().coerceIn(0f, 1f)
        if (smooth) {
            // Smoothstep eliminates visible grid creases while staying strictly monotonic (no overshoot)
            tx = tx * tx * (3f - 2f * tx)
            ty = ty * ty * (3f - 2f * ty)
        }
        val base = hourIndex.coerceIn(0, (grid.hourCount - 1).coerceAtLeast(0)) * grid.pointCount
        val v00 = values[base + r0 * grid.cols + c0]
        val v10 = values[base + r0 * grid.cols + c1]
        val v01 = values[base + r1 * grid.cols + c0]
        val v11 = values[base + r1 * grid.cols + c1]
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    /**
     * Generates a silky smooth ARGB pixel array for MapLibre ImageSource (row 0 = northLat, top of image).
     * Pure JVM-safe so unit tests can validate color rendering without Android frameworks.
     */
    internal fun renderSmoothFieldPixels(
        grid: WeatherGridField,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 144,
        height: Int = 144
    ): IntArray {
        val pixels = IntArray(width * height)
        if (grid.hourCount <= 0 || grid.rows < 2 || grid.cols < 2) return pixels

        val safeHour = hourIndex.coerceIn(0, grid.hourCount - 1)
        val fieldArray = when (layer) {
            WeatherMapLayer.WIND -> if (windMetricMode == WindMetricMode.GUSTS) grid.windGustsKmH else grid.windSpeedKmH
            WeatherMapLayer.STORMS -> grid.capeJkg
            WeatherMapLayer.SATELLITE -> grid.cloudCoverPct
            WeatherMapLayer.TEMPERATURE -> grid.temperatureC
            WeatherMapLayer.PRESSURE -> grid.pressureHpa
        }

        val invW = 1.0 / (width - 1).coerceAtLeast(1)
        val invH = 1.0 / (height - 1).coerceAtLeast(1)

        for (py in 0 until height) {
            // Image row 0 corresponds to northLat (normY = 1.0)
            val normY = 1.0 - py * invH
            val rowOffset = py * width
            for (px in 0 until width) {
                val normX = px * invW
                val value = sampleNormalized(grid, fieldArray, safeHour, normX, normY, smooth = true)
                pixels[rowOffset + px] = mapValueToArgb(layer, value)
            }
        }
        return pixels
    }

    fun renderSmoothFieldBitmap(
        grid: WeatherGridField,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 144,
        height: Int = 144
    ): Bitmap {
        val pixels = renderSmoothFieldPixels(grid, layer, hourIndex, windMetricMode, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    internal fun mapValueToArgb(layer: WeatherMapLayer, value: Float): Int = when (layer) {
        WeatherMapLayer.WIND -> interpolateColorStops(
            value = value,
            stops = WIND_COLOR_STOPS,
            alphaScale = (0.38f + (value / 45f).coerceIn(0f, 0.50f))
        )
        WeatherMapLayer.STORMS -> {
            // Subtle transparent convective instability (CAPE J/kg) glow; completely transparent below 250 J/kg
            if (value < 250f) {
                0x00000000
            } else {
                val alpha = ((value - 250f) / 1800f).coerceIn(0.08f, 0.68f)
                interpolateColorStops(value = value, stops = CAPE_COLOR_STOPS, alphaScale = alpha)
            }
        }
        WeatherMapLayer.SATELLITE -> {
            // Forecast / global cloud cover (%)
            if (value < 12f) {
                0x00000000
            } else {
                val alpha = ((value - 10f) / 90f).coerceIn(0.05f, 0.82f)
                interpolateColorStops(value = value, stops = CLOUD_COLOR_STOPS, alphaScale = alpha)
            }
        }
        WeatherMapLayer.TEMPERATURE -> interpolateColorStops(
            value = value,
            stops = TEMP_COLOR_STOPS,
            alphaScale = 0.80f
        )
        WeatherMapLayer.PRESSURE -> interpolateColorStops(
            value = value,
            stops = PRESSURE_COLOR_STOPS,
            alphaScale = 0.56f
        )
    }

    private fun interpolateColorStops(
        value: Float,
        stops: List<Pair<Float, Int>>,
        alphaScale: Float
    ): Int {
        val clampedAlpha = (alphaScale.coerceIn(0f, 1f) * 255f).roundToInt()
        if (value <= stops.first().first) {
            val rgb = stops.first().second and 0x00FFFFFF
            return (clampedAlpha shl 24) or rgb
        }
        if (value >= stops.last().first) {
            val rgb = stops.last().second and 0x00FFFFFF
            return (clampedAlpha shl 24) or rgb
        }
        for (i in 0 until stops.lastIndex) {
            val (v0, c0) = stops[i]
            val (v1, c1) = stops[i + 1]
            if (value in v0..v1) {
                val t = ((value - v0) / (v1 - v0).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
                val r = (((c0 ushr 16) and 0xFF) + ((((c1 ushr 16) and 0xFF) - ((c0 ushr 16) and 0xFF)) * t)).roundToInt()
                val g = (((c0 ushr 8) and 0xFF) + ((((c1 ushr 8) and 0xFF) - ((c0 ushr 8) and 0xFF)) * t)).roundToInt()
                val b = ((c0 and 0xFF) + (((c1 and 0xFF) - (c0 and 0xFF)) * t)).roundToInt()
                return (clampedAlpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return 0
    }

    /**
     * Marching Squares contour extractor producing smooth real sea-level pressure isobars
     * (every 4 hPa) from an upsampled smooth pressure field.
     */
    fun extractIsobarContours(
        grid: WeatherGridField,
        hourIndex: Int,
        stepHpa: Int = 4,
        sampleRes: Int = 25
    ): List<IsobarContourLine> {
        if (grid.hourCount <= 0 || grid.rows < 2 || grid.cols < 2) return emptyList()
        val safeHour = hourIndex.coerceIn(0, grid.hourCount - 1)

        // Upsample onto a fine smooth lattice so contour lines curve gracefully like Windy
        val fine = FloatArray(sampleRes * sampleRes)
        var minP = Float.MAX_VALUE
        var maxP = -Float.MAX_VALUE
        val inv = 1.0 / (sampleRes - 1)
        for (r in 0 until sampleRes) {
            val ny = r * inv
            for (c in 0 until sampleRes) {
                val nx = c * inv
                val p = sampleNormalized(grid, grid.pressureHpa, safeHour, nx, ny, smooth = true)
                fine[r * sampleRes + c] = p
                if (p < minP) minP = p
                if (p > maxP) maxP = p
            }
        }
        if (minP < 850f || maxP > 1100f || (maxP - minP) < 1.5f) return emptyList()

        val firstLevel = (ceil(minP / stepHpa.toDouble()).toInt()) * stepHpa
        val lastLevel = (floor(maxP / stepHpa.toDouble()).toInt()) * stepHpa
        if (firstLevel > lastLevel) return emptyList()

        val latSpan = grid.northLat - grid.southLat
        val lonSpan = grid.eastLon - grid.westLon
        val contours = mutableListOf<IsobarContourLine>()

        var level = firstLevel
        while (level <= lastLevel) {
            val iso = level.toFloat()
            val segments = mutableListOf<Pair<GeoPoint, GeoPoint>>()

            for (r in 0 until sampleRes - 1) {
                for (c in 0 until sampleRes - 1) {
                    val v00 = fine[r * sampleRes + c]           // bottom-left (south-west)
                    val v10 = fine[r * sampleRes + (c + 1)]     // bottom-right (south-east)
                    val v11 = fine[(r + 1) * sampleRes + (c + 1)] // top-right (north-east)
                    val v01 = fine[(r + 1) * sampleRes + c]     // top-left (north-west)

                    var mask = 0
                    if (v00 >= iso) mask = mask or 1
                    if (v10 >= iso) mask = mask or 2
                    if (v11 >= iso) mask = mask or 4
                    if (v01 >= iso) mask = mask or 8
                    if (mask == 0 || mask == 15) continue

                    fun safeFrac(a: Float, b: Float): Float {
                        val diff = b - a
                        return if (kotlin.math.abs(diff) > 1e-5f) {
                            ((iso - a) / diff).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                    }

                    fun interpPoint(edge: Int): GeoPoint {
                        val (fx, fy) = when (edge) {
                            0 -> { // bottom (v00 -> v10)
                                val t = safeFrac(v00, v10)
                                (c + t) * inv to r * inv
                            }
                            1 -> { // right (v10 -> v11)
                                val t = safeFrac(v10, v11)
                                (c + 1) * inv to (r + t) * inv
                            }
                            2 -> { // top (v01 -> v11)
                                val t = safeFrac(v01, v11)
                                (c + t) * inv to (r + 1) * inv
                            }
                            else -> { // left (v00 -> v01)
                                val t = safeFrac(v00, v01)
                                c * inv to (r + t) * inv
                            }
                        }
                        return GeoPoint(
                            latitude = grid.southLat + fy * latSpan,
                            longitude = grid.westLon + fx * lonSpan
                        )
                    }

                    val edgePairs = MARCHING_SQUARES_EDGES[mask]
                    for (pair in edgePairs) {
                        segments.add(interpPoint(pair.first) to interpPoint(pair.second))
                    }
                }
            }

            if (segments.isNotEmpty()) {
                val stitched = stitchSegmentsIntoPolylines(segments)
                stitched.forEach { poly ->
                    if (poly.size >= 2) {
                        contours.add(IsobarContourLine(pressureHpa = level, points = poly))
                    }
                }
            }
            level += stepHpa
        }
        return contours
    }

    /**
     * Locates genuine local High (H) and Low (L) sea-level pressure centers inside the interior
     * of the grid with a prominence filter to avoid noisy false centers.
     */
    fun findPressureCenters(
        grid: WeatherGridField,
        hourIndex: Int
    ): List<PressureCenterMarker> {
        if (grid.hourCount <= 0 || grid.rows < 3 || grid.cols < 3) return emptyList()
        val safeHour = hourIndex.coerceIn(0, grid.hourCount - 1)
        val base = safeHour * grid.pointCount

        var sum = 0.0
        for (i in 0 until grid.pointCount) sum += grid.pressureHpa[base + i]
        val meanP = (sum / grid.pointCount).toFloat()

        val latStep = (grid.northLat - grid.southLat) / (grid.rows - 1)
        val lonStep = (grid.eastLon - grid.westLon) / (grid.cols - 1)
        val centers = mutableListOf<PressureCenterMarker>()

        for (r in 1 until grid.rows - 1) {
            for (c in 1 until grid.cols - 1) {
                val centerP = grid.pressureHpa[base + r * grid.cols + c]
                var isStrictHigh = true
                var isStrictLow = true
                var neighborSum = 0f
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val np = grid.pressureHpa[base + (r + dr) * grid.cols + (c + dc)]
                        neighborSum += np
                        if (np >= centerP) isStrictHigh = false
                        if (np <= centerP) isStrictLow = false
                    }
                }
                val neighborMean = neighborSum / 8f
                val prominence = kotlin.math.abs(centerP - neighborMean)
                if (isStrictHigh && centerP >= maxOf(1016f, meanP + 1.5f) && prominence >= 0.8f) {
                    centers.add(
                        PressureCenterMarker(
                            isHigh = true,
                            pressureHpa = centerP.roundToInt(),
                            latitude = grid.southLat + r * latStep,
                            longitude = grid.westLon + c * lonStep
                        )
                    )
                } else if (isStrictLow && centerP <= minOf(1010f, meanP - 1.5f) && prominence >= 0.8f) {
                    centers.add(
                        PressureCenterMarker(
                            isHigh = false,
                            pressureHpa = centerP.roundToInt(),
                            latitude = grid.southLat + r * latStep,
                            longitude = grid.westLon + c * lonStep
                        )
                    )
                }
            }
        }
        return centers
    }

    private fun stitchSegmentsIntoPolylines(
        segments: List<Pair<GeoPoint, GeoPoint>>
    ): List<List<GeoPoint>> {
        fun keyOf(p: GeoPoint): Long {
            val latK = (p.latitude * 10_000.0).roundToInt().toLong()
            val lonK = (p.longitude * 10_000.0).roundToInt().toLong()
            return (latK shl 32) xor (lonK and 0xFFFFFFFFL)
        }

        val used = BooleanArray(segments.size)
        val adjacency = HashMap<Long, MutableList<Int>>(segments.size * 2)
        segments.forEachIndexed { idx, (a, b) ->
            adjacency.getOrPut(keyOf(a)) { mutableListOf() }.add(idx)
            adjacency.getOrPut(keyOf(b)) { mutableListOf() }.add(idx)
        }

        val polylines = mutableListOf<List<GeoPoint>>()
        for (startIdx in segments.indices) {
            if (used[startIdx]) continue
            used[startIdx] = true
            val chain = ArrayDeque<GeoPoint>()
            chain.addLast(segments[startIdx].first)
            chain.addLast(segments[startIdx].second)

            // Extend tail
            while (true) {
                val tailKey = keyOf(chain.last())
                val nextIdx = adjacency[tailKey]?.firstOrNull { !used[it] } ?: break
                used[nextIdx] = true
                val seg = segments[nextIdx]
                val nextPt = if (keyOf(seg.first) == tailKey) seg.second else seg.first
                chain.addLast(nextPt)
            }
            // Extend head
            while (true) {
                val headKey = keyOf(chain.first())
                val prevIdx = adjacency[headKey]?.firstOrNull { !used[it] } ?: break
                used[prevIdx] = true
                val seg = segments[prevIdx]
                val prevPt = if (keyOf(seg.first) == headKey) seg.second else seg.first
                chain.addFirst(prevPt)
            }
            polylines.add(chain.toList())
        }
        return polylines
    }

    private val WIND_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        0f to MapWindCalm.toArgb(),
        15f to MapWindLight.toArgb(),
        28f to MapWindBreeze.toArgb(),
        45f to MapWindFresh.toArgb(),
        65f to MapWindStrong.toArgb(),
        85f to MapWindGale.toArgb(),
        110f to MapWindStorm.toArgb(),
        140f to MapWindHurricane.toArgb()
    )

    private val CAPE_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        250f to MapCapeModerate.toArgb(),
        1000f to MapCapeHigh.toArgb(),
        2200f to MapCapeExtreme.toArgb(),
        3500f to MapRainExtreme.toArgb()
    )

    private val CLOUD_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        10f to MapCloudThin.toArgb(),
        55f to MapCloudDense.toArgb(),
        100f to PureWhite.toArgb()
    )

    private val TEMP_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        -15f to MapTempFrigid.toArgb(),
        0f to MapTempFreezing.toArgb(),
        8f to MapTempCold.toArgb(),
        15f to MapTempCool.toArgb(),
        21f to MapTempMild.toArgb(),
        28f to MapTempWarm.toArgb(),
        34f to MapTempHot.toArgb(),
        40f to MapTempScorching.toArgb(),
        46f to MapTempExtreme.toArgb()
    )

    private val PRESSURE_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        980f to MapPressureDeepLow.toArgb(),
        1000f to MapPressureLow.toArgb(),
        1013f to MapPressureNeutral.toArgb(),
        1024f to MapPressureHigh.toArgb(),
        1038f to MapPressureStrongHigh.toArgb()
    )

    // Marching Squares edge lookup (0=bottom, 1=right, 2=top, 3=left)
    private val MARCHING_SQUARES_EDGES: Array<List<Pair<Int, Int>>> = arrayOf(
        emptyList(),              // 0
        listOf(3 to 0),           // 1
        listOf(0 to 1),           // 2
        listOf(3 to 1),           // 3
        listOf(1 to 2),           // 4
        listOf(3 to 2, 0 to 1),   // 5 (saddle)
        listOf(0 to 2),           // 6
        listOf(3 to 2),           // 7
        listOf(2 to 3),           // 8
        listOf(0 to 2),           // 9
        listOf(0 to 3, 1 to 2),   // 10 (saddle)
        listOf(1 to 2),           // 11
        listOf(1 to 3),           // 12
        listOf(0 to 1),           // 13
        listOf(3 to 0),           // 14
        emptyList()               // 15
    )
}
