package com.google.weatherapp.notification

import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSummaryFormatterTest {

    private fun createDailyItem(
        dayLabel: String,
        minTemp: Int,
        maxTemp: Int,
        condition: String,
        rainProbability: Int = 0
    ) = DailyItem(
        dayLabel = dayLabel,
        dateText = "10/28",
        minTemp = minTemp,
        maxTemp = maxTemp,
        rainProbability = rainProbability,
        iconUri = null,
        conditionText = condition,
        morningWind = "",
        afternoonWind = ""
    )

    private fun createHourlyItem(
        hourLabel: String,
        tempDegrees: Int,
        rainProbability: Int = 0,
        precipitationMm: Double = 0.0
    ) = HourlyItem(
        hourLabel = hourLabel,
        tempDegrees = tempDegrees,
        rainProbability = rainProbability,
        precipitationMm = precipitationMm,
        iconUri = null,
        isDaytime = true
    )

    @Test
    fun testCoolerTomorrowWithCloud() {
        val today = createDailyItem("Today", minTemp = 15, maxTemp = 25, condition = "Sunny")
        val tomorrow = createDailyItem("Tomorrow", minTemp = 13, maxTemp = 21, condition = "Mostly Cloudy")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(
            today = today,
            tomorrow = tomorrow,
            locationName = "Cape Town"
        )

        assertEquals("Tomorrow's weather in Cape Town", summary.title)
        assertEquals("About 4° cooler tomorrow with cloud (21° / 13°).", summary.conciseText)
        assertEquals(-4, summary.tempDiff)
        assertTrue(summary.expandedDetails.contains("High: 21° • Low: 13°"))
    }

    @Test
    fun testWarmerTomorrowWithShowers() {
        val today = createDailyItem("Today", minTemp = 10, maxTemp = 18, condition = "Cloudy")
        val tomorrow = createDailyItem("Tomorrow", minTemp = 12, maxTemp = 23, condition = "Passing Showers", rainProbability = 70)

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(
            today = today,
            tomorrow = tomorrow,
            locationName = null
        )

        assertEquals("Tomorrow's weather", summary.title)
        assertEquals("About 5° warmer tomorrow with showers (23° / 12°).", summary.conciseText)
        assertEquals(5, summary.tempDiff)
        assertTrue(summary.expandedDetails.contains("Rain: 70%"))
    }

    @Test
    fun testSimilarTemperaturesWithClearSkies() {
        val today = createDailyItem("Today", minTemp = 15, maxTemp = 22, condition = "Sunny")
        val tomorrow = createDailyItem("Tomorrow", minTemp = 15, maxTemp = 22, condition = "Clear")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(
            today = today,
            tomorrow = tomorrow,
            locationName = "Berlin"
        )

        assertEquals("Similar temperatures tomorrow with clear skies (22° / 15°).", summary.conciseText)
        assertEquals(0, summary.tempDiff)
    }

    @Test
    fun testMorningSummaryWithRainTiming() {
        val today = createDailyItem("Today", minTemp = 14, maxTemp = 22, condition = "Cloudy")
        val hours = listOf(
            createHourlyItem("10 AM", 17, 10),
            createHourlyItem("1 PM", 21, 20),
            createHourlyItem("3 PM", 20, 65, precipitationMm = 1.2)
        )

        val summary = WeatherSummaryFormatter.generateMorningSummary(
            today = today,
            upcomingHours = hours,
            locationName = "London"
        )

        assertEquals("Today's forecast • London", summary.title)
        assertEquals("High of 22° with rain likely around 3 pm.", summary.conciseText)
        assertTrue(summary.expandedDetails.contains("High: 22° • Low: 14°"))
    }

    @Test
    fun testMorningSummaryClearDay() {
        val today = createDailyItem("Today", minTemp = 16, maxTemp = 27, condition = "Sunny")
        val hours = listOf(
            createHourlyItem("10 AM", 20, 0),
            createHourlyItem("1 PM", 25, 0),
            createHourlyItem("3 PM", 27, 5)
        )

        val summary = WeatherSummaryFormatter.generateMorningSummary(
            today = today,
            upcomingHours = hours,
            locationName = null
        )

        assertEquals("Today's forecast", summary.title)
        assertEquals("High of 27° today with clear skies.", summary.conciseText)
    }

    @Test
    fun testRainNowcastSummary() {
        val nowcast = PrecipitationNowcast(
            summaryText = "Rain starting in ~30 min (~2.4 mm/h)",
            isPrecipitationImminent = true,
            nextPrecipitationOnsetMinutes = 30,
            maxRateMmH = 2.4
        )

        val summary = WeatherSummaryFormatter.generateRainNowcastSummary(
            nowcast = nowcast,
            locationName = "Cape Town"
        )

        assertEquals("Rain alert • Cape Town", summary.title)
        assertEquals("Moderate rain starting in ~30 min (~2.4 mm/h) — grab an umbrella.", summary.conciseText)
        assertTrue(summary.expandedDetails.contains("Moderate rain starting in ~30 min"))
    }

    @Test
    fun testSevereStormSummary() {
        val alert = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.HIGH,
            thunderstormProbability = 80,
            title = "Severe Thunderstorm",
            subtitle = "Damaging winds and hail risk",
            isVisible = true,
            peakHourLabel = "4:30 PM"
        )

        val summary = WeatherSummaryFormatter.generateSevereAlertSummary(
            alert = alert,
            locationName = "Johannesburg"
        )
        assertEquals("Severe weather • Johannesburg", summary.title)
        assertEquals("Severe Thunderstorm watch peaking around 4:30 PM (80% risk).", summary.conciseText)
        assertTrue(summary.expandedDetails.contains("Thunderstorm probability: 80%"))
    }

    @Test
    fun testSevereStormSummary_NormalizesAllCapsAndAvoidsDuplicateWatchAndPercentage() {
        val alert = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.HIGH,
            thunderstormProbability = 40,
            title = "THUNDERSTORM WATCH",
            subtitle = "Elevated storm risk (40% peak probability). Peak activity expected around 15:00.",
            isVisible = true,
            peakHourLabel = "15:00"
        )

        val summary = WeatherSummaryFormatter.generateSevereAlertSummary(
            alert = alert,
            locationName = "Randburg"
        )
        assertEquals("Thunderstorm Watch peaking around 15:00 (40% risk).", summary.conciseText)
        org.junit.Assert.assertFalse(
            "Must never produce duplicate 'WATCH watch'",
            summary.conciseText.lowercase().contains("watch watch")
        )
        org.junit.Assert.assertFalse(
            "Must not append a duplicate Thunderstorm probability line when subtitle already states 40%",
            summary.expandedDetails.contains("Thunderstorm probability: 40%")
        )
    }

    @Test
    fun testMorningSummary_FiltersPastHoursAndTraceDrizzle() {
        val today = createDailyItem("Today", minTemp = 15, maxTemp = 26, condition = "Partly Cloudy")
        val hours = listOf(
            createHourlyItem("06:00", 16, 75, precipitationMm = 2.0), // Past morning shower
            createHourlyItem("15:00", 25, 80, precipitationMm = 3.5), // Past afternoon rain (3 PM)
            createHourlyItem("20:00", 20, 25, precipitationMm = 0.2), // Trace drizzle at 25% PoP -> ignore
            createHourlyItem("21:00", 19, 10, precipitationMm = 0.0)
        )

        // Evaluated at 20:00 (8 PM): 06:00 and 15:00 are in the past and must be ignored
        val summaryAt8Pm = WeatherSummaryFormatter.generateMorningSummary(
            today = today,
            upcomingHours = hours,
            locationName = "Randburg",
            minHourOfDay = 20
        )
        org.junit.Assert.assertFalse(
            "Must never announce past rain at 15:00 when evaluated at 20:00",
            summaryAt8Pm.conciseText.contains("15:00")
        )
        assertEquals("High of 26° today with cloud and sun.", summaryAt8Pm.conciseText)
    }

    @Test
    fun testMorningSummary_ContiguousRainWindowAndNowFormatting() {
        val today = createDailyItem("Today", minTemp = 14, maxTemp = 23, condition = "Showers")
        val windowHours = listOf(
            createHourlyItem("13:00", 22, 15, 0.0),
            createHourlyItem("14:00", 21, 60, 1.5),
            createHourlyItem("15:00", 20, 75, 2.4),
            createHourlyItem("16:00", 19, 55, 0.8),
            createHourlyItem("17:00", 19, 10, 0.0)
        )
        val windowSummary = WeatherSummaryFormatter.generateMorningSummary(today, windowHours, "Randburg")
        assertEquals("High of 23° with rain likely from 14:00 to 16:00.", windowSummary.conciseText)

        val nowHours = listOf(
            createHourlyItem("Now", 20, 80, 2.0),
            createHourlyItem("08:00", 19, 60, 1.0)
        )
        val nowSummary = WeatherSummaryFormatter.generateMorningSummary(today, nowHours, "Randburg")
        assertEquals("High of 23° with rain right now.", nowSummary.conciseText)
        org.junit.Assert.assertFalse(nowSummary.conciseText.contains("around now"))
    }

    @Test
    fun testHeatWarningSummary_ExtremeHeatAndTropicalNight() {
        val today = createDailyItem("Today", minTemp = 22, maxTemp = 37, condition = "Sunny")
        val summary = WeatherSummaryFormatter.generateHeatWarningSummary(
            today = today,
            locationName = "Phoenix"
        )
        assertEquals("Extreme Heat • Phoenix", summary.title)
        assertTrue(summary.conciseText.contains("Dangerous high of 37°C"))
        assertTrue(summary.conciseText.contains("low staying at 22°C"))
        assertTrue(summary.isExtremeHeat)
        assertTrue(summary.isTropicalNight)
    }

    @Test
    fun testHeatWarningSummary_ExtremeHeatOnly() {
        val today = createDailyItem("Today", minTemp = 18, maxTemp = 36, condition = "Sunny")
        val summary = WeatherSummaryFormatter.generateHeatWarningSummary(
            today = today,
            locationName = null
        )
        assertEquals("Extreme Heat", summary.title)
        assertTrue(summary.conciseText.contains("Dangerous high of 36°C"))
        assertTrue(summary.isExtremeHeat)
        org.junit.Assert.assertFalse(summary.isTropicalNight)
    }

    @Test
    fun testHeatWarningSummary_TropicalNightOnly() {
        val today = createDailyItem("Today", minTemp = 21, maxTemp = 28, condition = "Humid")
        val summary = WeatherSummaryFormatter.generateHeatWarningSummary(
            today = today,
            locationName = "Singapore"
        )
        assertEquals("Tropical Night • Singapore", summary.title)
        assertTrue(summary.conciseText.contains("Tropical night ahead"))
        org.junit.Assert.assertFalse(summary.isExtremeHeat)
        assertTrue(summary.isTropicalNight)
    }

    @Test
    fun testFreezeHazardSummary_HardFreeze() {
        val today = createDailyItem("Today", minTemp = -6, maxTemp = 2, condition = "Clear")
        val summary = WeatherSummaryFormatter.generateFreezeHazardSummary(
            today = today,
            locationName = "Minneapolis"
        )
        assertEquals("Hard Freeze • Minneapolis", summary.title)
        assertTrue(summary.conciseText.contains("Hard freeze expected with low plunging to -6°C"))
        assertTrue(summary.isHardFreeze)
        org.junit.Assert.assertFalse(summary.isBlackIce)
    }

    @Test
    fun testFreezeHazardSummary_BlackIce() {
        val today = createDailyItem("Today", minTemp = -1, maxTemp = 3, condition = "Rain", rainProbability = 60)
        val summary = WeatherSummaryFormatter.generateFreezeHazardSummary(
            today = today,
            locationName = "Munich"
        )
        assertEquals("Black Ice Risk • Munich", summary.title)
        assertTrue(summary.conciseText.contains("black ice"))
        assertTrue(summary.isBlackIce)
    }

    @Test
    fun testFreezeHazardSummary_Frost() {
        val today = createDailyItem("Today", minTemp = 0, maxTemp = 8, condition = "Clear")
        val summary = WeatherSummaryFormatter.generateFreezeHazardSummary(
            today = today,
            locationName = "Zurich"
        )
        assertEquals("Frost & Freeze • Zurich", summary.title)
        assertTrue(summary.conciseText.contains("Frost warning: Overnight low dropping to 0°C"))
        org.junit.Assert.assertFalse(summary.isHardFreeze)
        org.junit.Assert.assertFalse(summary.isBlackIce)
    }

    @Test
    fun testWindAlertSummary_GaleAndSevereGale() {
        val galeTelemetry = com.google.weatherapp.data.model.BentoTelemetry(
            windSpeedKmH = 52,
            windDirection = "W",
            windGustKmH = 68,
            precipitationMm = 0.0,
            uvIndex = 3,
            uvCategory = "Low",
            humidityPercent = 60,
            dewPointDegrees = 8,
            pressureHpa = 1005,
            sunriseTime = "06:00",
            sunsetTime = "18:00"
        )
        val galeSummary = WeatherSummaryFormatter.generateWindAlertSummary(galeTelemetry, "Hamburg")
        assertEquals("High Wind Alert • Hamburg", galeSummary.title)
        assertTrue(galeSummary.conciseText.contains("Sustained 52 km/h with gusts up to 68 km/h"))
        org.junit.Assert.assertFalse(galeSummary.isSevereGale)

        val severeTelemetry = galeTelemetry.copy(windGustKmH = 92)
        val severeSummary = WeatherSummaryFormatter.generateWindAlertSummary(severeTelemetry, "Hamburg")
        assertEquals("Severe Wind Alert • Hamburg", severeSummary.title)
        assertTrue(severeSummary.isSevereGale)
    }

    @Test
    fun testAqiAlertSummary_SensitiveAndUnhealthy() {
        val sensitiveTelemetry = com.google.weatherapp.data.model.BentoTelemetry(
            windSpeedKmH = 10,
            windDirection = "N",
            windGustKmH = 15,
            precipitationMm = 0.0,
            uvIndex = 4,
            uvCategory = "Moderate",
            humidityPercent = 45,
            dewPointDegrees = 10,
            pressureHpa = 1012,
            sunriseTime = "06:00",
            sunsetTime = "18:00",
            aqiValue = 125,
            aqiCategory = "Moderate/Sensitive",
            pm25 = 38.5
        )
        val sensitiveSummary = WeatherSummaryFormatter.generateAqiAlertSummary(sensitiveTelemetry, "Los Angeles")
        assertEquals("Air Quality Alert • Los Angeles", sensitiveSummary.title)
        assertTrue(sensitiveSummary.conciseText.contains("Air quality index is 125"))
        org.junit.Assert.assertFalse(sensitiveSummary.isUnhealthy)

        val unhealthyTelemetry = sensitiveTelemetry.copy(aqiValue = 165, aqiCategory = "Unhealthy", pm25 = 52.0)
        val unhealthySummary = WeatherSummaryFormatter.generateAqiAlertSummary(unhealthyTelemetry, "Los Angeles")
        assertEquals("Poor Air Quality • Los Angeles", unhealthySummary.title)
        assertTrue(unhealthySummary.isUnhealthy)
    }

    @Test
    fun testFogAlertSummary() {
        val fogTelemetry = com.google.weatherapp.data.model.BentoTelemetry(
            windSpeedKmH = 5,
            windDirection = "CALM",
            windGustKmH = 8,
            precipitationMm = 0.0,
            uvIndex = 1,
            uvCategory = "Low",
            humidityPercent = 95,
            dewPointDegrees = 8,
            pressureHpa = 1020,
            sunriseTime = "06:00",
            sunsetTime = "18:00",
            visibilityKm = 0.5
        )
        val summary = WeatherSummaryFormatter.generateFogAlertSummary(fogTelemetry, "San Francisco")
        assertEquals("Dense Fog • San Francisco", summary.title)
        assertTrue(summary.conciseText.contains("Visibility down to 0.5 km in dense fog (humidity 95%)"))
    }

    @Test
    fun testRainCessationSummary() {
        val nowcast = PrecipitationNowcast(
            summaryText = "Rain stopping in ~25 min",
            isPrecipitationImminent = true,
            maxRateMmH = 1.8,
            rainCessationMinutes = 25
        )
        val summary = WeatherSummaryFormatter.generateRainCessationSummary(nowcast, "Dublin")
        assertEquals("Rain Clearing Soon • Dublin", summary.title)
        assertEquals("Rain stopping in ~25 min — skies drying out soon.", summary.conciseText)
        assertEquals(25, summary.cessationMinutes)
    }

    @Test
    fun testUvAlertSummary_HighAndExtreme() {
        val todayHigh = createDailyItem("Today", 16, 28, "Sunny").copy(uvIndex = 9, uvCategory = "Very High")
        val highSummary = WeatherSummaryFormatter.generateUvAlertSummary(todayHigh, null, "Miami")
        assertEquals("High UV Alert • Miami", highSummary.title)
        assertTrue(highSummary.conciseText.contains("Peak UV index reaches 9 today"))
        org.junit.Assert.assertFalse(highSummary.isExtreme)

        val todayExtreme = todayHigh.copy(uvIndex = 12, uvCategory = "Extreme")
        val extremeSummary = WeatherSummaryFormatter.generateUvAlertSummary(todayExtreme, null, "Miami")
        assertEquals("Extreme UV Alert • Miami", extremeSummary.title)
        assertTrue(extremeSummary.isExtreme)
    }

    @Test
    fun testTempDropSummary() {
        val today = createDailyItem("Today", 15, 26, "Sunny")
        val tomorrow = createDailyItem("Tomorrow", 8, 17, "Breezy")
        val summary = WeatherSummaryFormatter.generateTempDropSummary(today, tomorrow, "Chicago")

        assertEquals("Cold Front Tomorrow • Chicago", summary.title)
        assertEquals(9, summary.dropDegrees)
        assertTrue(summary.conciseText.contains("Tomorrow drops by 9°C to a high of 17°C (vs 26°C today)"))
    }

    @Test
    fun testMorningSummary_SolarNoonProtectionGuidance() {
        val todayHighUv = createDailyItem("Today", 18, 30, "Sunny").copy(uvIndex = 9, uvCategory = "Very High")
        val summary = WeatherSummaryFormatter.generateMorningSummary(todayHighUv, emptyList(), "Miami")
        assertTrue(summary.expandedDetails.contains("Solar noon protection recommended (11 AM - 3 PM)"))

        val todayLowUv = createDailyItem("Today", 12, 18, "Overcast").copy(uvIndex = 3, uvCategory = "Moderate")
        val lowSummary = WeatherSummaryFormatter.generateMorningSummary(todayLowUv, emptyList(), "London")
        org.junit.Assert.assertFalse(lowSummary.expandedDetails.contains("Solar noon protection"))
    }

    @Test
    fun testTomorrowSummary_FrontalShiftGuidance() {
        val today = createDailyItem("Today", 16, 28, "Sunny")
        val tomorrow = createDailyItem("Tomorrow", 8, 19, "Windy")
        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Denver")
        assertTrue(summary.expandedDetails.contains("Sharp cold front: Tomorrow's high drops by 9°C"))

        val tomorrowMild = createDailyItem("Tomorrow", 14, 25, "Sunny")
        val mildSummary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrowMild, "Denver")
        org.junit.Assert.assertFalse(mildSummary.expandedDetails.contains("Sharp cold front"))
    }
}

