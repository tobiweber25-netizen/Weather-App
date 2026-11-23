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

    @Test
    fun webMercatorProjection_roundTripsAndEliminatesLatitudeDriftInRenderedBitmap() {
        for (lat in listOf(-75.0, -50.0, -33.92, 0.0, 28.5, 60.0, 75.0)) {
            val roundTrip = WeatherFieldMath.mercatorYToLat(WeatherFieldMath.latToMercatorY(lat))
            assertEquals("Mercator round-trip failed for $lat", lat, roundTrip, 1e-6)
        }

        // Over -50°..-10°, Web Mercator vertical midpoint is ~-32.14°, NOT linear midpoint -30.0°.
        // Put freezing (-5°C, blue) south of -31° and hot (+38°C, red) north of -31°.
        // Row midpoint (py = 50 of 101) lies at -32.14° in Web Mercator, so it MUST sample the cold southern band!
        val rows = 41
        val cols = 3
        val temps = FloatArray(rows * cols) { idx ->
            val r = idx / cols
            val lat = -50.0 + r * 1.0 // exact 1-degree steps from -50 to -10
            if (lat < -31.0) -5f else 38f
        }
        val wideGrid = buildTestGrid(
            rows = rows,
            cols = cols,
            southLat = -50.0,
            northLat = -10.0,
            westLon = 10.0,
            eastLon = 30.0,
            temps = temps
        )
        val pixels = WeatherFieldMath.renderSmoothFieldPixels(
            grid = wideGrid,
            layer = WeatherMapLayer.TEMPERATURE,
            hourIndex = 0,
            width = 11,
            height = 101
        )
        val midPixel = pixels[50 * 11 + 5]
        val red = (midPixel ushr 16) and 0xFF
        val blue = midPixel and 0xFF
        assertTrue(
            "Web Mercator midpoint (-32.14°) must sample cold southern zone (blue > red), got R=$red B=$blue",
            blue > red
        )
    }

    @Test
    fun compositeSampling_locksWindAndTemperatureWhenZoomingOutToCoarserBaseGrid() {
        // Base 4° grid covering -52..-16, 4..32 with 240° SW wind at 12 km/h and 14°C
        val baseGrid = buildTestGrid(
            rows = 10,
            cols = 8,
            southLat = -52.0,
            northLat = -16.0,
            westLon = 4.0,
            eastLon = 32.0,
            speeds = FloatArray(80) { 12f },
            dirs = FloatArray(80) { 240f },
            temps = FloatArray(80) { 14f }
        )
        // Detail 2° grid covering -42..-24, 12..26 with 170° S wind at 28 km/h and 22.5°C
        val detailGrid = buildTestGrid(
            rows = 10,
            cols = 8,
            southLat = -42.0,
            northLat = -24.0,
            westLon = 12.0,
            eastLon = 26.0,
            speeds = FloatArray(80) { 28f },
            dirs = FloatArray(80) { 170f },
            temps = FloatArray(80) { 22.5f }
        )
        val composite = WeatherGridComposite(baseGrid = baseGrid, detailGrids = listOf(detailGrid))

        // Inside detail interior (Cape Town -33.92, 18.42): MUST match detailGrid 100% identically
        val directReading = WeatherFieldMath.samplePointReading(detailGrid, 0, -33.92, 18.42, "Live")!!
        val zoomedOutReading = WeatherFieldMath.samplePointReading(composite, 0, -33.92, 18.42, "Live")!!
        assertEquals(directReading.windFromDegrees, zoomedOutReading.windFromDegrees)
        assertEquals(directReading.windSpeedKmH, zoomedOutReading.windSpeedKmH)
        assertEquals(directReading.temperatureC, zoomedOutReading.temperatureC, 0.01)

        // Far outside detailGrid (-48, 6): uses baseGrid seamlessly
        val outerReading = WeatherFieldMath.samplePointReading(composite, 0, -48.0, 6.0, "Live")!!
        assertEquals(240, outerReading.windFromDegrees)
        assertEquals(14.0, outerReading.temperatureC, 0.1)
    }

    @Test
    fun compositePressureCenters_preservesDetailHighCenterWithoutDuplicationOnZoomOut() {
        val basePressure = FloatArray(25) { 1012f }
        // Base has a coarse center inside detail area
        basePressure[2 * 5 + 2] = 1024f
        val baseGrid = buildTestGrid(
            rows = 5,
            cols = 5,
            southLat = -48.0,
            northLat = -16.0,
            westLon = 4.0,
            eastLon = 36.0,
            pressure = basePressure
        )
        // Detail grid has the exact 1029 hPa High center at (-33, 19)
        val detailPressure = FloatArray(25) { idx ->
            val r = idx / 5
            val c = idx % 5
            val distSq = (r - 2) * (r - 2) + (c - 2) * (c - 2)
            1029f - distSq * 3f
        }
        val detailGrid = buildTestGrid(
            rows = 5,
            cols = 5,
            southLat = -37.0,
            northLat = -29.0,
            westLon = 15.0,
            eastLon = 23.0,
            pressure = detailPressure
        )
        val composite = WeatherGridComposite(baseGrid, listOf(detailGrid))
        val centers = WeatherFieldMath.findPressureCenters(composite, 0)
        assertEquals("Detail center must replace overlapping coarse center without duplicating", 1, centers.size)
        assertEquals(1029, centers.first().pressureHpa)
        assertTrue(centers.first().isHigh)
    }

    @Test
    fun compositeSampling_synchronizesUtcTimestampsAcrossHourBoundary() {
        val baseGrid = buildTestGrid(
            rows = 4,
            cols = 4,
            southLat = -44.0,
            northLat = -20.0,
            westLon = 8.0,
            eastLon = 32.0,
            hourlyTimes = listOf("2026-11-23T13:00", "2026-11-23T14:00"),
            temps = FloatArray(32) { 10f }
        )
        // Detail grid fetched earlier: hour 0 = 12:00 (18°C), hour 1 = 13:00 (26°C)
        val detailTemps = FloatArray(32) { idx -> if (idx < 16) 18f else 26f }
        val detailGrid = buildTestGrid(
            rows = 4,
            cols = 4,
            southLat = -38.0,
            northLat = -26.0,
            westLon = 14.0,
            eastLon = 26.0,
            hourlyTimes = listOf("2026-11-23T12:00", "2026-11-23T13:00"),
            temps = detailTemps
        )
        val composite = WeatherGridComposite(baseGrid, listOf(detailGrid))

        // Sampling composite at hourIndex=0 ("13:00") must pull detailGrid's hour 1 (26°C), never hour 0 (18°C)
        val reading = WeatherFieldMath.samplePointReading(composite, 0, -32.0, 20.0, "Live")!!
        assertEquals(26.0, reading.temperatureC, 0.05)
    }

    @Test
    fun stormPrecipitationAndCapeRendering_staysClearOnDryAndShowsRainSmoothly() {
        val dryGrid = buildTestGrid(3, 3, precip = FloatArray(9) { 0f }, cape = FloatArray(9) { 50f })
        val dryPixels = WeatherFieldMath.renderSmoothFieldPixels(
            composite = WeatherGridComposite(dryGrid),
            layer = WeatherMapLayer.STORMS,
            hourIndex = 0,
            width = 16,
            height = 16,
            stormFieldMode = StormFieldMode.PRECIPITATION_AND_CAPE
        )
        assertTrue("Dry low-CAPE area must render fully transparent", dryPixels.all { it == 0 })

        val rainGrid = buildTestGrid(3, 3, precip = FloatArray(9) { 6.5f }, cape = FloatArray(9) { 0f })
        val rainPixels = WeatherFieldMath.renderSmoothFieldPixels(
            composite = WeatherGridComposite(rainGrid),
            layer = WeatherMapLayer.STORMS,
            hourIndex = 0,
            width = 16,
            height = 16,
            stormFieldMode = StormFieldMode.PRECIPITATION_AND_CAPE
        )
        assertTrue("Active rain must produce opaque/translucent pixels", rainPixels.all { (it ushr 24) > 80 })

        // Threshold edge (0.12 mm/h) must feather smoothly from near-zero alpha without a hard step halo
        val edgeAlpha = (WeatherFieldMath.mapStormPrecipAndCapeToArgb(0.12f, 0f) ushr 24)
        val lightAlpha = (WeatherFieldMath.mapStormPrecipAndCapeToArgb(0.35f, 0f) ushr 24)
        val heavyAlpha = (WeatherFieldMath.mapStormPrecipAndCapeToArgb(6.0f, 0f) ushr 24)
        assertTrue("Threshold edge alpha ($edgeAlpha) must start near zero", edgeAlpha <= 2)
        assertTrue("Alpha must ramp smoothly with rain intensity", edgeAlpha < lightAlpha && lightAlpha < heavyAlpha)
    }

    @Test
    fun compositePressureCenters_deduplicatesAcrossThreeTiersAndOverlappingSameTierDetails() {
        // 8° base grid covering -64..8, -12..44 with a coarse peak at (-28, 16)
        val baseP = FloatArray(80) { 1012f }
        baseP[4 * 8 + 3] = 1022f
        val base8 = buildTestGrid(10, 8, -64.0, 8.0, -12.0, 44.0, pressure = baseP)

        // 4° mid-detail grid covering -48..-12, 4..32 with a mid peak inside the 2° region at (-32, 20)
        val midP = FloatArray(80) { 1012f }
        midP[4 * 8 + 4] = 1025f
        val detail4 = buildTestGrid(10, 8, -48.0, -12.0, 4.0, 32.0, pressure = midP)

        // Two overlapping 2° detail grids both containing the real 1029 hPa High at (-34, 20)
        val fineP1 = FloatArray(80) { 1012f }
        fineP1[4 * 8 + 4] = 1029f // (-42 + 4*2 = -34, 12 + 4*2 = 20)
        val detail2A = buildTestGrid(10, 8, -42.0, -24.0, 12.0, 26.0, pressure = fineP1)

        val fineP2 = FloatArray(80) { 1012f }
        fineP2[4 * 8 + 2] = 1029f // (-42 + 4*2 = -34, 16 + 2*2 = 20)
        val detail2B = buildTestGrid(10, 8, -42.0, -24.0, 16.0, 30.0, pressure = fineP2)

        val composite = WeatherGridComposite(
            baseGrid = base8,
            detailGrids = listOf(detail4, detail2A, detail2B)
        )
        val centers = WeatherFieldMath.findPressureCenters(composite, 0)
        assertEquals(
            "Overlapping 8°, 4°, and dual 2° grids must produce exactly 1 deduplicated High center, got $centers",
            1,
            centers.size
        )
        assertEquals(1029, centers.first().pressureHpa)
        assertEquals(-34.0, centers.first().latitude, 0.01)
        assertEquals(20.0, centers.first().longitude, 0.01)
    }

    @Test
    fun compositeIsobars_preserveEmbeddedDetailContoursWhenZoomedOutTo16DegBase() {
        // Wide 16° base grid (144° x 112°) at uniform 1012 hPa
        val base16 = buildTestGrid(10, 8, -80.0, 64.0, -32.0, 80.0, pressure = FloatArray(80) { 1012f })
        // Embedded 2° regional detail grid (-42..-24, 12..26) with a prominent 1028 hPa High dome
        val detailP = FloatArray(80) { idx ->
            val r = idx / 8
            val c = idx % 8
            val distSq = (r - 4.5) * (r - 4.5) + (c - 3.5) * (c - 3.5)
            (1028.0 - distSq * 2.2).toFloat().coerceAtLeast(1012f)
        }
        val detail2 = buildTestGrid(10, 8, -42.0, -24.0, 12.0, 26.0, pressure = detailP)

        val contours = WeatherFieldMath.extractIsobarContours(
            composite = WeatherGridComposite(base16, listOf(detail2)),
            hourIndex = 0,
            stepHpa = 4
        )
        assertTrue(
            "Adaptive composite Marching Squares must resolve 1020 and 1024 hPa contours inside 2° detail grid",
            contours.any { it.pressureHpa == 1020 } && contours.any { it.pressureHpa == 1024 }
        )
    }

    @Test
    fun extractTemperatureSpots_samplesPriorityCitiesAndInteriorLatticeWithThermalColors() {
        // South-to-North thermal gradient from 8°C at -35° to 28°C at -31°
        val rows = 5
        val cols = 5
        val temps = FloatArray(rows * cols) { idx ->
            val r = idx / cols
            8f + r * 5f // 8, 13, 18, 23, 28°C
        }
        val grid = buildTestGrid(
            rows = rows,
            cols = cols,
            southLat = -35.0,
            northLat = -31.0,
            westLon = 17.0,
            eastLon = 21.0,
            temps = temps
        )
        val viewport = MapViewportBounds(
            south = -35.0,
            west = 17.0,
            north = -31.0,
            east = 21.0,
            zoom = 6.2
        )
        val priorityPlaces = listOf(
            "Cape Town" to GeoPoint(-33.0, 19.0),
            "Cape Town Duplicate" to GeoPoint(-33.005, 19.005), // within 0.02° -> deduplicated
            "Out of Bounds City" to GeoPoint(51.5, -0.12)       // outside grid -> ignored
        )

        val spots = WeatherFieldMath.extractTemperatureSpots(
            composite = WeatherGridComposite(grid),
            hourIndex = 0,
            viewport = viewport,
            priorityPlaces = priorityPlaces
        )

        assertTrue("Expected priority city + regional lattice temperature spots", spots.size >= 15)
        val prioritySpots = spots.filter { it.isPriorityCity }
        assertEquals("Near-identical priority coordinates must deduplicate", 1, prioritySpots.size)
        val capeTownSpot = prioritySpots.first()
        assertEquals("Cape Town", capeTownSpot.placeLabel)
        assertEquals(18, capeTownSpot.temperatureC)
        assertEquals(18.0, capeTownSpot.exactTemperatureC, 0.25)
        assertTrue("Spot color must be fully opaque ARGB", (capeTownSpot.colorArgb ushr 24) == 0xFF)

        // Regional lattice spots must span the cold southern band (~10-12°C) to warm northern band (~24-26°C)
        val regionalTemps = spots.filter { !it.isPriorityCity }.map { it.temperatureC }
        assertTrue("Expected cool southern spot <= 12°C, got $regionalTemps", regionalTemps.minOrNull()!! <= 12)
        assertTrue("Expected warm northern spot >= 24°C, got $regionalTemps", regionalTemps.maxOrNull()!! >= 24)
    }

    @Test
    fun extractIsothermContours_extracts4DegreeContoursAndFlagsFreezingLine() {
        // Temperature gradient from -6°C at south (-35°) to +18°C at north (-31°)
        val rows = 7
        val cols = 7
        val temps = FloatArray(rows * cols) { idx ->
            val r = idx / cols
            -6f + r * 4f // -6, -2, +2, +6, +10, +14, +18°C
        }
        val grid = buildTestGrid(
            rows = rows,
            cols = cols,
            southLat = -35.0,
            northLat = -31.0,
            westLon = 17.0,
            eastLon = 21.0,
            temps = temps
        )

        val isotherms = WeatherFieldMath.extractIsothermContours(grid, hourIndex = 0, stepC = 4)
        assertTrue("Expected multiple 4°C isotherms across -6..+18°C", isotherms.isNotEmpty())
        val levels = isotherms.map { it.temperatureC }.toSet()
        assertEquals(setOf(-4, 0, 4, 8, 12, 16), levels)

        val freezingContours = isotherms.filter { it.isFreezingLine }
        assertTrue("0°C isotherm must be flagged as freezing line", freezingContours.isNotEmpty())
        assertTrue(freezingContours.all { it.temperatureC == 0 })
        assertTrue(
            "Non-zero isotherms must have isFreezingLine == false",
            isotherms.filter { it.temperatureC != 0 }.none { it.isFreezingLine }
        )
    }

    private fun buildTestGrid(
        rows: Int,
        cols: Int,
        southLat: Double = -35.0,
        northLat: Double = -31.0,
        westLon: Double = 17.0,
        eastLon: Double = 21.0,
        hourlyTimes: List<String> = listOf("2026-11-23T08:00"),
        speeds: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 20f },
        dirs: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 180f },
        temps: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 22f },
        precip: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 0f },
        pressure: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 1015f },
        cape: FloatArray = FloatArray(rows * cols * hourlyTimes.size) { 0f }
    ): WeatherGridField {
        val n = rows * cols * hourlyTimes.size
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
            southLat = southLat,
            northLat = northLat,
            westLon = westLon,
            eastLon = eastLon,
            zoomLevel = 6.0,
            fetchEpochMs = 1000L,
            hourlyTimesUtc = hourlyTimes,
            windSpeedKmH = speeds,
            windDirectionDeg = dirs,
            windU = u,
            windV = v,
            windGustsKmH = FloatArray(n) { speeds[it] * 1.4f },
            temperatureC = temps,
            precipitationMm = precip,
            cloudCoverPct = FloatArray(n) { 25f },
            pressureHpa = pressure,
            capeJkg = cape,
            weatherCode = IntArray(n) { 1 }
        )
    }
}
