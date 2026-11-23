package com.google.weatherapp.data.map

import androidx.compose.ui.graphics.Color
import com.google.weatherapp.theme.*

data class MapLegendStop(
    val color: Color,
    val label: String
)

enum class WeatherMapLayer(
    val shortLabel: String,
    val fullTitle: String,
    val unitLabel: String,
    val supportsPastLoop: Boolean,
    val legendStops: List<MapLegendStop>
) {
    WIND(
        shortLabel = "Wind",
        fullTitle = "Wind Flow",
        unitLabel = "km/h",
        supportsPastLoop = false,
        legendStops = listOf(
            MapLegendStop(MapWindCalm, "0"),
            MapLegendStop(MapWindLight, "15"),
            MapLegendStop(MapWindBreeze, "30"),
            MapLegendStop(MapWindFresh, "45"),
            MapLegendStop(MapWindStrong, "65"),
            MapLegendStop(MapWindGale, "85"),
            MapLegendStop(MapWindStorm, "110+")
        )
    ),
    STORMS(
        shortLabel = "Storms",
        fullTitle = "Storms & Rain",
        unitLabel = "mm/h",
        supportsPastLoop = true,
        legendStops = listOf(
            MapLegendStop(MapRainLight, "0.5"),
            MapLegendStop(MapRainModerate, "2"),
            MapLegendStop(MapRainHeavy, "8"),
            MapLegendStop(MapRainIntense, "20"),
            MapLegendStop(MapRainExtreme, "40+"),
            MapLegendStop(MapHudLightningAccent, "Bolt")
        )
    ),
    SATELLITE(
        shortLabel = "Satellite",
        fullTitle = "Live Satellite",
        unitLabel = "Clouds",
        supportsPastLoop = true,
        legendStops = listOf(
            MapLegendStop(MapCanvasFallbackBg, "Clear"),
            MapLegendStop(MapCloudThin, "Scattered"),
            MapLegendStop(MapCloudDense, "Dense"),
            MapLegendStop(PureWhite, "Storm Top")
        )
    ),
    TEMPERATURE(
        shortLabel = "Temp",
        fullTitle = "Temperature",
        unitLabel = "°C",
        supportsPastLoop = false,
        legendStops = listOf(
            MapLegendStop(MapTempFrigid, "-10°"),
            MapLegendStop(MapTempFreezing, "0°"),
            MapLegendStop(MapTempCool, "12°"),
            MapLegendStop(MapTempMild, "20°"),
            MapLegendStop(MapTempWarm, "28°"),
            MapLegendStop(MapTempHot, "35°"),
            MapLegendStop(MapTempScorching, "42°+")
        )
    ),
    PRESSURE(
        shortLabel = "Pressure",
        fullTitle = "Pressure & Isobars",
        unitLabel = "hPa",
        supportsPastLoop = false,
        legendStops = listOf(
            MapLegendStop(MapPressureDeepLow, "985"),
            MapLegendStop(MapPressureLow, "1000"),
            MapLegendStop(MapPressureNeutral, "1013"),
            MapLegendStop(MapPressureHigh, "1024"),
            MapLegendStop(MapPressureStrongHigh, "1036+")
        )
    )
}

enum class WindMetricMode(val label: String) {
    SUSTAINED("Wind"),
    GUSTS("Gusts")
}

enum class SatelliteMode(val label: String) {
    GEOCOLOUR("GeoColour"),
    INFRARED("Infrared")
}

enum class CapeRiskTier(val label: String) {
    CALM("Calm"),
    MARGINAL("Low instability"),
    MODERATE("Moderate storm energy"),
    STRONG("Strong storm energy"),
    EXTREME("Severe storm energy")
}

data class MapViewportBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val zoom: Double
) {
    val centerLat: Double get() = (south + north) * 0.5
    val centerLon: Double get() = (west + east) * 0.5
    val latSpan: Double get() = (north - south).coerceAtLeast(0.01)
    val lonSpan: Double get() = (east - west).coerceAtLeast(0.01)

    /**
     * Returns true when [other] sits comfortably inside this grid's fetched area
     * and zoom hasn't shifted enough to warrant a sharper grid refetch.
     */
    fun coversViewport(other: MapViewportBounds, maxZoomDelta: Double = 1.05): Boolean {
        if (kotlin.math.abs(zoom - other.zoom) > maxZoomDelta) return false
        return other.south >= south &&
            other.north <= north &&
            other.west >= west &&
            other.east <= east
    }
}

/**
 * Immutable 48-hour real meteorological grid fetched in a single Open-Meteo multi-point request.
 * Arrays are laid out as [hourIndex * pointCount + row * cols + col], where row 0 = southLat
 * and col 0 = westLon.
 */
data class WeatherGridField(
    val rows: Int,
    val cols: Int,
    val southLat: Double,
    val northLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val zoomLevel: Double,
    val fetchEpochMs: Long,
    val hourlyTimesUtc: List<String>,
    val windSpeedKmH: FloatArray,
    val windDirectionDeg: FloatArray,
    val windU: FloatArray, // Eastward downwind component (km/h)
    val windV: FloatArray, // Northward downwind component (km/h)
    val windGustsKmH: FloatArray,
    val temperatureC: FloatArray,
    val precipitationMm: FloatArray,
    val cloudCoverPct: FloatArray,
    val pressureHpa: FloatArray,
    val capeJkg: FloatArray,
    val weatherCode: IntArray
) {
    val pointCount: Int get() = rows * cols
    val hourCount: Int get() = hourlyTimesUtc.size

    fun index(hourIndex: Int, row: Int, col: Int): Int {
        val h = hourIndex.coerceIn(0, (hourCount - 1).coerceAtLeast(0))
        val r = row.coerceIn(0, rows - 1)
        val c = col.coerceIn(0, cols - 1)
        return h * pointCount + r * cols + c
    }

    fun toBounds(): MapViewportBounds = MapViewportBounds(
        south = southLat,
        west = westLon,
        north = northLat,
        east = eastLon,
        zoom = zoomLevel
    )
}

data class WindVectorSample(
    val uKmH: Float,
    val vKmH: Float,
    val speedKmH: Float,
    val gustKmH: Float,
    val fromDegrees: Int,
    val compassFrom: String
)

data class MapPointReading(
    val latitude: Double,
    val longitude: Double,
    val placeLabel: String?,
    val windSpeedKmH: Int,
    val windGustKmH: Int,
    val windFromDegrees: Int,
    val windCompassFrom: String,
    val temperatureC: Double,
    val precipitationMmH: Double,
    val cloudCoverPct: Int,
    val pressureHpa: Int,
    val capeJkg: Int,
    val capeTier: CapeRiskTier,
    val isThunderstormCode: Boolean,
    val timeLabel: String
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class IsobarContourLine(
    val pressureHpa: Int,
    val points: List<GeoPoint>
)

data class PressureCenterMarker(
    val isHigh: Boolean,
    val pressureHpa: Int,
    val latitude: Double,
    val longitude: Double
)

sealed interface WeatherMapGridStatus {
    data object Idle : WeatherMapGridStatus
    data object Loading : WeatherMapGridStatus
    data object Ready : WeatherMapGridStatus
    data class Error(val message: String, val isRateLimit: Boolean = false) : WeatherMapGridStatus
}
