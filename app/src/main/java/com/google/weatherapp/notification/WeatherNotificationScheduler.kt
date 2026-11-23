package com.google.weatherapp.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.work.*
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.model.SkyThemeType
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.data.repository.WeatherConsensus
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.data.repository.WeatherRepository
import com.google.weatherapp.location.LocationClient
import com.google.weatherapp.widget.WeatherWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Robust AlarmManager-first scheduler for weather notifications with WorkManager background sync.
 * Uses AlarmManager (with exact and allow-while-idle fallback) for precise daily delivery,
 * and WorkManager for periodic background network synchronization, multi-hazard alerts, and watchdog catch-up.
 */
object WeatherNotificationScheduler {

    private const val TAG = "WeatherNotif"
    private const val REQUEST_CODE_MORNING = 3001
    private const val REQUEST_CODE_EVENING = 3002

    const val UNIQUE_WORK_MORNING = "weather_work_morning"
    const val UNIQUE_WORK_EVENING = "weather_work_evening"
    const val UNIQUE_WORK_PERIODIC = "weather_work_periodic"

    enum class AlarmScheduleStrategy {
        SET_EXACT_AND_ALLOW_WHILE_IDLE,
        SET_AND_ALLOW_WHILE_IDLE,
        SET_EXACT
    }

    fun determineScheduleStrategy(
        sdkInt: Int = Build.VERSION.SDK_INT,
        canScheduleExact: Boolean = true
    ): AlarmScheduleStrategy {
        return when {
            sdkInt >= Build.VERSION_CODES.S -> {
                if (canScheduleExact) AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE
                else AlarmScheduleStrategy.SET_AND_ALLOW_WHILE_IDLE
            }
            sdkInt >= Build.VERSION_CODES.M -> AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE
            else -> AlarmScheduleStrategy.SET_EXACT
        }
    }

