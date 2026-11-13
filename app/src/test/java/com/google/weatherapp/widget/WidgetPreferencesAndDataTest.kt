package com.google.weatherapp.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPreferencesAndDataTest {

    @Test
    fun testWidgetPackContainsOnlyTodayAndForecastWidgets() {
        val kinds = WidgetKind.entries
        assertEquals(4, kinds.size)
        assertEquals(
            listOf(
                WidgetKind.TODAY_COMPACT,
                WidgetKind.TODAY_HYBRID,
                WidgetKind.HOURLY_FORECAST,
                WidgetKind.DAILY_FORECAST
            ),
            kinds
        )
        kinds.forEach { kind ->
            assertTrue(kind.defaultWidthDp >= 110)
            assertTrue(kind.defaultHeightDp >= 110)
            assertTrue(kind.title.isNotBlank())
        }
    }

    @Test
    fun testWidgetBackgroundStylesIncludeLivingSkyAndTranslucent() {
        val styles = WidgetBackgroundStyle.entries
        assertEquals(
            listOf(WidgetBackgroundStyle.LIVING_SKY, WidgetBackgroundStyle.TRANSLUCENT),
            styles
        )
    }

    @Test
    fun testSamplePreviewDashboardDataCoversHourlyAndFiveDayForecast() {
        val sample = WeatherWidgetRenderer.samplePreviewDashboardData()
        assertTrue(sample.hero.locationName.isNotBlank())
        assertTrue(sample.hourlyList.size >= 6)
        assertTrue(sample.dailyList.size >= 5)
        assertEquals("Now", sample.hourlyList.first().hourLabel)
        assertEquals("Today", sample.dailyList.first().dayLabel)
    }
}
