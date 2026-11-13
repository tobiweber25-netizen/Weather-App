package com.google.weatherapp.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.widget.WeatherWidgetUpdater
import java.time.LocalTime
import java.time.ZoneId

class WeatherNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = NotificationPreferences(applicationContext)
        if (!prefs.isWeatherNotificationsEnabled) {
            return Result.success()
        }

        val type = inputData.getString(KEY_NOTIFICATION_TYPE) ?: TYPE_PERIODIC_CHECK

        // Widgets follow the city selected in the app; notifications always follow where the device is.
        val fresh = WeatherWidgetUpdater.fetchFreshWeatherIfNeeded(applicationContext)
        val widgetData = if (fresh != null && fresh.dailyList.isNotEmpty()) fresh else null
        val data = WeatherNotificationScheduler.fetchDeviceWeather(applicationContext)
            ?: WeatherNotificationScheduler.getEffectiveDashboardData(applicationContext)
        val hasDeviceLocationData = WeatherLocalDataSource(applicationContext).getDeviceWeather() != null

        when (type) {
            TYPE_MORNING -> {
                if (prefs.canPostMorningNotification()) {
                    val posted = WeatherNotificationHelper.showMorningBriefingNotification(applicationContext, data)
                    if (posted) {
                        prefs.recordMorningNotificationPosted()
                    }
                }
                WeatherNotificationScheduler.scheduleMorning(applicationContext)
            }
            TYPE_EVENING -> {
                if (prefs.canPostEveningNotification()) {
                    val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(applicationContext, data)
                    if (posted) {
                        prefs.recordEveningNotificationPosted()
                    }
                }
                WeatherNotificationScheduler.scheduleEvening(applicationContext)
            }
            TYPE_PERIODIC_CHECK, TYPE_BACKGROUND_SYNC -> {
                // 1. Update widgets with freshest data (for the city shown in the app)
                try {
                    WeatherWidgetUpdater.updateAllWidgets(applicationContext, widgetData ?: data)
                } catch (_: Exception) {
                    // Non-critical
                }

                // 2. Event alerts only when the device location is known: never alert about another place.
                if (hasDeviceLocationData) {
                    WeatherNotificationScheduler.checkAndDispatchEventAlerts(applicationContext, data)
                }

                // 3. Intelligent Catch-Up Delivery if scheduled time was missed (e.g. phone was off during alarm).
                // Alarms are scheduled on the device clock, so catch-up must use the device zone too,
                // even when the cached forecast belongs to a location in another timezone.
                val zoneId = ZoneId.systemDefault()
                val nowTime = LocalTime.now(zoneId)

                // Morning catchup (only AFTER morning time up to 11:30 AM)
                if (WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                        nowHour = nowTime.hour,
                        nowMinute = nowTime.minute,
                        morningHour = prefs.morningHour,
                        morningMinute = prefs.morningMinute
                    )
                ) {
                    if (prefs.canPostMorningNotification(zoneId = zoneId)) {
                        val posted = WeatherNotificationHelper.showMorningBriefingNotification(applicationContext, data)
                        if (posted) {
                            prefs.recordMorningNotificationPosted(zoneId = zoneId)
                        }
                    }
                }

                // Evening catchup (only AFTER evening time up to 23:59)
                if (WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                        nowHour = nowTime.hour,
                        nowMinute = nowTime.minute,
                        eveningHour = prefs.eveningHour,
                        eveningMinute = prefs.eveningMinute
                    )
                ) {
                    if (prefs.canPostEveningNotification(zoneId = zoneId)) {
                        val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(applicationContext, data)
                        if (posted) {
                            prefs.recordEveningNotificationPosted(zoneId = zoneId)
                        }
                    }
                }

                // 4. Watchdog: ensure daily morning and evening alarms remain actively scheduled
                WeatherNotificationScheduler.ensureAlarmsScheduled(applicationContext)
            }
        }

        return Result.success()
    }

    companion object {
        const val KEY_NOTIFICATION_TYPE = "key_notification_type"
        const val TYPE_MORNING = "TYPE_MORNING"
        const val TYPE_EVENING = "TYPE_EVENING"
        const val TYPE_PERIODIC_CHECK = "TYPE_PERIODIC_CHECK"
        const val TYPE_BACKGROUND_SYNC = "TYPE_BACKGROUND_SYNC"
    }
}
