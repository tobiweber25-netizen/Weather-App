package com.google.weatherapp.data

import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.WeatherMapper
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeatherAccuracyAndTelemetryFixesTest {

    private val testZone = ZoneId.of("UTC")

    @Test
    fun testParsePrecipitationNowcastFutureIntervalsDynamicOffset() {
        val now = LocalDateTime.now(testZone).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val t1 = now.plusMinutes(12).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val t2 = now.plusMinutes(27).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val t3 = now.plusMinutes(42).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        val minutely = OpenMeteoMinutely15(
            time = listOf(t1, t2, t3),
            precipitation = listOf(0.0, 0.8, 1.5),
            precipitationProbability = listOf(10, 75, 90),
            weatherCode = listOf(0, 61, 63)
        )

        val nowcast = WeatherMapper.parsePrecipitationNowcast(minutely, testZone)
        assertNotNull(nowcast)
        assertTrue(nowcast!!.isPrecipitationImminent)
        // Interval 0 is at +12 min, interval 1 is at +27 min (precipitation onset >= 0.1mm)
        assertEquals(27, nowcast.nextPrecipitationOnsetMinutes)
        assertEquals(3, nowcast.intervals.size)
        assertEquals(12, nowcast.intervals[0].minuteOffset)
        assertEquals(27, nowcast.intervals[1].minuteOffset)
        assertEquals(42, nowcast.intervals[2].minuteOffset)
    }

    @Test
    fun testHourlyNowCardBlendsWhenGooglePrecipPercentIsNull() {
        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 22.0),
            precipitation = null, // Current precipitation is null
            isDaytime = true
        )

        val nowLdt = LocalDateTime.now(testZone)
        val hour0 = ForecastHour(
            displayDateTime = DisplayDateTime(nowLdt.year, nowLdt.monthValue, nowLdt.dayOfMonth, nowLdt.hour),
            temperature = Temperature("CELSIUS", 22.0),
            precipitation = Precipitation(
                probability = PrecipProbability(percent = 50),
                qpf = PrecipQuantity(quantity = 1.8)
            )
        )

        val daily = DailyForecastResponse(
            forecastDays = listOf(
                ForecastDay(
                    displayDate = DisplayDate(nowLdt.year, nowLdt.monthValue, nowLdt.dayOfMonth),
                    maxTemperature = Temperature("CELSIUS", 26.0),
                    minTemperature = Temperature("CELSIUS", 16.0),
                    sunEvents = SunEvents("2026-10-30T06:00:00Z", "2026-10-30T18:00:00Z")
                )
            )
        )

        val nowKey = nowLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US))
        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            hourly = OpenMeteoHourly(
                time = listOf(nowKey),
                temperature2m = listOf(22.0),
                precipitationProbability = listOf(70),
                precipitation = listOf(2.5),
                weatherCode = listOf(61)
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(listOf(hour0)),
            daily = daily,
            omForecast = omForecast,
            omAqi = null
        )

        val nowCard = result.hourlyList.first()
        assertEquals("Now", nowCard.hourLabel)
        // Should blend maxOf(50, 70) = 70 instead of 0
        assertEquals(70, nowCard.rainProbability)
        // Should blend maxOf(1.8, 2.5) = 2.5 instead of 0.0
        assertEquals(2.5, nowCard.precipitationMm, 0.001)
    }

    @Test
    fun testTenDayDailySunEventsDoNotFallbackToCurrentForFutureDays() {
        val today = LocalDate.now(testZone)
        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 20.0),
            sunEvents = SunEvents("2026-10-30T05:30:00Z", "2026-10-30T18:00:00Z") // Today's sunrise 05:30
        )

        val day0 = ForecastDay(
            displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth),
            sunEvents = null
        )
        val tomorrow = today.plusDays(1)
        val day1 = ForecastDay(
            displayDate = DisplayDate(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth),
            sunEvents = null // Future day has null sunEvents
        )

        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            daily = OpenMeteoDaily(
                time = listOf(today.toString(), tomorrow.toString()),
                sunrise = listOf("2026-10-30T05:30:00Z", "2026-10-31T06:14:00Z"),
                sunset = listOf("2026-10-30T18:00:00Z", "2026-10-31T18:22:00Z")
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(listOf(day0, day1)),
            omForecast = omForecast,
            omAqi = null
        )

        // Day 0 (Today) can fall back to current.sunEvents -> 05:30
        assertEquals("05:30", result.dailyList[0].sunriseTime)
        // Day 1 (Tomorrow) must NOT fall back to current.sunEvents (05:30), but use OM sunrise -> 06:14
        assertEquals("06:14", result.dailyList[1].sunriseTime)
        assertEquals("18:22", result.dailyList[1].sunsetTime)
    }

    @Test
    fun testSecondaryTelemetryUvAndVisibilityTakesCurrentHourSlotNotMidnight() {
        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 24.0),
            uvIndex = null,
            visibility = null
        )

        val today = LocalDate.now(testZone)
        val nowHour = ZonedDateTime.now(testZone).hour
        val midnightKey = String.format(Locale.US, "%sT00:00", today)
        val currentHourKey = String.format(Locale.US, "%sT%02d:00", today, nowHour)

        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            hourly = OpenMeteoHourly(
                time = listOf(midnightKey, currentHourKey),
                uvIndex = listOf(0.0, 8.0), // Midnight = 0, Current hour = 8
                visibility = listOf(2000.0, 16000.0) // Midnight = 2km, Current hour = 16km
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth)))),
            omForecast = omForecast,
            omAqi = null
        )

        // UV and visibility should match current hour slot (8 and 16.0 km), not midnight (0 and 2.0 km)
        assertEquals(8, result.telemetry.uvIndex)
        assertEquals(16.0, result.telemetry.visibilityKm, 0.01)
    }

    @Test
    fun testDailyPopAndQpfBoundingEnforcedAgainstHourlyForecast() {
        val today = LocalDate.now(testZone)
        val current = CurrentConditionsResponse(temperature = Temperature("CELSIUS", 20.0))

        // Daily forecast claims only 20% PoP and 1.0 mm rain
        val day = ForecastDay(
            displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth),
            daytimeForecast = DayPeriodForecast(
                precipitation = Precipitation(
                    probability = PrecipProbability(percent = 20),
                    qpf = PrecipQuantity(quantity = 1.0)
                )
            )
        )

        // But 2 hourly slots total 4.5 mm with a spike to 85% PoP
        val gHours = listOf(
            ForecastHour(
                displayDateTime = DisplayDateTime(today.year, today.monthValue, today.dayOfMonth, 14),
                precipitation = Precipitation(
                    probability = PrecipProbability(percent = 85),
                    qpf = PrecipQuantity(quantity = 2.0)
                )
            ),
            ForecastHour(
                displayDateTime = DisplayDateTime(today.year, today.monthValue, today.dayOfMonth, 15),
                precipitation = Precipitation(
                    probability = PrecipProbability(percent = 60),
                    qpf = PrecipQuantity(quantity = 2.5)
                )
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(listOf(day)),
            omForecast = null,
            omAqi = null
        )

        val todayDaily = result.dailyList.first()
        // Daily rainProbability should be bounded to at least max hourly (85%)
        assertEquals(85, todayDaily.rainProbability)
        // Daily precipitationMm should be bounded to at least sum of hourly (4.5 mm)
        assertEquals(4.5, todayDaily.precipitationMm, 0.01)
    }

    @Test
    fun testOpenMeteoFallbackAnchorsHour0AndAveragesDailyTelemetry() {
        val today = LocalDate.now(testZone)
        val nowHour = ZonedDateTime.now(testZone).hour
        val nowKey = String.format(Locale.US, "%sT%02d:00", today, nowHour)
        val nextHourKey = String.format(Locale.US, "%sT%02d:00", today, (nowHour + 1) % 24)

        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            current = OpenMeteoCurrent(
                temperature2m = 25.4, // Current temperature is ~25°C
                relativeHumidity2m = 40,
                cloudCover = 10,
                weatherCode = 0
            ),
            hourly = OpenMeteoHourly(
                time = listOf(nowKey, nextHourKey),
                temperature2m = listOf(21.0, 22.0), // Raw hourly slot has 21°C
                relativeHumidity2m = listOf(60, 80),
                cloudCover = listOf(50, 70)
            ),
            daily = OpenMeteoDaily(
                time = listOf(today.toString()),
                weatherCode = listOf(0),
                temperature2mMax = listOf(28.0),
                temperature2mMin = listOf(18.0)
            )
        )

        val result = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Test City"
        )

        // Hourly card 0 must be anchored to currentTemp (25°C), not raw hourly slot (21°C)
        assertEquals(25, result.hourlyList.first().tempDegrees)
        // Daily humidity must be derived from hourly average ((60+80)/2 = 70%), not cloned from instantaneous 40%
        assertEquals(70, result.dailyList.first().humidityPercent)
        // Daily cloud cover must be derived from hourly average ((50+70)/2 = 60%), not cloned from instantaneous 10%
        assertEquals(60, result.dailyList.first().cloudCoverPercent)
    }

    @Test
    fun testNocturnalProgressComputesAcrossNightHours() {
        // Sunrise 06:00, Sunset 18:00 -> Total night = 12h (720 min)
        // 21:00 is 3h into night -> 3/12 = 0.25
        val progress2100 = WeatherMapper.computeSolarProgress("06:00", "18:00", testZone, referenceTime = java.time.LocalTime.of(21, 0))
        assertEquals(0.25f, progress2100, 0.01f)

        // 00:00 is 6h into night (midnight peak) -> 6/12 = 0.50
        val progressMidnight = WeatherMapper.computeSolarProgress("06:00", "18:00", testZone, referenceTime = java.time.LocalTime.of(0, 0))
        assertEquals(0.50f, progressMidnight, 0.01f)

        // 03:00 is 9h into night -> 9/12 = 0.75
        val progress0300 = WeatherMapper.computeSolarProgress("06:00", "18:00", testZone, referenceTime = java.time.LocalTime.of(3, 0))
        assertEquals(0.75f, progress0300, 0.01f)

        // Daytime 12:00 -> 6/12 = 0.50
        val progressNoon = WeatherMapper.computeSolarProgress("06:00", "18:00", testZone, referenceTime = java.time.LocalTime.of(12, 0))
        assertEquals(0.50f, progressNoon, 0.01f)

        // Polar night or equal sunrise/sunset -> gracefully defaults to 0.5f
        val polar = WeatherMapper.computeSolarProgress("12:00", "12:00", testZone)
        assertEquals(0.5f, polar, 0.001f)
    }

    @Test
    fun testPrecipitationNowcastReturnsNullWhenAllSlotsInPast() {
        val now = LocalDateTime.now(testZone).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val past1 = now.minusMinutes(45).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val past2 = now.minusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        val minutely = OpenMeteoMinutely15(
            time = listOf(past1, past2),
            precipitation = listOf(1.5, 2.0),
            precipitationProbability = listOf(80, 90),
            weatherCode = listOf(63, 63)
        )

        // All slots are in the past -> must return null rather than fabricating fake positive minute offsets
        val nowcast = WeatherMapper.parsePrecipitationNowcast(minutely, testZone)
        assertNull(nowcast)
    }

    @Test
    fun testEnsembleFallbackAnchorsHour0WhenGoogleHourlyEmpty() {
        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 23.0),
            precipitation = Precipitation(
                probability = PrecipProbability(percent = 45),
                qpf = PrecipQuantity(quantity = 1.2)
            ),
            isDaytime = true
        )

        val today = LocalDate.now(testZone)
        val nowHour = ZonedDateTime.now(testZone).hour
        val nowKey = String.format(Locale.US, "%sT%02d:00", today, nowHour)
        val nextKey = String.format(Locale.US, "%sT%02d:00", today, (nowHour + 1) % 24)

        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            hourly = OpenMeteoHourly(
                time = listOf(nowKey, nextKey),
                temperature2m = listOf(19.0, 20.0), // Raw OM is 19
                precipitationProbability = listOf(10, 20),
                precipitation = listOf(0.0, 0.0),
                weatherCode = listOf(0, 0)
            ),
            daily = OpenMeteoDaily(
                time = listOf(today.toString()),
                temperature2mMax = listOf(26.0),
                temperature2mMin = listOf(16.0)
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(emptyList()), // Google hourly is empty
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(today.year, today.monthValue, today.dayOfMonth)))),
            omForecast = omForecast,
            omAqi = null
        )

        val nowCard = result.hourlyList.first()
        assertEquals("Now", nowCard.hourLabel)
        assertEquals(23, nowCard.tempDegrees) // Anchored to current temp 23, not raw OM 19
        assertEquals(45, nowCard.rainProbability) // Anchored to current precip prob 45, not raw OM 10
        assertEquals(1.2, nowCard.precipitationMm, 0.01) // Anchored to current QPF 1.2, not raw OM 0.0
    }

    @Test
    fun testSevereStormAlertConsidersMergedHourlyConvectiveStorms() {
        val today = LocalDate.now(testZone)
        val nowLdt = LocalDateTime.now(testZone)
        val nowKey = nowLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US))

        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 22.0),
            thunderstormProbability = 0
        )

        // Google hourly reports 0% storm probability
        val gHours = listOf(
            ForecastHour(
                displayDateTime = DisplayDateTime(nowLdt.year, nowLdt.monthValue, nowLdt.dayOfMonth, nowLdt.hour),
                thunderstormProbability = 0
            )
        )

        // Open-Meteo detects severe convective thunderstorm with 75% probability
        val omForecast = OpenMeteoForecastResponse(
            timezone = "UTC",
            hourly = OpenMeteoHourly(
                time = listOf(nowKey),
                temperature2m = listOf(22.0),
                weatherCode = listOf(95), // WMO 95: Thunderstorm
                precipitationProbability = listOf(75)
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(nowLdt.year, nowLdt.monthValue, nowLdt.dayOfMonth)))),
            omForecast = omForecast,
            omAqi = null
        )

        // Severe alert must NOT be CLEAR; it must catch the 75% peak storm probability
        assertEquals(SeverityRiskLevel.SEVERE, result.alert.riskLevel)
        assertEquals(75, result.alert.thunderstormProbability)
        assertTrue(result.alert.title.contains("SEVERE THUNDERSTORM"))
    }

    @Test
    fun testPastHourlySlotsDroppedFromRollingHourlyList() {
        val nowLdt = LocalDateTime.now(testZone).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val pastLdt = nowLdt.minusHours(3)
        val currentLdt = nowLdt
        val futureLdt = nowLdt.plusHours(2)

        val gHours = listOf(
            ForecastHour(
                displayDateTime = DisplayDateTime(pastLdt.year, pastLdt.monthValue, pastLdt.dayOfMonth, pastLdt.hour),
                temperature = Temperature("CELSIUS", 15.0),
                thunderstormProbability = 80 // Past storm 3 hours ago!
            ),
            ForecastHour(
                displayDateTime = DisplayDateTime(currentLdt.year, currentLdt.monthValue, currentLdt.dayOfMonth, currentLdt.hour),
                temperature = Temperature("CELSIUS", 20.0),
                thunderstormProbability = 0
            ),
            ForecastHour(
                displayDateTime = DisplayDateTime(futureLdt.year, futureLdt.monthValue, futureLdt.dayOfMonth, futureLdt.hour),
                temperature = Temperature("CELSIUS", 22.0),
                thunderstormProbability = 0
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = CurrentConditionsResponse(temperature = Temperature("CELSIUS", 20.0), thunderstormProbability = 0),
            hourly = HourlyForecastResponse(gHours),
            daily = DailyForecastResponse(listOf(ForecastDay(displayDate = DisplayDate(currentLdt.year, currentLdt.monthValue, currentLdt.dayOfMonth)))),
            omForecast = OpenMeteoForecastResponse(timezone = "UTC"),
            omAqi = null
        )

        // Past hour (3 hours ago) must be dropped; only current + future (2 items) remain
        assertEquals(2, result.hourlyList.size)
        assertEquals("Now", result.hourlyList[0].hourLabel)
        // Past 80% storm from 3 hours ago must not trigger a SEVERE storm alert now
        assertEquals(0, result.alert.thunderstormProbability)
        assertEquals(SeverityRiskLevel.LOW, result.alert.riskLevel)
    }

    @Test
    fun testCalibrateRollingHourlyPeakParsesHourLabelWhenTodayHourlyIsPartial() {
        // Suppose Open-Meteo is null and Google only returned future hours starting at 13:00 (2 items: 13:00 and 14:00)
        val partialTodayHourly = listOf(
            HourlyItem(hourLabel = "13:00", tempDegrees = 24, rainProbability = 0, iconUri = null, isDaytime = true),
            HourlyItem(hourLabel = "14:00", tempDegrees = 26, rainProbability = 0, iconUri = null, isDaytime = true)
        )
        val rollingList = listOf(
            HourlyItem(hourLabel = "Now", tempDegrees = 24, rainProbability = 0, iconUri = null, isDaytime = true),
            HourlyItem(hourLabel = "14:00", tempDegrees = 26, rainProbability = 0, iconUri = null, isDaytime = true)
        )

        // At currentHour = 13, peak is at "14:00" (index 1, hour 14 > 13), so future slot 1 MUST calibrate to todayHigh (28°C) while "Now" stays anchored at 24°C
        val calibratedAt13 = WeatherMapper.calibrateRollingHourlyPeak(
            hourlyList = rollingList,
            todayHourly = partialTodayHourly,
            todayHigh = 28,
            currentHour = 13
        )
        assertEquals(24, calibratedAt13[0].tempDegrees)
        assertEquals(28, calibratedAt13[1].tempDegrees)

        // At currentHour = 20 (8 PM), peak at "14:00" has already passed (14 <= 20), so evening hours must NOT be inflated
        val eveningRolling = listOf(
            HourlyItem(hourLabel = "Now", tempDegrees = 19, rainProbability = 0, iconUri = null, isDaytime = false),
            HourlyItem(hourLabel = "21:00", tempDegrees = 18, rainProbability = 0, iconUri = null, isDaytime = false)
        )
        val calibratedAt20 = WeatherMapper.calibrateRollingHourlyPeak(
            hourlyList = eveningRolling,
            todayHourly = partialTodayHourly,
            todayHigh = 28,
            currentHour = 20
        )
        assertEquals(19, calibratedAt20[0].tempDegrees)
        assertEquals(18, calibratedAt20[1].tempDegrees)
    }
}
