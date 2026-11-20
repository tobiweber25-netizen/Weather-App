package com.google.weatherapp.data

import com.google.gson.JsonParser
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.WeatherConsensus
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.notification.WeatherNotificationScheduler
import com.google.weatherapp.notification.WeatherSummaryFormatter
import com.google.weatherapp.widget.WeatherWidgetRenderer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeatherConsensusAndAccuracyUpgradeTest {

    private val utcZone = ZoneId.of("UTC")
    private val jhbZone = ZoneId.of("Africa/Johannesburg")
    private val hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US)

    @Test
    fun testMultiModelConsensusDeduplicatesBestMatchAndDampensOutlier() {
        // In South Africa, best_match == ecmwf_ifs; consensus must deduplicate so ECMWF is not double-weighted.
        val json = """
            {
              "hourly": {
                "time": ["2026-11-20T12:00","2026-11-20T13:00","2026-11-20T14:00","2026-11-20T15:00"],
                "temperature_2m_best_match": [25.0, 26.0, 26.5, 26.0],
                "temperature_2m_ecmwf_ifs": [25.0, 26.0, 26.5, 26.0],
                "temperature_2m_ecmwf_aifs025_single": [25.5, 26.4, 27.0, 26.2],
                "temperature_2m_ukmo_seamless": [25.2, 26.2, 26.8, 26.1],
                "temperature_2m_icon_seamless": [25.1, 25.9, 26.4, 25.8],
                "temperature_2m_gfs_seamless": [31.0, 32.0, 33.0, 32.0],
                "precipitation_best_match": [0.0, 0.0, 0.0, 0.0],
                "precipitation_ecmwf_ifs": [0.0, 0.0, 0.0, 0.0],
                "precipitation_ecmwf_aifs025_single": [0.0, 0.0, 0.0, 0.0],
                "precipitation_ukmo_seamless": [0.0, 0.0, 0.0, 0.0],
                "precipitation_icon_seamless": [0.0, 0.0, 0.0, 0.0],
                "precipitation_gfs_seamless": [0.4, 0.0, 0.0, 0.0],
                "precipitation_probability_best_match": [10, 10, 15, 10],
                "precipitation_probability_ecmwf_ifs": [10, 10, 15, 10],
                "precipitation_probability_ecmwf_aifs025_single": [null, null, null, null],
                "precipitation_probability_ukmo_seamless": [15, 15, 20, 15],
                "precipitation_probability_icon_seamless": [10, 10, 10, 10],
                "precipitation_probability_gfs_seamless": [20, 20, 20, 20]
              }
            }
        """.trimIndent()

        val consensus = WeatherConsensus.parseMultiModelConsensus(JsonParser.parseString(json).asJsonObject)
        assertNotNull(consensus)
        assertFalse("Duplicate best_match matching ecmwf_ifs must be removed", consensus!!.hasDistinctRegionalModel)
        assertEquals(5, consensus.activeModels.size)

        val slot = consensus.hourlyByTime["2026-11-20T12:00"]!!
        // AIFS null PoP must be omitted (not stored as 0%)
        assertFalse(slot.popByModel.containsKey(WeatherConsensus.MODEL_ECMWF_AIFS))
        assertEquals(4, slot.popByModel.size)

        // GFS 31.0°C outlier (+5.8°C above median 25.2°C) must be dampened so consensus stays ~25.4°C
        val blendedTemp = WeatherConsensus.blendConsensusTemperature(25.3, slot.tempsByModel, leadHours = 12)!!
        assertTrue("Expected outlier-guarded temp near 25.4°C, got $blendedTemp", blendedTemp in 25.0..26.0)

        // Single noisy GFS 0.4mm among 5 dry models must snap to 0.0mm (no phantom drizzle)
        val blendedRain = WeatherConsensus.blendConsensusPrecipMm(0.0, slot.precipByModel, leadHours = 12)!!
        assertEquals(0.0, blendedRain, 0.001)

        // Weighted PoP must be ~12-15%, never inflated by maxOf
        val blendedPop = WeatherConsensus.blendConsensusRainProbability(10, slot.popByModel, leadHours = 12)!!
        assertTrue("Expected calibrated PoP 10..16%, got $blendedPop", blendedPop in 10..16)
    }

    @Test
    fun testDailyOvernightLowAlignsToTonightWindowMatchingGoogleAndMainstreamApps() {
        val today = LocalDate.now(jhbZone)
        val tomorrow = today.plusDays(1)

        val current = CurrentConditionsResponse(
            timeZone = TimeZoneInfo("Africa/Johannesburg"),
            temperature = Temperature("CELSIUS", 26.0),
            feelsLikeTemperature = Temperature("CELSIUS", 26.0),
            isDaytime = true
        )

        // Google Day 0 (07:00 today -> 07:00 tomorrow): High 28°C, Tonight's Low 18°C
        val gDays = listOf(
            ForecastDay(
                displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth),
                maxTemperature = Temperature("CELSIUS", 28.0),
                minTemperature = Temperature("CELSIUS", 18.0)
            )
        )

        // Open-Meteo: This morning (00:00..06:00) was cold 13°C, daytime peaked 27°C, TONIGHT (19:00..07:00 tomorrow) drops only to 18.2°C!
        val times = mutableListOf<String>()
        val temps = mutableListOf<Double>()
        for (h in 0..23) {
            times.add(today.atTime(h, 0).format(hourFmt))
            temps.add(
                when {
                    h <= 6 -> 13.0 // Expired pre-dawn cold this morning
                    h in 7..18 -> 22.0 + (if (h == 14) 5.0 else 0.0) // Peak 27.0°C
                    else -> 20.0 - (h - 19) * 0.3 // Evening cooling toward 18.5°C
                }
            )
        }
        for (h in 0..7) {
            times.add(tomorrow.atTime(h, 0).format(hourFmt))
            temps.add(if (h == 5) 18.2 else 19.0) // Tonight's pre-dawn minimum = 18.2°C
        }

        val omForecast = OpenMeteoForecastResponse(
            timezone = "Africa/Johannesburg",
            current = OpenMeteoCurrent(temperature2m = 25.5, isDay = 1),
            hourly = OpenMeteoHourly(time = times, temperature2m = temps),
            daily = OpenMeteoDaily(
                time = listOf(today.toString(), tomorrow.toString()),
                temperature2mMax = listOf(27.0, 27.0),
                temperature2mMin = listOf(13.0, 18.2) // Raw OM Today min is 13.0°C (this morning!)
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(gDays),
            omForecast = omForecast,
            omAqi = null,
            latitude = -26.2041,
            longitude = 28.0473
        )

        // Tonight's low MUST be 18°C (blending Google 18.0°C with OM tonight 18.2°C), NOT 13°C from this morning!
        assertEquals(18, result.dailyList.first().minTemp)
        assertEquals(18, result.hero.lowDegrees)
    }

    @Test
    fun testHourlyRainShiftAlignmentAndNoNowSkip() {
        val nowLdt = ZonedDateTime.now(utcZone).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val nextHour = nowLdt.plusHours(1)
        val plusTwo = nowLdt.plusHours(2)

        val current = CurrentConditionsResponse(
            timeZone = TimeZoneInfo("UTC"),
            temperature = Temperature("CELSIUS", 20.0),
            precipitation = Precipitation(PrecipProbability(percent = 5), PrecipQuantity(quantity = 0.0)),
            isDaytime = true
        )

        // Google hourly starts at nextHour (1 hour ahead of currentLocalDateHour):
        // nextHour [nextHour..plusTwo] has 60% rain, 2.0mm
        val gHours = listOf(
            ForecastHour(
                displayDateTime = DisplayDateTime(nextHour.year, nextHour.monthValue, nextHour.dayOfMonth, nextHour.hour),
                temperature = Temperature("CELSIUS", 19.0),
                precipitation = Precipitation(PrecipProbability(percent = 60), PrecipQuantity(quantity = 2.0))
            ),
            ForecastHour(
                displayDateTime = DisplayDateTime(plusTwo.year, plusTwo.monthValue, plusTwo.dayOfMonth, plusTwo.hour),
                temperature = Temperature("CELSIUS", 18.0),
                precipitation = Precipitation(PrecipProbability(percent = 10), PrecipQuantity(quantity = 0.0))
            )
        )

        // Open-Meteo uses PRECEDING hour convention:
        // rain during [nextHour..plusTwo] is labeled at timestamp `plusTwo` (NOT `nextHour`!)
        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            current = OpenMeteoCurrent(temperature2m = 20.0, isDay = 1),
            hourly = OpenMeteoHourly(
                time = listOf(
                    nowLdt.format(hourFmt),
                    nextHour.format(hourFmt),
                    plusTwo.format(hourFmt),
                    plusTwo.plusHours(1).format(hourFmt)
                ),
                temperature2m = listOf(20.0, 19.0, 18.0, 17.0),
                precipitationProbability = listOf(0, 0, 80, 0), // 80% at plusTwo == [nextHour..plusTwo]
                precipitation = listOf(0.0, 0.0, 4.0, 0.0)      // 4.0mm at plusTwo == [nextHour..plusTwo]
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(nowLdt.year, nowLdt.monthValue, nowLdt.dayOfMonth)))),
            omForecast = omForecast,
            omAqi = null
        )

        // Must prepend "Now" rather than overwriting/dropping nextHour -> 3 items: Now, nextHour, plusTwo
        assertEquals(3, result.hourlyList.size)
        assertEquals("Now", result.hourlyList[0].hourLabel)
        assertEquals(String.format(Locale.getDefault(), "%02d:00", nextHour.hour), result.hourlyList[1].hourLabel)
        assertEquals(String.format(Locale.getDefault(), "%02d:00", plusTwo.hour), result.hourlyList[2].hourLabel)

        // nextHour blends Google (60%, 2.0mm) with shifted Open-Meteo plusTwo (80%, 4.0mm) via weighted average (not maxOf)
        val nextHourItem = result.hourlyList[1]
        assertTrue("nextHour PoP should be weighted between 60 and 80, got ${nextHourItem.rainProbability}", nextHourItem.rainProbability in 65..76)
        assertTrue("nextHour precip should be weighted between 2.0 and 4.0, got ${nextHourItem.precipitationMm}", nextHourItem.precipitationMm in 2.4..3.6)

        // plusTwo blends Google (10%, 0.0mm) with shifted Open-Meteo plusThree (0%, 0.0mm) -> near dry, NOT smeared!
        assertTrue("plusTwo must not inherit previous hour's 80% rain", result.hourlyList[2].rainProbability <= 10)
        assertEquals(0.0, result.hourlyList[2].precipitationMm, 0.05)
    }

    @Test
    fun testFeelsLikeSanityGuardCatchesCapeTownHeatIndexGlitchAndNoSolarWarmthAtNight() {
        // Reproduces live Cape Town anomaly: 19.9°C air temp, 77% RH, 19 km/h wind, Google erroneously returned 24.4°C
        val sanitized = WeatherConsensus.computeSanitizedFeelsLike(
            actualTempC = 19.9,
            googleFeelsC = 24.4,
            omFeelsC = 20.5,
            humidityPercent = 77,
            windKmH = 19.0,
            isDaytime = true
        )
        assertTrue("Unphysical +4.5°C jump at 19.9°C must be rejected; got $sanitized", sanitized in 19..21)

        // Nighttime comfort reason must NEVER cite "Solar warmth"
        val nightReason = WeatherMapper.buildFeelsLikeReason(
            actual = 20,
            feelsLike = 23,
            humidity = 50,
            windKmH = 5,
            isDaytime = false
        )
        assertFalse("Must never mention solar warmth at night: '$nightReason'", nightReason.contains("Solar", ignoreCase = true))
    }

    @Test
    fun testNowcastNeverFiresOnZeroMmAndUsesShortTermForecastOutsideEuropeAndNA() {
        val now = LocalDateTime.of(2026, 11, 20, 14, 0)
        // High probability (60%) but 0.0 mm rainfall -> must NOT emit "starting in ~15 min (~0.0 mm/h)"
        val dryProbMinutely = OpenMeteoMinutely15(
            time = listOf("2026-11-20T14:15", "2026-11-20T14:30"),
            precipitation = listOf(0.0, 0.0),
            precipitationProbability = listOf(60, 55),
            weatherCode = listOf(3, 3)
        )
        val parsed = WeatherMapper.parsePrecipitationNowcast(dryProbMinutely, utcZone, referenceNow = now)!!
        assertFalse("0.0 mm must never trigger imminent rain", parsed.isPrecipitationImminent)
        assertNull(parsed.nextPrecipitationOnsetMinutes)

        // Wet slot 14 minutes in the future must NOT be labeled "active now"
        val futureWetMinutely = OpenMeteoMinutely15(
            time = listOf("2026-11-20T14:14", "2026-11-20T14:29"),
            precipitation = listOf(0.5, 2.0), // Onset rate = 2.0 mm/h, peak rate = 8.0 mm/h
            precipitationProbability = listOf(75, 90),
            weatherCode = listOf(61, 65)
        )
        val futureParsed = WeatherMapper.parsePrecipitationNowcast(futureWetMinutely, utcZone, referenceNow = now)!!
        assertEquals(14, futureParsed.nextPrecipitationOnsetMinutes)
        assertEquals(2.0, futureParsed.onsetRateMmH, 0.01)
        assertEquals(8.0, futureParsed.maxRateMmH, 0.01)
        // Rain notification should describe actual onset intensity (Moderate 2.0 mm/h), not later 8.0 mm/h peak
        val notif = WeatherSummaryFormatter.generateRainNowcastSummary(futureParsed, "Berlin")
        assertTrue("Expected onset rate ~2.0 mm/h in '${notif.conciseText}'", notif.conciseText.contains("Moderate rain") && notif.conciseText.contains("2.0 mm/h"))

        // Regional coverage check
        assertFalse(WeatherConsensus.hasNative15MinNowcast(-26.2041, 28.0473)) // Johannesburg
        assertFalse(WeatherConsensus.hasNative15MinNowcast(51.5074, -0.1278))  // London
        assertTrue(WeatherConsensus.hasNative15MinNowcast(48.137, 11.575))     // Munich
        assertTrue(WeatherConsensus.hasNative15MinNowcast(40.7128, -74.0060))  // New York

        // Short-term rain forecast in Johannesburg uses honest label
        val shortTerm = WeatherMapper.buildShortTermRainForecast(
            listOf(
                HourlyItem("Now", 24, 10, null, true, "Cloudy", precipitationMm = 0.0),
                HourlyItem("15:00", 23, 70, null, true, "Showers", precipitationMm = 1.6)
            )
        )!!
        assertEquals("SHORT-TERM RAIN FORECAST", shortTerm.headlineLabel)
        assertFalse(shortTerm.isHighRes15Min)
        assertTrue(shortTerm.isPrecipitationImminent)
        assertTrue(shortTerm.summaryText.contains("15:00"))
    }

    @Test
    fun testSawsCapPolygonMatchingAndRssHttpsUpgrade() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        val sampleRss = """
            <rss version="2.0"><channel>
              <item>
                <title>Severe Thunderstorms</title>
                <pubDate>Thu, 19 Nov 2026 09:00:00 +0200</pubDate>
                <link>http://www.weathersa.co.za/Documents/Warnings/GP_CAP_SevereThunderstorms.xml</link>
              </item>
              <item>
                <title>Old Alert</title>
                <pubDate>Tue, 10 Nov 2026 09:00:00 +0200</pubDate>
                <link>http://www.weathersa.co.za/Documents/Warnings/OLD.xml</link>
              </item>
            </channel></rss>
        """.trimIndent()
        val links = SawsAlertClient.parseSawsRssRecentLinks(sampleRss, now)
        assertEquals(1, links.size)
        assertTrue("HTTP link must be upgraded to HTTPS", links.first().startsWith("https://"))

        val capXml = """
            <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
              <status>Actual</status>
              <msgType>Alert</msgType>
              <info>
                <event>Severe Thunderstorms</event>
                <severity>Severe</severity>
                <expires>2026-11-19T23:59:59+02:00</expires>
                <headline>Severe Thunderstorms</headline>
                <description>Damaging winds and hail expected.</description>
                <instruction>Seek shelter indoors.</instruction>
                <parameter><valueName>alertType</valueName><value>Warning Level 4</value></parameter>
                <area>
                  <areaDesc>Johannesburg Metro</areaDesc>
                  <polygon>-26.0,27.8 -26.0,28.3 -26.4,28.3 -26.4,27.8 -26.0,27.8</polygon>
                </area>
              </info>
            </alert>
        """.trimIndent()

        // Johannesburg (-26.20, 28.04) is INSIDE the polygon
        val insideAlerts = SawsAlertClient.parseCapAlertXml(capXml, -26.2041, 28.0473, now, jhbZone)
        assertEquals(1, insideAlerts.size)
        assertEquals("SAWS Official", insideAlerts.first().source)
        assertEquals(SeverityRiskLevel.HIGH, insideAlerts.first().severity)
        assertEquals("Warning Level 4", insideAlerts.first().severityLabel)

        // Cape Town (-33.92, 18.42) is OUTSIDE the polygon
        val outsideAlerts = SawsAlertClient.parseCapAlertXml(capXml, -33.9249, 18.4241, now, jhbZone)
        assertTrue(outsideAlerts.isEmpty())

        // Expired alert returns empty
        val expiredAlerts = SawsAlertClient.parseCapAlertXml(capXml, -26.2041, 28.0473, Instant.parse("2026-11-20T05:00:00Z"), jhbZone)
        assertTrue(expiredAlerts.isEmpty())
    }

    @Test
    fun testMidnightTomorrowStormNotSuppressedAndOfficialAlertsTriggerSevere() {
        // At 22:30, a severe storm arriving at 00:30 ("Tomorrow 00:30") is only 2 hours away -> MUST alert!
        val midnightStorm = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.SEVERE,
            thunderstormProbability = 75,
            title = "SEVERE THUNDERSTORM WARNING",
            subtitle = "Peak activity expected tomorrow around 00:30.",
            isVisible = true,
            peakHourLabel = "Tomorrow 00:30",
            hourlyStormProbabilities = listOf(10, 45, 75, 50, 20, 0)
        )
        assertTrue("Imminent post-midnight storm (2h away) must NOT be suppressed", WeatherNotificationScheduler.isSevereAlert(midnightStorm))
        assertTrue("Imminent 75% post-midnight storm must break quiet hours", WeatherNotificationScheduler.isQuietHoursExemptSevereAlert(midnightStorm))

        // Official Severe alert (e.g., SAWS flood/hail warning) triggers even if thunderstormProbability is 0%
        val officialFlood = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.SEVERE,
            thunderstormProbability = 0,
            title = "Flash Flood Warning",
            subtitle = "Area: Cape Town",
            isVisible = true,
            officialAlerts = listOf(OfficialWeatherAlert("Flash Flood Warning", SeverityRiskLevel.SEVERE, source = "SAWS Official"))
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(officialFlood))
        assertTrue(WeatherNotificationScheduler.isQuietHoursExemptSevereAlert(officialFlood))
    }

    @Test
    fun testPhysicsDewPointPressureConversionAqiScalesWmoHazardsAndWidgetAdvance() {
        // 1. Dry-air Magnus dew point: 25°C at 20% RH -> ~0.6°C (rounded 1°C), NOT old crude formula 9°C!
        val dryDewPoint = WeatherConsensus.computeDewPoint(25.0, 20)
        assertTrue("Expected ~0..2°C dew point at 25°C/20% RH, got $dryDewPoint", dryDewPoint in 0..2)

        // 2. Barometric conversion: Johannesburg 834.4 hPa station at 1749m, 25.6°C -> ~1016 hPa sea level
        val msl = WeatherConsensus.convertSurfacePressureToSeaLevel(834.4, 1749.0, 25.6)
        assertTrue("Expected ~1013..1020 hPa sea-level pressure, got $msl", msl in 1012..1021)

        // 3. AQI scale separation + Very Unhealthy + Dominant Pollutant (JHB Ozone 110 vs PM2.5 8.8)
        assertEquals("Very Unhealthy", WeatherConsensus.categorizeUsAqi(250))
        assertEquals("Hazardous", WeatherConsensus.categorizeUsAqi(350))
        val euOnly = WeatherConsensus.resolveAirQuality(OpenMeteoAirQualityCurrent(usAqi = null, europeanAqi = 50, pm25 = 12.0))
        assertEquals("Moderate", euOnly.category)
        assertEquals("EU AQI", euOnly.scaleLabel)

        val jhbAqi = WeatherConsensus.resolveAirQuality(OpenMeteoAirQualityCurrent(usAqi = 59, europeanAqi = 50, pm25 = 8.8, pm10 = 10.8, ozone = 110.0, nitrogenDioxide = 2.9))
        assertTrue("Expected Ozone as dominant pollutant, got '${jhbAqi.dominantPollutantLabel}'", jhbAqi.dominantPollutantLabel.startsWith("Ozone"))

        // 4. Dangerous WMO hazard labels
        assertEquals("Freezing Drizzle", WeatherMapper.mapWmoCodeToCondition(56, true))
        assertEquals("Freezing Rain", WeatherMapper.mapWmoCodeToCondition(67, true))
        assertEquals("Thunderstorm with Hail", WeatherMapper.mapWmoCodeToCondition(96, true))

        // 5. Widget hourly slice advances after 2 hours of cache age
        val fetchedAt = 1_700_000_000_000L
        val sample = WeatherDashboardData(
            hero = HeroWeather(20, 20, 25, 15, "Clear", null, true, "JHB", SkyThemeType.CLEAR_DAY, lastUpdatedEpochMs = fetchedAt),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = listOf(
                HourlyItem("Now", 20, 0, null, true),
                HourlyItem("13:00", 21, 0, null, true),
                HourlyItem("14:00", 22, 0, null, true),
                HourlyItem("15:00", 23, 0, null, true),
                HourlyItem("16:00", 24, 0, null, true)
            ),
            dailyList = emptyList(),
            telemetry = BentoTelemetry(10, "N", 15, 0.0, 5, "Moderate", 50, 10, 1015, "06:00", "18:00")
        )
        val shifted = WeatherWidgetRenderer.resolveFreshHourlySlice(sample, count = 3, nowEpochMs = fetchedAt + 2 * 3_600_000L)
        assertEquals(3, shifted.size)
        assertEquals("Now", shifted[0].hourLabel)
        assertEquals(22, shifted[0].tempDegrees)
        assertEquals("15:00", shifted[1].hourLabel)
        assertEquals("16:00", shifted[2].hourLabel)
    }
}
