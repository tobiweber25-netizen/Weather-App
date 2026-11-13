package com.google.weatherapp.data

import com.google.gson.Gson
import com.google.weatherapp.data.model.*
import org.junit.Assert.*
import org.junit.Test

class WeatherCacheTest {

    @Test
    fun testWeatherDashboardDataSerializationAndDeserialization() {
        val dailyItem = DailyItem(
            dayLabel = "Tomorrow",
            dateText = "9/30",
            minTemp = 14,
            maxTemp = 28,
            rainProbability = 40,
            iconUri = "https://maps.gstatic.com/weather/v1/isolated_tstorms.png",
            conditionText = "Thunderstorms",
            morningWind = "AM: 14 km/h NW",
            afternoonWind = "PM: 22 km/h SE",
            fullDateString = "Wednesday, Sep 30",
            feelsLikeMaxTemp = 29,
            feelsLikeMinTemp = 13,
            precipitationMm = 4.2,
            uvIndex = 7,
            uvCategory = "High",
            humidityPercent = 65,
            thunderstormProbability = 55,
            cloudCoverPercent = 40,
            sunriseTime = "06:12",
            sunsetTime = "18:05",
            dayConditionText = "Isolated Thunderstorms",
            dayIconUri = "day_uri",
            nightConditionText = "Scattered Showers",
            nightIconUri = "night_uri",
            dayWindSpeedKmH = 14,
            dayWindDirection = "NW",
            nightWindSpeedKmH = 22,
            nightWindDirection = "SE"
        )

        val sample = WeatherDashboardData(
            hero = HeroWeather(25, 26, 28, 14, "Mostly Sunny", "uri", true, "Johannesburg", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 10, "Title", "Subtitle", false),
            hourlyList = emptyList(),
            dailyList = listOf(dailyItem),
            telemetry = BentoTelemetry(10, "N", 15, 0.0, 5, "Moderate", 50, 10, 1010, "06:00", "18:00")
        )

        val gson = Gson()
        val json = gson.toJson(sample)
        val deserialized = gson.fromJson(json, WeatherDashboardData::class.java)

        assertNotNull("Deserialized data must not be null", deserialized)
        assertEquals(sample.hero.tempDegrees, deserialized.hero.tempDegrees)
        assertEquals(sample.hero.locationName, deserialized.hero.locationName)
        assertEquals(1, deserialized.dailyList.size)

        val deserializedDaily = deserialized.dailyList.first()
        assertEquals(dailyItem.dayLabel, deserializedDaily.dayLabel)
        assertEquals(dailyItem.dateText, deserializedDaily.dateText)
        assertEquals(dailyItem.maxTemp, deserializedDaily.maxTemp)
        assertEquals(dailyItem.minTemp, deserializedDaily.minTemp)
        assertEquals(dailyItem.feelsLikeMaxTemp, deserializedDaily.feelsLikeMaxTemp)
        assertEquals(dailyItem.precipitationMm, deserializedDaily.precipitationMm, 0.001)
        assertEquals(dailyItem.uvIndex, deserializedDaily.uvIndex)
        assertEquals(dailyItem.uvCategory, deserializedDaily.uvCategory)
        assertEquals(dailyItem.thunderstormProbability, deserializedDaily.thunderstormProbability)
        assertEquals(dailyItem.sunriseTime, deserializedDaily.sunriseTime)
        assertEquals(dailyItem.sunsetTime, deserializedDaily.sunsetTime)
        assertEquals(dailyItem.dayConditionText, deserializedDaily.dayConditionText)
        assertEquals(dailyItem.nightConditionText, deserializedDaily.nightConditionText)
        assertNotNull(deserializedDaily.hourlyForecast)
        assertEquals(dailyItem.hourlyList.size, deserializedDaily.hourlyList.size)
    }

    @Test
    fun testLegacyDailyItemDeserializationWithMissingFields() {
        // Simulates old cache JSON without newly added fields
        val legacyJson = """
            {
                "dayLabel": "Friday",
                "dateText": "10/02",
                "minTemp": 12,
                "maxTemp": 24,
                "rainProbability": 10,
                "conditionText": "Sunny",
                "morningWind": "AM: 8 km/h S",
                "afternoonWind": "PM: 10 km/h SW"
            }
        """.trimIndent()

        val gson = Gson()
        val daily = gson.fromJson(legacyJson, DailyItem::class.java)

        assertNotNull(daily)
        assertEquals("Friday", daily.dayLabel)
        assertEquals("10/02", daily.dateText)
        assertEquals(12, daily.minTemp)
        assertEquals(24, daily.maxTemp)
        assertEquals(10, daily.rainProbability)
        assertEquals("Sunny", daily.conditionText)
        assertEquals("AM: 8 km/h S", daily.morningWind)
        assertEquals("PM: 10 km/h SW", daily.afternoonWind)
        // Default fields should be handled gracefully
        assertNull(daily.feelsLikeMaxTemp)
        assertNull(daily.feelsLikeMinTemp)
        assertEquals(0.0, daily.precipitationMm, 0.001)
        assertEquals(0, daily.uvIndex)
        assertTrue(daily.hourlyForecast.isNullOrEmpty())
    }

