package com.google.weatherapp.data.api

import com.google.weatherapp.data.model.LiveSatelliteStormObservation
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.notification.WeatherSummaryFormatter
import org.junit.Assert.*
import org.junit.Test

class EumetsatLiveStormClientTest {

    @Test
    fun coverageFootprint_coversAfricaAndEuropeAndSkipsAmericasAndAsia() {
        assertTrue(EumetsatLiveStormClient.isWithinEumetsatCoverage(-33.92, 18.42)) // Cape Town
        assertTrue(EumetsatLiveStormClient.isWithinEumetsatCoverage(-26.20, 28.04)) // Johannesburg
        assertTrue(EumetsatLiveStormClient.isWithinEumetsatCoverage(51.50, -0.12))  // London
        assertFalse(EumetsatLiveStormClient.isWithinEumetsatCoverage(40.71, -74.00)) // New York
        assertFalse(EumetsatLiveStormClient.isWithinEumetsatCoverage(35.68, 139.65)) // Tokyo
        assertFalse(EumetsatLiveStormClient.isWithinEumetsatCoverage(0.0, 0.0))      // Null Island
    }

    @Test
    fun analyzeLightningPixels_detectsDistanceDirectionAndCalmSkies() {
        // 1. All transparent pixels -> no lightning
        val empty = IntArray(32 * 32) { 0 }
        val calm = EumetsatLiveStormClient.analyzeLightningPixels(empty, 32, 32, radiusKm = 40.0)
        assertFalse(calm.detected)
        assertNull(calm.nearestKm)

        // 2. Bright yellow lightning pixel northwest of center (px=8, py=8 in 32x32)
        val nwPixels = IntArray(32 * 32) { 0 }
        nwPixels[8 * 32 + 8] = 0xFFFFDF00.toInt()
        val nwResult = EumetsatLiveStormClient.analyzeLightningPixels(nwPixels, 32, 32, radiusKm = 40.0)
        assertTrue(nwResult.detected)
        assertNotNull(nwResult.nearestKm)
        assertTrue(nwResult.nearestKm!! in 20..35)
        assertEquals("NW", nwResult.compassDirection)

        // 3. Overhead strike at exact center -> direction null (overhead)
        val centerPixels = IntArray(32 * 32) { 0 }
        centerPixels[16 * 32 + 16] = 0xFFFF3300.toInt()
        val overhead = EumetsatLiveStormClient.analyzeLightningPixels(centerPixels, 32, 32, radiusKm = 40.0)
        assertTrue(overhead.detected)
        assertTrue(overhead.nearestKm!! <= 4)
        assertNull(overhead.compassDirection)
    }

    @Test
    fun parseRdtAndH40bFeatureInfo_parsesRealEumetsatJsonSchemas() {
        val rdtJson = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": {
                  "PhaseLife": "Mature",
                  "CRainRate": 8.8,
                  "MvtSpeed": 45.14,
                  "MvtDirecti": 87,
                  "time": "2026-11-23T07:30:00Z"
                }
              }]
            }
        """.trimIndent()
        val cell = EumetsatLiveStormClient.parseRdtFeatureInfo(rdtJson)
        assertNotNull(cell)
        assertEquals("Mature", cell!!.phase)
        assertEquals(8.8, cell.rainRateMmH!!, 0.01)
        assertEquals(45, cell.moveSpeedKmH)
        assertEquals("E", cell.moveDirection)

        val rainJson = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": {"Instantaneous_rain_rate": 3.4, "Quality_index": 78}
              }],
              "timeStamp": "2026-11-23T07:52:01Z"
            }
        """.trimIndent()
        val rain = EumetsatLiveStormClient.parseH40bRainFeatureInfo(rainJson)
        assertNotNull(rain)
        assertEquals(3.4, rain!!.rainRateMmH, 0.01)
        assertEquals(78, rain.qualityIndex)
    }

    @Test
    fun liveSatelliteIntegration_upgradesNowcastAndSevereAlertWithoutFakeCalmNoise() {
        // Calm satellite reading leaves dry nowcast untouched
        val dryNowcast = PrecipitationNowcast(
            summaryText = "Dry for the next few hours",
            isPrecipitationImminent = false
        )
        val calmSat = LiveSatelliteStormObservation(observedRainRateMmH = 0.0)
        assertSame(dryNowcast, WeatherMapper.enhanceNowcastWithLiveSatellite(dryNowcast, calmSat))

        // Live satellite rain (>= 0.3 mm/h) upgrades dry model nowcast honestly
        val wetSat = LiveSatelliteStormObservation(observedRainRateMmH = 2.6)
        val upgraded = WeatherMapper.enhanceNowcastWithLiveSatellite(dryNowcast, wetSat)
        assertNotNull(upgraded)
        assertTrue(upgraded!!.isPrecipitationImminent)
        assertEquals("LIVE SATELLITE RAIN", upgraded.headlineLabel)
        assertTrue(upgraded.summaryText.contains("2.6 mm/h"))

        // Live lightning inside Open-Meteo fallback elevates alert & notification summary honestly
        val omForecast = OpenMeteoForecastResponse(
            timezone = "Africa/Johannesburg",
            current = OpenMeteoCurrent(
                time = "2026-11-23T09:00",
                temperature2m = 25.0,
                relativeHumidity2m = 60,
                apparentTemperature = 26.0,
                dewPoint2m = 16.0,
                isDay = 1,
                precipitation = 0.0,
                weatherCode = 2,
                cloudCover = 50,
                pressureMsl = 1012.0,
                windSpeed10m = 18.0,
                windDirection10m = 315,
                windGusts10m = 30.0
            ),
            hourly = OpenMeteoHourly(
                time = listOf("2026-11-23T09:00", "2026-11-23T10:00"),
                temperature2m = listOf(25.0, 26.0),
                precipitationProbability = listOf(10, 10),
                precipitation = listOf(0.0, 0.0),
                weatherCode = listOf(2, 2),
                windSpeed10m = listOf(18.0, 20.0),
                windGusts10m = listOf(30.0, 32.0),
                isDay = listOf(1, 1)
            ),
            daily = OpenMeteoDaily(
                time = listOf("2026-11-23"),
                weatherCode = listOf(2),
                temperature2mMax = listOf(28.0),
                temperature2mMin = listOf(15.0),
                precipitationSum = listOf(0.0),
                precipitationProbabilityMax = listOf(15)
            )
        )
        val liveLightning = LiveSatelliteStormObservation(
            lightningNearby = true,
            nearestLightningKm = 12,
            lightningDirection = "NW",
            activeStormPhase = "Mature",
            stormMoveSpeedKmH = 38,
            stormMoveDirection = "SE"
        )
        val dashboard = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Johannesburg",
            latitude = -26.20,
            longitude = 28.04,
            liveSatellite = liveLightning
        )
        assertEquals(SeverityRiskLevel.SEVERE, dashboard.alert.riskLevel)
        assertTrue(dashboard.alert.title.contains("LIGHTNING"))
        assertEquals("Now", dashboard.alert.peakHourLabel)

        val notif = WeatherSummaryFormatter.generateSevereAlertSummary(dashboard.alert, "Johannesburg")
        assertTrue(notif.conciseText.contains("12 km NW"))
        assertTrue(notif.conciseText.contains("Meteosat Live"))
    }
}
