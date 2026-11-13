package com.google.weatherapp.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.weatherapp.MainActivity
import com.google.weatherapp.R
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.model.WeatherDashboardData

object WeatherNotificationHelper {

    private const val TAG = "WeatherNotif"
    const val CHANNEL_FORECASTS = "weather_forecasts"
    const val CHANNEL_ALERTS = "weather_alerts"

    const val NOTIFICATION_GROUP_KEY = "com.google.weatherapp.WEATHER_ALERTS"

    const val NOTIFICATION_ID_GROUP_SUMMARY = 4000
    const val NOTIFICATION_ID_FORECAST = 4001
    const val NOTIFICATION_ID_RAIN = 4002
    const val NOTIFICATION_ID_SEVERE = 4003
    const val NOTIFICATION_ID_HEAT = 4004
    const val NOTIFICATION_ID_FREEZE = 4005
    const val NOTIFICATION_ID_WIND = 4006
    const val NOTIFICATION_ID_AQI = 4007
    const val NOTIFICATION_ID_FOG = 4008
    const val NOTIFICATION_ID_UV = 4009
    const val NOTIFICATION_ID_TEMP_DROP = 4010
    const val NOTIFICATION_ID_RAIN_CESSATION = 4011

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Channel 1: Daily Forecasts (Morning, Evening & Frontal Shift)
            val forecastChannel = NotificationChannel(
                CHANNEL_FORECASTS,
                "Daily Forecasts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Morning briefings, evening outlooks, and frontal temperature shifts"
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            // Channel 2: Weather Alerts (Storms, Rain, Heat, Freeze, Gale, Fog, AQI, UV)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Weather Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Immediate alerts for severe storms, extreme heat, freeze, high wind, rain nowcasts, fog, air quality, and UV"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            manager.createNotificationChannel(forecastChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return areNotificationsEnabledOverride?.invoke(context)
            ?: try {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } catch (_: Throwable) {
                false
            }
    }

    internal var areNotificationsEnabledOverride: ((Context) -> Boolean)? = null

    internal var notificationDispatcherOverride: ((
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        conciseText: String,
        expandedText: String,
        subText: String,
        priority: Int
    ) -> Boolean)? = null

    // Backward compatibility alias
    fun createNotificationChannel(context: Context) = createNotificationChannels(context)

    fun showTomorrowWeatherNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        createNotificationChannels(context)

        val effectiveData = if (data.dailyList.size >= 2) {
            data
        } else if (data.dailyList.isNotEmpty()) {
            val fallback = WeatherNotificationScheduler.createFallbackDashboardData()
            data.copy(dailyList = listOf(data.dailyList[0], fallback.dailyList[1]))
        } else {
            WeatherNotificationScheduler.createFallbackDashboardData()
        }
        val dailyList = effectiveData.dailyList
        if (dailyList.isEmpty()) return false

        val zoneId = try {
            if (!effectiveData.timezone.isNullOrBlank()) java.time.ZoneId.of(effectiveData.timezone) else java.time.ZoneId.systemDefault()
        } catch (_: Exception) {
            java.time.ZoneId.systemDefault()
        }
        val todayDate = java.time.LocalDate.now(zoneId)
        val todayStr = "${todayDate.monthValue}/${todayDate.dayOfMonth}"
        val tomorrowDate = todayDate.plusDays(1)
        val tomorrowStr = "${tomorrowDate.monthValue}/${tomorrowDate.dayOfMonth}"

        val todayIdx = dailyList.indexOfFirst { it.dateText == todayStr }.takeIf { it >= 0 } ?: 0
        val tomorrowIdx = dailyList.indexOfFirst { it.dateText == tomorrowStr }.takeIf { it >= 0 }
            ?: if (todayIdx + 1 < dailyList.size) (todayIdx + 1) else todayIdx

