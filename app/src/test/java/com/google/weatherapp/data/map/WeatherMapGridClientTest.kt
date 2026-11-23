package com.google.weatherapp.data.map

import org.junit.Assert.*
import org.junit.Test

class WeatherMapGridClientTest {

    @Test
    fun parseMultiPointResponse_buildsAccurateGridAndDownwindVectors() {
        // 2x2 grid (4 points) x 2 hours
        val pointTemplate = """
            {
              "hourly": {
                "time": ["2026-11-23T08:00", "2026-11-23T09:00"],
                "wind_speed_10m": [20.0, 35.0],
                "wind_direction_10m": [270.0, 0.0],
                "wind_gusts_10m": [32.0, 52.0],
                "temperature_2m": [21.5, 23.0],
                "precipitation": [0.0, 2.4],
                "cloud_cover": [30.0, 80.0],
                "pressure_msl": [1016.0, 1012.0],
                "cape": [100.0, 1450.0],
                "weather_code": [2, 95]
              }
            }
        """.trimIndent()
        val rawJson = "[$pointTemplate,$pointTemplate,$pointTemplate,$pointTemplate]"
        val bounds = MapViewportBounds(-35.0, 17.0, -32.0, 20.0, zoom = 6.0)

        val field = WeatherMapGridClient.parseMultiPointResponse(
            rawJson = rawJson,
            bounds = bounds,
            rows = 2,
            cols = 2,
            fetchEpochMs = 5000L
        )
        assertNotNull(field)
        assertEquals(2, field!!.hourCount)
        assertEquals(4, field.pointCount)

        // Hour 0: 270° West wind at 20 km/h -> blows East (+20, 0)
        assertEquals(20f, field.windU[field.index(0, 0, 0)], 0.05f)
        assertEquals(0f, field.windV[field.index(0, 0, 0)], 0.05f)

        // Hour 1: 0° North wind at 35 km/h -> blows South (0, -35), CAPE 1450, thunderstorm code 95
        val reading = WeatherFieldMath.samplePointReading(field, 1, -33.5, 18.5, "Today · 09:00")
        assertNotNull(reading)
        assertEquals(35, reading!!.windSpeedKmH)
        assertEquals(52, reading.windGustKmH)
        assertEquals("N", reading.windCompassFrom)
        assertEquals(1450, reading.capeJkg)
        assertEquals(CapeRiskTier.STRONG, reading.capeTier)
        assertTrue(reading.isThunderstormCode)
    }

    @Test
    fun parseMultiPointResponse_rejectsMalformedOrIncompletePointCount() {
        val bounds = MapViewportBounds(-35.0, 17.0, -32.0, 20.0, zoom = 6.0)
        assertNull(WeatherMapGridClient.parseMultiPointResponse("not json", bounds, rows = 2, cols = 2))
        assertNull(WeatherMapGridClient.parseMultiPointResponse("[]", bounds, rows = 2, cols = 2))
    }

    @Test
    fun paddedViewportBounds_coversGentlePansAndRejectsLargeZoomOrOutsidePan() {
        val initial = MapViewportBounds(-34.5, 18.0, -32.5, 20.0, zoom = 6.0)
        val padded = WeatherMapGridClient.computePaddedFetchBounds(initial)

        // Small pan inside padded box -> cache hit
        val gentlePan = MapViewportBounds(-34.3, 18.2, -32.3, 20.2, zoom = 6.2)
        assertTrue(padded.coversViewport(gentlePan))

        // Large pan across continent -> cache miss
        val farPan = MapViewportBounds(-27.0, 27.0, -25.0, 29.0, zoom = 6.0)
        assertFalse(padded.coversViewport(farPan))

        // Significant zoom-in -> cache miss so we fetch higher spatial resolution
        val zoomedIn = MapViewportBounds(-34.0, 18.3, -33.6, 18.7, zoom = 8.5)
        assertFalse(padded.coversViewport(zoomedIn))
    }

    @Test
    fun estimateInitialViewport_centersOnCityAndUsesFastFiveByFiveGrid() {
        assertEquals(5, WeatherMapGridClient.GRID_ROWS)
        assertEquals(5, WeatherMapGridClient.GRID_COLS)
        val bounds = WeatherMapGridClient.estimateInitialViewport(-33.9249, 18.4241)
        assertTrue(bounds.north > bounds.south)
        assertTrue(bounds.east > bounds.west)
        assertEquals(-33.9249, bounds.centerLat, 0.05)
        assertEquals(18.4241, bounds.centerLon, 0.05)
    }
}
