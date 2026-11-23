package com.google.weatherapp.data.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.theme.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure deterministic meteorological interpolation, Web-Mercator-exact color-field rendering,
 * hierarchical multi-grid composite sampling, Marching Squares isobar extraction,
 * and High/Low pressure center detection.
 * Operates exclusively on real WeatherGridField readings with zero synthetic fallback data.
 */
object WeatherFieldMath {

    private const val MAX_MERCATOR_LAT = 85.05112878

    /**
     * Exact spherical Web Mercator Y projection matching MapLibre GL ImageSource texture mapping.
     */
    fun latToMercatorY(latitudeDeg: Double): Double {
        val clamped = latitudeDeg.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        return ln(tan(PI * 0.25 + Math.toRadians(clamped) * 0.5))
    }

    /**
     * Exact inverse Web Mercator projection from Mercator Y back to geographic latitude (degrees).
     */
    fun mercatorYToLat(mercatorY: Double): Double {
        return Math.toDegrees(2.0 * atan(exp(mercatorY)) - PI * 0.5)
    }

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

    /**
     * Resolves matching hour indices for every detail grid in [composite] using exact UTC ISO timestamps
     * so cached detail grids remain time-synchronized with [composite.baseGrid] across hour boundaries.
     */
    internal fun resolveDetailHourIndices(composite: WeatherGridComposite, baseHourIndex: Int): IntArray {
        val details = composite.detailGrids
        if (details.isEmpty()) return IntArray(0)
        val base = composite.baseGrid
        val safeBase = baseHourIndex.coerceIn(0, (base.hourCount - 1).coerceAtLeast(0))
        val targetTime = base.hourlyTimesUtc.getOrNull(safeBase)
        return IntArray(details.size) { idx ->
            val d = details[idx]
            if (targetTime == null || d.hourCount <= 0) {
                -1
            } else {
                val matched = d.hourlyTimesUtc.indexOf(targetTime)
                if (matched >= 0) matched else -1
            }
        }
    }

    fun sampleWind(
        grid: WeatherGridField,
        hourIndex: Int,
        latitude: Double,
        longitude: Double
    ): WindVectorSample? = sampleWind(WeatherGridComposite(grid), hourIndex, latitude, longitude)

