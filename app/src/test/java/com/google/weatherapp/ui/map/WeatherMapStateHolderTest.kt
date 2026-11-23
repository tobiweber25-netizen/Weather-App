package com.google.weatherapp.ui.map

import com.google.weatherapp.data.map.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class WeatherMapStateHolderTest {

    private val utcHourFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00").withZone(ZoneOffset.UTC)

    @Test
    fun pastLoopScrub_mapsTapInspectionWindFlowAndTilesToRealPastHours() {
        val now = Instant.parse("2026-11-23T13:20:00Z")
        val lat = -33.9249
        val lon = 18.4241
        val initialViewport = WeatherMapGridClient.estimateInitialViewport(lat, lon)
        val canonicalBounds = WeatherMapGridClient.computePaddedFetchBounds(initialViewport)

        // 50-hour grid: index 0 = 11:00 (-2h), index 1 = 12:00 (-1h), index 2 = 13:00 (Now), index 3..49 = +1h..+47h
        val grid = buildMultiHourGrid(
            bounds = canonicalBounds,
            startHourUtc = Instant.parse("2026-11-23T11:00:00Z"),
            hours = 50,
            fetchEpochMs = now.toEpochMilli(),
            pastHours = 2
        ) { hourIdx ->
            when (hourIdx) {
                0 -> HourValues(speed = 45f, dir = 0f, temp = 14f, precip = 6.2f, cape = 1600f, wmo = 95)
                1 -> HourValues(speed = 30f, dir = 90f, temp = 17f, precip = 2.5f, cape = 800f, wmo = 63)
                2 -> HourValues(speed = 12f, dir = 180f, temp = 22f, precip = 0f, cape = 50f, wmo = 1)
                else -> HourValues(speed = 18f, dir = 270f, temp = 24f, precip = 0f, cape = 100f, wmo = 2)
            }
        }

        val client = WeatherMapGridClient(clockMs = { now.toEpochMilli() }).apply { putCachedGrid(grid) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = WeatherMapStateHolder(
            scope = scope,
            initialLayer = WeatherMapLayer.STORMS,
            initialLatitude = lat,
            initialLongitude = lon,
            initialPlaceName = "Cape Town",
            initialZoneId = ZoneOffset.UTC,
            gridClient = client,
            clock = { now }
        )

        try {
            // Live (0h) -> index 2 (13:00Z)
            assertEquals(0, controller.uiState.value.hourOffset)
            assertEquals(2, controller.uiState.value.gridHourIndex)
            controller.onMapTapped(lat, lon)
            val liveReading = controller.uiState.value.tappedReading!!
            assertEquals(12, liveReading.windSpeedKmH)
            assertEquals("S", liveReading.windCompassFrom)
            assertEquals(22.0, liveReading.temperatureC, 0.1)
            assertEquals(0.0, liveReading.precipitationMmH, 0.05)
            assertEquals(50, liveReading.capeJkg)
            assertFalse(liveReading.isThunderstormCode)

            // Scrub to -1h + toggle Wind Flow ON -> MUST use real -1h index 1 (12:00Z), never Now (2) or 0!
            controller.setHourOffset(-1)
            controller.toggleWindStreamlines()
            val past1State = controller.uiState.value
            assertEquals(-1, past1State.hourOffset)
            assertEquals(1, past1State.gridHourIndex)
            assertTrue(past1State.showWindStreamlines)
            assertTrue(
                "EUMETSAT past tile must align to 12:00Z",
                past1State.tilePlan.rasterLayers.first().urlTemplate.contains("time=2026-11-23T12:00:00Z")
            )
            val past1Reading = past1State.tappedReading!!
            assertEquals(30, past1Reading.windSpeedKmH)
            assertEquals("E", past1Reading.windCompassFrom)
            assertEquals(17.0, past1Reading.temperatureC, 0.1)
            assertEquals(2.5, past1Reading.precipitationMmH, 0.05)
            assertEquals(800, past1Reading.capeJkg)

            // Scrub to -2h -> MUST use real -2h index 0 (11:00Z)
            controller.setHourOffset(-2)
            val past2State = controller.uiState.value
            assertEquals(-2, past2State.hourOffset)
            assertEquals(0, past2State.gridHourIndex)
            assertTrue(
                "EUMETSAT past tile must align to 11:00Z",
                past2State.tilePlan.rasterLayers.first().urlTemplate.contains("time=2026-11-23T11:00:00Z")
            )
            val past2Reading = past2State.tappedReading!!
            assertEquals(45, past2Reading.windSpeedKmH)
            assertEquals("N", past2Reading.windCompassFrom)
            assertEquals(14.0, past2Reading.temperatureC, 0.1)
            assertEquals(6.2, past2Reading.precipitationMmH, 0.05)
            assertEquals(1600, past2Reading.capeJkg)
            assertTrue(past2Reading.isThunderstormCode)

            // Scrub to max forecast (+47h) -> index 49
            controller.setHourOffset(47)
            assertEquals(47, controller.uiState.value.hourOffset)
            assertEquals(49, controller.uiState.value.gridHourIndex)

            // Scrub back to -2h then switch to WIND (does not support past loop): clamps to 0 AND refreshes tapped card!
            controller.setHourOffset(-2)
            controller.selectLayer(WeatherMapLayer.WIND)
            val windState = controller.uiState.value
            assertEquals(0, windState.hourOffset)
            assertEquals(2, windState.gridHourIndex)
            assertEquals("Live · Now", windState.tappedReading!!.timeLabel)
            assertEquals(12, windState.tappedReading!!.windSpeedKmH)
            assertEquals(22.0, windState.tappedReading!!.temperatureC, 0.1)
        } finally {
            controller.dispose()
            scope.cancel()
        }
    }

    @Test
    fun hourRolloverWhileCached_advancesGridHourIndexAndKeepsCompositePastHoursAligned() {
        var fakeNow = Instant.parse("2026-11-23T12:55:00Z")
        val lat = -33.9249
        val lon = 18.4241
        val regionalViewport = WeatherMapGridClient.estimateInitialViewport(lat, lon)
        val regionalBounds = WeatherMapGridClient.computePaddedFetchBounds(regionalViewport)

        // Cached at 12:55Z: [0]=10:00, [1]=11:00, [2]=12:00 (Now@12:55), [3]=13:00, [4]=14:00...
        val detailGrid = buildMultiHourGrid(
            bounds = regionalBounds,
            startHourUtc = Instant.parse("2026-11-23T10:00:00Z"),
            hours = 50,
            fetchEpochMs = fakeNow.toEpochMilli(),
            pastHours = 2
        ) { hourIdx ->
            HourValues(speed = 10f + hourIdx * 3f, dir = 180f, temp = 15f + hourIdx, precip = 0f, cape = 0f, wmo = 1)
        }

        val client = WeatherMapGridClient(clockMs = { fakeNow.toEpochMilli() }).apply { putCachedGrid(detailGrid) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = WeatherMapStateHolder(
            scope = scope,
            initialLayer = WeatherMapLayer.STORMS,
            initialLatitude = lat,
            initialLongitude = lon,
            initialPlaceName = "Cape Town",
            initialZoneId = ZoneOffset.UTC,
            gridClient = client,
            clock = { fakeNow }
        )

        try {
            assertEquals(2, controller.uiState.value.gridHourIndex) // 12:00Z

            // Advance 10 minutes across UTC hour boundary (still within 15-min cache TTL!)
            fakeNow = Instant.parse("2026-11-23T13:05:00Z")
            controller.onMapTapped(lat, lon)
            assertEquals("After rollover to 13:05Z, Now must shift to 13:00Z (index 3)", 3, controller.uiState.value.gridHourIndex)
            assertEquals(18.0, controller.uiState.value.tappedReading!!.temperatureC, 0.1)

            // Scrubbing to -1h at 13:05Z must read 12:00Z (index 2, 17°C), and -2h must read 11:00Z (index 1, 16°C)
            controller.setHourOffset(-1)
            assertEquals(2, controller.uiState.value.gridHourIndex)
            assertEquals(17.0, controller.uiState.value.tappedReading!!.temperatureC, 0.1)

            controller.setHourOffset(-2)
            assertEquals(1, controller.uiState.value.gridHourIndex)
            assertEquals(16.0, controller.uiState.value.tappedReading!!.temperatureC, 0.1)

            // Zooming out to a freshly fetched 4° base grid at 13:05Z ([0]=11:00, [1]=12:00, [2]=13:00...)
            // composited with the 12:55Z 2° detail grid MUST stay synchronized on 11:00Z at -2h!
            val wideViewport = MapViewportBounds(-46.0, 8.0, -22.0, 28.0, zoom = 4.2)
            val wideBounds = WeatherMapGridClient.computePaddedFetchBounds(wideViewport)
            val baseGrid = buildMultiHourGrid(
                bounds = wideBounds,
                startHourUtc = Instant.parse("2026-11-23T11:00:00Z"),
                hours = 50,
                fetchEpochMs = fakeNow.toEpochMilli(),
                pastHours = 2
            ) { hourIdx ->
                HourValues(speed = 50f, dir = 0f, temp = 30f + hourIdx, precip = 0f, cape = 0f, wmo = 1)
            }
            client.putCachedGrid(baseGrid)
            controller.onViewportIdle(wideViewport)

            assertEquals("Base grid -2h (11:00Z) is index 0", 0, controller.uiState.value.gridHourIndex)
            // Detail grid has 11:00Z at index 1 (16°C, 13 km/h) -> interior point MUST preserve detail grid's 11:00Z value!
            assertEquals(16.0, controller.uiState.value.tappedReading!!.temperatureC, 0.1)
            assertEquals(13, controller.uiState.value.tappedReading!!.windSpeedKmH)
        } finally {
            controller.dispose()
            scope.cancel()
        }
    }

    @Test
    fun legacyGridWithoutPastHours_remainsBackwardsCompatibleOnPastOffsets() {
        val now = Instant.parse("2026-11-23T13:20:00Z")
        val lat = -33.9249
        val lon = 18.4241
        val initialViewport = WeatherMapGridClient.estimateInitialViewport(lat, lon)
        val canonicalBounds = WeatherMapGridClient.computePaddedFetchBounds(initialViewport)

        val legacyGrid = buildMultiHourGrid(
            bounds = canonicalBounds,
            startHourUtc = Instant.parse("2026-11-23T13:00:00Z"),
            hours = 48,
            fetchEpochMs = now.toEpochMilli(),
            pastHours = 0
        ) { hourIdx ->
            HourValues(speed = 20f, dir = 180f, temp = 20f + hourIdx, precip = 0f, cape = 0f, wmo = 1)
        }

        val stateAtMinus2 = WeatherMapUiState(
            selectedLayer = WeatherMapLayer.STORMS,
            hourOffset = -2,
            gridComposite = WeatherGridComposite(legacyGrid),
            referenceTimeUtc = now
        )
        assertEquals(0, stateAtMinus2.gridHourIndex)
        assertEquals(0, stateAtMinus2.copy(hourOffset = -1).gridHourIndex)
        assertEquals(0, stateAtMinus2.copy(hourOffset = 0).gridHourIndex)
        assertEquals(1, stateAtMinus2.copy(hourOffset = 1).gridHourIndex)
        assertEquals(47, stateAtMinus2.copy(hourOffset = 47).gridHourIndex)

        // Null grid still resolves safely to 0
        assertEquals(0, WeatherMapUiState(hourOffset = -2, gridComposite = null).gridHourIndex)
    }

    private data class HourValues(
        val speed: Float,
        val dir: Float,
        val temp: Float,
        val precip: Float,
        val cape: Float,
        val wmo: Int
    )

    private fun buildMultiHourGrid(
        bounds: MapViewportBounds,
        startHourUtc: Instant,
        hours: Int,
        fetchEpochMs: Long,
        pastHours: Int,
        valuesForHour: (Int) -> HourValues
    ): WeatherGridField {
        val rows = WeatherMapGridClient.GRID_ROWS
        val cols = WeatherMapGridClient.GRID_COLS
        val pts = rows * cols
        val total = hours * pts
        val times = List(hours) { h -> utcHourFmt.format(startHourUtc.plus(h.toLong(), ChronoUnit.HOURS)) }
        val ws = FloatArray(total)
        val wd = FloatArray(total)
        val wu = FloatArray(total)
        val wv = FloatArray(total)
        val wg = FloatArray(total)
        val t = FloatArray(total)
        val pr = FloatArray(total)
        val cl = FloatArray(total)
        val p = FloatArray(total)
        val cp = FloatArray(total)
        val wmo = IntArray(total)

        for (h in 0 until hours) {
            val hv = valuesForHour(h)
            val (u, v) = WeatherFieldMath.windFromToDownwindUV(hv.speed, hv.dir)
            val base = h * pts
            for (i in 0 until pts) {
                ws[base + i] = hv.speed
                wd[base + i] = hv.dir
                wu[base + i] = u
                wv[base + i] = v
                wg[base + i] = hv.speed * 1.3f
                t[base + i] = hv.temp
                pr[base + i] = hv.precip
                cl[base + i] = 40f
                p[base + i] = 1014f
                cp[base + i] = hv.cape
                wmo[base + i] = hv.wmo
            }
        }
        return WeatherGridField(
            rows = rows,
            cols = cols,
            southLat = bounds.south,
            northLat = bounds.north,
            westLon = bounds.west,
            eastLon = bounds.east,
            zoomLevel = bounds.zoom,
            fetchEpochMs = fetchEpochMs,
            hourlyTimesUtc = times,
            windSpeedKmH = ws,
            windDirectionDeg = wd,
            windU = wu,
            windV = wv,
            windGustsKmH = wg,
            temperatureC = t,
            precipitationMm = pr,
            cloudCoverPct = cl,
            pressureHpa = p,
            capeJkg = cp,
            weatherCode = wmo,
            pastHours = pastHours
        )
    }
}
