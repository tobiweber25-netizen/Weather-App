package com.google.weatherapp.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPreferencesAndDataTest {

    @Test
    fun testWidgetPackContainsLockScreenTodayAndForecastWidgets() {
        val kinds = WidgetKind.entries
        assertEquals(5, kinds.size)
        assertEquals(
            listOf(
                WidgetKind.LOCK_SCREEN,
                WidgetKind.TODAY_COMPACT,
                WidgetKind.TODAY_HYBRID,
                WidgetKind.HOURLY_FORECAST,
                WidgetKind.DAILY_FORECAST
            ),
            kinds
        )
        kinds.forEach { kind ->
            assertTrue(kind.defaultWidthDp >= 110)
            assertTrue(kind.defaultHeightDp >= 40)
            assertTrue(kind.title.isNotBlank())
        }
        assertEquals(WidgetBackgroundStyle.TRANSLUCENT, WidgetKind.LOCK_SCREEN.defaultBackgroundStyle)
        assertEquals(WidgetBackgroundStyle.LIVING_SKY, WidgetKind.TODAY_COMPACT.defaultBackgroundStyle)
    }

    @Test
    fun testLockScreenCanvasSizingAndOrientationAdaptation() {
        // Default 2x1 pill uses horizontal layout and preserves compact height without stretching
        assertEquals(156 to 64, WeatherWidgetRenderer.resolveLockScreenCanvasSizeDp(156, 64))
        assertTrue(WeatherWidgetRenderer.isLockScreenHorizontalLayout(156, 64))
        assertTrue(WeatherWidgetRenderer.isLockScreenHorizontalLayout(110, 48))

        // Compact 1x1 square / tall slots preserve compact bounds and switch to stacked vertical layout
        assertEquals(56 to 56, WeatherWidgetRenderer.resolveLockScreenCanvasSizeDp(56, 56))
        assertFalse(WeatherWidgetRenderer.isLockScreenHorizontalLayout(56, 56))
        assertFalse(WeatherWidgetRenderer.isLockScreenHorizontalLayout(64, 80))

        // Clamps extreme sizes safely
        assertEquals(48 to 40, WeatherWidgetRenderer.resolveLockScreenCanvasSizeDp(10, 10))
        assertEquals(460 to 460, WeatherWidgetRenderer.resolveLockScreenCanvasSizeDp(900, 900))
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
