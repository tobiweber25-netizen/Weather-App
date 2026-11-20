package com.google.weatherapp.widget

import android.content.Context

enum class WidgetKind(
    val title: String,
    val subtitle: String,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
    val defaultBackgroundStyle: WidgetBackgroundStyle = WidgetBackgroundStyle.LIVING_SKY
) {
    LOCK_SCREEN(
        title = "Lock Screen Weather",
        subtitle = "Simple current temperature and weather icon (2×1)",
        defaultWidthDp = 156,
        defaultHeightDp = 64,
        defaultBackgroundStyle = WidgetBackgroundStyle.TRANSLUCENT
    ),
    TODAY_COMPACT(
        title = "Today Weather",
        subtitle = "Current conditions, temperature & high/low (2×2)",
        defaultWidthDp = 160,
        defaultHeightDp = 160
    ),
    TODAY_HYBRID(
        title = "Today & Next Hours",
        subtitle = "Current summary alongside upcoming 4-hour outlook (4×2)",
        defaultWidthDp = 328,
        defaultHeightDp = 156
    ),
    HOURLY_FORECAST(
        title = "Hourly Forecast",
        subtitle = "6-hour timeline with continuous thermal curve (4×2)",
        defaultWidthDp = 328,
        defaultHeightDp = 164
    ),
    DAILY_FORECAST(
        title = "5-Day Forecast",
        subtitle = "Today header + 5-day outlook with thermal range bars (4×3)",
        defaultWidthDp = 328,
        defaultHeightDp = 264
    )
}

enum class WidgetBackgroundStyle(
    val displayName: String,
    val description: String
) {
    LIVING_SKY(
        displayName = "Living Sky",
        description = "Reactive atmospheric sky gradient with frosted glass cards"
    ),
    TRANSLUCENT(
        displayName = "Fully Translucent",
        description = "Clear glass overlay letting your wallpaper shine through"
    )
}

class WidgetPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBackgroundStyle(
        appWidgetId: Int,
        defaultStyle: WidgetBackgroundStyle = WidgetBackgroundStyle.LIVING_SKY
    ): WidgetBackgroundStyle {
        val raw = prefs.getString(styleKey(appWidgetId), null) ?: return defaultStyle
        return try {
            WidgetBackgroundStyle.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            defaultStyle
        }
    }

    fun setBackgroundStyle(appWidgetId: Int, style: WidgetBackgroundStyle) {
        prefs.edit().putString(styleKey(appWidgetId), style.name).apply()
    }

    fun clearWidget(appWidgetId: Int) {
        prefs.edit().remove(styleKey(appWidgetId)).apply()
    }

    private fun styleKey(appWidgetId: Int): String = "widget_bg_style_$appWidgetId"

    companion object {
        private const val PREFS_NAME = "weather_widget_preferences"
    }
}
