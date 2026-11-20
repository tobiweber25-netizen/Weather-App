package com.google.weatherapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.google.weatherapp.data.model.*
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.components.*

private fun previewSampleData(
    isDaytime: Boolean = true,
    conditionText: String = "Partly Cloudy",
    skyTheme: SkyThemeType = SkyThemeType.OVERCAST
): WeatherDashboardData {
    return WeatherDashboardData(
        hero = HeroWeather(
            tempDegrees = if (isDaytime) 24 else 16,
            feelsLikeDegrees = if (isDaytime) 25 else 15,
            highDegrees = 27,
            lowDegrees = 14,
            conditionText = conditionText,
            iconUri = null,
            isDaytime = isDaytime,
            locationName = "Cape Town",
            skyTheme = skyTheme,
            weatherStory = "Warm coastal light slips behind high Atlantic clouds—perfect for an unhurried walk before the evening breeze cools.",
            modelSourcesLabel = "Google + ECMWF",
            lastUpdatedEpochMs = System.currentTimeMillis() - 120_000L
        ),
        alert = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.MODERATE,
            thunderstormProbability = 45,
            title = "Evening Coastal Showers",
            subtitle = "Brief passing squalls possible along the peninsula after sunset.",
            isVisible = !isDaytime,
            peakHourLabel = "20:00",
            hourlyStormProbabilities = listOf(10, 15, 25, 40, 45, 35, 20, 10, 5, 5, 10, 15)
        ),
        hourlyList = listOf(
            HourlyItem("Now", 24, 15, null, true, "Partly Cloudy", windSpeedKmH = 16, uvIndex = 6, humidityPercent = 56),
            HourlyItem("15:00", 26, 10, null, true, "Mostly Sunny", windSpeedKmH = 18, uvIndex = 7, humidityPercent = 52),
            HourlyItem("16:00", 27, 20, null, true, "Partly Cloudy", windSpeedKmH = 21, uvIndex = 5, humidityPercent = 54),
            HourlyItem("17:00", 25, 35, null, true, "Light Rain", windSpeedKmH = 24, precipitationMm = 1.2, uvIndex = 3, humidityPercent = 64),
            HourlyItem("18:00", 22, 45, null, true, "Scattered Showers", windSpeedKmH = 20, precipitationMm = 2.4, uvIndex = 1, humidityPercent = 72),
            HourlyItem("19:00", 19, 25, null, false, "Partly Cloudy", windSpeedKmH = 14, uvIndex = 0, humidityPercent = 75),
            HourlyItem("20:00", 17, 10, null, false, "Clear Night", windSpeedKmH = 11, uvIndex = 0, humidityPercent = 78)
        ),
        dailyList = listOf(
            DailyItem("Today", "11/19", 14, 27, 35, null, "Partly Cloudy", "AM: 14 km/h NW", "PM: 22 km/h SW"),
            DailyItem("Fri", "11/20", 13, 22, 70, null, "Scattered Showers", "AM: 18 km/h W", "PM: 26 km/h SW"),
            DailyItem("Sat", "11/21", 12, 24, 15, null, "Mostly Sunny", "AM: 10 km/h S", "PM: 16 km/h SE"),
            DailyItem("Sun", "11/22", 15, 29, 0, null, "Clear and Sunny", "AM: 8 km/h SE", "PM: 14 km/h SE"),
            DailyItem("Mon", "11/23", 16, 26, 45, null, "Thunderstorms", "AM: 12 km/h NW", "PM: 32 km/h NW")
        ),
        telemetry = BentoTelemetry(
            windSpeedKmH = 18,
            windDirection = "NW",
            windGustKmH = 29,
            precipitationMm = 3.6,
            uvIndex = 6,
            uvCategory = "High",
            humidityPercent = 56,
            dewPointDegrees = 14,
            pressureHpa = 1018,
            sunriseTime = "05:32",
            sunsetTime = "19:34",
            windDegrees = 315,
            maxUvIndex = 8,
            aqiValue = 22,
            aqiCategory = "Good",
            pm25 = 4.8,
            visibilityKm = 14.5,
            sunProgress = 0.62f,
            feelsLikeDegrees = 25,
            feelsLikeReason = "Gentle sea breeze keeps the afternoon comfortable"
        )
    )
}

@Preview(name = "Almanac Day Dashboard", widthDp = 412, heightDp = 1580, showBackground = true)
@Composable
fun AlmanacDayDashboardPreview() {
    val data = previewSampleData(isDaytime = true, conditionText = "Partly Cloudy", skyTheme = SkyThemeType.CLEAR_DAY)
    WeatherAppTheme(darkTheme = false) {
        CompositionLocalProvider(LocalSkyIsBright provides true) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(SkyDayClearStart, SkyDayClearMid, SkyDayClearEnd))
                    )
                    .background(
                        Brush.verticalGradient(listOf(SkyContrastScrimTop, SkyContrastScrimMidDark, SkyContrastScrimBottomDark))
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = SpacingTokens.Large),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
                ) {
                    HeroWeatherSection(hero = data.hero, hourlyList = data.hourlyList, onLocationClick = {})
                    HourlyForecastStrip(hourlyItems = data.hourlyList)
                    DailyForecastList(dailyList = data.dailyList, currentTemp = data.hero.tempDegrees)
                    BentoTelemetryGrid(telemetry = data.telemetry, hourlyList = data.hourlyList, isDaytime = true)
                }
            }
        }
    }
}

@Preview(name = "Almanac Night Storm Dashboard", widthDp = 412, heightDp = 1580, showBackground = true)
@Composable
fun AlmanacNightDashboardPreview() {
    val data = previewSampleData(isDaytime = false, conditionText = "Partly Cloudy", skyTheme = SkyThemeType.CLEAR_NIGHT)
    WeatherAppTheme(darkTheme = true) {
        CompositionLocalProvider(LocalSkyIsBright provides false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(SkyNightClearStart, SkyNightClearMid, SkyNightClearEnd))
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = SpacingTokens.Large),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
                ) {
                    HeroWeatherSection(hero = data.hero, hourlyList = data.hourlyList, onLocationClick = {})
                    SevereWeatherAlertPill(alert = data.alert)
                    HourlyForecastStrip(hourlyItems = data.hourlyList)
                    DailyForecastList(dailyList = data.dailyList, currentTemp = data.hero.tempDegrees)
                    BentoTelemetryGrid(telemetry = data.telemetry, hourlyList = data.hourlyList, isDaytime = false)
                }
            }
        }
    }
}

@Preview(name = "Almanac Icon Gallery", widthDp = 412, heightDp = 280, showBackground = true)
@Composable
fun AlmanacIconGalleryPreview() {
    WeatherAppTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(SkyNightClearStart, SkyNightClearEnd)))
                .padding(SpacingTokens.Base)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NothingWeatherIcon(WeatherEffectType.CLEAR_DAY, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.CLOUDY, isPartlyCloudy = true, isDaytime = true, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.CLOUDY, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.RAIN, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NothingWeatherIcon(WeatherEffectType.CLEAR_NIGHT, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.CLOUDY, isPartlyCloudy = true, isDaytime = false, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.THUNDERSTORM, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                    NothingWeatherIcon(WeatherEffectType.SNOW, animate = false, modifier = Modifier.size(IconSizeTokens.Hero))
                }
            }
        }
    }
}
