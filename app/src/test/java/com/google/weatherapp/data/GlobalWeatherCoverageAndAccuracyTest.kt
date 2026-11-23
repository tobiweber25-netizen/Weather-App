package com.google.weatherapp.data

import com.google.gson.JsonParser
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.LocationSearchEngine
import com.google.weatherapp.data.repository.WeatherConsensus
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.notification.WeatherNotificationScheduler
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class GlobalWeatherCoverageAndAccuracyTest {

    private val utcZone = ZoneId.of("UTC")
    private val hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US)

    @Test
    fun testHybridSearchFindsSuburbsRanksQualifiersAndDeduplicatesWithTimezone() {
        // 1. Suburb/City query where Open-Meteo returns 0 results, Photon finds Sandton
        val photonSandton = listOf(
            PhotonFeature(
                geometry = PhotonGeometry(listOf(28.0567, -26.1076)),
                properties = PhotonProperties(
                    name = "Sandton",
                    city = "Johannesburg",
                    state = "Gauteng",
                    country = "South Africa",
                    countryCode = "ZA",
                    osmKey = "place",
                    osmValue = "suburb"
                )
            )
        )
        val sandtonResults = LocationSearchEngine.mergeAndRankSearchResults(
            query = "Sandton, Johannesburg",
            omResults = emptyList(),
            photonFeatures = photonSandton
        )
        assertEquals(1, sandtonResults.size)
        assertEquals("Sandton", sandtonResults.first().name)
        assertTrue(sandtonResults.first().displaySubtitle.contains("Johannesburg"))
        assertTrue(sandtonResults.first().displaySubtitle.contains("South Africa"))

        // 2. Qualifier ranking: "London, Ontario" ranks Ontario, Canada ahead of London, England
        val omLondons = listOf(
            OpenMeteoGeoResult(1, "London", 51.5074, -0.1278, "United Kingdom", "GB", "England", null, "Europe/London", 8900000),
            OpenMeteoGeoResult(2, "London", 42.9834, -81.2497, "Canada", "CA", "Ontario", null, "America/Toronto", 422000)
        )
        val photonLondons = listOf(
            PhotonFeature(
                geometry = PhotonGeometry(listOf(-81.2453, 42.9849)),
                properties = PhotonProperties(name = "London", state = "Ontario", country = "Canada", countryCode = "CA", osmKey = "place", osmValue = "city")
            )
        )
        val londonResults = LocationSearchEngine.mergeAndRankSearchResults("London, Ontario", omLondons, photonLondons)
        assertEquals("Canada", londonResults.first().country)
        // Deduplication merges nearby London, Ontario and preserves Open-Meteo timezone
        assertEquals("America/Toronto", londonResults.first().timezone)
        assertEquals(2, londonResults.size)

        // 3. Duplicate subtitle clean-up ("Tokyo", "Tokyo", "Japan" -> "Japan")
        val tokyo = SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503)
        assertEquals("Japan", tokyo.displaySubtitle)
    }

    @Test
    fun testCoordinateParsingAndHonestLocationNameNeverFakesTimezoneCity() {
        // Parses decimal, hemisphere, and space/comma coordinates
        val c1 = LocationSearchEngine.parseCoordinateQuery("-33.9249, 18.4241")!!
        assertEquals(-33.9249, c1.first, 0.0001)
        assertEquals(18.4241, c1.second, 0.0001)

        val c2 = LocationSearchEngine.parseCoordinateQuery("40.71° N, 74.01° W")!!
        assertEquals(40.71, c2.first, 0.0001)
        assertEquals(-74.01, c2.second, 0.0001)

        // 5-digit postal code must NOT be misread as coordinates
        assertNull(LocationSearchEngine.parseCoordinateQuery("90210"))
        assertNull(LocationSearchEngine.parseCoordinateQuery("Paris, France"))

        // Reverse geocode formatter produces clean "Suburb, City"
        val revName = LocationSearchEngine.formatReverseGeocodeName(
            PhotonProperties(district = "Shibuya", city = "Tokyo", country = "Japan", osmKey = "place", osmValue = "suburb")
        )
        assertEquals("Shibuya, Tokyo", revName)

        // In Osaka (Asia/Tokyo timezone), missing geocoder name must show real coordinates — NEVER fake "Tokyo"!
        val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
        val osakaResult = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = OpenMeteoForecastResponse(
                timezone = "Asia/Tokyo",
                current = OpenMeteoCurrent(temperature2m = 16.0, isDay = 1),
                daily = OpenMeteoDaily(time = listOf(today.toString()), temperature2mMax = listOf(19.0), temperature2mMin = listOf(11.0))
            ),
            omAqi = null,
            resolvedLocationName = null,
            latitude = 34.6937,
            longitude = 135.5023
        )
        assertFalse("Must never fake 'Tokyo' for Osaka coordinates", osakaResult.hero.locationName.contains("Tokyo", ignoreCase = true))
        assertEquals("34.69° N, 135.50° E", osakaResult.hero.locationName)
    }

    @Test
    fun testRegionalBestMatchModelPromotesHrrrAndIconD2WithoutDoubleCountingGlobalEcmwf() {
        // US (New York): best_match == gfs_seamless (HRRR 3km) -> gfs_seamless must be promoted to full regional weight!
        val usJson = """
            {
              "hourly": {
                "time": ["2026-11-23T12:00","2026-11-23T13:00","2026-11-23T14:00","2026-11-23T15:00"],
                "temperature_2m_best_match": [14.5, 15.0, 15.2, 14.8],
                "temperature_2m_gfs_seamless": [14.5, 15.0, 15.2, 14.8],
                "temperature_2m_ecmwf_ifs": [13.8, 14.2, 14.5, 14.1],
                "temperature_2m_ukmo_seamless": [14.0, 14.4, 14.6, 14.2],
                "temperature_2m_icon_seamless": [13.9, 14.3, 14.5, 14.0],
                "temperature_2m_ecmwf_aifs025_single": [14.1, 14.5, 14.8, 14.3]
              }
            }
        """.trimIndent()
        val usConsensus = WeatherConsensus.parseMultiModelConsensus(JsonParser.parseString(usJson).asJsonObject)!!
        assertEquals(WeatherConsensus.MODEL_GFS, usConsensus.promotedRegionalModelId)
        assertEquals(5, usConsensus.activeModels.size)
        assertEquals(0.28, WeatherConsensus.modelWeight(WeatherConsensus.MODEL_GFS, 0, usConsensus.promotedRegionalModelId), 0.001)
        assertEquals(0.24, WeatherConsensus.modelWeight(WeatherConsensus.MODEL_GFS, 12, usConsensus.promotedRegionalModelId), 0.001)

        // Germany (Berlin): best_match == icon_seamless (ICON-D2 2km) -> icon_seamless promoted to 0.28!
        val deJson = """
            {
              "hourly": {
                "time": ["2026-11-23T12:00","2026-11-23T13:00","2026-11-23T14:00","2026-11-23T15:00"],
                "temperature_2m_best_match": [4.2, 4.6, 4.9, 4.4],
                "temperature_2m_icon_seamless": [4.2, 4.6, 4.9, 4.4],
                "temperature_2m_ecmwf_ifs": [3.5, 3.8, 4.0, 3.7],
                "temperature_2m_gfs_seamless": [5.0, 5.2, 5.5, 5.1]
              }
            }
        """.trimIndent()
        val deConsensus = WeatherConsensus.parseMultiModelConsensus(JsonParser.parseString(deJson).asJsonObject)!!
        assertEquals(WeatherConsensus.MODEL_ICON, deConsensus.promotedRegionalModelId)
        assertEquals(0.28, WeatherConsensus.modelWeight(WeatherConsensus.MODEL_ICON, 0, deConsensus.promotedRegionalModelId), 0.001)

        // South Africa / Global: best_match == ecmwf_ifs -> NOT promoted (keeps normal ECMWF weight without double-counting)
        val zaJson = """
            {
              "hourly": {
                "time": ["2026-11-23T12:00","2026-11-23T13:00","2026-11-23T14:00","2026-11-23T15:00"],
                "temperature_2m_best_match": [25.0, 26.0, 26.5, 26.0],
                "temperature_2m_ecmwf_ifs": [25.0, 26.0, 26.5, 26.0],
                "temperature_2m_gfs_seamless": [27.0, 28.0, 28.5, 28.0]
              }
            }
        """.trimIndent()
        val zaConsensus = WeatherConsensus.parseMultiModelConsensus(JsonParser.parseString(zaJson).asJsonObject)!!
        assertNull(zaConsensus.promotedRegionalModelId)
        assertEquals(0.04, WeatherConsensus.modelWeight(WeatherConsensus.MODEL_GFS, 0, zaConsensus.promotedRegionalModelId), 0.001)
    }

    @Test
    fun testGlobalTimezonesIncludingHalfHour45MinAndRemoteOceanOffsets() {
        assertEquals(ZoneId.of("Asia/Kolkata"), WeatherMapper.parseZoneIdOrNull("Asia/Kolkata"))
        assertEquals(ZoneId.of("Asia/Kathmandu"), WeatherMapper.parseZoneIdOrNull("Asia/Kathmandu"))
        assertEquals(ZoneId.of("Pacific/Chatham"), WeatherMapper.parseZoneIdOrNull("Pacific/Chatham"))
        // Google remote-ocean format "UTC-11:00:00" & duration offset "20700s" (+05:45)
        val now = Instant.parse("2026-11-23T12:00:00Z")
        assertEquals(ZoneOffset.ofHours(-11), WeatherMapper.parseZoneIdOrNull("UTC-11:00:00")!!.rules.getOffset(now))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), WeatherMapper.parseZoneIdOrNull("UTC+05:30:00")!!.rules.getOffset(now))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), WeatherMapper.parseZoneIdOrNull("20700s")!!.rules.getOffset(now))
        assertEquals(ZoneOffset.ofHours(-11), WeatherMapper.parseZoneIdOrNull("-39600s")!!.rules.getOffset(now))
    }

    @Test
    fun testPolarDayPolarNightHighLatitudeSunsetAndNoFakeSunTimes() {
        // 1. McMurdo Antarctica 24h Polar Day (Open-Meteo 00:00 -> next day 00:00)
        val mcMurdoOm = WeatherMapper.resolveSunTimes(
            sunriseRaw = "2026-11-23T00:00",
            sunsetRaw = "2026-11-24T00:00",
            zoneId = ZoneId.of("Antarctica/McMurdo"),
            latitude = -77.8419,
            isDaytimeHint = true
        )
        assertEquals("24h Sun", mcMurdoOm.sunriseLabel)
        assertEquals("No Set", mcMurdoOm.sunsetLabel)
        assertTrue(mcMurdoOm.daylightStatusOverride!!.contains("Midnight sun"))

        // 2. Svalbard 24h Polar Night (Google empty sunEvents={})
        val svalbardPolarNight = WeatherMapper.resolveSunTimes(
            sunriseRaw = null,
            sunsetRaw = null,
            zoneId = ZoneId.of("Arctic/Longyearbyen"),
            latitude = 78.2232,
            isDaytimeHint = false
        )
        assertEquals("No Rise", svalbardPolarNight.sunriseLabel)
        assertEquals("24h Night", svalbardPolarNight.sunsetLabel)
        assertTrue(svalbardPolarNight.daylightStatusOverride!!.contains("Polar night"))

        // 3. Mid-latitude genuinely missing sun times -> "—", NEVER fake "06:15" / "18:15"!
        val missingMidLat = WeatherMapper.resolveSunTimes(
            sunriseRaw = null,
            sunsetRaw = null,
            zoneId = ZoneId.of("America/New_York"),
            latitude = 40.7128,
            isDaytimeHint = true
        )
        assertEquals("—", missingMidLat.sunriseLabel)
        assertEquals("—", missingMidLat.sunsetLabel)
        assertEquals("Sun times unavailable", missingMidLat.daylightStatusOverride)

        // 4. Reykjavik summer midnight-crossing sunset (02:55 -> 00:05)
        val reykjavikNoon = WeatherMapper.computeSolarProgress(
            sunriseHHmm = "02:55",
            sunsetHHmm = "00:05",
            zoneId = ZoneId.of("Atlantic/Reykjavik"),
            referenceTime = LocalTime.of(13, 30)
        )
        assertTrue("Expected near solar noon ~0.50, got $reykjavikNoon", reykjavikNoon in 0.45f..0.55f)
    }

    @Test
    fun testZeroFakeSensorPlaceholdersAndNoPhantomFogOrAqiAlerts() {
        val today = LocalDate.now(utcZone)
        val nowHour = ZonedDateTime.now(utcZone).hour
        val h0 = String.format(Locale.US, "%sT%02d:00", today, nowHour)
        val h1 = String.format(Locale.US, "%sT%02d:00", today, (nowHour + 1) % 24)

        // Open-Meteo feed with real -25°C temp, null second hourly temp, and missing pressure/visibility/humidity/sun/AQI
        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            current = OpenMeteoCurrent(
                temperature2m = -25.0,
                relativeHumidity2m = null,
                pressureMsl = null,
                surfacePressure = null,
                isDay = 0,
                weatherCode = 71
            ),
            hourly = OpenMeteoHourly(
                time = listOf(h0, h1),
                temperature2m = listOf(-25.0, null), // Null slot must be skipped, NOT turned into fake +20°C!
                relativeHumidity2m = listOf(null, null),
                visibility = listOf(null, null),
                weatherCode = listOf(71, 71)
            ),
            daily = OpenMeteoDaily(
                time = listOf(today.toString()),
                temperature2mMax = listOf(-20.0),
                temperature2mMin = listOf(-30.0),
                weatherCode = listOf(71)
            )
        )

        val result = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Yakutsk",
            latitude = 62.0355,
            longitude = 129.6755
        )

        // Hourly list must NOT contain fake +20°C from the null slot
        assertTrue("All hourly temps must stay sub-zero, got ${result.hourlyList.map { it.tempDegrees }}", result.hourlyList.all { it.tempDegrees < 0 })
        // Pressure, visibility, humidity, dew point, AQI must honestly report unavailable
        assertEquals(0, result.telemetry.pressureHpa)
        assertEquals("Unavailable", result.telemetry.pressureLabel)
        assertTrue(result.telemetry.visibilityKm < 0.0)
        assertEquals(-1, result.telemetry.humidityPercent)
        assertTrue(result.telemetry.isDewPointUnavailable)
        assertEquals("Unavailable", result.telemetry.aqiCategory)

        // Unavailable visibility (-1.0) must NEVER trigger a false Dense Fog alert
        assertFalse(WeatherNotificationScheduler.isDenseFogAlert(result.telemetry.copy(humidityPercent = 95)))
        // Unavailable AQI must NEVER trigger a Poor AQI alert; EU AQI > 60 MUST trigger!
        assertFalse(WeatherNotificationScheduler.isPoorAqiAlert(result.telemetry))
        assertTrue(
            WeatherNotificationScheduler.isPoorAqiAlert(
                result.telemetry.copy(aqiValue = 72, aqiCategory = "Poor", aqiScaleLabel = "EU AQI")
            )
        )
    }

    @Test
    fun testSnowWordingInShortTermAndMinutelyForecasts() {
        val snowyHours = listOf(
            HourlyItem("Now", -4, 15, null, true, "Overcast", precipitationMm = 0.0),
            HourlyItem("15:00", -5, 80, null, true, "Snow Showers", precipitationMm = 1.4)
        )
        val shortTerm = WeatherMapper.buildShortTermRainForecast(snowyHours)!!
        assertEquals("SHORT-TERM SNOW FORECAST", shortTerm.headlineLabel)
        assertEquals("Snow", shortTerm.precipitationLabel)
        assertTrue("Expected 'Snow likely' in '${shortTerm.summaryText}'", shortTerm.summaryText.startsWith("Snow likely"))

        val now = LocalDateTime.of(2026, 11, 23, 14, 0)
        val minutelySnow = OpenMeteoMinutely15(
            time = listOf("2026-11-23T14:02", "2026-11-23T14:17"),
            precipitation = listOf(0.4, 0.0),
            precipitationProbability = listOf(85, 10),
            weatherCode = listOf(73, 3)
        )
        val snowNowcast = WeatherMapper.parsePrecipitationNowcast(minutelySnow, utcZone, referenceNow = now)!!
        assertEquals("15-MIN SNOW NOWCAST", snowNowcast.headlineLabel)
        assertTrue(snowNowcast.summaryText.startsWith("Snow stopping"))
    }

    @Test
    fun testGlobalOfficialAlertsParseGoogleAgencyNwsAndEcccAndDeduplicate() {
        val now = Instant.parse("2026-11-23T12:00:00Z")

        // 1. Google Public Alert preserves real agency name & safetyRecommendations
        val googleResp = PublicAlertsResponse(
            weatherAlerts = listOf(
                GooglePublicAlert(
                    alertTitle = ConditionDescription("Moderate Snow Ice Warning"),
                    eventType = "SNOW_EVENT",
                    areaName = "Troms og Finnmark",
                    severity = "MODERATE",
                    expirationTime = "2026-11-24T12:00:00Z",
                    dataSource = AlertDataSource(publisher = "METEO_ALARM_NO", name = "Norwegian Meteorological Institute"),
                    safetyRecommendations = listOf(SafetyRecommendation("Use winter tires", "Allow extra travel time"))
                )
            )
        )
        val parsedGoogle = WeatherMapper.parseGooglePublicAlerts(googleResp, ZoneId.of("Europe/Oslo"), now)
        assertEquals(1, parsedGoogle.size)
        assertEquals("Norwegian Meteorological Institute", parsedGoogle.first().source)
        assertTrue(parsedGoogle.first().instruction.contains("Use winter tires"))

        // 2. NOAA NWS GeoJSON parsing
        val nwsJson = """
            {
              "features": [
                {
                  "properties": {
                    "status": "Actual",
                    "messageType": "Alert",
                    "event": "Winter Storm Warning",
                    "severity": "Severe",
                    "senderName": "NWS New York NY",
                    "areaDesc": "New York County; Kings County",
                    "expires": "2026-11-24T00:00:00-05:00",
                    "description": "Heavy snow expected.",
                    "instruction": "Avoid unnecessary travel."
                  }
                },
                {
                  "properties": {
                    "status": "Actual",
                    "messageType": "Cancel",
                    "event": "Canceled Advisory",
                    "severity": "Minor",
                    "expires": "2026-11-24T00:00:00-05:00"
                  }
                }
              ]
            }
        """.trimIndent()
        val nwsAlerts = GlobalOfficialAlertClient.parseNwsGeoJson(nwsJson, ZoneId.of("America/New_York"), now)
        assertEquals(1, nwsAlerts.size)
        assertEquals("Winter Storm Warning", nwsAlerts.first().title)
        assertEquals("NWS New York NY", nwsAlerts.first().source)
        assertEquals(SeverityRiskLevel.SEVERE, nwsAlerts.first().severity)

        // 3. Canada ECCC GeoJSON polygon matching
        val ecccJson = """
            {
              "features": [
                {
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-80.0, 43.4], [-79.0, 43.4], [-79.0, 44.0], [-80.0, 44.0], [-80.0, 43.4]]]
                  },
                  "properties": {
                    "alert_type": "warning",
                    "alert_name_en": "Freezing Rain Warning",
                    "area_name_en": "City of Toronto",
                    "alert_text_en": "Significant ice accretion expected.",
                    "expiry_datetime": "2026-11-24T12:00:00Z"
                  }
                }
              ]
            }
        """.trimIndent()
        val torontoAlerts = GlobalOfficialAlertClient.parseEcccGeoJson(ecccJson, 43.6532, -79.3832, ZoneId.of("America/Toronto"), now)
        assertEquals(1, torontoAlerts.size)
        assertEquals("Environment Canada", torontoAlerts.first().source)
        // Montreal is outside Toronto polygon
        val montrealAlerts = GlobalOfficialAlertClient.parseEcccGeoJson(ecccJson, 45.5017, -73.5673, ZoneId.of("America/Toronto"), now)
        assertTrue(montrealAlerts.isEmpty())

        // 4. Deduplication prevents duplicate warnings across Google + direct NWS
        val dupGoogleNws = listOf(
            OfficialWeatherAlert("Winter Storm Warning", SeverityRiskLevel.SEVERE, source = "National Weather Service")
        )
        val merged = WeatherMapper.mergeOfficialAlerts(dupGoogleNws, nwsAlerts)
        assertEquals(1, merged.size)
    }
}
