package com.google.weatherapp.ui

import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.theme.*
import com.google.weatherapp.widget.WeatherWidgetRenderer
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class WeatherEffectsAndDetailTest {

    @Test
    fun testWeatherEffectResolution_Thunderstorm() {
        val stormHero1 = HeroWeather(
            tempDegrees = 20,
            feelsLikeDegrees = 20,
            highDegrees = 25,
            lowDegrees = 15,
            conditionText = "Isolated Thunderstorms",
            iconUri = null,
            isDaytime = true,
            locationName = "Johannesburg",
            skyTheme = SkyThemeType.THUNDERSTORM
        )
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffect(stormHero1, WeatherEffectType.AUTO))

        val stormHero2 = stormHero1.copy(conditionText = "Severe Lightning", skyTheme = SkyThemeType.CLEAR_DAY)
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffect(stormHero2, WeatherEffectType.AUTO))
    }

    @Test
    fun testWeatherEffectResolution_Rain() {
        val rainHero = HeroWeather(
            tempDegrees = 17,
            feelsLikeDegrees = 16,
            highDegrees = 19,
            lowDegrees = 12,
            conditionText = "Scattered Showers",
            iconUri = null,
            isDaytime = true,
            locationName = "Cape Town",
            skyTheme = SkyThemeType.RAIN
        )
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffect(rainHero, WeatherEffectType.AUTO))

        val drizzleHero = rainHero.copy(conditionText = "Light Drizzle", skyTheme = SkyThemeType.OVERCAST)
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffect(drizzleHero, WeatherEffectType.AUTO))
    }

    @Test
    fun testWeatherEffectResolution_Snow() {
        val snowHero = HeroWeather(
            tempDegrees = -2,
            feelsLikeDegrees = -5,
            highDegrees = 0,
            lowDegrees = -6,
            conditionText = "Heavy Snow Flurries",
            iconUri = null,
            isDaytime = true,
            locationName = "Denver",
            skyTheme = SkyThemeType.OVERCAST
        )
        assertEquals(WeatherEffectType.SNOW, resolveActiveEffect(snowHero, WeatherEffectType.AUTO))
    }

    @Test
    fun testWeatherEffectResolution_ClearDayAndNight() {
        val clearDayHero = HeroWeather(
            tempDegrees = 28,
            feelsLikeDegrees = 29,
            highDegrees = 30,
            lowDegrees = 18,
            conditionText = "Clear and Sunny",
            iconUri = null,
            isDaytime = true,
            locationName = "Durban",
            skyTheme = SkyThemeType.CLEAR_DAY
        )
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffect(clearDayHero, WeatherEffectType.AUTO))

        val clearNightHero = clearDayHero.copy(
            isDaytime = false,
            skyTheme = SkyThemeType.CLEAR_NIGHT,
            conditionText = "Clear Night"
        )
        assertEquals(WeatherEffectType.CLEAR_NIGHT, resolveActiveEffect(clearNightHero, WeatherEffectType.AUTO))
    }

    @Test
    fun testWeatherEffectResolution_CloudyMist() {
        val cloudyHero = HeroWeather(
            tempDegrees = 19,
            feelsLikeDegrees = 19,
            highDegrees = 22,
            lowDegrees = 14,
            conditionText = "Dense Fog and Mist",
            iconUri = null,
            isDaytime = true,
            locationName = "London",
            skyTheme = SkyThemeType.OVERCAST
        )
        assertEquals(WeatherEffectType.CLOUDY, resolveActiveEffect(cloudyHero, WeatherEffectType.AUTO))
    }

    @Test
    fun testWeatherEffectResolution_ManualOverride() {
        val clearHero = HeroWeather(
            tempDegrees = 28,
            feelsLikeDegrees = 29,
            highDegrees = 30,
            lowDegrees = 18,
            conditionText = "Sunny",
            iconUri = null,
            isDaytime = true,
            locationName = "Pretoria",
            skyTheme = SkyThemeType.CLEAR_DAY
        )

        assertEquals(WeatherEffectType.RAIN, resolveActiveEffect(clearHero, WeatherEffectType.RAIN))
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffect(clearHero, WeatherEffectType.THUNDERSTORM))
        assertEquals(WeatherEffectType.SNOW, resolveActiveEffect(clearHero, WeatherEffectType.SNOW))
        assertEquals(WeatherEffectType.OFF, resolveActiveEffect(clearHero, WeatherEffectType.OFF))
    }

    @Test
    fun testDailyItemDetailedFieldsIntegrity() {
        val days = (0 until 10).map { i ->
            DailyItem(
                dayLabel = if (i == 0) "Today" else if (i == 1) "Tomorrow" else "Day $i",
                dateText = "10/${i + 1}",
                minTemp = 10 + i,
                maxTemp = 20 + i,
                rainProbability = i * 10,
                iconUri = "uri_$i",
                conditionText = if (i % 2 == 0) "Sunny" else "Scattered Thunderstorms",
                morningWind = "AM: 10 km/h NW",
                afternoonWind = "PM: 15 km/h SE",
                fullDateString = "Day $i, October ${i + 1}",
                feelsLikeMaxTemp = 21 + i,
                feelsLikeMinTemp = 9 + i,
                precipitationMm = if (i % 2 == 0) 0.0 else 5.5,
                uvIndex = (i % 12),
                uvCategory = if (i >= 8) "Very High" else "Moderate",
                humidityPercent = 40 + i * 2,
                thunderstormProbability = if (i % 2 == 0) 5 else 60,
                cloudCoverPercent = 20 + i * 5,
                sunriseTime = "06:10",
                sunsetTime = "18:10",
                dayConditionText = "Sunny",
                dayIconUri = "day_uri",
                nightConditionText = "Clear",
                nightIconUri = "night_uri",
                dayWindSpeedKmH = 12,
                dayWindDirection = "NW",
                nightWindSpeedKmH = 16,
                nightWindDirection = "SE"
            )
        }

        assertEquals(10, days.size)
        val firstDay = days.first()
        assertEquals("Today", firstDay.dayLabel)
        assertEquals(0, firstDay.rainProbability)
        assertEquals(0.0, firstDay.precipitationMm, 0.001)

        val lastDay = days.last()
        assertEquals(90, lastDay.rainProbability)
        assertEquals(29, lastDay.maxTemp)
        assertEquals(19, lastDay.minTemp)
        assertEquals("Very High", lastDay.uvCategory)
        assertEquals(60, lastDay.thunderstormProbability)
    }

    @Test
    fun testResolveActiveEffectForDay_AllConditions() {
        val baseDay = DailyItem(
            dayLabel = "Friday",
            dateText = "10/02",
            minTemp = 12,
            maxTemp = 24,
            rainProbability = 10,
            iconUri = null,
            conditionText = "Clear and Sunny",
            morningWind = "AM: 8 km/h S",
            afternoonWind = "PM: 10 km/h SW"
        )

        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffectForDay(baseDay, WeatherEffectType.AUTO))

        val stormDay = baseDay.copy(conditionText = "Severe Thunderstorms")
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffectForDay(stormDay, WeatherEffectType.AUTO))

        val stormProbDay = baseDay.copy(conditionText = "Cloudy", thunderstormProbability = 55)
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffectForDay(stormProbDay, WeatherEffectType.AUTO))

        val snowDay = baseDay.copy(conditionText = "Blowing Snow and Sleet")
        assertEquals(WeatherEffectType.SNOW, resolveActiveEffectForDay(snowDay, WeatherEffectType.AUTO))

        val rainDay = baseDay.copy(conditionText = "Scattered Rain Showers")
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffectForDay(rainDay, WeatherEffectType.AUTO))

        val rainProbDay = baseDay.copy(conditionText = "Partly Cloudy", rainProbability = 50)
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffectForDay(rainProbDay, WeatherEffectType.AUTO))

        val mistyDay = baseDay.copy(conditionText = "Morning Mist and Fog")
        assertEquals(WeatherEffectType.CLOUDY, resolveActiveEffectForDay(mistyDay, WeatherEffectType.AUTO))

        assertEquals(WeatherEffectType.SNOW, resolveActiveEffectForDay(stormDay, WeatherEffectType.SNOW))
        assertEquals(WeatherEffectType.OFF, resolveActiveEffectForDay(stormDay, WeatherEffectType.OFF))
    }

    @Test
    fun testDailyForecastWith24HourProgression() {
        val daysWithHourly = (0 until 10).map { dayIdx ->
            val hourlyForDay = (0..23).map { h ->
                HourlyItem(
                    hourLabel = String.format(java.util.Locale.getDefault(), "%02d:00", h),
                    tempDegrees = 15 + h % 10,
                    rainProbability = (dayIdx * 10 + h) % 100,
                    iconUri = "icon_uri",
                    isDaytime = h in 6..18,
                    conditionText = if (h in 6..18) "Sunny" else "Clear"
                )
            }

            DailyItem(
                dayLabel = if (dayIdx == 0) "Today" else "Day $dayIdx",
                dateText = "10/${dayIdx + 1}",
                minTemp = 12 + dayIdx,
                maxTemp = 24 + dayIdx,
                rainProbability = dayIdx * 10,
                iconUri = null,
                conditionText = "Sunny",
                morningWind = "AM: 8 km/h S",
                afternoonWind = "PM: 10 km/h SW",
                hourlyForecast = hourlyForDay
            )
        }

        assertEquals(10, daysWithHourly.size)
        daysWithHourly.forEach { dayItem ->
            assertEquals(24, dayItem.hourlyList.size)
            assertEquals("00:00", dayItem.hourlyList.first().hourLabel)
            assertEquals("23:00", dayItem.hourlyList.last().hourLabel)
            assertTrue(dayItem.hourlyList[12].isDaytime)
            assertFalse(dayItem.hourlyList[0].isDaytime)
        }
    }

    @Test
    fun testResolveActiveEffectForHourly_Conditions() {
        val sunnyWithRainChance = HourlyItem(
            hourLabel = "Now",
            tempDegrees = 23,
            rainProbability = 40,
            iconUri = null,
            isDaytime = true,
            conditionText = "Sunny"
        )
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffectForHourly(sunnyWithRainChance))

        val rainHour = sunnyWithRainChance.copy(conditionText = "Scattered Showers")
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffectForHourly(rainHour))

        val stormHour = sunnyWithRainChance.copy(conditionText = "Isolated Thunderstorms")
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffectForHourly(stormHour))

        val nightClear = sunnyWithRainChance.copy(isDaytime = false, conditionText = "Clear")
        assertEquals(WeatherEffectType.CLEAR_NIGHT, resolveActiveEffectForHourly(nightClear))

        val cloudyWithHeavyRainChance = sunnyWithRainChance.copy(conditionText = "Overcast", rainProbability = 70)
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffectForHourly(cloudyWithHeavyRainChance))
    }

    @Test
    fun testEnsembleBlendingAndConfidenceScoring() {
        // 0.6 * 25.0 + 0.4 * 20.0 = 15.0 + 8.0 = 23
        assertEquals(23, WeatherMapper.blendTemperatures(25.0, 20.0))
        assertEquals(25, WeatherMapper.blendTemperatures(25.2, null))
        assertEquals(19, WeatherMapper.blendTemperatures(null, 18.9))
        assertNull(WeatherMapper.blendTemperatures(null, null))

        // High agreement (0.5°C spread, 5% PoP spread) -> >90% confidence
        val highConfidence = WeatherMapper.computeEnsembleConfidence(0.5, 5)
        assertTrue("Expected high confidence >= 90, got $highConfidence", highConfidence >= 90)

        // Wide disagreement (4.5°C spread, 40% PoP spread) -> lower confidence
        val lowConfidence = WeatherMapper.computeEnsembleConfidence(4.5, 40)
        assertTrue("Expected lower confidence < 75, got $lowConfidence", lowConfidence < 75)
        assertTrue("Confidence must stay within valid bounds", lowConfidence in 58..98)
    }

    @Test
    fun testWmoCodeMappingAndSolarProgressBounds() {
        assertEquals("Clear and Sunny", WeatherMapper.mapWmoCodeToCondition(0, isDaytime = true))
        assertEquals("Clear Night", WeatherMapper.mapWmoCodeToCondition(0, isDaytime = false))
        assertEquals("Scattered Showers", WeatherMapper.mapWmoCodeToCondition(61, isDaytime = true))
        assertEquals("Thunderstorms", WeatherMapper.mapWmoCodeToCondition(95, isDaytime = true))
        assertEquals("Snow Showers", WeatherMapper.mapWmoCodeToCondition(73, isDaytime = true))

        val solarProgress = WeatherMapper.computeSolarProgress("06:00", "18:00")
        assertTrue("Solar progress should be clamped in 0.05..0.95", solarProgress in 0.05f..0.95f)
    }

    @Test
    fun testTenDayForecastDateAlignmentAndHourlyBounds() {
        val current = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 26.0),
            feelsLikeTemperature = Temperature("CELSIUS", 27.0),
            isDaytime = true
        )

        val forecastDays = (0 until 10).map { i ->
            ForecastDay(
                displayDate = DisplayDate(2026, 10, 20 + i),
                maxTemperature = Temperature("CELSIUS", 24.0 + i),
                minTemperature = Temperature("CELSIUS", 12.0 + i)
            )
        }

        // Simulate Open-Meteo shifted by 1 day (starts at 2026-10-19) + hourly spike on 2026-10-22
        val omDates = (0..10).map { i -> String.format("2026-10-%02d", 19 + i) }
        val omMaxTemps = (0..10).map { i -> if (i == 0) 5.0 else (24.0 + (i - 1)) }
        val omMinTemps = (0..10).map { i -> if (i == 0) -2.0 else (12.0 + (i - 1)) }

        val omHourlyTimes = listOf("2026-10-22T04:00", "2026-10-22T14:00")
        val omHourlyTemps = listOf(10.0, 31.0)

        val omForecast = com.google.weatherapp.data.api.OpenMeteoForecastResponse(
            timezone = "Africa/Johannesburg",
            daily = com.google.weatherapp.data.api.OpenMeteoDaily(
                time = omDates,
                temperature2mMax = omMaxTemps,
                temperature2mMin = omMinTemps
            ),
            hourly = com.google.weatherapp.data.api.OpenMeteoHourly(
                time = omHourlyTimes,
                temperature2m = omHourlyTemps
            )
        )

        val result = WeatherMapper.mapEnsembleDashboardData(
            current = current,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(forecastDays),
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Johannesburg"
        )

        assertEquals(10, result.dailyList.size)
        // Today (2026-10-20): Google max is 24, Open-Meteo matched by date is 24, but current is 26 -> max must bound current (26)
        assertEquals(26, result.dailyList[0].maxTemp)
        assertEquals(12, result.dailyList[0].minTemp)
        assertEquals(result.dailyList[0].maxTemp, result.hero.highDegrees)
        assertEquals(result.dailyList[0].minTemp, result.hero.lowDegrees)

        // Day 2 (2026-10-22): daily blended is 26/14, but hourly reaches 31/10 -> high/low must bound hourly (31/10)
        assertEquals(31, result.dailyList[2].maxTemp)
        assertEquals(10, result.dailyList[2].minTemp)

        result.dailyList.forEach { day ->
            assertTrue("minTemp (${day.minTemp}) <= maxTemp (${day.maxTemp})", day.minTemp <= day.maxTemp)
        }
    }

    @Test
    fun testAlignHourlyExtremes_HarmonizesPeakAndMinWithDaily() {
        val hours = listOf(
            HourlyItem("10:00", tempDegrees = 20, rainProbability = 0, iconUri = null, isDaytime = true),
            HourlyItem("14:00", tempDegrees = 28, rainProbability = 0, iconUri = null, isDaytime = true),
            HourlyItem("18:00", tempDegrees = 24, rainProbability = 0, iconUri = null, isDaytime = true)
        )
        val aligned = WeatherMapper.alignHourlyExtremes(hours, targetMax = 29, targetMin = 18)
        assertEquals(29, aligned.maxOf { it.tempDegrees })
        assertEquals(18, aligned.minOf { it.tempDegrees })
        assertEquals(29, aligned[1].tempDegrees)
        assertEquals(18, aligned[0].tempDegrees)
    }

    @Test
    fun testCalibrateRollingHourlyPeak_PeakAhead_UpdatesRollingHourly() {
        // Full day has 24 hours, peak at 14:00 (hour 14)
        val today24Hours = (0..23).map { h ->
            HourlyItem(
                hourLabel = String.format("%02d:00", h),
                tempDegrees = if (h == 14) 28 else 20,
                rainProbability = 0,
                iconUri = null,
                isDaytime = true
            )
        }
        // Rolling feed starts at 08:00 (currentHour = 8)
        val rollingHourly = (8..23).map { h ->
            HourlyItem(
                hourLabel = if (h == 8) "Now" else String.format("%02d:00", h),
                tempDegrees = if (h == 14) 28 else 20,
                rainProbability = 0,
                iconUri = null,
                isDaytime = true
            )
        }

        val calibrated = WeatherMapper.calibrateRollingHourlyPeak(
            hourlyList = rollingHourly,
            todayHourly = today24Hours,
            todayHigh = 29,
            currentHour = 8
        )
        // Hour 14 (index 6 in rolling list) should be updated to 29
        assertEquals(29, calibrated.maxOf { it.tempDegrees })
        assertEquals(29, calibrated[6].tempDegrees)
        assertEquals("14:00", calibrated[6].hourLabel)
    }

    @Test
    fun testCalibrateRollingHourlyPeak_PeakPassed_PreservesEveningTemps() {
        // Full day has peak at 14:00 (hour 14)
        val today24Hours = (0..23).map { h ->
            HourlyItem(
                hourLabel = String.format("%02d:00", h),
                tempDegrees = if (h == 14) 29 else 20,
                rainProbability = 0,
                iconUri = null,
                isDaytime = true
            )
        }
        // Rolling feed at 19:00 (currentHour = 19, peak has elapsed)
        val rollingHourly = listOf(
            HourlyItem("Now", tempDegrees = 22, rainProbability = 0, iconUri = null, isDaytime = false),
            HourlyItem("20:00", tempDegrees = 21, rainProbability = 0, iconUri = null, isDaytime = false),
            HourlyItem("21:00", tempDegrees = 19, rainProbability = 0, iconUri = null, isDaytime = false)
        )

        val calibrated = WeatherMapper.calibrateRollingHourlyPeak(
            hourlyList = rollingHourly,
            todayHourly = today24Hours,
            todayHigh = 29,
            currentHour = 19
        )
        // Should NOT inject 29 into evening
        assertEquals(22, calibrated.maxOf { it.tempDegrees })
        assertEquals(22, calibrated[0].tempDegrees)
    }

    @Test
    fun testGuardProbabilityOverrides_ClearAndSunnyNeverOverridden() {
        val clearDay = DailyItem(
            dayLabel = "Saturday",
            dateText = "10/03",
            minTemp = 14,
            maxTemp = 26,
            rainProbability = 60,
            cloudCoverPercent = 85,
            thunderstormProbability = 70,
            iconUri = null,
            conditionText = "Clear and Sunny",
            morningWind = "AM: 8 km/h S",
            afternoonWind = "PM: 10 km/h SW"
        )

        // Clear and Sunny condition should NOT be overridden by high probabilities
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffectForDay(clearDay))

        val sunnyDay = clearDay.copy(conditionText = "Sunny")
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffectForDay(sunnyDay))

        val mostlySunnyDay = clearDay.copy(conditionText = "Mostly Sunny")
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffectForDay(mostlySunnyDay))

        // Hero resolution check: clear condition with high storm prob or skyTheme THUNDERSTORM should not override
        val clearHero = HeroWeather(
            tempDegrees = 25,
            feelsLikeDegrees = 25,
            highDegrees = 27,
            lowDegrees = 15,
            conditionText = "Clear and Sunny",
            iconUri = null,
            isDaytime = true,
            locationName = "Sunny City",
            skyTheme = SkyThemeType.THUNDERSTORM
        )
        assertEquals(WeatherEffectType.CLEAR_DAY, resolveActiveEffect(clearHero))

        // Open-Meteo fallback mapping: Clear weather code with high storm probability must remain CLEAR_DAY
        val omCurrent = com.google.weatherapp.data.api.OpenMeteoCurrent(
            time = "2026-10-31T12:00",
            temperature2m = 25.0,
            apparentTemperature = 25.0,
            isDay = 1,
            weatherCode = 0 // Clear
        )
        val omForecast = com.google.weatherapp.data.api.OpenMeteoForecastResponse(
            timezone = "UTC",
            current = omCurrent
        )
        val fallbackResult = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Sunny City"
        )
        assertEquals(SkyThemeType.CLEAR_DAY, fallbackResult.hero.skyTheme)

        // Google API ensemble mapping: Clear condition with high storm probability must remain CLEAR_DAY
        val gCurrent = CurrentConditionsResponse(
            temperature = Temperature("CELSIUS", 25.0),
            feelsLikeTemperature = Temperature("CELSIUS", 25.0),
            weatherCondition = WeatherCondition(
                type = "CLEAR",
                description = ConditionDescription(text = "Sunny")
            ),
            thunderstormProbability = 70,
            isDaytime = true
        )
        val ensembleResult = WeatherMapper.mapEnsembleDashboardData(
            current = gCurrent,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(emptyList()),
            omForecast = null,
            omAqi = null,
            resolvedLocationName = "Sunny City"
        )
        assertEquals(SkyThemeType.CLEAR_DAY, ensembleResult.hero.skyTheme)

        // Google API ensemble mapping at night: Clear condition with high storm probability must remain CLEAR_NIGHT
        val gCurrentNight = gCurrent.copy(
            isDaytime = false,
            weatherCondition = WeatherCondition(
                type = "CLEAR",
                description = ConditionDescription(text = "Clear Night")
            )
        )
        val ensembleNightResult = WeatherMapper.mapEnsembleDashboardData(
            current = gCurrentNight,
            hourly = HourlyForecastResponse(emptyList()),
            daily = DailyForecastResponse(emptyList()),
            omForecast = null,
            omAqi = null,
            resolvedLocationName = "Sunny City"
        )
        assertEquals(SkyThemeType.CLEAR_NIGHT, ensembleNightResult.hero.skyTheme)
    }

    @Test
    fun testNocturnalPalettes_RenderDarkGradientsAtNight() {
        // Daytime palettes
        val dayCloudy = resolveSkyGradientStopColors(WeatherEffectType.CLOUDY, isDaytime = true)
        assertEquals(SkyOvercastStart, dayCloudy.first)
        assertEquals(SkyOvercastEnd, dayCloudy.third)

        val dayRain = resolveSkyGradientStopColors(WeatherEffectType.RAIN, isDaytime = true)
        assertEquals(SkyRainStart, dayRain.first)
        assertEquals(SkyRainEnd, dayRain.third)

        val daySnow = resolveSkyGradientStopColors(WeatherEffectType.SNOW, isDaytime = true)
        assertEquals(SkySnowStart, daySnow.first)
        assertEquals(SkySnowEnd, daySnow.third)

        val dayStorm = resolveSkyGradientStopColors(WeatherEffectType.THUNDERSTORM, isDaytime = true)
        assertEquals(SkyStormStart, dayStorm.first)
        assertEquals(SkyStormEnd, dayStorm.third)

        // Nocturnal palettes (isDaytime = false)
        val nightCloudy = resolveSkyGradientStopColors(WeatherEffectType.CLOUDY, isDaytime = false)
        assertEquals(SkyNightOvercastStart, nightCloudy.first)
        assertEquals(SkyNightOvercastMid, nightCloudy.second)
        assertEquals(SkyNightOvercastEnd, nightCloudy.third)

        val nightRain = resolveSkyGradientStopColors(WeatherEffectType.RAIN, isDaytime = false)
        assertEquals(SkyNightRainStart, nightRain.first)
        assertEquals(SkyNightRainMid, nightRain.second)
        assertEquals(SkyNightRainEnd, nightRain.third)

        val nightSnow = resolveSkyGradientStopColors(WeatherEffectType.SNOW, isDaytime = false)
        assertEquals(SkyNightSnowStart, nightSnow.first)
        assertEquals(SkyNightSnowMid, nightSnow.second)
        assertEquals(SkyNightSnowEnd, nightSnow.third)

        val nightStorm = resolveSkyGradientStopColors(WeatherEffectType.THUNDERSTORM, isDaytime = false)
        assertEquals(SkyNightStormStart, nightStorm.first)
        assertEquals(SkyNightStormMid, nightStorm.second)
        assertEquals(SkyNightStormEnd, nightStorm.third)

        // WeatherWidgetRenderer resolveSkyPalette nocturnal check
        val widgetNightRain = WeatherWidgetRenderer.resolveSkyPalette(WeatherEffectType.RAIN, isDaytime = false)
        assertEquals(SkyNightRainStart, widgetNightRain.first)
        assertEquals(SkyNightRainEnd, widgetNightRain.third)

        val widgetNightSnow = WeatherWidgetRenderer.resolveSkyPalette(WeatherEffectType.SNOW, isDaytime = false)
        assertEquals(SkyNightSnowStart, widgetNightSnow.first)
        assertEquals(SkyNightSnowEnd, widgetNightSnow.third)
    }

    @Test
    fun testResolveActiveEffectForNight_DynamicResolution() {
        val baseDay = DailyItem(
            dayLabel = "Sunday",
            dateText = "10/04",
            minTemp = 10,
            maxTemp = 20,
            rainProbability = 10,
            iconUri = null,
            conditionText = "Partly Cloudy",
            nightConditionText = "Clear Night",
            morningWind = "",
            afternoonWind = ""
        )

        assertEquals(WeatherEffectType.CLEAR_NIGHT, resolveActiveEffectForNight(baseDay))

        val rainNight = baseDay.copy(nightConditionText = "Scattered Showers")
        assertEquals(WeatherEffectType.RAIN, resolveActiveEffectForNight(rainNight))

        val stormNight = baseDay.copy(nightConditionText = "Thunderstorms")
        assertEquals(WeatherEffectType.THUNDERSTORM, resolveActiveEffectForNight(stormNight))

        val snowNight = baseDay.copy(nightConditionText = "Light Snow Flurries")
        assertEquals(WeatherEffectType.SNOW, resolveActiveEffectForNight(snowNight))

        val foggyNight = baseDay.copy(nightConditionText = "Dense Fog")
        assertEquals(WeatherEffectType.CLOUDY, resolveActiveEffectForNight(foggyNight))

        // High rain prob should not override Clear night
        val clearNightWithProb = baseDay.copy(
            nightConditionText = "Clear",
            rainProbability = 80,
            cloudCoverPercent = 90,
            thunderstormProbability = 60
        )
        assertEquals(WeatherEffectType.CLEAR_NIGHT, resolveActiveEffectForNight(clearNightWithProb))
    }

    @Test
    fun testSnowSkyThemeTypeAndFallbackMapping() {
        // Open-Meteo code 71 (Snow) -> SkyThemeType.SNOW
        val omCurrent = com.google.weatherapp.data.api.OpenMeteoCurrent(
            time = "2026-10-31T12:00",
            temperature2m = -3.0,
            apparentTemperature = -7.0,
            isDay = 1,
            weatherCode = 71
        )
        val omForecast = com.google.weatherapp.data.api.OpenMeteoForecastResponse(
            timezone = "UTC",
            current = omCurrent
        )
        val result = WeatherMapper.mapOpenMeteoFallbackDashboardData(
            omForecast = omForecast,
            omAqi = null,
            resolvedLocationName = "Oslo"
        )
        assertEquals(SkyThemeType.SNOW, result.hero.skyTheme)

        // Hero with SkyThemeType.SNOW resolves to WeatherEffectType.SNOW
        val snowHero = HeroWeather(
            tempDegrees = -2,
            feelsLikeDegrees = -5,
            highDegrees = 1,
            lowDegrees = -4,
            conditionText = "Winter Day",
            iconUri = null,
            isDaytime = true,
            locationName = "Oslo",
            skyTheme = SkyThemeType.SNOW
        )
        assertEquals(WeatherEffectType.SNOW, resolveActiveEffect(snowHero))
    }

    @Test
    fun testGetDefaultWeatherEffect_TimeOfDay() {
        val daytimeNoon = LocalTime.of(12, 0)
        assertEquals(WeatherEffectType.CLEAR_DAY, getDefaultWeatherEffect(daytimeNoon))

        val daytimeMorning = LocalTime.of(7, 30)
        assertEquals(WeatherEffectType.CLEAR_DAY, getDefaultWeatherEffect(daytimeMorning))

        val nighttimeMidnight = LocalTime.of(0, 0)
        assertEquals(WeatherEffectType.CLEAR_NIGHT, getDefaultWeatherEffect(nighttimeMidnight))

        val nighttimeLate = LocalTime.of(22, 15)
        assertEquals(WeatherEffectType.CLEAR_NIGHT, getDefaultWeatherEffect(nighttimeLate))

        val nighttimeEarly = LocalTime.of(4, 45)
        assertEquals(WeatherEffectType.CLEAR_NIGHT, getDefaultWeatherEffect(nighttimeEarly))
    }
}