    fun isExactAlarmPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (prefs.isWeatherNotificationsEnabled) {
            scheduleMorning(appContext)
            scheduleEvening(appContext)
            schedulePeriodicSync(appContext)
        } else {
            cancelAll(appContext)
        }
    }

    fun ensureAlarmsScheduled(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (prefs.isWeatherNotificationsEnabled) {
            scheduleMorning(appContext)
            scheduleEvening(appContext)
        }
    }

    fun scheduleMorning(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (!prefs.isWeatherNotificationsEnabled || !prefs.isMorningBriefingEnabled) return

        val triggerAtMillis = computeNextTriggerMillis(
            targetHour = prefs.morningHour,
            targetMinute = prefs.morningMinute
        )

        scheduleAlarm(
            context = appContext,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = createMorningPendingIntent(appContext)
        )
    }

    fun scheduleEvening(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (!prefs.isWeatherNotificationsEnabled || !prefs.isEveningOutlookEnabled) return

        val triggerAtMillis = computeNextTriggerMillis(
            targetHour = prefs.eveningHour,
            targetMinute = prefs.eveningMinute
        )

        scheduleAlarm(
            context = appContext,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = createEveningPendingIntent(appContext)
        )
    }

    internal var alarmSchedulerOverride: ((
        triggerAtMillis: Long,
        pendingIntent: PendingIntent?,
        strategy: AlarmScheduleStrategy
    ) -> Unit)? = null

    fun scheduleAlarm(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent?
    ) {
        val strategy = determineScheduleStrategy(
            sdkInt = Build.VERSION.SDK_INT,
            canScheduleExact = isExactAlarmPermissionGranted(context)
        )
        if (alarmSchedulerOverride != null) {
            alarmSchedulerOverride!!.invoke(triggerAtMillis, pendingIntent, strategy)
            return
        }

        if (pendingIntent == null) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm rejected, using inexact allow-while-idle fallback", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } catch (e2: Throwable) {
                Log.e(TAG, "Fallback alarm scheduling failed", e2)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Alarm scheduling failed", e)
        }
    }

    fun schedulePeriodicSync(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (!prefs.isWeatherNotificationsEnabled) return

        // Do not gate the periodic watchdog/catch-up on NetworkType.CONNECTED:
        // Doze/offline states would otherwise block ensureAlarmsScheduled() and catch-up delivery.
        val periodicWork = PeriodicWorkRequestBuilder<WeatherNotificationWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setInputData(workDataOf(WeatherNotificationWorker.KEY_NOTIFICATION_TYPE to WeatherNotificationWorker.TYPE_PERIODIC_CHECK))
            .addTag(UNIQUE_WORK_PERIODIC)
            .build()

        try {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWork
            )
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun schedulePeriodicCheck(context: Context) = schedulePeriodicSync(context)

    fun enqueueImmediateBriefing(context: Context, type: String) {
        val appContext = context.applicationContext
        val workRequest = OneTimeWorkRequestBuilder<WeatherNotificationWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(WeatherNotificationWorker.KEY_NOTIFICATION_TYPE to type))
            .build()

        val uniqueName = if (type == WeatherNotificationWorker.TYPE_MORNING) UNIQUE_WORK_MORNING else UNIQUE_WORK_EVENING
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun cancelMorning(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            createMorningPendingIntent(appContext)?.let { alarmManager.cancel(it) }
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun cancelEvening(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            createEveningPendingIntent(appContext)?.let { alarmManager.cancel(it) }
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun enqueueBackgroundSync(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<WeatherNotificationWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(WeatherNotificationWorker.KEY_NOTIFICATION_TYPE to WeatherNotificationWorker.TYPE_BACKGROUND_SYNC))
            .build()

        try {
            WorkManager.getInstance(appContext).enqueue(workRequest)
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        try {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(UNIQUE_WORK_MORNING)
            workManager.cancelUniqueWork(UNIQUE_WORK_EVENING)
            workManager.cancelUniqueWork(UNIQUE_WORK_PERIODIC)
        } catch (_: Throwable) {
            // Ignore
        }

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            createMorningPendingIntent(appContext)?.let { alarmManager.cancel(it) }
            createEveningPendingIntent(appContext)?.let { alarmManager.cancel(it) }
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun cancel(context: Context) = cancelAll(context)

    const val MAX_EVENT_ALERT_DATA_AGE_MS = 45 * 60 * 1000L

    // Alert Detection Rules
    fun isSevereAlert(alert: SevereAlertInfo): Boolean {
        if (!alert.isVisible || alert.riskLevel == SeverityRiskLevel.LOW) return false
        if (alert.officialAlerts.any { it.severity == SeverityRiskLevel.HIGH || it.severity == SeverityRiskLevel.SEVERE }) {
            return true
        }
        if (alert.hourlyStormProbabilities.isNotEmpty()) {
            val nearTermMax = alert.hourlyStormProbabilities.take(6).maxOrNull() ?: 0
            if (nearTermMax < 30) return false
        } else if (alert.peakHourLabel?.startsWith("Tomorrow", ignoreCase = true) == true) {
            // Only suppress "Tomorrow" labels when we have no hourly curve to verify proximity;
            // otherwise nearTermMax (next 6 hours) accurately permits imminent post-midnight storms
            // while blocking distant storms 18 hours away.
            return false
        }
        return alert.riskLevel == SeverityRiskLevel.HIGH ||
            alert.riskLevel == SeverityRiskLevel.SEVERE ||
            alert.thunderstormProbability >= 60
    }

    fun isQuietHoursExemptSevereAlert(alert: SevereAlertInfo): Boolean {
        if (!isSevereAlert(alert)) return false
        if (alert.officialAlerts.any { it.severity == SeverityRiskLevel.SEVERE }) return true
        if (alert.riskLevel != SeverityRiskLevel.SEVERE && alert.thunderstormProbability < 60) return false
        if (alert.hourlyStormProbabilities.isNotEmpty()) {
            val imminentMax = alert.hourlyStormProbabilities.take(3).maxOrNull() ?: 0
            if (imminentMax < 60) return false
        }
        return true
    }

    fun isExtremeHeatAlert(
        today: DailyItem,
        hero: HeroWeather? = null,
        currentLocalHour: Int? = null
    ): Boolean {
        if (currentLocalHour != null && (currentLocalHour >= 17 || currentLocalHour < 8)) {
            val currentTemp = hero?.tempDegrees ?: return false
            val currentFeels = hero.feelsLikeDegrees
            return currentTemp >= 35 || currentFeels >= 40
        }
        val effectiveMax = maxOf(today.maxTemp, hero?.tempDegrees ?: today.maxTemp)
        val effectiveFeels = maxOf(today.feelsLikeMaxTemp ?: today.maxTemp, hero?.feelsLikeDegrees ?: today.maxTemp)
        // A warm night alone (minTemp >= 20) is normal in tropical climates; it only enriches the text.
        return effectiveMax >= 35 || effectiveFeels >= 40
    }

    fun isFreezeHazard(today: DailyItem, telemetry: BentoTelemetry? = null): Boolean {
        val isSubZero = today.minTemp <= 0
        val isHardFreeze = today.minTemp <= -4
        val isWetSurface = today.precipitationMm > 0.0 || today.rainProbability >= 30 || (telemetry != null && telemetry.precipitationMm > 0.0)
        // Road surfaces can freeze while air is slightly above 0°C; a low dew point alone does not imply ice.
        val isBlackIce = isWetSurface && today.minTemp <= 2
        return isSubZero || isHardFreeze || isBlackIce
    }

    fun isGaleWindAlert(
        telemetry: BentoTelemetry,
        today: DailyItem? = null,
        currentLocalHour: Int? = null
    ): Boolean {
        val liveGale = telemetry.windSpeedKmH >= 50 || telemetry.windGustKmH >= 65
        if (currentLocalHour != null && currentLocalHour >= 17) {
            return liveGale
        }
        return liveGale || (today != null && today.dayWindSpeedKmH >= 50)
    }

    fun isPoorAqiAlert(telemetry: BentoTelemetry): Boolean {
        return WeatherConsensus.isPoorAirQuality(
            aqiValue = telemetry.aqiValue,
            scaleLabel = telemetry.aqiScaleLabel,
            pm25 = telemetry.pm25,
            category = telemetry.aqiCategory
        )
    }

    fun isDenseFogAlert(telemetry: BentoTelemetry): Boolean {
        return telemetry.visibilityKm in 0.0..1.0 && telemetry.humidityPercent >= 90
    }

    fun isRainCessationAlert(nowcast: PrecipitationNowcast?): Boolean {
        val mins = nowcast?.rainCessationMinutes ?: return false
        return mins in 1..30
    }

    fun isExtremeUvAlert(today: DailyItem, telemetry: BentoTelemetry? = null): Boolean {
        val maxUv = maxOf(today.uvIndex, telemetry?.uvIndex ?: 0, telemetry?.maxUvIndex ?: 0)
        return maxUv >= 8
    }

    fun isSharpTempDropAlert(today: DailyItem, tomorrow: DailyItem): Boolean {
        return tomorrow.maxTemp - today.maxTemp <= -7
    }

    fun isQuietHours(
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val localTime = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalTime()
        val hour = localTime.hour
        return hour >= 22 || hour < 7
    }

    /**
     * Anti-spam priority ladder & mutual suppression:
     * Severe Storm (Tier 1) > Heat/Freeze/Gale/Fog/TempDrop (Tier 2) > Rain Start/Stop (Tier 3) > AQI/UV (Tier 4).
     * Quiet hours: 22:00 to 07:00 for non-emergency alerts (only imminent SEVERE storms exempt).
     */
    fun checkAndDispatchEventAlerts(
        context: Context,
        data: WeatherDashboardData,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (!prefs.isWeatherNotificationsEnabled) return

        // Reject stale cached forecast data so past weather events are never announced late
        val lastUpdated = data.hero.lastUpdatedEpochMs
        if (lastUpdated > 0L && (nowEpochMs - lastUpdated) > MAX_EVENT_ALERT_DATA_AGE_MS) {
            return
        }

        // Location zone drives sun-position logic (UV); the user's own clock drives quiet hours.
        val zoneId = WeatherMapper.resolveZoneId(data.timezone)
        val deviceZoneId = ZoneId.systemDefault()
        val inQuietHours = isQuietHours(nowEpochMs, deviceZoneId)
        val localHour = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).hour

        // =========================================================================
        // TIER 1: Severe Storm (Only SEVERE/imminent >=60% exempt from Quiet Hours)
        // =========================================================================
        val alert = data.alert
        val isEmergencyStorm = isQuietHoursExemptSevereAlert(alert)
        val canFireStormNow = if (inQuietHours) {
            isEmergencyStorm && prefs.canTriggerSevereAlert(nowEpochMs)
        } else {
            isSevereAlert(alert) &&
                prefs.canTriggerSevereAlert(nowEpochMs) &&
                (isEmergencyStorm || prefs.canTriggerNonEmergencyEventAlert(nowEpochMs))
        }
        if (canFireStormNow) {
            val sent = WeatherNotificationHelper.showSevereWeatherNotification(appContext, data)
            if (sent) {
                prefs.lastSevereAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return // Suppress all lower tiers
            }
        }

        // All non-emergency alerts are suppressed during quiet hours (22:00 - 07:00)
        // and respect the global inter-alert cooldown & daily briefing buffer
        if (inQuietHours || !prefs.canTriggerNonEmergencyEventAlert(nowEpochMs)) {
            return
        }

        val dailyList = data.dailyList
        if (dailyList.isEmpty()) return
        val todayLocalDate = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val todayDateStr = "${todayLocalDate.monthValue}/${todayLocalDate.dayOfMonth}"
        val todayIdx = dailyList.indexOfFirst { it.dateText == todayDateStr }.takeIf { it >= 0 } ?: 0
        val today = dailyList.getOrNull(todayIdx) ?: return
        val tomorrow = dailyList.getOrNull(todayIdx + 1)

        // =========================================================================
        // TIER 2: Severe Hazards (Heat, Freeze, Gale, Dense Fog)
        // =========================================================================
        // 1. Extreme Heat & Tropical Night
        if (isExtremeHeatAlert(today, data.hero, localHour) && prefs.canTriggerHeatAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showExtremeHeatNotification(appContext, data, localHour)
            if (sent) {
                prefs.lastHeatAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // 2. Frost, Freeze & Black Ice Hazard
        if (isFreezeHazard(today, data.telemetry) && prefs.canTriggerFreezeAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showFreezeHazardNotification(appContext, data)
            if (sent) {
                prefs.lastFreezeAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // 3. Gale Gust & High Wind
        if (isGaleWindAlert(data.telemetry, today, localHour) && prefs.canTriggerWindAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showGaleWindNotification(appContext, data, localHour)
            if (sent) {
                prefs.lastWindAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // 4. Dense Fog & Low Visibility
        if (isDenseFogAlert(data.telemetry) && prefs.canTriggerFogAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showDenseFogNotification(appContext, data)
            if (sent) {
                prefs.lastFogAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // =========================================================================
        // TIER 3: Rain Events (Rain Start, Rain Cessation)
        // =========================================================================
        val elapsedMinutes = if (lastUpdated > 0L && nowEpochMs > lastUpdated) {
            ((nowEpochMs - lastUpdated) / 60_000L).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val rawNowcast = data.nowcast
        val adjustedNowcast = if (rawNowcast != null && elapsedMinutes > 0) {
            rawNowcast.copy(
                nextPrecipitationOnsetMinutes = rawNowcast.nextPrecipitationOnsetMinutes?.let { it - elapsedMinutes },
                rainCessationMinutes = rawNowcast.rainCessationMinutes?.let { it - elapsedMinutes }
            )
        } else {
            rawNowcast
        }
        val adjustedData = if (adjustedNowcast !== rawNowcast) data.copy(nowcast = adjustedNowcast) else data

        // Rain Start Alert
        if (adjustedNowcast != null && adjustedNowcast.isPrecipitationImminent) {
            val onset = adjustedNowcast.nextPrecipitationOnsetMinutes
            if (onset != null && onset in 1..60 && prefs.canTriggerRainAlert(nowEpochMs)) {
                val sent = WeatherNotificationHelper.showRainNowcastNotification(appContext, adjustedData)
                if (sent) {
                    prefs.lastRainAlertEpochMs = nowEpochMs
                    prefs.lastAnyEventAlertEpochMs = nowEpochMs
                    return
                }
            }
        }

        // Rain Cessation Alert
        if (isRainCessationAlert(adjustedNowcast) && prefs.canTriggerRainCessationAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showRainCessationNotification(appContext, adjustedData)
            if (sent) {
                prefs.lastRainCessationAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // =========================================================================
        // TIER 4: Environmental & Outlook Alerts (Air Quality, UV Index, Temp Drop)
        // =========================================================================
        // 1. Poor Air Quality Alert
        if (isPoorAqiAlert(data.telemetry) && prefs.canTriggerAqiAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showPoorAqiNotification(appContext, data)
            if (sent) {
                prefs.lastAqiAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // 2. Extreme UV Alert (midday reminder or high live UV index)
        val isMiddayOrHighUv = localHour in 10..14 || (data.telemetry.uvIndex >= 8)
        if (isMiddayOrHighUv && isExtremeUvAlert(today, data.telemetry) && prefs.canTriggerUvAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showExtremeUvNotification(appContext, data)
            if (sent) {
                prefs.lastUvAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }

        // 3. Sharp Temperature Drop / Frontal Shift
        if (tomorrow != null && isSharpTempDropAlert(today, tomorrow) && prefs.canTriggerTempDropAlert(nowEpochMs)) {
            val sent = WeatherNotificationHelper.showTempDropNotification(appContext, data)
            if (sent) {
                prefs.lastTempDropAlertEpochMs = nowEpochMs
                prefs.lastAnyEventAlertEpochMs = nowEpochMs
                return
            }
        }
    }

    /** Forecast for the device's own location (never the city selected in the dashboard). */
    fun getEffectiveDashboardData(context: Context): WeatherDashboardData {
        val cached = try {
            WeatherLocalDataSource(context).getDeviceWeather()
        } catch (_: Exception) {
            null
        }
        return cached ?: createFallbackDashboardData()
    }

    /**
     * Fresh forecast for where the device is right now, for notifications and alerts.
     * Returns null when no location is known: callers must then skip event alerts rather than
     * alert about some other place.
     */
    suspend fun fetchDeviceWeather(context: Context, maxCacheAgeMs: Long = 30 * 60 * 1000L): WeatherDashboardData? {
        val appContext = context.applicationContext
        val local = WeatherLocalDataSource(appContext)
        local.getDeviceWeather(maxCacheAgeMs)?.let { cached ->
            if (isForToday(cached)) return cached
        }
        val coords = LocationClient(appContext).fetchLocation(forceFresh = false) ?: return local.getDeviceWeather()
        // localDataSource = null: do not let a background GPS fetch overwrite the dashboard cache.
        return try {
            WeatherRepository(localDataSource = null).getWeatherData(
                latitude = coords.latitude,
                longitude = coords.longitude,
                resolvedLocationName = coords.locationName,
                altitude = coords.altitude
            ).also { local.saveDeviceWeather(it) }
        } catch (_: Exception) {
            local.getDeviceWeather()
        }
    }

    internal fun isForToday(
        data: WeatherDashboardData,
        referenceNow: java.time.ZonedDateTime? = null
    ): Boolean {
        val first = data.dailyList.firstOrNull()?.dateText ?: return false
        val zone = WeatherMapper.resolveZoneId(data.timezone)
        val nowZoned = referenceNow?.withZoneSameInstant(zone) ?: java.time.ZonedDateTime.now(zone)
        val today = nowZoned.toLocalDate()
        val todayStr = "${today.monthValue}/${today.dayOfMonth}"
        if (first == todayStr) return true
        // Between 00:00 and 06:59 local time, Google's 07:00–07:00 meteorological day may still
        // place yesterday at index 0 with today immediately following.
        if (nowZoned.hour < 7) {
            val yesterday = today.minusDays(1)
            val yesterdayStr = "${yesterday.monthValue}/${yesterday.dayOfMonth}"
            if (first == yesterdayStr && data.dailyList.any { it.dateText == todayStr }) {
                return true
            }
        }
        return false
    }

    fun triggerTestNotification(
        context: Context,
        type: TestNotificationType = TestNotificationType.EVENING,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val data = getEffectiveDashboardData(appContext)

            val success = when (type) {
                TestNotificationType.MORNING ->
                    WeatherNotificationHelper.showMorningBriefingNotification(appContext, data)
                TestNotificationType.EVENING ->
                    WeatherNotificationHelper.showTomorrowWeatherNotification(appContext, data)
                TestNotificationType.RAIN -> {
                    val realNowcast = data.nowcast
                    val targetData = if (realNowcast != null && realNowcast.isPrecipitationImminent && realNowcast.nextPrecipitationOnsetMinutes != null) {
                        data
                    } else {
                        data.copy(
                            nowcast = PrecipitationNowcast(
                                summaryText = "Rain starting in ~20 min (~2.5 mm/h)",
                                isPrecipitationImminent = true,
                                nextPrecipitationOnsetMinutes = 20,
                                maxRateMmH = 2.5
                            )
                        )
                    }
                    WeatherNotificationHelper.showRainNowcastNotification(appContext, targetData)
                }
                TestNotificationType.RAIN_CESSATION -> {
                    val targetData = data.copy(
                        nowcast = PrecipitationNowcast(
                            summaryText = "Rain stopping in ~20 min",
                            isPrecipitationImminent = true,
                            nextPrecipitationOnsetMinutes = null,
                            maxRateMmH = 1.0,
                            rainCessationMinutes = 20
                        )
                    )
                    WeatherNotificationHelper.showRainCessationNotification(appContext, targetData)
                }
                TestNotificationType.SEVERE -> {
                    val realAlert = data.alert
                    val targetData = if (isSevereAlert(realAlert)) {
                        data
                    } else {
                        data.copy(
                            alert = SevereAlertInfo(
                                riskLevel = SeverityRiskLevel.HIGH,
                                thunderstormProbability = 75,
                                title = "Severe Weather Alert",
                                subtitle = "Thunderstorms expected in your area",
                                isVisible = true,
                                peakHourLabel = "4:00 PM"
                            )
                        )
                    }
                    WeatherNotificationHelper.showSevereWeatherNotification(appContext, targetData)
                }
                TestNotificationType.HEAT -> {
                    val today = data.dailyList.firstOrNull() ?: createFallbackDashboardData().dailyList[0]
                    val targetData = data.copy(
                        dailyList = listOf(today.copy(maxTemp = 36, feelsLikeMaxTemp = 41, minTemp = 22)),
                        hero = data.hero.copy(tempDegrees = 36, feelsLikeDegrees = 41)
                    )
                    WeatherNotificationHelper.showExtremeHeatNotification(appContext, targetData)
                }
                TestNotificationType.FREEZE -> {
                    val today = data.dailyList.firstOrNull() ?: createFallbackDashboardData().dailyList[0]
                    val targetData = data.copy(
                        dailyList = listOf(today.copy(minTemp = -2, precipitationMm = 1.2, rainProbability = 40)),
                        telemetry = data.telemetry.copy(precipitationMm = 1.2, dewPointDegrees = -2)
                    )
                    WeatherNotificationHelper.showFreezeHazardNotification(appContext, targetData)
                }
                TestNotificationType.WIND -> {
                    val targetData = data.copy(
                        telemetry = data.telemetry.copy(windSpeedKmH = 55, windGustKmH = 75)
                    )
                    WeatherNotificationHelper.showGaleWindNotification(appContext, targetData)
                }
                TestNotificationType.AQI -> {
                    val targetData = data.copy(
                        telemetry = data.telemetry.copy(aqiValue = 165, pm25 = 45.0, aqiCategory = "Unhealthy")
                    )
                    WeatherNotificationHelper.showPoorAqiNotification(appContext, targetData)
                }
                TestNotificationType.FOG -> {
                    val targetData = data.copy(
                        telemetry = data.telemetry.copy(visibilityKm = 0.6, humidityPercent = 95)
                    )
                    WeatherNotificationHelper.showDenseFogNotification(appContext, targetData)
                }
                TestNotificationType.UV -> {
                    val today = data.dailyList.firstOrNull() ?: createFallbackDashboardData().dailyList[0]
                    val targetData = data.copy(
                        dailyList = listOf(today.copy(uvIndex = 11, uvCategory = "Extreme")),
                        telemetry = data.telemetry.copy(uvIndex = 11, maxUvIndex = 11, uvCategory = "Extreme")
                    )
                    WeatherNotificationHelper.showExtremeUvNotification(appContext, targetData)
                }
                TestNotificationType.TEMP_DROP -> {
                    val today = data.dailyList.firstOrNull() ?: createFallbackDashboardData().dailyList[0]
                    val tomorrow = data.dailyList.getOrNull(1) ?: createFallbackDashboardData().dailyList[1]
                    val targetData = data.copy(
                        dailyList = listOf(
                            today.copy(maxTemp = 25),
                            tomorrow.copy(maxTemp = 16)
                        )
                    )
                    WeatherNotificationHelper.showTempDropNotification(appContext, targetData)
                }
            }
            withContext(Dispatchers.Main) {
                onResult?.invoke(success)
            }
        }
    }

    fun isWithinMorningCatchupWindow(
        nowHour: Int,
        nowMinute: Int,
        morningHour: Int,
        morningMinute: Int
    ): Boolean {
        val currentMinutes = nowHour * 60 + nowMinute
        val scheduledMinutes = morningHour * 60 + morningMinute
        val endMinutes = maxOf(11 * 60 + 30, scheduledMinutes + 120).coerceAtMost(23 * 60 + 59)
        return currentMinutes >= scheduledMinutes && currentMinutes < endMinutes
    }

    fun isWithinEveningCatchupWindow(
        nowHour: Int,
        nowMinute: Int,
        eveningHour: Int,
        eveningMinute: Int
    ): Boolean {
        val currentMinutes = nowHour * 60 + nowMinute
        val scheduledMinutes = eveningHour * 60 + eveningMinute
        val endMinutes = 23 * 60 + 59 // 11:59 PM
        return currentMinutes >= scheduledMinutes && currentMinutes <= endMinutes
    }

    fun computeNextTriggerMillis(
        targetHour: Int,
        targetMinute: Int,
        now: Calendar = Calendar.getInstance()
    ): Long {
        val safeHour = targetHour.coerceIn(0, 23)
        val safeMinute = targetMinute.coerceIn(0, 59)
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, safeHour)
            set(Calendar.MINUTE, safeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If target is in the past or within 5 seconds, roll to tomorrow
        if (target.timeInMillis - now.timeInMillis <= 5_000L) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun createMorningPendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, WeatherNotificationReceiver::class.java).apply {
            action = WeatherNotificationReceiver.ACTION_MORNING_WEATHER
        }
        return try {
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MORNING,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun createEveningPendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, WeatherNotificationReceiver::class.java).apply {
            action = WeatherNotificationReceiver.ACTION_EVENING_WEATHER
        }
        return try {
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_EVENING,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun createFallbackDashboardData(
        zoneId: ZoneId = ZoneId.systemDefault()
    ): WeatherDashboardData {
        val today = java.time.LocalDate.now(zoneId)
        val todayStr = "${today.monthValue}/${today.dayOfMonth}"
        val tomorrow = today.plusDays(1)
        val tomorrowStr = "${tomorrow.monthValue}/${tomorrow.dayOfMonth}"

        return WeatherDashboardData(
            hero = HeroWeather(
                tempDegrees = 21,
                feelsLikeDegrees = 21,
                highDegrees = 23,
                lowDegrees = 14,
                conditionText = "Partly Cloudy",
                iconUri = null,
                isDaytime = true,
                locationName = "Current Location",
                skyTheme = SkyThemeType.CLEAR_DAY
            ),
            dailyList = listOf(
                DailyItem(
                    dayLabel = "Today",
                    dateText = todayStr,
                    minTemp = 14,
                    maxTemp = 23,
                    conditionText = "Partly Cloudy",
                    rainProbability = 15,
                    iconUri = null,
                    morningWind = "10 km/h",
                    afternoonWind = "15 km/h",
                    dayWindSpeedKmH = 15,
                    uvIndex = 5,
                    uvCategory = "Moderate"
                ),
                DailyItem(
                    dayLabel = "Tomorrow",
                    dateText = tomorrowStr,
                    minTemp = 15,
                    maxTemp = 25,
                    conditionText = "Sunny",
                    rainProbability = 0,
                    iconUri = null,
                    morningWind = "8 km/h",
                    afternoonWind = "12 km/h",
                    dayWindSpeedKmH = 12,
                    uvIndex = 6,
                    uvCategory = "High"
                )
            ),
            hourlyList = listOf(
                HourlyItem(
                    hourLabel = "Now",
                    tempDegrees = 21,
                    rainProbability = 15,
                    precipitationMm = 0.0,
                    iconUri = null,
                    isDaytime = true
                )
            ),
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.LOW,
                thunderstormProbability = 0,
                title = "No Alerts",
                subtitle = "Normal weather conditions",
                isVisible = false,
                peakHourLabel = null
            ),
            nowcast = null,
            telemetry = BentoTelemetry(
                windSpeedKmH = 15,
                windDirection = "NW",
                windGustKmH = 20,
                precipitationMm = 0.0,
                uvIndex = 5,
                uvCategory = "Moderate",
                humidityPercent = 55,
                dewPointDegrees = 12,
                pressureHpa = 1014,
                sunriseTime = "06:30",
                sunsetTime = "19:45"
            ),
            timezone = zoneId.id
        )
    }
}

enum class TestNotificationType {
    MORNING,
    EVENING,
    RAIN,
    RAIN_CESSATION,
    SEVERE,
    HEAT,
    FREEZE,
    WIND,
    AQI,
    FOG,
    UV,
    TEMP_DROP
}

// Backward compatibility alias
typealias EveningWeatherScheduler = WeatherNotificationScheduler