    fun sampleWind(
        composite: WeatherGridComposite,
        hourIndex: Int,
        latitude: Double,
        longitude: Double
    ): WindVectorSample? {
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return null
        if (latitude < base.southLat || latitude > base.northLat ||
            longitude < base.westLon || longitude > base.eastLon
        ) {
            return null
        }
        val detailHours = resolveDetailHourIndices(composite, hourIndex)
        val out = FloatArray(4)
        if (!sampleWindInto(composite, hourIndex, detailHours, latitude, longitude, out)) return null
        val u = out[0]
        val v = out[1]
        val effectiveSpeed = out[2]
        val gust = out[3]
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

    /**
     * Zero-allocation hot-path wind sampler for the 60/120fps particle animation loop.
     * Writes [uKmH, vKmH, effectiveSpeedKmH, gustKmH] into [outUVSG] (length >= 4).
     */
    internal fun sampleWindInto(
        composite: WeatherGridComposite,
        baseHourIndex: Int,
        detailHours: IntArray,
        latitude: Double,
        longitude: Double,
        outUVSG: FloatArray
    ): Boolean {
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return false
        if (latitude < base.southLat || latitude > base.northLat ||
            longitude < base.westLon || longitude > base.eastLon
        ) {
            return false
        }
        var u = sampleScalarBilinear(base, base.windU, baseHourIndex, latitude, longitude, smooth = false)
        var v = sampleScalarBilinear(base, base.windV, baseHourIndex, latitude, longitude, smooth = false)
        var scalarSpeed = sampleScalarBilinear(base, base.windSpeedKmH, baseHourIndex, latitude, longitude, smooth = false)
        var gust = sampleScalarBilinear(base, base.windGustsKmH, baseHourIndex, latitude, longitude, smooth = false)

        val details = composite.detailGrids
        for (i in details.indices) {
            val dHour = detailHours.getOrElse(i) { -1 }
            if (dHour < 0) continue
            val detail = details[i]
            val weight = computeDetailBlendWeight(base, detail, latitude, longitude)
            if (weight <= 0f) continue
            val du = sampleScalarBilinear(detail, detail.windU, dHour, latitude, longitude, smooth = false)
            val dv = sampleScalarBilinear(detail, detail.windV, dHour, latitude, longitude, smooth = false)
            val ds = sampleScalarBilinear(detail, detail.windSpeedKmH, dHour, latitude, longitude, smooth = false)
            val dg = sampleScalarBilinear(detail, detail.windGustsKmH, dHour, latitude, longitude, smooth = false)
            if (weight >= 0.999f) {
                u = du
                v = dv
                scalarSpeed = ds
                gust = dg
            } else {
                u += (du - u) * weight
                v += (dv - v) * weight
                scalarSpeed += (ds - scalarSpeed) * weight
                gust += (dg - gust) * weight
            }
        }

        val vectorSpeed = hypot(u.toDouble(), v.toDouble()).toFloat()
        val effectiveSpeed = maxOf(vectorSpeed, scalarSpeed * 0.85f)
        outUVSG[0] = u
        outUVSG[1] = v
        outUVSG[2] = effectiveSpeed
        outUVSG[3] = maxOf(gust, effectiveSpeed)
        return true
    }

    fun samplePointReading(
        grid: WeatherGridField,
        hourIndex: Int,
        latitude: Double,
        longitude: Double,
        timeLabel: String,
        placeLabel: String? = null
    ): MapPointReading? = samplePointReading(
        composite = WeatherGridComposite(grid),
        hourIndex = hourIndex,
        latitude = latitude,
        longitude = longitude,
        timeLabel = timeLabel,
        placeLabel = placeLabel
    )

    fun samplePointReading(
        composite: WeatherGridComposite,
        hourIndex: Int,
        latitude: Double,
        longitude: Double,
        timeLabel: String,
        placeLabel: String? = null
    ): MapPointReading? {
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return null
        val clampedLat = latitude.coerceIn(base.southLat, base.northLat)
        val clampedLon = longitude.coerceIn(base.westLon, base.eastLon)
        val detailHours = resolveDetailHourIndices(composite, hourIndex)

        val wind = sampleWind(composite, hourIndex, clampedLat, clampedLon) ?: return null
        val temp = sampleCompositeScalar(composite, hourIndex, detailHours, clampedLat, clampedLon, smooth = true) { it.temperatureC }
        val precip = sampleCompositeScalar(composite, hourIndex, detailHours, clampedLat, clampedLon, smooth = true) { it.precipitationMm }
            .coerceAtLeast(0f)
        val clouds = sampleCompositeScalar(composite, hourIndex, detailHours, clampedLat, clampedLon, smooth = true) { it.cloudCoverPct }
            .roundToInt().coerceIn(0, 100)
        val pressure = sampleCompositeScalar(composite, hourIndex, detailHours, clampedLat, clampedLon, smooth = true) { it.pressureHpa }
            .roundToInt()
        val cape = sampleCompositeScalar(composite, hourIndex, detailHours, clampedLat, clampedLon, smooth = true) { it.capeJkg }
            .roundToInt().coerceAtLeast(0)

        // Pick WMO thunderstorm code from the finest covering grid
        val wmoGridPair = findFinestCoveringGrid(composite, hourIndex, detailHours, clampedLat, clampedLon)
        val wmoGrid = wmoGridPair.first
        val wmoHour = wmoGridPair.second
        val nearestRow = (((clampedLat - wmoGrid.southLat) / (wmoGrid.northLat - wmoGrid.southLat).coerceAtLeast(1e-6)) *
            (wmoGrid.rows - 1)).roundToInt().coerceIn(0, wmoGrid.rows - 1)
        val nearestCol = (((clampedLon - wmoGrid.westLon) / (wmoGrid.eastLon - wmoGrid.westLon).coerceAtLeast(1e-6)) *
            (wmoGrid.cols - 1)).roundToInt().coerceIn(0, wmoGrid.cols - 1)
        val wmo = wmoGrid.weatherCode[wmoGrid.index(wmoHour, nearestRow, nearestCol)]

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

    private fun findFinestCoveringGrid(
        composite: WeatherGridComposite,
        baseHourIndex: Int,
        detailHours: IntArray,
        latitude: Double,
        longitude: Double
    ): Pair<WeatherGridField, Int> {
        for (i in composite.detailGrids.lastIndex downTo 0) {
            val dHour = detailHours.getOrElse(i) { -1 }
            if (dHour < 0) continue
            val d = composite.detailGrids[i]
            if (latitude in d.southLat..d.northLat && longitude in d.westLon..d.eastLon) {
                return d to dHour
            }
        }
        return composite.baseGrid to baseHourIndex
    }

    /**
     * Smooth 1-cell feather weight in [0..1] for blending a finer [detail] grid seamlessly
     * into a wider [base] grid without boundary creases. Interior points (>= 1 detail cell
     * from any interior boundary) return 1.0 so zoomed-out regions match zoomed-in data 100%.
     */
    internal fun computeDetailBlendWeight(
        base: WeatherGridField,
        detail: WeatherGridField,
        latitude: Double,
        longitude: Double
    ): Float {
        if (latitude < detail.southLat || latitude > detail.northLat ||
            longitude < detail.westLon || longitude > detail.eastLon
        ) {
            return 0f
        }
        val featherLat = detail.latStepDeg.coerceAtLeast(0.25)
        val featherLon = detail.lonStepDeg.coerceAtLeast(0.25)
        var minDistNorm = 1.0
        if (detail.southLat > base.southLat + 1e-4) {
            minDistNorm = minOf(minDistNorm, (latitude - detail.southLat) / featherLat)
        }
        if (detail.northLat < base.northLat - 1e-4) {
            minDistNorm = minOf(minDistNorm, (detail.northLat - latitude) / featherLat)
        }
        if (detail.westLon > base.westLon + 1e-4) {
            minDistNorm = minOf(minDistNorm, (longitude - detail.westLon) / featherLon)
        }
        if (detail.eastLon < base.eastLon - 1e-4) {
            minDistNorm = minOf(minDistNorm, (detail.eastLon - longitude) / featherLon)
        }
        val t = minDistNorm.toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private inline fun sampleCompositeScalar(
        composite: WeatherGridComposite,
        baseHourIndex: Int,
        detailHours: IntArray,
        latitude: Double,
        longitude: Double,
        smooth: Boolean,
        selector: (WeatherGridField) -> FloatArray
    ): Float {
        val base = composite.baseGrid
        var value = sampleScalarBilinear(base, selector(base), baseHourIndex, latitude, longitude, smooth)
        val details = composite.detailGrids
        for (i in details.indices) {
            val dHour = detailHours.getOrElse(i) { -1 }
            if (dHour < 0) continue
            val detail = details[i]
            val w = computeDetailBlendWeight(base, detail, latitude, longitude)
            if (w <= 0f) continue
            val detailVal = sampleScalarBilinear(detail, selector(detail), dHour, latitude, longitude, smooth)
            value = if (w >= 0.999f) detailVal else value + (detailVal - value) * w
        }
        return value
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
     * Generates a silky smooth Web-Mercator-exact ARGB pixel array for MapLibre ImageSource
     * (row 0 = northLat, top of image). Every pixel row is projected via exact inverse Web Mercator
     * so color fields never drift North/South relative to coastlines when zooming in and out.
     */
    internal fun renderSmoothFieldPixels(
        grid: WeatherGridField,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 192,
        height: Int = 192,
        stormFieldMode: StormFieldMode = StormFieldMode.CAPE_ONLY
    ): IntArray = renderSmoothFieldPixels(
        composite = WeatherGridComposite(grid),
        layer = layer,
        hourIndex = hourIndex,
        windMetricMode = windMetricMode,
        width = width,
        height = height,
        stormFieldMode = stormFieldMode
    )

    internal fun renderSmoothFieldPixels(
        composite: WeatherGridComposite,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 192,
        height: Int = 192,
        stormFieldMode: StormFieldMode = StormFieldMode.PRECIPITATION_AND_CAPE
    ): IntArray {
        val pixels = IntArray(width * height)
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return pixels

        val safeHour = hourIndex.coerceIn(0, base.hourCount - 1)
        val detailHours = resolveDetailHourIndices(composite, safeHour)
        val selector: (WeatherGridField) -> FloatArray = when (layer) {
            WeatherMapLayer.WIND -> if (windMetricMode == WindMetricMode.GUSTS) {
                WeatherGridField::windGustsKmH
            } else {
                WeatherGridField::windSpeedKmH
            }
            WeatherMapLayer.STORMS -> if (stormFieldMode == StormFieldMode.CAPE_ONLY) {
                WeatherGridField::capeJkg
            } else {
                WeatherGridField::precipitationMm
            }
            WeatherMapLayer.SATELLITE -> WeatherGridField::cloudCoverPct
            WeatherMapLayer.TEMPERATURE -> WeatherGridField::temperatureC
            WeatherMapLayer.PRESSURE -> WeatherGridField::pressureHpa
        }

        val invW = 1.0 / width.coerceAtLeast(1)
        val invH = 1.0 / height.coerceAtLeast(1)
        val mercNorth = latToMercatorY(base.northLat)
        val mercSouth = latToMercatorY(base.southLat)
        val mercSpan = (mercNorth - mercSouth).coerceAtLeast(1e-9)
        val lonSpan = (base.eastLon - base.westLon).coerceAtLeast(1e-6)
        val blendStormPrecipAndCape = layer == WeatherMapLayer.STORMS &&
            stormFieldMode == StormFieldMode.PRECIPITATION_AND_CAPE

        for (py in 0 until height) {
            // Image row 0 = northLat; map OpenGL ES texel centers linearly in Web Mercator Y to match MapLibre LatLngQuad
            val mercY = mercNorth - ((py + 0.5) * invH) * mercSpan
            val lat = mercatorYToLat(mercY).coerceIn(base.southLat, base.northLat)
            val rowOffset = py * width
            for (px in 0 until width) {
                val lon = (base.westLon + ((px + 0.5) * invW) * lonSpan).coerceIn(base.westLon, base.eastLon)
                if (blendStormPrecipAndCape) {
                    val precip = sampleCompositeScalar(
                        composite, safeHour, detailHours, lat, lon, smooth = true
                    ) { it.precipitationMm }.coerceAtLeast(0f)
                    val cape = sampleCompositeScalar(
                        composite, safeHour, detailHours, lat, lon, smooth = true
                    ) { it.capeJkg }.coerceAtLeast(0f)
                    pixels[rowOffset + px] = mapStormPrecipAndCapeToArgb(precip, cape)
                } else {
                    val value = sampleCompositeScalar(
                        composite, safeHour, detailHours, lat, lon, smooth = true, selector = selector
                    )
                    pixels[rowOffset + px] = mapValueToArgb(layer, value)
                }
            }
        }
        return pixels
    }

    fun renderSmoothFieldBitmap(
        grid: WeatherGridField,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 192,
        height: Int = 192,
        stormFieldMode: StormFieldMode = StormFieldMode.CAPE_ONLY
    ): Bitmap = renderSmoothFieldBitmap(
        composite = WeatherGridComposite(grid),
        layer = layer,
        hourIndex = hourIndex,
        windMetricMode = windMetricMode,
        width = width,
        height = height,
        stormFieldMode = stormFieldMode
    )

    fun renderSmoothFieldBitmap(
        composite: WeatherGridComposite,
        layer: WeatherMapLayer,
        hourIndex: Int,
        windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
        width: Int = 192,
        height: Int = 192,
        stormFieldMode: StormFieldMode = StormFieldMode.PRECIPITATION_AND_CAPE
    ): Bitmap {
        val pixels = renderSmoothFieldPixels(
            composite = composite,
            layer = layer,
            hourIndex = hourIndex,
            windMetricMode = windMetricMode,
            width = width,
            height = height,
            stormFieldMode = stormFieldMode
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    internal fun mapStormPrecipAndCapeToArgb(precipMmH: Float, capeJkg: Float): Int {
        if (precipMmH >= 0.12f) {
            // Smooth zero-origin feathered alpha ramp so light drizzle fades gently with zero halo edges
            val alpha = when {
                precipMmH < 0.5f -> {
                    val t = ((precipMmH - 0.12f) / 0.38f).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t) * 0.42f
                }
                precipMmH < 3.0f -> 0.42f + ((precipMmH - 0.5f) / 2.5f) * 0.30f
                else -> (0.72f + ((precipMmH - 3.0f) / 18.0f).coerceIn(0f, 0.18f))
            }.coerceIn(0f, 0.90f)
            return interpolateColorStops(
                value = precipMmH,
                stops = PRECIP_COLOR_STOPS,
                alphaScale = alpha
            )
        }
        // Outside rain areas, show only meaningful convective storm energy (>= 450 J/kg) softly
        if (capeJkg >= 450f) {
            val alpha = ((capeJkg - 450f) / 2200f).coerceIn(0f, 0.42f)
            return interpolateColorStops(
                value = capeJkg,
                stops = CAPE_COLOR_STOPS,
                alphaScale = alpha
            )
        }
        return 0x00000000
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
                val alpha = ((value - 250f) / 1800f).coerceIn(0f, 0.62f)
                interpolateColorStops(value = value, stops = CAPE_COLOR_STOPS, alphaScale = alpha)
            }
        }
        WeatherMapLayer.SATELLITE -> {
            // Forecast / global cloud cover (%)
            if (value < 12f) {
                0x00000000
            } else {
                val alpha = ((value - 12f) / 88f).coerceIn(0f, 0.82f)
                interpolateColorStops(value = value, stops = CLOUD_COLOR_STOPS, alphaScale = alpha)
            }
        }
        WeatherMapLayer.TEMPERATURE -> mapTemperatureToArgb(temperatureC = value, alphaScale = 0.80f)
        WeatherMapLayer.PRESSURE -> interpolateColorStops(
            value = value,
            stops = PRESSURE_COLOR_STOPS,
            alphaScale = 0.56f
        )
    }

    fun mapTemperatureToArgb(temperatureC: Float, alphaScale: Float = 1.0f): Int =
        interpolateColorStops(
            value = temperatureC,
            stops = TEMP_COLOR_STOPS,
            alphaScale = alphaScale
        )

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
     * Samples real interpolated temperatures at visible priority places (focused city + saved cities)
     * plus a clean interior lattice of regional points across the active [viewport] (or base grid bounds),
     * mapping each spot to its exact thermal color stop for direct on-map temperature pills.
     */
    fun extractTemperatureSpots(
        grid: WeatherGridField,
        hourIndex: Int,
        viewport: MapViewportBounds? = null,
        priorityPlaces: List<Pair<String, GeoPoint>> = emptyList()
    ): List<TemperatureSpotMarker> = extractTemperatureSpots(
        composite = WeatherGridComposite(grid),
        hourIndex = hourIndex,
        viewport = viewport,
        priorityPlaces = priorityPlaces
    )

    fun extractTemperatureSpots(
        composite: WeatherGridComposite,
        hourIndex: Int,
        viewport: MapViewportBounds? = null,
        priorityPlaces: List<Pair<String, GeoPoint>> = emptyList()
    ): List<TemperatureSpotMarker> {
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return emptyList()
        val safeHour = hourIndex.coerceIn(0, base.hourCount - 1)
        val detailHours = resolveDetailHourIndices(composite, safeHour)

        val clippedSouth = (viewport?.south ?: base.southLat).coerceIn(base.southLat, base.northLat)
        val clippedNorth = (viewport?.north ?: base.northLat).coerceIn(base.southLat, base.northLat)
        val clippedWest = (viewport?.west ?: base.westLon).coerceIn(base.westLon, base.eastLon)
        val clippedEast = (viewport?.east ?: base.eastLon).coerceIn(base.westLon, base.eastLon)

        val hasValidViewportIntersection =
            (clippedNorth - clippedSouth) >= 0.05 && (clippedEast - clippedWest) >= 0.05
        val south = if (hasValidViewportIntersection) clippedSouth else base.southLat
        val north = if (hasValidViewportIntersection) clippedNorth else base.northLat
        val west = if (hasValidViewportIntersection) clippedWest else base.westLon
        val east = if (hasValidViewportIntersection) clippedEast else base.eastLon

        val latSpan = (north - south).coerceAtLeast(1e-4)
        val lonSpan = (east - west).coerceAtLeast(1e-4)

        val spots = mutableListOf<TemperatureSpotMarker>()

        // 1. Visible priority cities first so named locations always win screen-space collision checks
        for ((rawLabel, point) in priorityPlaces) {
            val lat = point.latitude
            val lon = point.longitude
            if (lat !in base.southLat..base.northLat || lon !in base.westLon..base.eastLon) continue
            if (lat !in south..north || lon !in west..east) continue
            val isDuplicate = spots.any { existing ->
                abs(existing.latitude - lat) < 0.02 && abs(existing.longitude - lon) < 0.02
            }
            if (isDuplicate) continue
            val exactTemp = sampleCompositeScalar(
                composite = composite,
                baseHourIndex = safeHour,
                detailHours = detailHours,
                latitude = lat,
                longitude = lon,
                smooth = true
            ) { it.temperatureC }
            spots.add(
                TemperatureSpotMarker(
                    latitude = lat,
                    longitude = lon,
                    temperatureC = exactTemp.roundToInt(),
                    exactTemperatureC = exactTemp.toDouble(),
                    colorArgb = mapTemperatureToArgb(exactTemp, alphaScale = 1.0f),
                    placeLabel = rawLabel.trim().takeIf { it.isNotEmpty() },
                    isPriorityCity = true
                )
            )
        }

        // 2. Interior 4x5 regional lattice across the active viewport bounds
        val latticeRows = 4
        val latticeCols = 5
        val priorityGuardLat = latSpan * 0.07
        val priorityGuardLon = lonSpan * 0.07

        for (r in 0 until latticeRows) {
            val lat = south + ((r + 0.5) / latticeRows) * latSpan
            for (c in 0 until latticeCols) {
                val lon = west + ((c + 0.5) / latticeCols) * lonSpan
                val nearPriority = spots.any { existing ->
                    existing.isPriorityCity &&
                        abs(existing.latitude - lat) <= priorityGuardLat &&
                        abs(existing.longitude - lon) <= priorityGuardLon
                }
                if (nearPriority) continue
                val exactTemp = sampleCompositeScalar(
                    composite = composite,
                    baseHourIndex = safeHour,
                    detailHours = detailHours,
                    latitude = lat,
                    longitude = lon,
                    smooth = true
                ) { it.temperatureC }
                spots.add(
                    TemperatureSpotMarker(
                        latitude = lat,
                        longitude = lon,
                        temperatureC = exactTemp.roundToInt(),
                        exactTemperatureC = exactTemp.toDouble(),
                        colorArgb = mapTemperatureToArgb(exactTemp, alphaScale = 1.0f),
                        placeLabel = null,
                        isPriorityCity = false
                    )
                )
            }
        }
        return spots
    }

    /**
     * Marching Squares contour extractor producing smooth real temperature isotherms
     * (every [stepC] °C across -24°C..+48°C, flagging 0°C with [IsothermContourLine.isFreezingLine])
     * from an upsampled smooth composite temperature field.
     */
    fun extractIsothermContours(
        grid: WeatherGridField,
        hourIndex: Int,
        stepC: Int = 4,
        sampleRes: Int = 29
    ): List<IsothermContourLine> = extractIsothermContours(
        composite = WeatherGridComposite(grid),
        hourIndex = hourIndex,
        stepC = stepC,
        sampleRes = sampleRes
    )

    fun extractIsothermContours(
        composite: WeatherGridComposite,
        hourIndex: Int,
        stepC: Int = 4,
        sampleRes: Int = 29
    ): List<IsothermContourLine> {
        val safeStep = stepC.coerceAtLeast(1)
        return extractScalarContours(
            composite = composite,
            hourIndex = hourIndex,
            step = safeStep,
            sampleRes = sampleRes,
            minValidValue = -85f,
            maxValidValue = 68f,
            minRangeSpan = 1.2f,
            minLevelClamp = -24,
            maxLevelClamp = 48,
            selector = WeatherGridField::temperatureC
        ) { level, poly ->
            IsothermContourLine(
                temperatureC = level,
                isFreezingLine = level == 0,
                points = poly
            )
        }
    }

    /**
     * Marching Squares contour extractor producing smooth real sea-level pressure isobars
     * (every 4 hPa) from an upsampled smooth composite pressure field.
     */
    fun extractIsobarContours(
        grid: WeatherGridField,
        hourIndex: Int,
        stepHpa: Int = 4,
        sampleRes: Int = 29
    ): List<IsobarContourLine> = extractIsobarContours(
        composite = WeatherGridComposite(grid),
        hourIndex = hourIndex,
        stepHpa = stepHpa,
        sampleRes = sampleRes
    )

    fun extractIsobarContours(
        composite: WeatherGridComposite,
        hourIndex: Int,
        stepHpa: Int = 4,
        sampleRes: Int = 29
    ): List<IsobarContourLine> {
        val safeStep = stepHpa.coerceAtLeast(1)
        return extractScalarContours(
            composite = composite,
            hourIndex = hourIndex,
            step = safeStep,
            sampleRes = sampleRes,
            minValidValue = 850f,
            maxValidValue = 1100f,
            minRangeSpan = 1.5f,
            minLevelClamp = 860,
            maxLevelClamp = 1084,
            selector = WeatherGridField::pressureHpa
        ) { level, poly ->
            IsobarContourLine(pressureHpa = level, points = poly)
        }
    }

    private fun <T> extractScalarContours(
        composite: WeatherGridComposite,
        hourIndex: Int,
        step: Int,
        sampleRes: Int,
        minValidValue: Float,
        maxValidValue: Float,
        minRangeSpan: Float,
        minLevelClamp: Int,
        maxLevelClamp: Int,
        selector: (WeatherGridField) -> FloatArray,
        builder: (level: Int, points: List<GeoPoint>) -> T
    ): List<T> {
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2) return emptyList()
        val safeHour = hourIndex.coerceIn(0, base.hourCount - 1)
        val detailHours = resolveDetailHourIndices(composite, safeHour)

        val latSpan = base.northLat - base.southLat
        val lonSpan = base.eastLon - base.westLon

        // Scale Marching Squares lattice dynamically when finer cached detail grids sit inside a wider base
        val finestDetailStep = composite.detailGrids
            .filterIndexed { idx, _ -> detailHours.getOrElse(idx) { -1 } >= 0 }
            .minOfOrNull { it.latStepDeg }
        val effectiveRes = if (finestDetailStep != null && finestDetailStep > 0.05) {
            val ratio = (base.latStepDeg / finestDetailStep).coerceIn(1.0, 2.5)
            (sampleRes * ratio).roundToInt().coerceIn(sampleRes, 65)
        } else {
            sampleRes
        }

        // Upsample onto a fine smooth lattice so contour lines curve gracefully like Windy
        val fine = FloatArray(effectiveRes * effectiveRes)
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        val inv = 1.0 / (effectiveRes - 1)
        for (r in 0 until effectiveRes) {
            val lat = base.southLat + (r * inv) * latSpan
            for (c in 0 until effectiveRes) {
                val lon = base.westLon + (c * inv) * lonSpan
                val v = sampleCompositeScalar(
                    composite = composite,
                    baseHourIndex = safeHour,
                    detailHours = detailHours,
                    latitude = lat,
                    longitude = lon,
                    smooth = true,
                    selector = selector
                )
                fine[r * effectiveRes + c] = v
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
        }
        if (minVal < minValidValue || maxVal > maxValidValue || (maxVal - minVal) < minRangeSpan) {
            return emptyList()
        }

        val firstLevel = ((ceil(minVal / step.toDouble()).toInt()) * step).coerceAtLeast(minLevelClamp)
        val lastLevel = ((floor(maxVal / step.toDouble()).toInt()) * step).coerceAtMost(maxLevelClamp)
        if (firstLevel > lastLevel) return emptyList()

        val contours = mutableListOf<T>()

        var level = firstLevel
        while (level <= lastLevel) {
            val iso = level.toFloat()
            val segments = mutableListOf<Pair<GeoPoint, GeoPoint>>()

            for (r in 0 until effectiveRes - 1) {
                for (c in 0 until effectiveRes - 1) {
                    val v00 = fine[r * effectiveRes + c]
                    val v10 = fine[r * effectiveRes + (c + 1)]
                    val v11 = fine[(r + 1) * effectiveRes + (c + 1)]
                    val v01 = fine[(r + 1) * effectiveRes + c]

                    var mask = 0
                    if (v00 >= iso) mask = mask or 1
                    if (v10 >= iso) mask = mask or 2
                    if (v11 >= iso) mask = mask or 4
                    if (v01 >= iso) mask = mask or 8
                    if (mask == 0 || mask == 15) continue

                    fun safeFrac(a: Float, b: Float): Float {
                        val diff = b - a
                        return if (abs(diff) > 1e-5f) {
                            ((iso - a) / diff).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                    }

                    fun interpPoint(edge: Int): GeoPoint {
                        val (fx, fy) = when (edge) {
                            0 -> {
                                val t = safeFrac(v00, v10)
                                (c + t) * inv to r * inv
                            }
                            1 -> {
                                val t = safeFrac(v10, v11)
                                (c + 1) * inv to (r + t) * inv
                            }
                            2 -> {
                                val t = safeFrac(v01, v11)
                                (c + t) * inv to (r + 1) * inv
                            }
                            else -> {
                                val t = safeFrac(v00, v01)
                                c * inv to (r + t) * inv
                            }
                        }
                        return GeoPoint(
                            latitude = base.southLat + fy * latSpan,
                            longitude = base.westLon + fx * lonSpan
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
                        contours.add(builder(level, poly))
                    }
                }
            }
            level += step
        }
        return contours
    }

    fun findPressureCenters(
        composite: WeatherGridComposite,
        hourIndex: Int
    ): List<PressureCenterMarker> {
        if (composite.detailGrids.isEmpty()) {
            return findPressureCenters(composite.baseGrid, hourIndex)
        }
        val detailHours = resolveDetailHourIndices(composite, hourIndex)
        val merged = mutableListOf<PressureCenterMarker>()
        val coveredFinerGrids = mutableListOf<WeatherGridField>()
        // detailGrids is ordered coarsest-to-finest; walk finest-to-coarsest so finer centers always win
        for (i in composite.detailGrids.lastIndex downTo 0) {
            val dHour = detailHours.getOrElse(i) { -1 }
            if (dHour < 0) continue
            val detail = composite.detailGrids[i]
            for (center in findPressureCenters(detail, dHour)) {
                val suppressedByFiner = coveredFinerGrids.any { finer ->
                    finer.latStepDeg < detail.latStepDeg - 0.1 &&
                        center.latitude in finer.southLat..finer.northLat &&
                        center.longitude in finer.westLon..finer.eastLon
                }
                val duplicateNearby = merged.any { existing ->
                    existing.isHigh == center.isHigh &&
                        abs(existing.latitude - center.latitude) <= detail.latStepDeg * 0.6 &&
                        abs(existing.longitude - center.longitude) <= detail.lonStepDeg * 0.6
                }
                if (!suppressedByFiner && !duplicateNearby) {
                    merged.add(center)
                }
            }
            coveredFinerGrids.add(detail)
        }
        // Keep base centers strictly outside covered finer grids so H/L markers don't duplicate or shift
        for (baseCenter in findPressureCenters(composite.baseGrid, hourIndex)) {
            val insideDetail = coveredFinerGrids.any { d ->
                baseCenter.latitude in d.southLat..d.northLat &&
                    baseCenter.longitude in d.westLon..d.eastLon
            }
            val duplicateNearby = merged.any { existing ->
                existing.isHigh == baseCenter.isHigh &&
                    abs(existing.latitude - baseCenter.latitude) <= composite.baseGrid.latStepDeg * 0.5 &&
                    abs(existing.longitude - baseCenter.longitude) <= composite.baseGrid.lonStepDeg * 0.5
            }
            if (!insideDetail && !duplicateNearby) {
                merged.add(baseCenter)
            }
        }
        return merged
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
                val prominence = abs(centerP - neighborMean)
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

            while (true) {
                val tailKey = keyOf(chain.last())
                val nextIdx = adjacency[tailKey]?.firstOrNull { !used[it] } ?: break
                used[nextIdx] = true
                val seg = segments[nextIdx]
                val nextPt = if (keyOf(seg.first) == tailKey) seg.second else seg.first
                chain.addLast(nextPt)
            }
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

    private val PRECIP_COLOR_STOPS: List<Pair<Float, Int>> = listOf(
        0.12f to MapRainLight.toArgb(),
        1.2f to MapRainModerate.toArgb(),
        5.0f to MapRainHeavy.toArgb(),
        14.0f to MapRainIntense.toArgb(),
        32.0f to MapRainExtreme.toArgb()
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
