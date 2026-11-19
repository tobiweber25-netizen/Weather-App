package com.google.weatherapp.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.weatherapp.data.local.WeatherLocalDataSource
import java.util.Calendar

open class WeatherNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext ?: context
        val prefs = preferencesProvider?.invoke(appContext) ?: NotificationPreferences(appContext)

        when (action) {
            ACTION_MORNING_WEATHER -> {
                handleMorningAlarm(appContext, prefs)
            }
            ACTION_EVENING_WEATHER, ACTION_LEGACY_EVENING, ACTION_LEGACY_EVENING_ALT -> {
                handleEveningAlarm(appContext, prefs)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            ACTION_TIME_SET,
            ACTION_TIME_CHANGED_ALT,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_SCHEDULE_EXACT_ALARM_STATE_CHANGED -> {
                WeatherNotificationScheduler.ensureScheduled(appContext)
                val cal = calendarProvider?.invoke() ?: Calendar.getInstance()
                checkMissedAlarms(appContext, cal, prefs)
                WeatherNotificationScheduler.enqueueBackgroundSync(appContext)
            }
        }
    }

    internal fun handleMorningAlarm(
        context: Context,
        prefs: NotificationPreferences = preferencesProvider?.invoke(context) ?: NotificationPreferences(context)
    ) {
        if (prefs.canPostMorningNotification()) {
            val cached = WeatherLocalDataSource(context).getDeviceWeather()
            val hasFreshTodayData = WeatherNotificationHelper.notificationDispatcherOverride != null ||
                (cached != null && WeatherNotificationScheduler.isForToday(cached))
            if (hasFreshTodayData) {
                val data = cached ?: WeatherNotificationScheduler.getEffectiveDashboardData(context)
                val posted = WeatherNotificationHelper.showMorningBriefingNotification(context, data)
                if (posted) {
                    prefs.recordMorningNotificationPosted()
                }
                WeatherNotificationScheduler.enqueueBackgroundSync(context)
            } else {
                // Avoid posting stale/fallback data and locking out today's briefing; fetch fresh first.
                WeatherNotificationScheduler.enqueueImmediateBriefing(context, WeatherNotificationWorker.TYPE_MORNING)
            }
        }
        // Reschedule next morning's alarm
        WeatherNotificationScheduler.scheduleMorning(context)
    }

    internal fun handleEveningAlarm(
        context: Context,
        prefs: NotificationPreferences = preferencesProvider?.invoke(context) ?: NotificationPreferences(context)
    ) {
        if (prefs.canPostEveningNotification()) {
            val cached = WeatherLocalDataSource(context).getDeviceWeather()
            val hasFreshTodayData = WeatherNotificationHelper.notificationDispatcherOverride != null ||
                (cached != null && WeatherNotificationScheduler.isForToday(cached))
            if (hasFreshTodayData) {
                val data = cached ?: WeatherNotificationScheduler.getEffectiveDashboardData(context)
                val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(context, data)
                if (posted) {
                    prefs.recordEveningNotificationPosted()
                }
                WeatherNotificationScheduler.enqueueBackgroundSync(context)
            } else {
                WeatherNotificationScheduler.enqueueImmediateBriefing(context, WeatherNotificationWorker.TYPE_EVENING)
            }
        }
        // Reschedule next evening's alarm
        WeatherNotificationScheduler.scheduleEvening(context)
    }

    internal fun checkMissedAlarms(
        context: Context,
        cal: Calendar = calendarProvider?.invoke() ?: Calendar.getInstance(),
        prefs: NotificationPreferences = preferencesProvider?.invoke(context) ?: NotificationPreferences(context)
    ) {
        if (!prefs.isWeatherNotificationsEnabled) return

        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(Calendar.MINUTE)

        if (WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = currentHour,
                nowMinute = currentMinute,
                morningHour = prefs.morningHour,
                morningMinute = prefs.morningMinute
            )
        ) {
            if (prefs.canPostMorningNotification()) {
                val cached = WeatherLocalDataSource(context).getDeviceWeather()
                val hasFreshTodayData = WeatherNotificationHelper.notificationDispatcherOverride != null ||
                    (cached != null && WeatherNotificationScheduler.isForToday(cached))
                if (hasFreshTodayData) {
                    val data = cached ?: WeatherNotificationScheduler.getEffectiveDashboardData(context)
                    val posted = WeatherNotificationHelper.showMorningBriefingNotification(context, data)
                    if (posted) {
                        prefs.recordMorningNotificationPosted()
                    }
                } else {
                    WeatherNotificationScheduler.enqueueImmediateBriefing(context, WeatherNotificationWorker.TYPE_MORNING)
                }
            }
        }

        if (WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                nowHour = currentHour,
                nowMinute = currentMinute,
                eveningHour = prefs.eveningHour,
                eveningMinute = prefs.eveningMinute
            )
        ) {
            if (prefs.canPostEveningNotification()) {
                val cached = WeatherLocalDataSource(context).getDeviceWeather()
                val hasFreshTodayData = WeatherNotificationHelper.notificationDispatcherOverride != null ||
                    (cached != null && WeatherNotificationScheduler.isForToday(cached))
                if (hasFreshTodayData) {
                    val data = cached ?: WeatherNotificationScheduler.getEffectiveDashboardData(context)
                    val posted = WeatherNotificationHelper.showTomorrowWeatherNotification(context, data)
                    if (posted) {
                        prefs.recordEveningNotificationPosted()
                    }
                } else {
                    WeatherNotificationScheduler.enqueueImmediateBriefing(context, WeatherNotificationWorker.TYPE_EVENING)
                }
            }
        }
    }

    companion object {
        const val ACTION_MORNING_WEATHER = "com.google.weatherapp.action.MORNING_WEATHER_NOTIFICATION"
        const val ACTION_EVENING_WEATHER = "com.google.weatherapp.action.EVENING_WEATHER_NOTIFICATION"
        const val ACTION_LEGACY_EVENING = "com.google.weatherapp.action.EVENING_WEATHER_NOTIFICATION"
        const val ACTION_LEGACY_EVENING_ALT = "com.google.weatherapp.action.EVENING_WEATHER"
        const val ACTION_SCHEDULE_EXACT_ALARM_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
        const val ACTION_TIME_CHANGED_ALT = "android.intent.action.TIME_CHANGED"

        internal var preferencesProvider: ((Context) -> NotificationPreferences)? = null
        internal var calendarProvider: (() -> Calendar)? = null
    }
}

// Subclass to ensure legacy references remain valid
class EveningWeatherReceiver : WeatherNotificationReceiver()
