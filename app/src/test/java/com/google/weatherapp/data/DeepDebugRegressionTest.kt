package com.google.weatherapp.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.LocationSearchEngine
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.notification.WeatherNotificationHelper
import com.google.weatherapp.notification.WeatherNotificationScheduler
import com.google.weatherapp.notification.WeatherSummaryFormatter
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DeepDebugRegressionTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()
    private val hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US)

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
        WeatherNotificationHelper.areNotificationsEnabledOverride = null
        WeatherNotificationHelper.notificationDispatcherOverride = null
    }

    // =========================================================================
    // P0 #3: Environment Canada (ECCC) live API schema & null geometry safety
    // =========================================================================

    @Test
    fun testEcccLiveSchemaFiltersEndedAlertsParsesFieldsHandlesNullGeometryAndAvoidsRedundantSuffix() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val torontoZone = ZoneId.of("America/Toronto")
        val ecccLiveJson = """
            {
              "features": [
                {
                  "geometry": null,
                  "properties": {
                    "status_en": "ended",
                    "alert_type": "warning",
                    "alert_name_en": "Old Storm Warning",
                    "feature_name_en": "City of Toronto",
                    "province": "Ontario",
                    "expiration_datetime": "2026-11-24T12:00:00Z"
                  }
                },
                {
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-80.0, 43.4], [-79.0, 43.4], [-79.0, 44.0], [-80.0, 44.0], [-80.0, 43.4]]]
                  },
                  "properties": {
                    "status_en": "ended",
                    "alert_type": "warning",
                    "alert_name_en": "Ended Wind Warning",
                    "feature_name_en": "City of Toronto",
                    "province": "Ontario",
                    "expiration_datetime": "2026-11-24T12:00:00Z"
                  }
                },
                {
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-80.0, 43.4], [-79.0, 43.4], [-79.0, 44.0], [-80.0, 44.0], [-80.0, 43.4]]]
                  },
                  "properties": {
                    "status_en": "active",
                    "alert_type": "warning",
                    "alert_name_en": "Snowfall warning",
                    "feature_name_en": "City of Toronto",
                    "province": "Ontario",
                    "alert_text_en": "15 to 25 cm of snow expected.",
                    "expiration_datetime": "2026-11-24T05:00:00Z"
                  }
                },
                {
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-80.0, 43.4], [-79.0, 43.4], [-79.0, 44.0], [-80.0, 44.0], [-80.0, 43.4]]]
                  },
                  "properties": {
                    "status_en": "active",
                    "alert_type": "statement",
                    "alert_name_en": "Special weather statement",
                    "feature_name_en": "City of Toronto",
                    "province": "Ontario",
                    "alert_text_en": "Wintry travel conditions.",
                    "expiration_datetime": "2026-11-24T05:00:00Z"
                  }
                },
                {
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-80.0, 43.4], [-79.0, 43.4], [-79.0, 44.0], [-80.0, 44.0], [-80.0, 43.4]]]
                  },
                  "properties": {
                    "status_en": "active",
                    "alert_type": "warning",
                    "alert_name_en": "Expired Snowfall warning",
                    "feature_name_en": "City of Toronto",
                    "province": "Ontario",
                    "expiration_datetime": "2026-11-23T11:00:00Z"
                  }
                }
              ]
            }
        """.trimIndent()

        val alerts = GlobalOfficialAlertClient.parseEcccGeoJson(
            rawJson = ecccLiveJson,
            latitude = 43.6532,
            longitude = -79.3832,
            zoneId = torontoZone,
            now = now
        )
        assertEquals("Only the 2 active, unexpired alerts should remain", 2, alerts.size)

        val snowfall = alerts[0]
        assertEquals("Snowfall warning", snowfall.title)
        assertEquals(SeverityRiskLevel.SEVERE, snowfall.severity)
        assertEquals("City of Toronto, Ontario", snowfall.areaName)
        assertEquals("Redundant 'Warning' suffix must be omitted", "", snowfall.severityLabel)
        assertTrue("Expiry must be parsed from expiration_datetime", snowfall.expiresLabel.contains("00:00"))
        assertNotNull(snowfall.expiresEpochMs)

        val statement = alerts[1]
        assertEquals("Special weather statement", statement.title)
        assertEquals("Redundant 'Statement' suffix must be omitted", "", statement.severityLabel)

        // Verify WeatherMapper.buildSevereAlertInfo also does not produce "Snowfall warning (Warning)"
        val mapped = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = OpenMeteoForecastResponse(
                timezone = "America/Toronto",
                current = OpenMeteoCurrent(temperature2m = -3.0, isDay = 1, weatherCode = 73),
                daily = OpenMeteoDaily(time = listOf("2026-11-23"), temperature2mMax = listOf(-1.0), temperature2mMin = listOf(-6.0))
            ),
            omAqi = null,
            resolvedLocationName = "Toronto",
            globalOfficialAlerts = alerts,
            referenceNow = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, torontoZone)
        )
        assertEquals("Snowfall warning", mapped.alert.title)
        assertFalse(mapped.alert.title.contains("(Warning)", ignoreCase = true))
    }

    // =========================================================================
    // P0 #4: 00:00-06:59 Google meteorological day mismatch
    // =========================================================================

    @Test
    fun testEarlyMorningGoogleMeteorologicalDayAlignsTodayOpenMeteoAndHourlyChart() {
        val zone = ZoneId.of("Africa/Johannesburg")
        // 02:30 AM local time on 2026-11-24
        val nowAt0230 = ZonedDateTime.of(2026, 11, 24, 2, 30, 0, 0, zone)
        val yesterday = LocalDate.of(2026, 11, 23)
        val today = LocalDate.of(2026, 11, 24)
        val tomorrow = LocalDate.of(2026, 11, 25)

        val current = CurrentConditionsResponse(
            timeZone = TimeZoneInfo("Africa/Johannesburg"),
            temperature = Temperature("CELSIUS", 13.0),
            feelsLikeTemperature = Temperature("CELSIUS", 13.0),
            isDaytime = false
        )

        // Google daily at 02:30 AM returns forecastDays[0] = yesterday (31°C high), forecastDays[1] = today (19°C high)
        val gDays = listOf(
            ForecastDay(
                displayDate = DisplayDate(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth),
                maxTemperature = Temperature("CELSIUS", 31.0),
                minTemperature = Temperature("CELSIUS", 14.0),
                sunEvents = SunEvents("2026-11-23T05:10:00+02:00", "2026-11-23T18:35:00+02:00")
            ),
            ForecastDay(
                displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth),
                maxTemperature = Temperature("CELSIUS", 19.0),
                minTemperature = Temperature("CELSIUS", 11.0),
                sunEvents = SunEvents("2026-11-24T05:11:00+02:00", "2026-11-24T18:36:00+02:00")
            ),
            ForecastDay(
                displayDate = DisplayDate(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth),
                maxTemperature = Temperature("CELSIUS", 24.0),
                minTemperature = Temperature("CELSIUS", 12.0),
                sunEvents = SunEvents("2026-11-25T05:12:00+02:00", "2026-11-25T18:37:00+02:00")
            )
        )

        // Google hourly starts at 02:00 today
        val gHours = (2..23).map { h ->
            ForecastHour(
                displayDateTime = DisplayDateTime(today.year, today.monthValue, today.dayOfMonth, h),
                temperature = Temperature("CELSIUS", if (h == 14) 19.0 else 14.0)
            )
        }

        // Open-Meteo starts on today (2026-11-24)
        val omHourlyTimes = (0..23).map { today.atTime(it, 0).format(hourFmt) } +
            (0..23).map { tomorrow.atTime(it, 0).format(hourFmt) }
        val omHourlyTemps = (0..23).map { if (it == 14) 19.0 else 13.0 } +
            (0..23).map { if (it == 14) 24.0 else 12.0 }
        val omForecast = OpenMeteoForecastResponse(
            timezone = "Africa/Johannesburg",
            current = OpenMeteoCurrent(temperature2m = 13.0, isDay = 0),
            hourly = OpenMeteoHourly(time = omHourlyTimes, temperature2m = omHourlyTemps),
            daily = OpenMeteoDaily(
                time = listOf(today.toString(), tomorrow.toString()),
                temperature2mMax = listOf(19.0, 24.0),
                temperature2mMin = listOf(11.0, 12.0)
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(gDays),
            omForecast = omForecast,
            omAqi = null,
            referenceNow = nowAt0230
        )

        assertEquals(2, result.dailyList.size)
        assertEquals("Today", result.dailyList[0].dayLabel)
        assertEquals("11/24", result.dailyList[0].dateText)
        assertEquals("Today's high must be 19°C, NOT yesterday's 31°C", 19, result.dailyList[0].maxTemp)
        assertEquals("Tomorrow", result.dailyList[1].dayLabel)
        assertEquals("11/25", result.dailyList[1].dateText)
        assertEquals("Tomorrow must match OM day 1 (24°C), not duplicate day 0", 24, result.dailyList[1].maxTemp)
        assertTrue(
            "Today's DayDetailModalSheet hourly chart must NEVER be empty at 02:30 AM",
            result.dailyList[0].hourlyList.size == 24
        )
        assertEquals("05:11", result.telemetry.sunriseTime)

        // Freshness validators must accept both aligned data and early-morning legacy [yesterday, today]
        val localSource = WeatherLocalDataSource(FakeContext())
        assertNotNull(localSource.validateFreshForLocalDate(result, nowAt0230))
        assertTrue(WeatherNotificationScheduler.isForToday(result, nowAt0230))

        val legacyPreRollover = result.copy(
            dailyList = listOf(
                DailyItem("Today", "11/23", 14, 31, 0, null, "Clear", "", ""),
                DailyItem("Tomorrow", "11/24", 11, 19, 0, null, "Clear", "", ""),
                DailyItem("Wed", "11/25", 12, 24, 0, null, "Clear", "", "")
            )
        )
        assertNotNull(
            "Before 07:00, [yesterday, today] meteorological day must still be valid",
            localSource.validateFreshForLocalDate(legacyPreRollover, nowAt0230)
        )
        assertTrue(WeatherNotificationScheduler.isForToday(legacyPreRollover, nowAt0230))

        val afterRollover = ZonedDateTime.of(2026, 11, 24, 7, 15, 0, 0, zone)
        assertNull(
            "After 07:00, [yesterday, today] must expire",
            localSource.validateFreshForLocalDate(legacyPreRollover, afterRollover)
        )
        assertFalse(WeatherNotificationScheduler.isForToday(legacyPreRollover, afterRollover))
    }

    @Test
    fun testNotificationHelperResolvesTodayAndTomorrowByDateTextAcrossAllHazardMethods() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val yStr = "${yesterday.monthValue}/${yesterday.dayOfMonth}"
        val tStr = "${today.monthValue}/${today.dayOfMonth}"
        val tmStr = "${tomorrow.monthValue}/${tomorrow.dayOfMonth}"

        val capturedTitles = mutableListOf<String>()
        val context = FakeContext()
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, title, _, _, _, _ ->
            capturedTitles.add(title)
            true
        }

        // Yesterday was mild (20°C, UV 2); TODAY has heat (37°C), freeze (-3°C), gale (60 km/h), UV 10, and tomorrow drops -10°C
        val baseTelemetry = BentoTelemetry(
            windSpeedKmH = 15, windDirection = "NW", windGustKmH = 20,
            precipitationMm = 0.0, uvIndex = 2, uvCategory = "Low",
            humidityPercent = 40, dewPointDegrees = 5, pressureHpa = 1012,
            sunriseTime = "06:00", sunsetTime = "18:00"
        )
        val shiftedHeatUvWindDropData = WeatherDashboardData(
            hero = HeroWeather(30, 31, 37, 20, "Sunny", null, true, "Phoenix", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = emptyList(),
            dailyList = listOf(
                DailyItem("Yesterday", yStr, 15, 20, 0, null, "Mild", "", "", uvIndex = 2, dayWindSpeedKmH = 10),
                DailyItem("Today", tStr, 21, 37, 0, null, "Hot", "", "", uvIndex = 10, uvCategory = "Very High", dayWindSpeedKmH = 55),
                DailyItem("Tomorrow", tmStr, 14, 27, 0, null, "Cooler", "", "", uvIndex = 4)
            ),
            telemetry = baseTelemetry,
            timezone = "UTC"
        )

        assertTrue("Extreme heat must inspect today (37°C), not yesterday (20°C)",
            WeatherNotificationHelper.showExtremeHeatNotification(context, shiftedHeatUvWindDropData, currentLocalHour = 12))
        assertTrue("Gale wind must inspect today (55 km/h), not yesterday (10 km/h)",
            WeatherNotificationHelper.showGaleWindNotification(context, shiftedHeatUvWindDropData, currentLocalHour = 12))
        assertTrue("Extreme UV must inspect today (UV 10), not yesterday (UV 2)",
            WeatherNotificationHelper.showExtremeUvNotification(context, shiftedHeatUvWindDropData))
        assertTrue("Temp drop must compare today (37°C) vs tomorrow (27°C), not yesterday vs today",
            WeatherNotificationHelper.showTempDropNotification(context, shiftedHeatUvWindDropData))

        val shiftedFreezeData = shiftedHeatUvWindDropData.copy(
            dailyList = listOf(
                DailyItem("Yesterday", yStr, 10, 18, 0, null, "Mild", "", ""),
                DailyItem("Today", tStr, -3, 4, 0, null, "Freezing", "", "")
            )
        )
        assertTrue("Freeze hazard must inspect today (-3°C), not yesterday (10°C)",
            WeatherNotificationHelper.showFreezeHazardNotification(context, shiftedFreezeData))
        assertEquals(5, capturedTitles.size)
    }

    // =========================================================================
    // P0 #5: currentConditionsHistory.maxTemperature must NOT inflate morning high
    // =========================================================================

    @Test
    fun testRolling24hHistoryMaxDoesNotInflateColdFrontMorningHighOrAfternoonHourly() {
        val zone = ZoneId.of("UTC")
        val nowAt0900 = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone)
        val today = nowAt0900.toLocalDate()

        val current = CurrentConditionsResponse(
            timeZone = TimeZoneInfo("UTC"),
            temperature = Temperature("CELSIUS", 13.0),
            feelsLikeTemperature = Temperature("CELSIUS", 12.0),
            isDaytime = true,
            // Yesterday afternoon was 32°C — past 24h rolling peak!
            currentConditionsHistory = CurrentConditionsHistory(
                maxTemperature = Temperature("CELSIUS", 32.0),
                minTemperature = Temperature("CELSIUS", 11.0)
            )
        )
        val gDay = ForecastDay(
            displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth),
            maxTemperature = Temperature("CELSIUS", 18.0),
            minTemperature = Temperature("CELSIUS", 10.0)
        )
        val gHours = (9..20).map { h ->
            ForecastHour(
                displayDateTime = DisplayDateTime(today.year, today.monthValue, today.dayOfMonth, h),
                temperature = Temperature("CELSIUS", if (h == 14) 18.0 else 15.0)
            )
        }

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(listOf(gDay)),
            omForecast = null,
            omAqi = null,
            referenceNow = nowAt0900
        )

        assertEquals("Today's high must stay 18°C, NOT inflated by yesterday's 32°C 24h history", 18, result.hero.highDegrees)
        assertEquals(18, result.dailyList.first().maxTemp)
        assertTrue(
            "Hourly peak must stay <= 18°C, got ${result.hourlyList.map { it.tempDegrees }}",
            result.hourlyList.all { it.tempDegrees <= 18 }
        )
    }

    // =========================================================================
    // P0 #6 & P1 #11 & P2: Non-Latin search/dedup, comma-less qualifiers, no POI fallback, coord spacing
    // =========================================================================

    @Test
    fun testNonLatinPlaceSearchRankingDeduplicationAndBlankGuards() {
        assertEquals("東京", LocationSearchEngine.normalizeText("東京"))
        assertEquals("서울", LocationSearchEngine.normalizeText("서울"))
        assertEquals("москва", LocationSearchEngine.normalizeText("Москва"))
        assertEquals("دبي", LocationSearchEngine.normalizeText("دبي"))

        val tokyo = SavedLocation("東京", "東京都", "日本", 35.6762, 139.6503)
        val yokohama = SavedLocation("横浜", "神奈川県", "日本", 35.4437, 139.6380) // ~26km away
        val seoul = SavedLocation("서울", "서울특별시", "대한민국", 37.5665, 126.9780)
        val incheon = SavedLocation("인천", "인천광역시", "대한민국", 37.4563, 126.7052) // ~27km away
        val punctA = SavedLocation("---", "", "", 10.0, 10.0)
        val punctB = SavedLocation("!!!", "", "", 10.05, 10.05) // ~7.8km away

        assertFalse("Distinct Japanese cities must never collapse in isSamePlace", LocationSearchEngine.isSamePlace(tokyo, yokohama))
        assertFalse("Distinct Korean cities must never collapse in isSamePlace", LocationSearchEngine.isSamePlace(seoul, incheon))
        assertFalse("Blank normalized names must not falsely match", LocationSearchEngine.isSamePlace(punctA, punctB))

        // Search ranking for non-Latin query "東京"
        val omJapan = listOf(
            OpenMeteoGeoResult(1, "横浜", 35.4437, 139.6380, "日本", "JP", "神奈川県", null, "Asia/Tokyo", 3700000),
            OpenMeteoGeoResult(2, "東京", 35.6762, 139.6503, "日本", "JP", "東京都", null, "Asia/Tokyo", 14000000)
        )
        val ranked = LocationSearchEngine.mergeAndRankSearchResults("東京", omJapan, emptyList())
        assertEquals(2, ranked.size)
        assertEquals("東京", ranked.first().name)
    }

    @Test
    fun testCommaLessQualifiedSearchesAndRejectCommercialPoiFallback() {
        // 1. "Paris Texas" without comma ranks Paris, Texas above Paris, France
        val omParis = listOf(
            OpenMeteoGeoResult(1, "Paris", 48.8566, 2.3522, "France", "FR", "Île-de-France", null, "Europe/Paris", 2161000),
            OpenMeteoGeoResult(2, "Paris", 33.6609, -95.5555, "United States", "US", "Texas", null, "America/Chicago", 24476)
        )
        val parisTexas = LocationSearchEngine.mergeAndRankSearchResults("Paris Texas", omParis, emptyList())
        assertEquals("United States", parisTexas.first().country)
        assertEquals("Texas", parisTexas.first().adminArea)

        // 2. "London Ontario" without comma ranks London, Ontario above London, England
        val omLondon = listOf(
            OpenMeteoGeoResult(1, "London", 51.5074, -0.1278, "United Kingdom", "GB", "England", null, "Europe/London", 8900000),
            OpenMeteoGeoResult(2, "London", 42.9834, -81.2497, "Canada", "CA", "Ontario", null, "America/Toronto", 422000)
        )
        val londonOntario = LocationSearchEngine.mergeAndRankSearchResults("London Ontario", omLondon, emptyList())
        assertEquals("Canada", londonOntario.first().country)

        // 3. "San Jose California" (multi-word city + state)
        val omSanJose = listOf(
            OpenMeteoGeoResult(1, "San Jose", 9.9281, -84.0907, "Costa Rica", "CR", "San José", null, "America/Costa_Rica", 335000),
            OpenMeteoGeoResult(2, "San Jose", 37.3382, -121.8863, "United States", "US", "California", null, "America/Los_Angeles", 1013000)
        )
        val sanJoseCa = LocationSearchEngine.mergeAndRankSearchResults("San Jose California", omSanJose, emptyList())
        assertEquals("California", sanJoseCa.first().adminArea)

        // 4. Photon commercial POIs (restaurants, pubs, shops, offices, hairdressers) must NEVER be re-admitted
        val noisyPois = listOf(
            PhotonFeature(PhotonGeometry(listOf(-0.12, 51.50)), PhotonProperties(name = "Joe's Pub", osmKey = "amenity", osmValue = "pub", postcode = "WC2N 5DU")),
            PhotonFeature(PhotonGeometry(listOf(-0.13, 51.51)), PhotonProperties(name = "Cut & Style", osmKey = "shop", osmValue = "hairdresser", postcode = "W1D 3QU")),
            PhotonFeature(PhotonGeometry(listOf(-0.14, 51.52)), PhotonProperties(name = "Acme Corp", osmKey = "office", osmValue = "company", postcode = "NW1 5LJ")),
            PhotonFeature(PhotonGeometry(listOf(-0.15, 51.53)), PhotonProperties(name = "Pasta Palace", osmKey = "amenity", osmValue = "restaurant", postcode = "NW1 6XE"))
        )
        val poiResults = LocationSearchEngine.mergeAndRankSearchResults("Joe's Pub", emptyList(), noisyPois)
        assertTrue("Commercial POIs must be filtered out and never re-admitted", poiResults.isEmpty())

        // 5. Coordinate regex with optional space before comma
        val spacedCoord = LocationSearchEngine.parseCoordinateQuery("40.71 , -74.01")
        assertNotNull(spacedCoord)
        assertEquals(40.71, spacedCoord!!.first, 0.0001)
        assertEquals(-74.01, spacedCoord.second, 0.0001)

        val spacedHemCoord = LocationSearchEngine.parseCoordinateQuery("40.71° N , 74.01° W")
        assertNotNull(spacedHemCoord)
        assertEquals(40.71, spacedHemCoord!!.first, 0.0001)
        assertEquals(-74.01, spacedHemCoord.second, 0.0001)
    }

    // =========================================================================
    // P1 #8: US/Canada alert expiry times formatted in location timezone
    // =========================================================================

    @Test
    fun testUsAndCanadaAlertExpiryReformattedToLocationTimezoneNotDeviceTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Johannesburg"))
        val now = Instant.parse("2026-11-23T12:00:00Z")
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
                    "areaDesc": "New York County",
                    "expires": "2026-11-24T00:00:00-05:00",
                    "description": "Heavy snow expected."
                  }
                }
              ]
            }
        """.trimIndent()

        // Initially parsed with device timezone (Africa/Johannesburg -> 07:00), as happens when fallbackTimezone is null
        val rawNwsInDeviceTz = GlobalOfficialAlertClient.parseNwsGeoJson(nwsJson, ZoneId.of("Africa/Johannesburg"), now)
        assertTrue(rawNwsInDeviceTz.first().expiresLabel.contains("07:00"))

        val mapped = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = OpenMeteoForecastResponse(
                timezone = "America/New_York",
                current = OpenMeteoCurrent(temperature2m = -2.0, isDay = 1, weatherCode = 73),
                daily = OpenMeteoDaily(time = listOf("2026-11-23"), temperature2mMax = listOf(0.0), temperature2mMin = listOf(-5.0))
            ),
            omAqi = null,
            resolvedLocationName = "New York",
            globalOfficialAlerts = rawNwsInDeviceTz,
            referenceNow = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, ZoneId.of("America/New_York"))
        )

        val localizedAlert = mapped.alert.officialAlerts.first()
        assertTrue("Expiry must be reformatted to NY local 00:00, got '${localizedAlert.expiresLabel}'",
            localizedAlert.expiresLabel.contains("00:00"))
        assertFalse("Must not remain in Johannesburg 07:00", localizedAlert.expiresLabel.contains("07:00"))
    }

    // =========================================================================
    // P1 #9: Official alert misattribution & WeatherSummaryFormatter storm hijack
    // =========================================================================

    @Test
    fun testStormWatchDoesNotMisattributeLowerOfficialAlertAndOfficialSummaryNotHijackedByLowStormRisk() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 23, 12, 0, 0, 0, zone)
        val today = now.toLocalDate()

        // 1. Storm tracker HIGH (THUNDERSTORM WATCH, 45%) beats lower-severity MODERATE Wind Advisory
        val windAdvisory = OfficialWeatherAlert(
            title = "Wind Advisory",
            severity = SeverityRiskLevel.MODERATE,
            source = "NWS New York NY",
            areaName = "Kings County",
            instruction = "Secure loose patio furniture.",
            expiresLabel = "Mon 18:00"
        )
        val stormHours = listOf(
            ForecastHour(
                displayDateTime = DisplayDateTime(today.year, today.monthValue, today.dayOfMonth, 14),
                temperature = Temperature("CELSIUS", 22.0),
                thunderstormProbability = 45
            )
        )
        val mappedStormWins = WeatherMapper.mapEnsembleDashboardData(
            current = CurrentConditionsResponse(timeZone = TimeZoneInfo("America/New_York"), temperature = Temperature("CELSIUS", 22.0)),
            hourly = HourlyForecastResponse(stormHours),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth)))),
            omForecast = null,
            omAqi = null,
            globalOfficialAlerts = listOf(windAdvisory),
            referenceNow = now
        )
        assertEquals("THUNDERSTORM WATCH", mappedStormWins.alert.title)
        assertNull("Storm Watch must NOT inherit Wind Advisory's official sourceLabel", mappedStormWins.alert.sourceLabel)
        assertEquals("Secondary official alert must still be preserved for separate attribution", 1, mappedStormWins.alert.officialAlerts.size)

        // 2. Official non-storm alert (Winter Storm Warning) + minor 10% storm probability must NOT be hijacked
        val officialWinterStorm = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.SEVERE,
            thunderstormProbability = 10,
            title = "Winter Storm Warning",
            subtitle = "Area: New York County. Heavy snow expected. Active until Tue 00:00.",
            isVisible = true,
            peakHourLabel = "15:00",
            officialAlerts = listOf(
                OfficialWeatherAlert(
                    title = "Winter Storm Warning",
                    severity = SeverityRiskLevel.SEVERE,
                    source = "NWS New York NY",
                    areaName = "New York County",
                    instruction = "Avoid unnecessary travel."
                )
            ),
            sourceLabel = "NWS New York NY"
        )
        val summary = WeatherSummaryFormatter.generateSevereAlertSummary(officialWinterStorm, "New York")
        assertTrue("Official warning must keep its real details", summary.conciseText.contains("Heavy snow expected"))
        assertFalse("Must NOT overwrite with 'peaking around 15:00 (10% risk)'", summary.conciseText.contains("10% risk"))
        assertFalse("Must NOT append 'Thunderstorm probability: 10%'", summary.expandedDetails.contains("Thunderstorm probability: 10%"))
        assertTrue("Should cite issuing agency", summary.expandedDetails.contains("NWS New York NY"))
    }

    // =========================================================================
    // P1 #10: Day 10 overnight low warm bias & 16h truncated rain total
    // =========================================================================

    @Test
    fun testDay10OvernightLowAnd24hRainDoNotUseTruncatedEveningOnlyWindow() {
        val zone = ZoneId.of("UTC")
        val day0 = LocalDate.of(2026, 11, 20)
        val day9 = day0.plusDays(9) // Day 10 (0-indexed 9), final day in 10-day forecast

        val times = mutableListOf<String>()
        val temps = mutableListOf<Double>()
        val precips = mutableListOf<Double>()
        for (d in 0..9) {
            val date = day0.plusDays(d.toLong())
            for (h in 0..23) {
                times.add(date.atTime(h, 0).format(hourFmt))
                // Evening 19..23 stays warm (20°C), while pre-dawn 00..06 is cold (9°C)
                temps.add(
                    when {
                        h <= 6 -> 9.0
                        h in 7..18 -> 24.0
                        else -> 20.0
                    }
                )
                // Only 0.1mm/h during 08..23 (1.6mm across 16h), while daily sum includes 12mm overnight = 14.0mm
                precips.add(if (h in 8..23) 0.1 else 0.0)
            }
        }

        val dailyDates = (0..9).map { day0.plusDays(it.toLong()).toString() }
        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            current = OpenMeteoCurrent(temperature2m = 20.0, isDay = 1),
            hourly = OpenMeteoHourly(time = times, temperature2m = temps, precipitation = precips),
            daily = OpenMeteoDaily(
                time = dailyDates,
                temperature2mMax = List(10) { 24.0 },
                temperature2mMin = List(10) { 9.0 },
                precipitationSum = List(10) { 14.0 }
            )
        )

        val result = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "London",
            referenceNow = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, zone)
        )

        assertEquals(10, result.dailyList.size)
        val day10 = result.dailyList[9]
        assertEquals("Day 10 low must fall back to 9°C, NOT warm 20°C evening-only min", 9, day10.minTemp)
        assertEquals("Day 10 precip must fall back to full 24h daily 14.0 mm, NOT truncated 1.6 mm", 14.0, day10.precipitationMm, 0.2)

        // Day 0 still has full next-day 00..07 coverage, so its aligned overnight low is 9°C
        assertEquals(9, result.dailyList[0].minTemp)
    }

    // =========================================================================
    // P1 #13: Legacy savedLocations sanitization & thread-safe cache map writes
    // =========================================================================

    @Test
    fun testLegacySavedLocationsSanitizationStripsGpsAndDuplicatesAndConcurrentCacheWritesNeverDropEntries() {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)
        val localSource = WeatherLocalDataSource(context)

        // Inject unsanitized legacy savedLocations with isCurrentGps=true and duplicate coordinates/cities
        val legacyJson = """
            [
              {"name":"Current GPS","adminArea":"","country":"","latitude":-33.9249,"longitude":18.4241,"isCurrentGps":true},
              {"name":"London","adminArea":"England","country":"United Kingdom","latitude":51.5074,"longitude":-0.1278,"isCurrentGps":false},
              {"name":"London Duplicate","adminArea":"England","country":"United Kingdom","latitude":51.5074,"longitude":-0.1278,"isCurrentGps":false},
              {"name":"Tokyo","adminArea":"Tokyo","country":"Japan","latitude":35.6762,"longitude":139.6503,"isCurrentGps":false}
            ]
        """.trimIndent()
        prefs.edit().putString("saved_locations_json", legacyJson).apply()

        val loaded = localSource.getSavedLocations()
        assertEquals(2, loaded.size)
        assertTrue("No entry with isCurrentGps == true may survive", loaded.none { it.isCurrentGps })
        assertEquals(listOf("London", "Tokyo"), loaded.map { it.name })

        // Concurrent saves across 8 threads must retain all 8 cities without clobbering each other
        val today = LocalDate.now(ZoneId.of("UTC"))
        val todayStr = "${today.monthValue}/${today.dayOfMonth}"
        val cities = (0 until 8).map { i ->
            SavedLocation("City$i", "", "Country", 10.0 + i * 5.0, 20.0 + i * 5.0, timezone = "UTC")
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = cities.map { city ->
                Callable {
                    val data = WeatherDashboardData(
                        hero = HeroWeather(20, 20, 24, 14, "Clear", null, true, city.name, SkyThemeType.CLEAR_DAY),
                        alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
                        hourlyList = emptyList(),
                        dailyList = listOf(DailyItem("Today", todayStr, 14, 24, 0, null, "Clear", "", "")),
                        telemetry = BentoTelemetry(10, "N", 12, 0.0, 3, "Low", 50, 10, 1015, "06:00", "18:00"),
                        timezone = "UTC",
                        latitude = city.latitude,
                        longitude = city.longitude
                    )
                    localSource.saveWeather(data, targetLocation = city, updateActiveSlot = false)
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val allEntries = localSource.getAllCachedWeatherEntries()
        assertEquals("All 8 concurrently written cities must persist in KEY_LOCATION_CACHE_MAP", 8, allEntries.size)
    }

    // =========================================================================
    // P2 Polish: Snow wording, nowcast timezone label, OM fallback nulls, snowQpf, Unicode alert dedup, Locale.US clock
    // =========================================================================

    @Test
    fun testP2PolishFixesSnowWordingNowcastTzOmFallbackNullsSnowQpfUnicodeAlertsAndArabicLocale() {
        // 1. Snow wording: no "rain" or "umbrella"
        val snowNowcast = PrecipitationNowcast(
            summaryText = "Snow starting in ~20 min (~2.0 mm/h)",
            isPrecipitationImminent = true,
            nextPrecipitationOnsetMinutes = 20,
            maxRateMmH = 2.0,
            isHighRes15Min = true,
            headlineLabel = "15-MIN SNOW NOWCAST",
            precipitationLabel = "Snow"
        )
        val snowSummary = WeatherSummaryFormatter.generateRainNowcastSummary(snowNowcast, "Oslo")
        assertEquals("Snow alert • Oslo", snowSummary.title)
        assertFalse("Snow alert must never suggest an umbrella: '${snowSummary.conciseText}'", snowSummary.conciseText.contains("umbrella", ignoreCase = true))
        assertTrue(snowSummary.conciseText.contains("Moderate snow"))

        val snowStop = WeatherSummaryFormatter.generateRainCessationSummary(snowNowcast.copy(rainCessationMinutes = 15), "Oslo")
        assertEquals("Snow Clearing Soon • Oslo", snowStop.title)
        assertTrue(snowStop.conciseText.startsWith("Snow stopping"))

        val snowyDay = DailyItem("Today", "1/15", -8, -2, 80, null, "Snow Showers", "", "", precipitationType = "SNOW")
        val snowyHours = listOf(HourlyItem("14:00", -4, 80, null, true, "Snow Showers", precipitationMm = 1.5))
        val morningSnow = WeatherSummaryFormatter.generateMorningSummary(snowyDay, snowyHours, "Oslo")
        assertTrue("Expected 'snow likely', got '${morningSnow.conciseText}'", morningSnow.conciseText.contains("snow likely"))
        assertTrue("Expected 'Snow: 80%' in '${morningSnow.expandedDetails}'", morningSnow.expandedDetails.contains("Snow: 80%"))

        // 2. parsePrecipitationNowcast formats timeLabel from timezone-converted slotTime (not raw UTC string!)
        val nyZone = ZoneId.of("America/New_York")
        val offsetMinutely = OpenMeteoMinutely15(
            time = listOf("2026-11-23T12:00:00Z", "2026-11-23T12:15:00Z"),
            precipitation = listOf(0.5, 0.2),
            precipitationProbability = listOf(80, 70),
            weatherCode = listOf(61, 61)
        )
        val parsedNowcast = WeatherMapper.parsePrecipitationNowcast(
            minutely15 = offsetMinutely,
            zoneId = nyZone,
            referenceNow = LocalDateTime.of(2026, 11, 23, 6, 45)
        )!!
        assertEquals("12:00Z in America/New_York (UTC-5) must format as 07:00, not 12:00", "07:00", parsedNowcast.intervals[0].timeLabel)
        assertEquals("07:15", parsedNowcast.intervals[1].timeLabel)

        // 3. Open-Meteo fallback: null wind direction -> "—", null isDay/weatherCode falls back to hourly slot
        val refNow = ZonedDateTime.of(2026, 11, 23, 22, 0, 0, 0, ZoneId.of("UTC"))
        val omNullFields = OpenMeteoForecastResponse(
            timezone = "UTC",
            current = OpenMeteoCurrent(temperature2m = 5.0, isDay = null, weatherCode = null, windDirection10m = null),
            hourly = OpenMeteoHourly(
                time = listOf("2026-11-23T22:00"),
                temperature2m = listOf(5.0),
                isDay = listOf(0),
                weatherCode = listOf(73),
                windDirection10m = listOf(null)
            ),
            daily = OpenMeteoDaily(time = listOf("2026-11-23"), temperature2mMax = listOf(7.0), temperature2mMin = listOf(2.0))
        )
        val omFallback = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omNullFields,
            omAqi = null,
            resolvedLocationName = "Reykjavik",
            referenceNow = refNow
        )
        assertEquals("Null wind direction must display '—', never fake 'N'", "—", omFallback.telemetry.windDirection)
        assertFalse("Null current isDay must fall back to hourly slot (night)", omFallback.hero.isDaytime)
        assertEquals("Null current weatherCode must fall back to hourly slot (Snow Showers)", "Snow Showers", omFallback.hero.conditionText)

        // 4. extractGooglePrecipMm must never substitute raw snow depth when qpf is explicitly 0.0
        val explicitZeroQpf = Precipitation(qpf = PrecipQuantity(quantity = 0.0), snowQpf = PrecipQuantity(quantity = 25.0))
        assertEquals(0.0, WeatherMapper.extractGooglePrecipMm(explicitZeroQpf)!!, 0.001)
        val missingQpfOnlySnow = Precipitation(qpf = null, snowQpf = PrecipQuantity(quantity = 20.0))
        assertEquals("Missing qpf should convert snow depth via 10:1 liquid equivalent", 2.0, WeatherMapper.extractGooglePrecipMm(missingQpfOnlySnow)!!, 0.001)

        // 5. Unicode-safe dedup in mergeOfficialAlerts preserves distinct non-Latin alerts
        val jpAlerts = listOf(
            OfficialWeatherAlert(title = "大雨警報", severity = SeverityRiskLevel.SEVERE, source = "JMA"),
            OfficialWeatherAlert(title = "暴風警報", severity = SeverityRiskLevel.HIGH, source = "JMA")
        )
        val mergedJp = WeatherMapper.mergeOfficialAlerts(jpAlerts)
        assertEquals("Distinct Japanese alerts must NOT collapse into one", 2, mergedJp.size)

        // 6. Arabic default locale must still produce ASCII "%02d:00" clock labels
        Locale.setDefault(Locale("ar", "EG"))
        val arNow = ZonedDateTime.of(2026, 11, 23, 13, 0, 0, 0, ZoneId.of("UTC"))
        val arResult = WeatherMapper.mapEnsembleDashboardData(
            current = CurrentConditionsResponse(timeZone = TimeZoneInfo("UTC"), temperature = Temperature("CELSIUS", 20.0)),
            hourly = HourlyForecastResponse(
                listOf(
                    ForecastHour(displayDateTime = DisplayDateTime(2026, 11, 23, 13), temperature = Temperature("CELSIUS", 20.0)),
                    ForecastHour(displayDateTime = DisplayDateTime(2026, 11, 23, 14), temperature = Temperature("CELSIUS", 21.0))
                )
            ),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(2026, 11, 23)))),
            omForecast = null,
            omAqi = null,
            referenceNow = arNow
        )
        assertEquals("14:00", arResult.hourlyList[1].hourLabel)
    }

    @Test
    fun testAuditRefinementsUnicodeCombiningMarksStrictPhotonFilteringFrenchEcccUtcDateConvCorruptedJsonSnowSummaryAndSolarRefTime() {
        // 1. Unicode combining marks preserved (Japanese dakuten, Hindi vowel signs, Thai, Arabic, Korean) while Latin accents strip cleanly
        assertEquals("がっこう", LocationSearchEngine.normalizeText("がっこう"))
        assertEquals("मुंबई", LocationSearchEngine.normalizeText("मुंबई"))
        assertEquals("กรุงเทพมหานคร", LocationSearchEngine.normalizeText("กรุงเทพมหานคร"))
        assertEquals("sao paulo", LocationSearchEngine.normalizeText("São Paulo"))
        assertEquals("zurich", LocationSearchEngine.normalizeText("Zürich"))

        // 2. Strict Photon filtering rejects industrial/religious landuse, local parks/aquatic centers, political ridings even with type="locality"
        val noisyLivePhoton = listOf(
            PhotonFeature(
                PhotonGeometry(listOf(-95.55, 33.66)),
                PhotonProperties(name = "Paris", state = "Texas", country = "United States", countryCode = "US", osmKey = "place", osmValue = "town", type = "city")
            ),
            PhotonFeature(
                PhotonGeometry(listOf(-95.54, 33.67)),
                PhotonProperties(name = "Coca-Cola of Paris", city = "Paris", state = "TX", country = "United States", countryCode = "US", osmKey = "landuse", osmValue = "industrial", type = "locality")
            ),
            PhotonFeature(
                PhotonGeometry(listOf(-95.53, 33.65)),
                PhotonProperties(name = "First Baptist Church of Paris", city = "Paris", state = "Texas", country = "United States", countryCode = "US", osmKey = "landuse", osmValue = "religious", type = "other")
            ),
            PhotonFeature(
                PhotonGeometry(listOf(-95.56, 33.66)),
                PhotonProperties(name = "Paris Aquatic Center", city = "Paris", state = "TX", country = "United States", countryCode = "US", osmKey = "leisure", osmValue = "park", type = "other")
            ),
            PhotonFeature(
                PhotonGeometry(listOf(-81.20, 42.98)),
                PhotonProperties(name = "London—Fanshawe", city = "London", state = "Ontario", country = "Canada", countryCode = "CA", osmKey = "boundary", osmValue = "political", type = "other")
            )
        )
        val omParis = listOf(
            OpenMeteoGeoResult(1, "Paris", 48.8566, 2.3522, "France", "FR", "Île-de-France", null, "Europe/Paris", 2161000)
        )
        val parisTxResults = LocationSearchEngine.mergeAndRankSearchResults("Paris TX", omParis, noisyLivePhoton)
        assertEquals("Paris, Texas must rank #1 for abbreviated 'Paris TX'", "Texas", parisTxResults.first().adminArea)
        assertTrue("Industrial/religious/park/political entries must be filtered out",
            parisTxResults.none { it.name.contains("Coca-Cola") || it.name.contains("Church") || it.name.contains("Aquatic") || it.name.contains("Fanshawe") })

        // 3. ECCC French-only status_fr ended/cancelled alerts filtered out + Arabic locale still yields ASCII expiry time
        Locale.setDefault(Locale("ar", "EG"))
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val quebecJson = """
            {
              "features": [
                {
                  "geometry": null,
                  "properties": {
                    "status_fr": "terminée",
                    "alert_type": "warning",
                    "alert_name_en": "Ended Freezing Rain Warning",
                    "feature_name_en": "Montréal",
                    "province": "Quebec",
                    "expiration_datetime": "2026-11-24T12:00:00Z"
                  }
                },
                {
                  "geometry": null,
                  "properties": {
                    "status_fr": "annulée",
                    "alert_type": "watch",
                    "alert_name_en": "Cancelled Snow Squall Watch",
                    "feature_name_en": "Montréal",
                    "province": "Quebec",
                    "expiration_datetime": "2026-11-24T12:00:00Z"
                  }
                },
                {
                  "geometry": null,
                  "properties": {
                    "status_en": "active",
                    "status_fr": "en vigueur",
                    "alert_type": "warning",
                    "alert_name_en": "Freezing rain warning",
                    "feature_name_en": "Montréal",
                    "province": "Quebec",
                    "expiration_datetime": "2026-11-24T05:00:00Z"
                  }
                }
              ]
            }
        """.trimIndent()
        val qcAlerts = GlobalOfficialAlertClient.parseEcccGeoJson(quebecJson, 45.5017, -73.5673, ZoneId.of("America/Toronto"), now)
        assertEquals("French ended/cancelled alerts must be filtered out", 1, qcAlerts.size)
        assertEquals("Freezing rain warning", qcAlerts.first().title)
        assertTrue("Expiry time must use ASCII digits under Arabic locale, got '${qcAlerts.first().expiresLabel}'",
            qcAlerts.first().expiresLabel.contains("00:00"))
        Locale.setDefault(originalLocale)

        // 4. validateFreshForLocalDate converts UTC referenceNow to the location's timezone (Asia/Tokyo)
        val localSource = WeatherLocalDataSource(FakeContext())
        val tokyoNov24Data = WeatherDashboardData(
            hero = HeroWeather(14, 14, 18, 9, "Clear", null, true, "Tokyo", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = emptyList(),
            dailyList = listOf(DailyItem("Today", "11/24", 9, 18, 0, null, "Clear", "", "")),
            telemetry = BentoTelemetry(10, "N", 12, 0.0, 2, "Low", 50, 5, 1015, "06:25", "16:30"),
            timezone = "Asia/Tokyo"
        )
        // 2026-11-23T22:00:00Z is already 2026-11-24T07:00+09:00 in Asia/Tokyo!
        val utcRefNow = ZonedDateTime.of(2026, 11, 23, 22, 0, 0, 0, ZoneId.of("UTC"))
        assertNotNull(
            "UTC 22:00 on Nov 23 is Nov 24 07:00 in Tokyo, so Tokyo 11/24 data must validate as fresh",
            localSource.validateFreshForLocalDate(tokyoNov24Data, utcRefNow)
        )

        // 5. sanitizeSavedLocations drops corrupted legacy JSON (missing name, NaN/out-of-range coords) without NPE
        val corruptedPrefs = FakeSharedPreferences()
        val corruptedSource = WeatherLocalDataSource(FakeContext(corruptedPrefs))
        corruptedPrefs.edit().putString(
            "saved_locations_json",
            """
                [
                  {"latitude":51.5074,"longitude":-0.1278,"isCurrentGps":false},
                  {"name":"   ","latitude":51.5074,"longitude":-0.1278,"isCurrentGps":false},
                  {"name":"BadLat","latitude":195.0,"longitude":10.0,"isCurrentGps":false},
                  {"name":"Oslo","latitude":59.9139,"longitude":10.7522,"isCurrentGps":false}
                ]
            """.trimIndent()
        ).apply()
        val sanitized = corruptedSource.getSavedLocations()
        assertEquals(1, sanitized.size)
        assertEquals("Oslo", sanitized.first().name)
        assertEquals("", sanitized.first().adminArea)
        assertEquals("", sanitized.first().country)

        // 6. Overcast sub-zero snow day must say "snow likely" (NOT "rain likely"), and blank official storm subtitle must not end with ": ."
        val coldToday = DailyItem("Today", "1/15", -9, -3, 20, null, "Cloudy", "", "")
        val coldSnowTomorrow = DailyItem("Tomorrow", "1/16", -11, -4, 75, null, "Overcast", "", "", precipitationType = "SNOW")
        val tomorrowSnowSummary = WeatherSummaryFormatter.generateTomorrowSummary(coldToday, coldSnowTomorrow, "Oslo")
        assertTrue("Expected 'snow likely', got '${tomorrowSnowSummary.conciseText}'", tomorrowSnowSummary.conciseText.contains("snow likely"))
        assertFalse("Must never say 'rain likely' on -4°C snow day", tomorrowSnowSummary.conciseText.contains("rain likely"))

        val blankSubtitleOfficialStorm = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.SEVERE,
            thunderstormProbability = 80,
            title = "Severe Thunderstorm Warning",
            subtitle = "   ",
            isVisible = true,
            officialAlerts = listOf(
                OfficialWeatherAlert(
                    title = "Severe Thunderstorm Warning",
                    severity = SeverityRiskLevel.SEVERE,
                    source = "NWS Dallas",
                    areaName = "Dallas County"
                )
            ),
            sourceLabel = "NWS Dallas"
        )
        val stormSummary = WeatherSummaryFormatter.generateSevereAlertSummary(blankSubtitleOfficialStorm, "Dallas")
        assertFalse("Must never contain empty ': .' punctuation: '${stormSummary.conciseText}'", stormSummary.conciseText.contains(": ."))
        assertFalse("Expanded details must never contain ': .': '${stormSummary.expandedDetails}'", stormSummary.expandedDetails.contains(": ."))

        // 7. resolveSunTimes via WeatherMapper respects referenceNow for solarProgress
        val utcNoon = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("UTC"))
        val noonMapped = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = OpenMeteoForecastResponse(
                timezone = "UTC",
                current = OpenMeteoCurrent(temperature2m = 22.0, isDay = 1),
                daily = OpenMeteoDaily(
                    time = listOf("2026-06-21"),
                    temperature2mMax = listOf(25.0),
                    temperature2mMin = listOf(15.0),
                    sunrise = listOf("2026-06-21T06:00"),
                    sunset = listOf("2026-06-21T18:00")
                )
            ),
            omAqi = null,
            resolvedLocationName = "Greenwich",
            referenceNow = utcNoon
        )
        assertEquals("Solar progress at exact midpoint 12:00 between 06:00 and 18:00 must be 0.5", 0.5f, noonMapped.telemetry.sunProgress, 0.02f)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val target: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val updates = LinkedHashMap<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) updates[key] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) updates[key] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                synchronized(target) {
                    if (clearAll) target.clear()
                    updates.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
                }
            }
        }
    }

    private class FakeContext(private val prefs: SharedPreferences = FakeSharedPreferences()) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }
}
