package com.google.weatherapp.notification

import android.content.Context
import android.content.SharedPreferences

class NotificationPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    // Master Switch
    var isWeatherNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_NOTIFICATIONS_ENABLED, value).apply()

    // Backward compatibility for evening toggle
    var isEveningNotificationEnabled: Boolean
        get() = isEveningOutlookEnabled
        set(value) {
            isEveningOutlookEnabled = value
        }

    // Category Toggles
    var isMorningBriefingEnabled: Boolean
        get() = prefs.getBoolean(KEY_MORNING_BRIEFING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MORNING_BRIEFING_ENABLED, value).apply()

    var isEveningOutlookEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVENING_OUTLOOK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_EVENING_OUTLOOK_ENABLED, value).apply()

    var isSevereAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEVERE_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SEVERE_ALERTS_ENABLED, value).apply()

    var isExtremeHeatEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEAT_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HEAT_ALERTS_ENABLED, value).apply()

    var isFreezeHazardEnabled: Boolean
        get() = prefs.getBoolean(KEY_FREEZE_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FREEZE_ALERTS_ENABLED, value).apply()

    var isGaleWindEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIND_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WIND_ALERTS_ENABLED, value).apply()

    var isDenseFogEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOG_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FOG_ALERTS_ENABLED, value).apply()

    var isRainNowcastEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAIN_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RAIN_ALERTS_ENABLED, value).apply()

    var isRainCessationEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAIN_CESSATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RAIN_CESSATION_ENABLED, value).apply()

    var isPoorAqiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AQI_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AQI_ALERTS_ENABLED, value).apply()

    var isExtremeUvEnabled: Boolean
        get() = prefs.getBoolean(KEY_UV_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_UV_ALERTS_ENABLED, value).apply()

    var isSharpTempDropEnabled: Boolean
        get() = prefs.getBoolean(KEY_TEMP_DROP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TEMP_DROP_ENABLED, value).apply()

    // Hours and Minutes
    var morningHour: Int
        get() = prefs.getInt(KEY_MORNING_HOUR, DEFAULT_MORNING_HOUR).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_MORNING_HOUR, value.coerceIn(0, 23)).apply()

    var morningMinute: Int
        get() = prefs.getInt(KEY_MORNING_MINUTE, DEFAULT_MORNING_MINUTE).coerceIn(0, 59)
        set(value) = prefs.edit().putInt(KEY_MORNING_MINUTE, value.coerceIn(0, 59)).apply()

    var eveningHour: Int
        get() = prefs.getInt(KEY_EVENING_HOUR, DEFAULT_EVENING_HOUR).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_EVENING_HOUR, value.coerceIn(0, 23)).apply()

    var eveningMinute: Int
        get() = prefs.getInt(KEY_EVENING_MINUTE, DEFAULT_EVENING_MINUTE).coerceIn(0, 59)
        set(value) = prefs.edit().putInt(KEY_EVENING_MINUTE, value.coerceIn(0, 59)).apply()

    // Timestamps
    var lastRainAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_RAIN_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RAIN_ALERT, value).apply()

    var lastRainCessationAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_RAIN_CESSATION_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RAIN_CESSATION_ALERT, value).apply()

    var lastSevereAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_SEVERE_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SEVERE_ALERT, value).apply()

    var lastHeatAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_HEAT_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HEAT_ALERT, value).apply()

    var lastFreezeAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_FREEZE_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FREEZE_ALERT, value).apply()

    var lastWindAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_WIND_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_WIND_ALERT, value).apply()

    var lastFogAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_FOG_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FOG_ALERT, value).apply()

    var lastAqiAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_AQI_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AQI_ALERT, value).apply()

    var lastUvAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_UV_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UV_ALERT, value).apply()

    var lastTempDropAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_TEMP_DROP_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TEMP_DROP_ALERT, value).apply()

    var lastAnyEventAlertEpochMs: Long
        get() = prefs.getLong(KEY_LAST_ANY_EVENT_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ANY_EVENT_ALERT, value).apply()

    var lastMorningNotificationEpochMs: Long
        get() = prefs.getLong(KEY_LAST_MORNING_NOTIFICATION, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MORNING_NOTIFICATION, value).apply()

    var lastEveningNotificationEpochMs: Long
        get() = prefs.getLong(KEY_LAST_EVENING_NOTIFICATION, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EVENING_NOTIFICATION, value).apply()

    var lastMorningNotificationDate: String
        get() = prefs.getString(KEY_LAST_MORNING_NOTIFICATION_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_MORNING_NOTIFICATION_DATE, value).apply()

    var lastEveningNotificationDate: String
        get() = prefs.getString(KEY_LAST_EVENING_NOTIFICATION_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_EVENING_NOTIFICATION_DATE, value).apply()

    fun getTodayDateString(
        now: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): String = Companion.getTodayDateString(now, zoneId)

    fun recordMorningNotificationPosted(
        now: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ) {
        lastMorningNotificationEpochMs = now
        lastMorningNotificationDate = getTodayDateString(now, zoneId)
    }

    fun recordEveningNotificationPosted(
        now: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ) {
        lastEveningNotificationEpochMs = now
        lastEveningNotificationDate = getTodayDateString(now, zoneId)
    }

    fun canPostMorningNotification(
        now: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Boolean {
        if (!isWeatherNotificationsEnabled || !isMorningBriefingEnabled) return false
        val today = getTodayDateString(now, zoneId)
        if (lastMorningNotificationDate == today) return false
        val diff = now - lastMorningNotificationEpochMs
        return diff >= MORNING_EVENING_COOLDOWN_MS || diff < 0L
    }

    fun canPostEveningNotification(
        now: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Boolean {
        if (!isWeatherNotificationsEnabled || !isEveningOutlookEnabled) return false
        val today = getTodayDateString(now, zoneId)
        if (lastEveningNotificationDate == today) return false
        val diff = now - lastEveningNotificationEpochMs
        return diff >= MORNING_EVENING_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerNonEmergencyEventAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled) return false
        val sinceAnyAlert = now - lastAnyEventAlertEpochMs
        if (lastAnyEventAlertEpochMs > 0L && sinceAnyAlert in 0L until GLOBAL_EVENT_ALERT_COOLDOWN_MS) {
            return false
        }
        val sinceMorning = now - lastMorningNotificationEpochMs
        if (lastMorningNotificationEpochMs > 0L && sinceMorning in 0L until BRIEFING_TO_ALERT_BUFFER_MS) {
            return false
        }
        val sinceEvening = now - lastEveningNotificationEpochMs
        if (lastEveningNotificationEpochMs > 0L && sinceEvening in 0L until BRIEFING_TO_ALERT_BUFFER_MS) {
            return false
        }
        return true
    }

    fun canTriggerSevereAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isSevereAlertsEnabled) return false
        val diff = now - lastSevereAlertEpochMs
        return diff >= SEVERE_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerRainAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isRainNowcastEnabled) return false
        val diff = now - lastRainAlertEpochMs
        return diff >= RAIN_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerRainCessationAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isRainCessationEnabled) return false
        val diff = now - lastRainCessationAlertEpochMs
        return diff >= RAIN_CESSATION_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerHeatAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isExtremeHeatEnabled) return false
        val diff = now - lastHeatAlertEpochMs
        return diff >= HEAT_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerFreezeAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isFreezeHazardEnabled) return false
        val diff = now - lastFreezeAlertEpochMs
        return diff >= FREEZE_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerWindAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isGaleWindEnabled) return false
        val diff = now - lastWindAlertEpochMs
        return diff >= WIND_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerFogAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isDenseFogEnabled) return false
        val diff = now - lastFogAlertEpochMs
        return diff >= FOG_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerAqiAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isPoorAqiEnabled) return false
        val diff = now - lastAqiAlertEpochMs
        return diff >= AQI_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerUvAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isExtremeUvEnabled) return false
        val diff = now - lastUvAlertEpochMs
        return diff >= UV_COOLDOWN_MS || diff < 0L
    }

    fun canTriggerTempDropAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isWeatherNotificationsEnabled || !isSharpTempDropEnabled) return false
        val diff = now - lastTempDropAlertEpochMs
        return diff >= TEMP_DROP_COOLDOWN_MS || diff < 0L
    }

    companion object {
        private const val PREFS_NAME = "weather_notification_prefs"
        private const val KEY_WEATHER_NOTIFICATIONS_ENABLED = "weather_notifications_master_enabled"

        const val KEY_MORNING_BRIEFING_ENABLED = "key_morning_briefing_enabled"
        const val KEY_EVENING_OUTLOOK_ENABLED = "key_evening_outlook_enabled"
        const val KEY_SEVERE_ALERTS_ENABLED = "key_severe_alerts_enabled"
        const val KEY_HEAT_ALERTS_ENABLED = "key_heat_alerts_enabled"
        const val KEY_FREEZE_ALERTS_ENABLED = "key_freeze_alerts_enabled"
        const val KEY_WIND_ALERTS_ENABLED = "key_wind_alerts_enabled"
        const val KEY_FOG_ALERTS_ENABLED = "key_fog_alerts_enabled"
        const val KEY_RAIN_ALERTS_ENABLED = "key_rain_alerts_enabled"
        const val KEY_RAIN_CESSATION_ENABLED = "key_rain_cessation_enabled"
        const val KEY_AQI_ALERTS_ENABLED = "key_aqi_alerts_enabled"
        const val KEY_UV_ALERTS_ENABLED = "key_uv_alerts_enabled"
        const val KEY_TEMP_DROP_ENABLED = "key_temp_drop_enabled"

        private const val KEY_MORNING_HOUR = "weather_morning_hour"
        private const val KEY_MORNING_MINUTE = "weather_morning_minute"
        private const val KEY_EVENING_HOUR = "evening_weather_notification_hour"
        private const val KEY_EVENING_MINUTE = "evening_weather_notification_minute"

        private const val KEY_LAST_RAIN_ALERT = "key_last_rain_alert_epoch_ms"
        private const val KEY_LAST_RAIN_CESSATION_ALERT = "key_last_rain_cessation_alert_epoch_ms"
        private const val KEY_LAST_SEVERE_ALERT = "key_last_severe_alert_epoch_ms"
        private const val KEY_LAST_HEAT_ALERT = "key_last_heat_alert_epoch_ms"
        private const val KEY_LAST_FREEZE_ALERT = "key_last_freeze_alert_epoch_ms"
        private const val KEY_LAST_WIND_ALERT = "key_last_wind_alert_epoch_ms"
        private const val KEY_LAST_FOG_ALERT = "key_last_fog_alert_epoch_ms"
        private const val KEY_LAST_AQI_ALERT = "key_last_aqi_alert_epoch_ms"
        private const val KEY_LAST_UV_ALERT = "key_last_uv_alert_epoch_ms"
        private const val KEY_LAST_TEMP_DROP_ALERT = "key_last_temp_drop_alert_epoch_ms"
        private const val KEY_LAST_ANY_EVENT_ALERT = "key_last_any_event_alert_epoch_ms"

        private const val KEY_LAST_MORNING_NOTIFICATION = "key_last_morning_notification_epoch_ms"
        private const val KEY_LAST_EVENING_NOTIFICATION = "key_last_evening_notification_epoch_ms"
        const val KEY_LAST_MORNING_NOTIFICATION_DATE = "key_last_morning_notification_date"
        const val KEY_LAST_EVENING_NOTIFICATION_DATE = "key_last_evening_notification_date"

        const val DEFAULT_MORNING_HOUR = 7
        const val DEFAULT_MORNING_MINUTE = 30
        const val DEFAULT_EVENING_HOUR = 20
        const val DEFAULT_EVENING_MINUTE = 0

        const val MORNING_EVENING_COOLDOWN_MS = 4 * 60 * 60 * 1000L // 4 hours cooldown
        const val GLOBAL_EVENT_ALERT_COOLDOWN_MS = 3 * 60 * 60 * 1000L // 3 hours global cooldown between event alerts
        const val BRIEFING_TO_ALERT_BUFFER_MS = 60 * 60 * 1000L // 1 hour buffer after morning/evening forecast
        const val SEVERE_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 6 hours cooldown
        const val RAIN_COOLDOWN_MS = 4 * 60 * 60 * 1000L // 4 hours cooldown
        const val RAIN_CESSATION_COOLDOWN_MS = 4 * 60 * 60 * 1000L // 4 hours cooldown
        // Daily-condition alerts: once per day (20h so a 07:00 alert re-arms for 07:00 next day)
        const val HEAT_COOLDOWN_MS = 20 * 60 * 60 * 1000L
        const val FREEZE_COOLDOWN_MS = 20 * 60 * 60 * 1000L
        const val WIND_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 6 hours cooldown
        const val FOG_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 6 hours cooldown
        const val AQI_COOLDOWN_MS = 12 * 60 * 60 * 1000L // 12 hours cooldown
        const val UV_COOLDOWN_MS = 20 * 60 * 60 * 1000L // once per day
        const val TEMP_DROP_COOLDOWN_MS = 20 * 60 * 60 * 1000L // once per day

        fun getTodayDateString(
            now: Long = System.currentTimeMillis(),
            zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): String {
            return java.time.Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().toString()
        }
    }
}
