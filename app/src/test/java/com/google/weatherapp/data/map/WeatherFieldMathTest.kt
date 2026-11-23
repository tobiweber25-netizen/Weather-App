package com.google.weatherapp.data.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WeatherFieldMathTest {

    @Test
    fun windFromToDownwindUV_matchesMeteorologicalConventions() {
        // 0° North wind blows South (u = 0, v = -speed)
        val (uN, vN) = WeatherFieldMath.windFromToDownwindUV(20f, 0f)
        assertEquals(0f, uN, 0.01f)
        assertEquals(-20f, vN, 0.01f)

        // 90° East wind blows West (u = -speed, v = 0)
        val (uE, vE) = WeatherFieldMath.windFromToDownwindUV(30f, 90f)
        assertEquals(-30f, uE, 0.01f)
        assertEquals(0f, vE, 0.01f)

        // 180° South wind blows North (u = 0, v = +speed)
        val (uS, vS) = WeatherFieldMath.windFromToDownwindUV(25f, 180f)
        assertEquals(0f, uS, 0.01f)
        assertEquals(25f, vS, 0.01f)

        // 270° West wind blows East (u = +speed, v = 0)
        val (uW, vW) = WeatherFieldMath.windFromToDownwindUV(40f, 270f)
        assertEquals(40f, uW, 0.01f)
        assertEquals(0f, vW, 0.01f)

        assertEquals(0, WeatherFieldMath.downwindUVToFromDegrees(uN, vN))
        assertEquals(90, WeatherFieldMath.downwindUVToFromDegrees(uE, vE))
        assertEquals(180, WeatherFieldMath.downwindUVToFromDegrees(uS, vS))
        assertEquals(270, WeatherFieldMath.downwindUVToFromDegrees(uW, vW))
    }

    @Test
    fun circularWindInterpolation_wrapsAcrossNorthWithoutFlippingSouth() {
        // West half blows from 350°, East half blows from 10° -> midpoint MUST be 0° (N), never 180° (S)
        val rows = 3
        val cols = 3
        val dirs = FloatArray(rows * cols) { idx ->
            val col = idx % cols
            if (col == 0) 350f else if (col == 2) 10f else 0f
        }
        val speeds = FloatArray(rows * cols) { 30f }
        val grid = buildTestGrid(rows, cols, speeds = speeds, dirs = dirs)

        val mid = WeatherFieldMath.sampleWind(grid, hourIndex = 0, latitude = -33.0, longitude = 19.0)
        assertNotNull(mid)
        assertTrue("Expected wind from North (~0°), got ${mid!!.fromDegrees}°", mid.fromDegrees <= 3 || mid.fromDegrees >= 357)
        assertEquals("N", mid.compassFrom)
        assertEquals(30f, mid.speedKmH, 1.5f)
    }

    @Test
    fun sampleWind_returnsNullOutsideGridBounds() {
        val grid = buildTestGrid(3, 3)
        assertNull(WeatherFieldMath.sampleWind(grid, 0, latitude = 10.0, longitude = 19.0))
        assertNull(WeatherFieldMath.sampleWind(grid, 0, latitude = -33.0, longitude = 50.0))
    }

    @Test
    fun isobarExtractionAndPressureCenters_detectRealGradientAndHighLow() {
        val rows = 5
        val cols = 5
        // Build a smooth pressure dome peaking at 1028 hPa in the center (2,2) and dropping to 1004 hPa at corners
        val pressure = FloatArray(rows * cols) { idx ->
            val r = idx / cols
            val c = idx % cols
            val distSq = (r - 2) * (r - 2) + (c - 2) * (c - 2)
            (1028f - distSq * 3.0f)
        }
        val grid = buildTestGrid(rows, cols, pressure = pressure)

        val isobars = WeatherFieldMath.extractIsobarContours(grid, hourIndex = 0, stepHpa = 4)
        assertTrue("Expected multiple 4 hPa isobars", isobars.isNotEmpty())
        assertTrue("Expected 1016 or 1020 hPa contour", isobars.any { it.pressureHpa in listOf(1012, 1016, 1020, 1024) })

        val centers = WeatherFieldMath.findPressureCenters(grid, hourIndex = 0)
        assertEquals(1, centers.size)
        assertTrue(centers.first().isHigh)
        assertEquals(1028, centers.first().pressureHpa)
        assertTrue(abs(centers.first().latitude - (-33.0)) < 0.01)
        assertTrue(abs(centers.first().longitude - 19.0) < 0.01)
    }

    @Test
    fun renderSmoothFieldPixels_staysTransparentOnCalmCapeAndOpaqueOnWind() {
        val grid = buildTestGrid(3, 3, cape = FloatArray(9) { 50f })
        val capePixels = WeatherFieldMath.renderSmoothFieldPixels(grid, WeatherMapLayer.STORMS, 0, width = 16, height = 16)
        assertTrue("Calm CAPE (<250 J/kg) must render fully transparent", capePixels.all { it == 0 })

        val windPixels = WeatherFieldMath.renderSmoothFieldPixels(grid, WeatherMapLayer.WIND, 0, width = 16, height = 16)
        assertTrue("Wind layer must produce non-transparent ARGB pixels", windPixels.all { (it ushr 24) > 0 })
    }

    private fun buildTestGrid(
        rows: Int,
        cols: Int,
        speeds: FloatArray = FloatArray(rows * cols) { 20f },
        dirs: FloatArray = FloatArray(rows * cols) { 180f },
        pressure: FloatArray = FloatArray(rows * cols) { 1015f },
        cape: FloatArray = FloatArray(rows * cols) { 0f }
    ): WeatherGridField {
        val n = rows * cols
        val u = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until n) {
            val uv = WeatherFieldMath.windFromToDownwindUV(speeds[i], dirs[i])
            u[i] = uv.first
            v[i] = uv.second
        }
        return WeatherGridField(
            rows = rows,
            cols = cols,
            southLat = -35.0,
            northLat = -31.0,
            westLon = 17.0,
            eastLon = 21.0,
            zoomLevel = 6.0,
            fetchEpochMs = 1000L,
            hourlyTimesUtc = listOf("2026-11-23T08:00"),
            windSpeedKmH = speeds,
            windDirectionDeg = dirs,
            windU = u,
            windV = v,
            windGustsKmH = FloatArray(n) { speeds[it] * 1.4f },
            temperatureC = FloatArray(n) { 22f },
            precipitationMm = FloatArray(n) { 0f },
            cloudCoverPct = FloatArray(n) { 25f },
            pressureHpa = pressure,
            capeJkg = cape,
            weatherCode = IntArray(n) { 1 }
        )
    }
}
