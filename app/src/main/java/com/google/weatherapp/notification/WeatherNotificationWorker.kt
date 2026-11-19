package com.google.weatherapp.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        // Notifications always follow where the device is; only post briefings when real today data is available.
        val deviceData = WeatherNotificationScheduler.fetchDeviceWeather(applicationContext)
        val freshTodayData = deviceData?.takeIf { WeatherNotificationScheduler.isForToday(it) }

        when (type) {
            TYPE_MORNING -> {
                WeatherNotificationScheduler.scheduleMorning(applicationContext)
                if (prefs.canPostMorningNotification()) {
                    if (freshTodayData != null) {
                        val posted = WeatherNotificationHelper.showMorningBriefingNotification(applicationContext, freshTodayData)
                        if (posted) {
                            prefs.recordMorningNotificationPosted()
                        }
                    } else if (runAttemptCount < 3) {
                        return Result.retry()
                    }
                }
            }
            TYPE_EVENING -> {
                WeatherNotificationScheduler.scheduleEvening(applicationContext)
                if (prefs.canPostEveningNotification()) {
                    if (freshTodayData != null) {
                        val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(applicationContext, freshTodayData)
                        if (posted) {
                            prefs.recordEveningNotificationPosted()
                        }
                    } else if (runAttemptCount < 3) {
                        return Result.retry()
                    }
                }
            }
            TYPE_PERIODIC_CHECK, TYPE_BACKGROUND_SYNC -> {
                // 1. Update widgets with freshest data (for the city shown in the app)
                val fresh = WeatherWidgetUpdater.fetchFreshWeatherIfNeeded(applicationContext)
                val widgetData = if (fresh != null && fresh.dailyList.isNotEmpty()) fresh else deviceData
                if (widgetData != null) {
                    try {
                        WeatherWidgetUpdater.updateAllWidgets(applicationContext, widgetData)
                    } catch (_: Exception) {
                        // Non-critical
                    }
                }

                // 2. Event alerts only when the device location is known: never alert about another place.
                if (deviceData != null) {
                    WeatherNotificationScheduler.checkAndDispatchEventAlerts(applicationContext, deviceData)
                }

                // 3. Intelligent Catch-Up Delivery if scheduled time was missed (e.g. phone was off during alarm).
                // Alarms are scheduled on the device clock, so catch-up must use the device zone too,
                // even when the cached forecast belongs to a location in another timezone.
                val zoneId = ZoneId.systemDefault()
                val nowTime = LocalTime.now(zoneId)

                // Morning catchup (only AFTER morning time up to 11:30 AM)
                if (freshTodayData != null && WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                        nowHour = nowTime.hour,
                        nowMinute = nowTime.minute,
                        morningHour = prefs.morningHour,
                        morningMinute = prefs.morningMinute
                    )
                ) {
                    if (prefs.canPostMorningNotification(zoneId = zoneId)) {
                        val posted = WeatherNotificationHelper.showMorningBriefingNotification(applicationContext, freshTodayData)
                        if (posted) {
                            prefs.recordMorningNotificationPosted(zoneId = zoneId)
                        }
                    }
                }

                // Evening catchup (only AFTER evening time up to 23:59)
                if (freshTodayData != null && WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                        nowHour = nowTime.hour,
                        nowMinute = nowTime.minute,
                        eveningHour = prefs.eveningHour,
                        eveningMinute = prefs.eveningMinute
                    )
                ) {
                    if (prefs.canPostEveningNotification(zoneId = zoneId)) {
                        val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(applicationContext, freshTodayData)
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
