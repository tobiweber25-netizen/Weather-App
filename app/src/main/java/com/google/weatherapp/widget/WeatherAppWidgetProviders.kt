package com.google.weatherapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.google.weatherapp.MainActivity
import com.google.weatherapp.R
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.data.repository.WeatherRepository
import com.google.weatherapp.location.LocationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WeatherWidgetUpdater {

    private val providerMap: List<Pair<Class<out BaseWeatherWidgetProvider>, WidgetKind>> = listOf(
        TodayCompactWidgetReceiver::class.java to WidgetKind.TODAY_COMPACT,
        TodayHybridWidgetReceiver::class.java to WidgetKind.TODAY_HYBRID,
        HourlyForecastWidgetReceiver::class.java to WidgetKind.HOURLY_FORECAST,
        DailyForecastWidgetReceiver::class.java to WidgetKind.DAILY_FORECAST
    )

    fun resolveWidgetKind(context: Context, appWidgetId: Int): WidgetKind {
        val manager = AppWidgetManager.getInstance(context)
        val providerClass = manager.getAppWidgetInfo(appWidgetId)?.provider?.className
        return providerMap.firstOrNull { it.first.name == providerClass }?.second
            ?: WidgetKind.TODAY_COMPACT
    }

    fun updateAllWidgets(context: Context, preloadedData: WeatherDashboardData? = null) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val data = preloadedData ?: WeatherLocalDataSource(appContext).getLatestCachedWeatherIgnoringTtl()

        providerMap.forEach { (cls, kind) ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, cls))
            ids.forEach { id ->
                updateSingleWidget(
                    context = appContext,
                    appWidgetManager = manager,
                    appWidgetId = id,
                    kind = kind,
                    preloadedData = data
                )
            }
        }
    }

    fun updateSingleWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        kind: WidgetKind,
        options: Bundle? = null,
        preloadedData: WeatherDashboardData? = null
    ) {
        val appContext = context.applicationContext
        val opts = options ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            .takeIf { it > 0 } ?: kind.defaultWidthDp
        val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            .takeIf { it > 0 } ?: kind.defaultHeightDp

        val style = WidgetPreferences(appContext).getBackgroundStyle(appWidgetId)
        val data = preloadedData ?: WeatherLocalDataSource(appContext).getLatestCachedWeatherIgnoringTtl()

        val bitmap = WeatherWidgetRenderer.renderWidgetBitmap(
            context = appContext,
            kind = kind,
            style = style,
            data = data,
            widthDp = minW,
            heightDp = maxH
        )

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            appWidgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val views = RemoteViews(appContext.packageName, R.layout.weather_widget_canvas).apply {
            setImageViewBitmap(R.id.widget_canvas_image, bitmap)
            setOnClickPendingIntent(R.id.widget_root_container, pendingIntent)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    suspend fun fetchFreshWeatherIfNeeded(context: Context): WeatherDashboardData? {
        val appContext = context.applicationContext
        val localSource = WeatherLocalDataSource(appContext)
        val freshCached = localSource.getCachedWeather()
        if (freshCached != null) return freshCached

        val repo = WeatherRepository(localDataSource = localSource)
        val customLoc = repo.getSelectedCustomLocation()
        return try {
            if (customLoc != null && !customLoc.isCurrentGps) {
                repo.getWeatherData(
                    latitude = customLoc.latitude,
                    longitude = customLoc.longitude,
                    resolvedLocationName = customLoc.name
                )
            } else {
                val coords = LocationClient(appContext).fetchLocation(forceFresh = false)
                if (coords != null) {
                    repo.getWeatherData(
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        resolvedLocationName = coords.locationName,
                        altitude = coords.altitude
                    )
                } else {
                    val fallbackCity = repo.getSavedLocations().firstOrNull()
                    if (fallbackCity != null) {
                        // Do not overwrite deviceWeather with a fallback saved city when GPS is unavailable.
                        WeatherRepository(localDataSource = null).getWeatherData(
                            latitude = fallbackCity.latitude,
                            longitude = fallbackCity.longitude,
                            resolvedLocationName = fallbackCity.name
                        )
                    } else {
                        localSource.getLatestCachedWeatherIgnoringTtl()
                    }
                }
            }
        } catch (_: Exception) {
            localSource.getLatestCachedWeatherIgnoringTtl()
        }
    }
}

abstract class BaseWeatherWidgetProvider(
    private val kind: WidgetKind
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val cached = WeatherLocalDataSource(context).getLatestCachedWeatherIgnoringTtl()
        appWidgetIds.forEach { id ->
            WeatherWidgetUpdater.updateSingleWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = id,
                kind = kind,
                preloadedData = cached
            )
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val updated = WeatherWidgetUpdater.fetchFreshWeatherIfNeeded(context)
                if (updated != null) {
                    appWidgetIds.forEach { id ->
                        WeatherWidgetUpdater.updateSingleWidget(
                            context = context,
                            appWidgetManager = appWidgetManager,
                            appWidgetId = id,
                            kind = kind,
                            preloadedData = updated
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WeatherWidgetUpdater.updateSingleWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            kind = kind,
            options = newOptions
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = WidgetPreferences(context)
        appWidgetIds.forEach { prefs.clearWidget(it) }
    }
}

/** 1. Today Weather Compact (2x2) */
class TodayCompactWidgetReceiver : BaseWeatherWidgetProvider(WidgetKind.TODAY_COMPACT)

/** 2. Today + Next Hours Hybrid (4x2) */
class TodayHybridWidgetReceiver : BaseWeatherWidgetProvider(WidgetKind.TODAY_HYBRID)

/** 3. Hourly Forecast Strip (4x2) */
class HourlyForecastWidgetReceiver : BaseWeatherWidgetProvider(WidgetKind.HOURLY_FORECAST)

/** 4. 5-Day Forecast Card (4x3) */
class DailyForecastWidgetReceiver : BaseWeatherWidgetProvider(WidgetKind.DAILY_FORECAST)
