package com.google.weatherapp.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.WeatherDashboardData

class WeatherLocalDataSource(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCachedWeather(
        maxAgeMs: Long = 30 * 60 * 1000L,
        latitude: Double? = null,
        longitude: Double? = null
    ): WeatherDashboardData? {
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (timestamp == 0L || (now - timestamp) !in 0..maxAgeMs) {
            return null
        }
        // Cache is a single entry; never serve data fetched for a different location.
        val cachedLoc = prefs.getString(KEY_CACHE_LOCATION, null)
        val expectedLoc = currentLocationKey(latitude, longitude)
        if (expectedLoc != null) {
            if (cachedLoc != expectedLoc) return null
        } else {
            if (cachedLoc == null || (!cachedLoc.startsWith("gps_") && cachedLoc != "gps")) return null
        }
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            val data = gson.fromJson(json, WeatherDashboardData::class.java)
            val firstDaily = data.dailyList.firstOrNull()
            if (data.dailyList.isEmpty()) {
                return null
            }
            if (firstDaily != null && firstDaily.dateText.isNotBlank()) {
                val zoneId = try {
                    if (!data.timezone.isNullOrBlank()) java.time.ZoneId.of(data.timezone) else java.time.ZoneId.systemDefault()
                } catch (_: Exception) {
                    java.time.ZoneId.systemDefault()
                }
                val nowLocalDate = java.time.LocalDate.now(zoneId)
                val todayStr = "${nowLocalDate.monthValue}/${nowLocalDate.dayOfMonth}"
                if (firstDaily.dateText != todayStr) {
                    return null
                }
            }
            data
        } catch (_: Exception) {
            null
        }
    }

    fun getLatestCachedWeatherIgnoringTtl(): WeatherDashboardData? {
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            gson.fromJson(json, WeatherDashboardData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun saveWeather(data: WeatherDashboardData) {
        try {
            val loc = getSelectedCustomLocation()
            val cacheKey = if (loc != null && !loc.isCurrentGps) {
                "%.3f,%.3f".format(java.util.Locale.US, loc.latitude, loc.longitude)
            } else {
                val lat = data.latitude
                val lon = data.longitude
                if (lat != null && lon != null) {
                    "gps_%.2f,%.2f".format(java.util.Locale.US, lat, lon)
                } else {
                    "gps"
                }
            }
            val json = gson.toJson(data)
            prefs.edit()
                .putString(KEY_CACHED_WEATHER, json)
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .putString(KEY_CACHE_LOCATION, cacheKey)
                .apply()
            // Dashboard is showing the device's own location: notifications can reuse this forecast.
            if (loc == null || loc.isCurrentGps) saveDeviceWeather(data)
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    /**
     * Forecast for where the device physically is. Kept separate from the dashboard cache so that
     * browsing another city never changes what notifications and alerts are about.
     */
    fun saveDeviceWeather(data: WeatherDashboardData) {
        try {
            prefs.edit()
                .putString(KEY_DEVICE_WEATHER, gson.toJson(data))
                .putLong(KEY_DEVICE_WEATHER_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    fun getDeviceWeather(
        maxAgeMs: Long = Long.MAX_VALUE,
        now: Long = System.currentTimeMillis()
    ): WeatherDashboardData? {
        val age = now - prefs.getLong(KEY_DEVICE_WEATHER_TIMESTAMP, 0L)
        if (age < 0L || age > maxAgeMs) return null
        val json = prefs.getString(KEY_DEVICE_WEATHER, null) ?: return null
        return try {
            gson.fromJson(json, WeatherDashboardData::class.java)?.takeIf { it.dailyList.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedLocations(): List<SavedLocation> {
        val json = prefs.getString(KEY_SAVED_LOCATIONS, null) ?: return defaultPopularCities()
        return try {
            val type = object : TypeToken<List<SavedLocation>>() {}.type
            gson.fromJson<List<SavedLocation>>(json, type) ?: defaultPopularCities()
        } catch (_: Exception) {
            defaultPopularCities()
        }
    }

    fun saveLocations(locations: List<SavedLocation>) {
        try {
            val json = gson.toJson(locations)
            prefs.edit().putString(KEY_SAVED_LOCATIONS, json).apply()
        } catch (_: Exception) {
            // Ignore write failure
        }
    }

    fun getSelectedCustomLocation(): SavedLocation? {
        val json = prefs.getString(KEY_ACTIVE_CUSTOM_LOCATION, null) ?: return null
        return try {
            gson.fromJson(json, SavedLocation::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun setSelectedCustomLocation(location: SavedLocation?) {
        try {
            if (location == null || location.isCurrentGps) {
                prefs.edit().remove(KEY_ACTIVE_CUSTOM_LOCATION).apply()
            } else {
                prefs.edit().putString(KEY_ACTIVE_CUSTOM_LOCATION, gson.toJson(location)).apply()
            }
        } catch (_: Exception) {
            // Ignore write failure
        }
    }

    fun clearCache() {
        prefs.edit()
            .remove(KEY_CACHED_WEATHER)
            .remove(KEY_CACHE_TIMESTAMP)
            .remove(KEY_CACHE_LOCATION)
            .apply()
    }

    fun currentLocationKey(latitude: Double? = null, longitude: Double? = null): String? {
        val loc = getSelectedCustomLocation()
        if (loc != null && !loc.isCurrentGps) {
            return "%.3f,%.3f".format(java.util.Locale.US, loc.latitude, loc.longitude)
        }
        if (latitude != null && longitude != null) {
            return "gps_%.2f,%.2f".format(java.util.Locale.US, latitude, longitude)
        }
        return null
    }

    private fun defaultPopularCities(): List<SavedLocation> = listOf(
        SavedLocation("Cape Town", "Western Cape", "South Africa", -33.9249, 18.4241),
        SavedLocation("Johannesburg", "Gauteng", "South Africa", -26.2041, 28.0473),
        SavedLocation("London", "England", "United Kingdom", 51.5074, -0.1278),
        SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503)
    )

    companion object {
        private const val PREFS_NAME = "weather_dashboard_cache"
        private const val KEY_CACHED_WEATHER = "cached_weather_json"
        private const val KEY_CACHE_TIMESTAMP = "cached_weather_timestamp"
        private const val KEY_CACHE_LOCATION = "cached_weather_location_key"
        private const val KEY_DEVICE_WEATHER = "device_weather_json"
        private const val KEY_DEVICE_WEATHER_TIMESTAMP = "device_weather_timestamp"
        private const val KEY_SAVED_LOCATIONS = "saved_locations_json"
        private const val KEY_ACTIVE_CUSTOM_LOCATION = "active_custom_location_json"
    }
}