    @Test
    fun testCardinalDirectionParsingAndDegreesConversion() {
        assertEquals("N", com.google.weatherapp.data.repository.WeatherMapper.parseCardinal("NORTH"))
        assertEquals("ENE", com.google.weatherapp.data.repository.WeatherMapper.parseCardinal("EAST_NORTHEAST"))
        assertEquals("SW", com.google.weatherapp.data.repository.WeatherMapper.parseCardinal("SOUTH_WEST"))
        assertEquals("NNW", com.google.weatherapp.data.repository.WeatherMapper.parseCardinal("NORTH_NORTHWEST"))
        assertEquals("—", com.google.weatherapp.data.repository.WeatherMapper.parseCardinal(null))

        assertEquals("N", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(0))
        assertEquals("NE", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(45))
        assertEquals("E", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(90))
        assertEquals("SSE", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(157))
        assertEquals("S", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(180))
        assertEquals("WNW", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(292))
        assertEquals("N", com.google.weatherapp.data.repository.WeatherMapper.degreesToCardinal(360))

        assertEquals(0, com.google.weatherapp.data.repository.WeatherMapper.cardinalToDegrees("NORTH"))
        assertEquals(68, com.google.weatherapp.data.repository.WeatherMapper.cardinalToDegrees("ENE"))
        assertEquals(180, com.google.weatherapp.data.repository.WeatherMapper.cardinalToDegrees("S"))
    }

    @Test
    fun testBentoTelemetryNewFieldsSerialization() {
        val telemetry = BentoTelemetry(
            windSpeedKmH = 18,
            windDirection = "ENE",
            windDegrees = 68,
            windGustKmH = 26,
            precipitationMm = 1.2,
            uvIndex = 8,
            maxUvIndex = 9,
            uvCategory = "Very High",
            humidityPercent = 45,
            dewPointDegrees = 11,
            pressureHpa = 1018,
            sunriseTime = "06:14",
            sunsetTime = "18:22"
        )
        val gson = Gson()
        val json = gson.toJson(telemetry)
        val deserialized = gson.fromJson(json, BentoTelemetry::class.java)

        assertEquals(68, deserialized.windDegrees)
        assertEquals(9, deserialized.maxUvIndex)
        assertEquals("ENE", deserialized.windDirection)
    }

    @Test
    fun testRegionalModelResolution() {
        val repo = com.google.weatherapp.data.repository.WeatherRepository()
        // Standardized on "best_match" across all regions to preserve unsuffixed JSON keys and full 10-day forecasts
        assertEquals("best_match", repo.resolveBestRegionalModels(40.7128, -74.0060))
        assertEquals("best_match", repo.resolveBestRegionalModels(48.137, 11.575))
        assertEquals("best_match", repo.resolveBestRegionalModels(48.8566, 2.3522))
        assertEquals("best_match", repo.resolveBestRegionalModels(35.6762, 139.6503))
    }

    @Test
    fun testPrecipitationNowcastParsing() {
        val minutely = com.google.weatherapp.data.api.OpenMeteoMinutely15(
            time = listOf("2026-10-21T14:30", "2026-10-21T14:45", "2026-10-21T15:00", "2026-10-21T15:15"),
            precipitation = listOf(0.0, 0.4, 1.2, 0.2),
            precipitationProbability = listOf(10, 60, 85, 40),
            weatherCode = listOf(0, 61, 63, 61)
        )
        val nowcast = com.google.weatherapp.data.repository.WeatherMapper.parsePrecipitationNowcast(
            minutely,
            java.time.ZoneId.of("UTC"),
            referenceNow = java.time.LocalDateTime.parse("2026-10-21T14:15")
        )
        assertNotNull(nowcast)
        assertTrue(nowcast!!.isPrecipitationImminent)
        assertEquals(30, nowcast.nextPrecipitationOnsetMinutes)
        assertEquals(4.8, nowcast.maxRateMmH, 0.1) // 1.2 mm in 15m = 4.8 mm/h
        assertTrue(nowcast.summaryText.contains("Precipitation starting in ~30 min"))
    }

    @Test
    fun testEnsembleDispersionComputation() {
        // High consensus case: Google and OpenMeteo very close
        val tight = com.google.weatherapp.data.repository.WeatherMapper.computeEnsembleDispersion(
            currentTemp = 20,
            gTemp = 20.2,
            omTemp = 19.8,
            gHigh = 24.0,
            omHigh = 24.2,
            gPop = 20,
            omPop = 22
        )
        assertTrue(tight.confidencePercent >= 90)
        assertEquals("High Agreement", tight.confidenceTier)
        assertEquals(19, tight.p10Temp)
        assertEquals(21, tight.p90Temp)

        // Divergence case: 3.5°C gap between convective and global
        val loose = com.google.weatherapp.data.repository.WeatherMapper.computeEnsembleDispersion(
            currentTemp = 18,
            gTemp = 20.0,
            omTemp = 16.5,
            gHigh = 24.0,
            omHigh = 19.0,
            gPop = 80,
            omPop = 20
        )
        assertTrue(loose.confidencePercent < 75)
        assertEquals("High Uncertainty", loose.confidenceTier)
        assertTrue(loose.modelSpreadDegrees >= 3.0)
    }
}