        val today = dailyList[todayIdx]
        val tomorrow = dailyList[tomorrowIdx]

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(
            today = today,
            tomorrow = tomorrow,
            locationName = effectiveData.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_FORECASTS,
            notificationId = NOTIFICATION_ID_FORECAST,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Evening Outlook",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun showMorningBriefingNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        createNotificationChannels(context)

        val effectiveData = if (data.dailyList.isNotEmpty()) {
            data
        } else {
            WeatherNotificationScheduler.createFallbackDashboardData()
        }
        val dailyList = effectiveData.dailyList
        if (dailyList.isEmpty()) return false

        val zoneId = try {
            if (!effectiveData.timezone.isNullOrBlank()) java.time.ZoneId.of(effectiveData.timezone) else java.time.ZoneId.systemDefault()
        } catch (_: Exception) {
            java.time.ZoneId.systemDefault()
        }
        val todayDate = java.time.LocalDate.now(zoneId)
        val todayStr = "${todayDate.monthValue}/${todayDate.dayOfMonth}"
        val today = dailyList.firstOrNull { it.dateText == todayStr }
            ?: dailyList.firstOrNull() ?: return false

        val currentLocalHour = java.time.ZonedDateTime.now(zoneId).hour
        val upcomingHours = if (today.hourlyList.isNotEmpty()) {
            today.hourlyList
        } else {
            effectiveData.hourlyList.take((24 - currentLocalHour).coerceAtLeast(1))
        }

        val summary = WeatherSummaryFormatter.generateMorningSummary(
            today = today,
            upcomingHours = upcomingHours,
            locationName = effectiveData.hero.locationName,
            minHourOfDay = currentLocalHour
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_FORECASTS,
            notificationId = NOTIFICATION_ID_FORECAST,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Morning Briefing",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun showRainNowcastNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val nowcast = data.nowcast ?: return false
        if (!nowcast.isPrecipitationImminent) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateRainNowcastSummary(
            nowcast = nowcast,
            locationName = data.hero.locationName
        )

        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_RAIN_CESSATION)
        } catch (_: Throwable) {}

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_RAIN,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Rain Alert",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showRainCessationNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val nowcast = data.nowcast ?: return false
        if (nowcast.rainCessationMinutes == null) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateRainCessationSummary(
            nowcast = nowcast,
            locationName = data.hero.locationName
        )

        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_RAIN)
        } catch (_: Throwable) {}

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_RAIN_CESSATION,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Rain Stopping",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun showSevereWeatherNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val alert = data.alert
        if (!alert.isVisible || alert.riskLevel == SeverityRiskLevel.LOW || !WeatherNotificationScheduler.isSevereAlert(alert)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateSevereAlertSummary(
            alert = alert,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_SEVERE,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Severe Weather",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showExtremeHeatNotification(
        context: Context,
        data: WeatherDashboardData,
        currentLocalHour: Int? = null
    ): Boolean {
        val today = data.dailyList.firstOrNull() ?: return false
        if (!WeatherNotificationScheduler.isExtremeHeatAlert(today, data.hero, currentLocalHour)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateHeatWarningSummary(
            today = today,
            hero = data.hero,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_HEAT,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Heat Warning",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showFreezeHazardNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val today = data.dailyList.firstOrNull() ?: return false
        if (!WeatherNotificationScheduler.isFreezeHazard(today, data.telemetry)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateFreezeHazardSummary(
            today = today,
            telemetry = data.telemetry,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_FREEZE,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Freeze Hazard",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showGaleWindNotification(
        context: Context,
        data: WeatherDashboardData,
        currentLocalHour: Int? = null
    ): Boolean {
        val today = data.dailyList.firstOrNull()
        if (!WeatherNotificationScheduler.isGaleWindAlert(data.telemetry, today, currentLocalHour)) return false
        createNotificationChannels(context)

        val effectiveSpeed = if (currentLocalHour != null && currentLocalHour >= 17) {
            data.telemetry.windSpeedKmH
        } else {
            maxOf(data.telemetry.windSpeedKmH, today?.dayWindSpeedKmH ?: 0)
        }
        val effectiveGust = maxOf(data.telemetry.windGustKmH, effectiveSpeed)
        val effectiveDirection = if (data.telemetry.windDirection != "—" && data.telemetry.windDirection.isNotBlank()) {
            data.telemetry.windDirection
        } else {
            today?.dayWindDirection ?: data.telemetry.windDirection
        }
        val effectiveTelemetry = data.telemetry.copy(
            windSpeedKmH = effectiveSpeed,
            windGustKmH = effectiveGust,
            windDirection = effectiveDirection
        )

        val summary = WeatherSummaryFormatter.generateWindAlertSummary(
            telemetry = effectiveTelemetry,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_WIND,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Wind Alert",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showPoorAqiNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        if (!WeatherNotificationScheduler.isPoorAqiAlert(data.telemetry)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateAqiAlertSummary(
            telemetry = data.telemetry,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_AQI,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Air Quality",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun showDenseFogNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        if (!WeatherNotificationScheduler.isDenseFogAlert(data.telemetry)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateFogAlertSummary(
            telemetry = data.telemetry,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_FOG,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Fog Alert",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun showExtremeUvNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val today = data.dailyList.firstOrNull() ?: return false
        if (!WeatherNotificationScheduler.isExtremeUvAlert(today, data.telemetry)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateUvAlertSummary(
            today = today,
            telemetry = data.telemetry,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_ALERTS,
            notificationId = NOTIFICATION_ID_UV,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "UV Alert",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun showTempDropNotification(
        context: Context,
        data: WeatherDashboardData
    ): Boolean {
        val today = data.dailyList.firstOrNull() ?: return false
        val tomorrow = data.dailyList.getOrNull(1) ?: return false
        if (!WeatherNotificationScheduler.isSharpTempDropAlert(today, tomorrow)) return false
        createNotificationChannels(context)

        val summary = WeatherSummaryFormatter.generateTempDropSummary(
            today = today,
            tomorrow = tomorrow,
            locationName = data.hero.locationName
        )

        return dispatchNotification(
            context = context,
            channelId = CHANNEL_FORECASTS,
            notificationId = NOTIFICATION_ID_TEMP_DROP,
            title = summary.title,
            conciseText = summary.conciseText,
            expandedText = summary.expandedDetails,
            subText = "Temperature Drop",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    private fun dispatchNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        conciseText: String,
        expandedText: String,
        subText: String,
        priority: Int
    ): Boolean {
        if (!areNotificationsEnabled(context)) {
            return false
        }

        if (notificationDispatcherOverride != null) {
            return notificationDispatcherOverride!!.invoke(
                context, channelId, notificationId, title, conciseText, expandedText, subText, priority
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_weather)
            .setContentTitle(title)
            .setContentText(conciseText)
            .setSubText(subText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedText)
                    .setSummaryText(subText)
            )
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            if (areNotificationsEnabled(context)) {
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.notify(notificationId, notification)
                postGroupSummary(context, notificationManager)
                true
            } else {
                false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "notify() rejected (permission revoked?) id=$notificationId", e)
            false
        } catch (e: Throwable) {
            Log.e(TAG, "notify() failed id=$notificationId", e)
            false
        }
    }

    private fun postGroupSummary(context: Context, manager: NotificationManagerCompat) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_GROUP_SUMMARY,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification_weather)
            .setContentTitle("Weather Updates")
            .setContentText("Active weather forecasts & alerts")
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText("Weather alerts")
            )
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            // Summary must never chime/heads-up itself; only the child notification alerts.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            manager.notify(NOTIFICATION_ID_GROUP_SUMMARY, summaryNotification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the permission check and notify()
        }
    }
}
