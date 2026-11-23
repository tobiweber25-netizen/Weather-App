package com.google.weatherapp.data.map

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

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
    fun canonicalBounds_snapsToPowerOfTwoWorldLatticeAndLocksZoomInWithoutRefetch() {
        assertEquals(10, WeatherMapGridClient.GRID_ROWS)
        assertEquals(8, WeatherMapGridClient.GRID_COLS)

        val initial = WeatherMapGridClient.estimateInitialViewport(-33.9249, 18.4241, zoom = 6.2)
        val canonical = WeatherMapGridClient.computePaddedFetchBounds(initial)

        // All 4 edges lie on exact 2.0-degree canonical coordinates
        assertEquals(0.0, abs(canonical.south % 2.0), 1e-6)
        assertEquals(0.0, abs(canonical.north % 2.0), 1e-6)
        assertEquals(0.0, abs(canonical.west % 2.0), 1e-6)
        assertEquals(0.0, abs(canonical.east % 2.0), 1e-6)
        assertEquals(18.0, canonical.north - canonical.south, 1e-6)
        assertEquals(14.0, canonical.east - canonical.west, 1e-6)

        // Gentle pan inside canonical box -> cache hit
        val gentlePan = MapViewportBounds(-37.0, 15.5, -30.5, 21.0, zoom = 6.2)
        assertTrue(canonical.coversViewport(gentlePan))

        // Deep zoom IN (z=8.5 and z=11.5) -> STILL covered so data never jumps or refetches!
        val zoomedIn = MapViewportBounds(-34.2, 18.2, -33.6, 18.7, zoom = 8.5)
        val streetZoom = MapViewportBounds(-33.98, 18.38, -33.86, 18.48, zoom = 11.5)
        assertTrue("Zooming in must stay locked to cached regional grid", canonical.coversViewport(zoomedIn))
        assertTrue("Deep zoom must stay locked to cached regional grid", canonical.coversViewport(streetZoom))

        // Far pan across continent -> cache miss as expected
        val farPan = MapViewportBounds(-27.0, 27.0, -25.0, 29.0, zoom = 6.2)
        assertFalse(canonical.coversViewport(farPan))
    }

    @Test
    fun spatialCache_zoomOutCompositesDetailGridAndZoomBackInReusesFinerGrid() {
        var nowMs = 10_000L
        val client = WeatherMapGridClient(clockMs = { nowMs })

        val regionalViewport = WeatherMapGridClient.estimateInitialViewport(-33.9249, 18.4241, zoom = 6.2)
        val regionalBounds = WeatherMapGridClient.computePaddedFetchBounds(regionalViewport)
        val regionalGrid = makeDummyGrid(regionalBounds, fetchEpochMs = nowMs)
        client.putCachedGrid(regionalGrid)

        // Zooming IN to z=9.5 hits the exact cached 2° regional grid
        val closeViewport = MapViewportBounds(-34.3, 18.1, -33.5, 18.8, zoom = 9.5)
        val closeHit = client.findCachedComposite(closeViewport)
        assertNotNull(closeHit)
        assertSame(regionalGrid, closeHit!!.baseGrid)
        assertTrue(closeHit.detailGrids.isEmpty())

        // Zooming OUT to continental view (z=4.2) misses regional grid, then caches 4° grid
        val wideViewport = MapViewportBounds(-46.0, 8.0, -22.0, 28.0, zoom = 4.2)
        assertNull(client.findCachedComposite(wideViewport))
        val wideBounds = WeatherMapGridClient.computePaddedFetchBounds(wideViewport)
        assertEquals(36.0, wideBounds.north - wideBounds.south, 1e-6)
        assertEquals(28.0, wideBounds.east - wideBounds.west, 1e-6)
        val wideGrid = makeDummyGrid(wideBounds, fetchEpochMs = nowMs)
        client.putCachedGrid(wideGrid)

        // Wide lookup now returns base=wideGrid WITH regionalGrid attached as detail layer!
        val wideComposite = client.findCachedComposite(wideViewport)
        assertNotNull(wideComposite)
        assertSame(wideGrid, wideComposite!!.baseGrid)
        assertEquals(1, wideComposite.detailGrids.size)
        assertSame(regionalGrid, wideComposite.detailGrids.first())

        // Zooming BACK IN to z=6.2 immediately selects the finer 2° regionalGrid (not the coarse 4° grid!)
        val zoomBackHit = client.findCachedComposite(regionalViewport)
        assertNotNull(zoomBackHit)
        assertSame(regionalGrid, zoomBackHit!!.baseGrid)

        // Ultra-wide z=2.0 hits max-tier cached grid without triggering infinite refetches
        val globalViewport = MapViewportBounds(-70.0, -60.0, 70.0, 90.0, zoom = 2.0)
        val globalBounds = WeatherMapGridClient.computePaddedFetchBounds(globalViewport)
        val globalGrid = makeDummyGrid(globalBounds, fetchEpochMs = nowMs)
        client.putCachedGrid(globalGrid)
        val globalHit = client.findCachedComposite(globalViewport)
        assertNotNull("Max-tier viewport must hit cached max-tier grid", globalHit)
        assertSame(globalGrid, globalHit!!.baseGrid)
    }

    @Test
    fun canonicalBounds_handlesPolarAndAntimeridianClampingSafely() {
        val northPoleEdge = MapViewportBounds(70.0, -172.0, 79.0, -162.0, zoom = 5.5)
        val snapped = WeatherMapGridClient.computePaddedFetchBounds(northPoleEdge)
        assertTrue(snapped.south >= -80.0)
        assertTrue(snapped.north <= 80.0)
        assertTrue(snapped.west >= -180.0)
        assertTrue(snapped.east <= 180.0)
        assertTrue(snapped.north > snapped.south)
        assertTrue(snapped.east > snapped.west)
    }

    @Test
    fun canonicalBounds_preservesFineTierNearAntimeridianAndPolesWithoutCollapsingTo16Deg() {
        val client = WeatherMapGridClient(clockMs = { 10_000L })

        // Auckland at default regional zoom z=6.2 (east=176.8 > old 176 clamp) MUST stay on 2° tier!
        val auckland = MapViewportBounds(-40.0, 172.8, -33.6, 176.8, zoom = 6.2)
        val aucklandBounds = WeatherMapGridClient.computePaddedFetchBounds(auckland)
        assertEquals("Auckland regional grid must stay 2° (18° lat span)", 18.0, aucklandBounds.north - aucklandBounds.south, 1e-6)
        assertEquals("Auckland regional grid must stay 2° (14° lon span)", 14.0, aucklandBounds.east - aucklandBounds.west, 1e-6)
        assertTrue(aucklandBounds.east <= 180.0)

        // Cache hit on Auckland and when zooming in to z=9.0 -> zero jump!
        val aucklandGrid = makeDummyGrid(aucklandBounds, fetchEpochMs = 10_000L)
        client.putCachedGrid(aucklandGrid)
        assertSame(aucklandGrid, client.findCachedComposite(auckland)?.baseGrid)
        val aucklandZoomedIn = MapViewportBounds(-37.2, 174.5, -36.4, 175.2, zoom = 9.0)
        assertSame(aucklandGrid, client.findCachedComposite(aucklandZoomedIn)?.baseGrid)

        // Fiji (east=179.5) and Svalbard (north=80.5) must ALSO remain on 2° regional tier
        val fiji = MapViewportBounds(-19.5, 177.0, -16.5, 179.5, zoom = 7.0)
        val fijiBounds = WeatherMapGridClient.computePaddedFetchBounds(fiji)
        assertEquals(18.0, fijiBounds.north - fijiBounds.south, 1e-6)
        assertEquals(14.0, fijiBounds.east - fijiBounds.west, 1e-6)

        val svalbard = MapViewportBounds(76.0, 10.0, 80.5, 22.0, zoom = 6.0)
        val svalbardBounds = WeatherMapGridClient.computePaddedFetchBounds(svalbard)
        assertEquals(18.0, svalbardBounds.north - svalbardBounds.south, 1e-6)
        assertEquals(14.0, svalbardBounds.east - svalbardBounds.west, 1e-6)
        assertEquals(80.0, svalbardBounds.north, 1e-6)
    }

    @Test
    fun normalizeVisibleViewport_handlesAntimeridianCrossingWithoutDroppingViewport() {
        val eastSide = WeatherMapGridClient.normalizeVisibleViewport(
            south = -22.0,
            west = 172.0,
            north = -14.0,
            east = -174.0,
            centerLon = 178.0,
            zoom = 6.0
        )
        assertNotNull("Eastern-hemisphere antimeridian viewport must not be dropped", eastSide)
        assertEquals(172.0, eastSide!!.west, 1e-6)
        assertEquals(180.0, eastSide.east, 1e-6)

        val westSide = WeatherMapGridClient.normalizeVisibleViewport(
            south = -22.0,
            west = 174.0,
            north = -14.0,
            east = -172.0,
            centerLon = -178.0,
            zoom = 6.0
        )
        assertNotNull("Western-hemisphere antimeridian viewport must not be dropped", westSide)
        assertEquals(-180.0, westSide!!.west, 1e-6)
        assertEquals(-172.0, westSide.east, 1e-6)
    }

    @Test
    fun findCachedComposite_prefersFresherGridWhenSameStepOverlaps() {
        val client = WeatherMapGridClient(clockMs = { 50_000L })
        val older = makeDummyGrid(MapViewportBounds(-42.0, 12.0, -24.0, 26.0, zoom = 6.2), fetchEpochMs = 10_000L)
        val fresher = makeDummyGrid(MapViewportBounds(-42.0, 16.0, -24.0, 30.0, zoom = 6.2), fetchEpochMs = 45_000L)
        client.putCachedGrid(older)
        client.putCachedGrid(fresher)

        val overlapZoomed = MapViewportBounds(-35.0, 19.0, -31.0, 23.0, zoom = 8.0)
        assertSame(fresher, client.findCachedComposite(overlapZoomed)?.baseGrid)
    }

    @Test
    fun buildOpenMeteoGridUrl_requestsPastTwoHoursAnd48ForecastHoursInUtc() {
        val bounds = MapViewportBounds(-36.0, 16.0, -32.0, 20.0, zoom = 6.2)
        val defaultUrl = WeatherMapGridClient.buildOpenMeteoGridUrl(bounds, rows = 2, cols = 2)
        assertTrue("Default grid URL must include past_hours=2", defaultUrl.contains("&past_hours=2"))
        assertTrue("Default grid URL must include forecast_hours=48", defaultUrl.contains("&forecast_hours=48"))
        assertTrue("Default grid URL must request UTC timezone", defaultUrl.contains("&timezone=UTC"))

        val noPastUrl = WeatherMapGridClient.buildOpenMeteoGridUrl(bounds, rows = 2, cols = 2, pastHours = 0)
        assertFalse("pastHours=0 must omit past_hours param", noPastUrl.contains("past_hours="))
        assertTrue(noPastUrl.contains("&forecast_hours=48&timezone=UTC"))
    }

    @Test
    fun parseAndResolveHourIndex_mapsPastNowFutureAndHourRolloversAccurately() {
        val pointTemplate = """
            {
              "hourly": {
                "time": [
                  "2026-11-23T11:00",
                  "2026-11-23T12:00",
                  "2026-11-23T13:00",
                  "2026-11-23T14:00",
                  "2026-11-23T15:00"
                ],
                "wind_speed_10m": [45.0, 30.0, 15.0, 22.0, 28.0],
                "wind_direction_10m": [0.0, 90.0, 180.0, 270.0, 315.0],
                "wind_gusts_10m": [60.0, 42.0, 22.0, 30.0, 38.0],
                "temperature_2m": [14.0, 17.0, 22.0, 24.0, 25.0],
                "precipitation": [6.5, 2.2, 0.0, 0.0, 0.4],
                "cloud_cover": [95.0, 80.0, 20.0, 15.0, 40.0],
                "pressure_msl": [1006.0, 1009.0, 1014.0, 1015.0, 1016.0],
                "cape": [1600.0, 850.0, 50.0, 100.0, 200.0],
                "weather_code": [95, 63, 1, 1, 3]
              }
            }
        """.trimIndent()
        val rawJson = "[$pointTemplate,$pointTemplate,$pointTemplate,$pointTemplate]"
        val bounds = MapViewportBounds(-35.0, 17.0, -32.0, 20.0, zoom = 6.0)
        val fetchTime = Instant.parse("2026-11-23T13:19:00Z")

        val field = WeatherMapGridClient.parseMultiPointResponse(
            rawJson = rawJson,
            bounds = bounds,
            rows = 2,
            cols = 2,
            fetchEpochMs = fetchTime.toEpochMilli(),
            pastHours = WeatherMapGridClient.PAST_HOURS
        )!!

        assertEquals(2, field.pastHours)
        assertEquals(5, field.hourCount)

        // Same UTC hour (13:25Z): -2h -> 0 (11:00), -1h -> 1 (12:00), 0h -> 2 (13:00), +1h -> 3, +2h -> 4
        val nowSameHour = Instant.parse("2026-11-23T13:25:00Z")
        assertEquals(0, field.resolveHourIndex(-2, nowSameHour))
        assertEquals(1, field.resolveHourIndex(-1, nowSameHour))
        assertEquals(2, field.resolveHourIndex(0, nowSameHour))
        assertEquals(3, field.resolveHourIndex(1, nowSameHour))
        assertEquals(4, field.resolveHourIndex(2, nowSameHour))
        assertEquals(0, field.resolveHourIndex(-5, nowSameHour))
        assertEquals(4, field.resolveHourIndex(47, nowSameHour))

        // After UTC hour rollover while cached (14:05Z): Now shifts to index 3 (14:00), -1h -> 2 (13:00), -2h -> 1 (12:00)
        val afterRollover = Instant.parse("2026-11-23T14:05:00Z")
        assertEquals(1, field.resolveHourIndex(-2, afterRollover))
        assertEquals(2, field.resolveHourIndex(-1, afterRollover))
        assertEquals(3, field.resolveHourIndex(0, afterRollover))
        assertEquals(4, field.resolveHourIndex(1, afterRollover))
        assertEquals(4, field.resolveHourIndex(47, afterRollover))

        // Skewed device clock outside grid window still falls back cleanly via fetchEpochMs / pastHours
        val skewedClock = Instant.parse("2027-05-01T10:00:00Z")
        assertEquals(0, field.resolveHourIndex(-2, skewedClock))
        assertEquals(1, field.resolveHourIndex(-1, skewedClock))
        assertEquals(2, field.resolveHourIndex(0, skewedClock))
        assertEquals(3, field.resolveHourIndex(1, skewedClock))
    }

    @Test
    fun resolveHourIndex_preservesBackwardsCompatibilityWithoutPastHoursAndOnEdgeInputs() {
        val bounds = MapViewportBounds(-36.0, 16.0, -32.0, 20.0, zoom = 6.2)
        val legacyTimes = listOf("2026-11-23T13:00", "2026-11-23T14:00", "2026-11-23T15:00")
        val legacyGrid = makeDummyGrid(
            bounds = bounds,
            fetchEpochMs = Instant.parse("2026-11-23T13:10:00Z").toEpochMilli(),
            hourlyTimesUtc = legacyTimes,
            pastHours = 0
        )

        val now13 = Instant.parse("2026-11-23T13:15:00Z")
        assertEquals(0, legacyGrid.resolveHourIndex(-2, now13))
        assertEquals(0, legacyGrid.resolveHourIndex(-1, now13))
        assertEquals(0, legacyGrid.resolveHourIndex(0, now13))
        assertEquals(1, legacyGrid.resolveHourIndex(1, now13))
        assertEquals(2, legacyGrid.resolveHourIndex(2, now13))
        assertEquals(2, legacyGrid.resolveHourIndex(47, now13))

        // After 1 hour rollover (14:05Z), legacy grid still has 13:00 at index 0 -> -1h resolves to 0, 0h to 1
        val now14 = Instant.parse("2026-11-23T14:05:00Z")
        assertEquals(0, legacyGrid.resolveHourIndex(-2, now14))
        assertEquals(0, legacyGrid.resolveHourIndex(-1, now14))
        assertEquals(1, legacyGrid.resolveHourIndex(0, now14))
        assertEquals(2, legacyGrid.resolveHourIndex(1, now14))

        // Static fixture with dummy fetchEpochMs and pastHours=2 falls back monotonically to pastHours anchor
        val dummyPastGrid = makeDummyGrid(
            bounds = bounds,
            fetchEpochMs = 5000L,
            hourlyTimesUtc = listOf("2025-01-01T08:00Z", "2025-01-01T09:00Z", "2025-01-01T10:00Z", "2025-01-01T11:00Z"),
            pastHours = 2
        )
        assertEquals(0, dummyPastGrid.resolveHourIndex(-2, now13))
        assertEquals(1, dummyPastGrid.resolveHourIndex(-1, now13))
        assertEquals(2, dummyPastGrid.resolveHourIndex(0, now13))
        assertEquals(3, dummyPastGrid.resolveHourIndex(1, now13))
        // And matches ISO Z-suffixed timestamps when nowUtc aligns
        assertEquals(1, dummyPastGrid.resolveHourIndex(-1, Instant.parse("2025-01-01T10:20:00Z")))

        // Empty hourlyTimesUtc never crashes
        val emptyGrid = makeDummyGrid(bounds = bounds, fetchEpochMs = 0L, hourlyTimesUtc = emptyList(), pastHours = 2)
        assertEquals(0, emptyGrid.resolveHourIndex(-2, now13))
        assertEquals(0, emptyGrid.resolveHourIndex(0, now13))
        assertEquals(0, emptyGrid.resolveHourIndex(10, now13))
    }

    private fun makeDummyGrid(
        bounds: MapViewportBounds,
        fetchEpochMs: Long,
        hourlyTimesUtc: List<String> = listOf("2026-11-23T12:00"),
        pastHours: Int = 0
    ): WeatherGridField {
        val rows = WeatherMapGridClient.GRID_ROWS
        val cols = WeatherMapGridClient.GRID_COLS
        val n = rows * cols * hourlyTimesUtc.size.coerceAtLeast(1)
        return WeatherGridField(
            rows = rows,
            cols = cols,
            southLat = bounds.south,
            northLat = bounds.north,
            westLon = bounds.west,
            eastLon = bounds.east,
            zoomLevel = bounds.zoom,
            fetchEpochMs = fetchEpochMs,
            hourlyTimesUtc = hourlyTimesUtc,
            windSpeedKmH = FloatArray(n) { 20f },
            windDirectionDeg = FloatArray(n) { 180f },
            windU = FloatArray(n) { 0f },
            windV = FloatArray(n) { 20f },
            windGustsKmH = FloatArray(n) { 28f },
            temperatureC = FloatArray(n) { 21f },
            precipitationMm = FloatArray(n) { 0f },
            cloudCoverPct = FloatArray(n) { 30f },
            pressureHpa = FloatArray(n) { 1015f },
            capeJkg = FloatArray(n) { 0f },
            weatherCode = IntArray(n) { 1 },
            pastHours = pastHours
        )
    }
}
