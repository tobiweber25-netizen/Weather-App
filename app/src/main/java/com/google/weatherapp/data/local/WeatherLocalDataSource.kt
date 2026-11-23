package com.google.weatherapp.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.data.repository.WeatherMapper
import java.time.LocalDate
import java.util.Locale

class WeatherLocalDataSource(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class CachedWeatherEntry(
        val timestampMs: Long,
        val data: WeatherDashboardData
    )

    fun getCachedWeather(
        maxAgeMs: Long = 30 * 60 * 1000L,
        latitude: Double? = null,
        longitude: Double? = null
    ): WeatherDashboardData? {
        val expectedKey = currentLocationKey(latitude, longitude)
        if (expectedKey != null) {
            getCachedWeatherForKey(expectedKey, maxAgeMs)?.let { return it }
        }

        // Legacy single-slot fallback (strictly verified for location match, TTL, and local date)
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (timestamp == 0L || (now - timestamp) !in 0..maxAgeMs) {
            return null
        }
        val cachedLoc = prefs.getString(KEY_CACHE_LOCATION, null)
        if (expectedKey != null) {
            if (cachedLoc != expectedKey) return null
        } else {
            if (cachedLoc == null || (!cachedLoc.startsWith("gps_") && cachedLoc != "gps")) return null
        }
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            val data = gson.fromJson(json, WeatherDashboardData::class.java)
            validateFreshForLocalDate(data)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads a cached forecast for a specific city or GPS slot (used for instant horizontal swiping).
     * Never serves weather belonging to a different location or a different calendar day.
     */
    fun getCachedWeatherForLocation(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null,
        maxAgeMs: Long = 30 * 60 * 1000L
    ): WeatherDashboardData? {
        val key = locationKeyFor(location, gpsLatitude, gpsLongitude) ?: return null
        return getCachedWeatherForKey(key, maxAgeMs)
    }

    private fun getCachedWeatherForKey(key: String, maxAgeMs: Long): WeatherDashboardData? {
        val now = System.currentTimeMillis()
        val entries = loadLocationCacheMap()
        val entry = entries[key] ?: return null
        if (entry.timestampMs == 0L || (now - entry.timestampMs) !in 0..maxAgeMs) {
            return null
        }
        return validateFreshForLocalDate(entry.data)
    }

    private fun validateFreshForLocalDate(data: WeatherDashboardData?): WeatherDashboardData? {
        if (data == null || data.dailyList.isEmpty()) return null
        val firstDaily = data.dailyList.firstOrNull()
        if (firstDaily != null && firstDaily.dateText.isNotBlank()) {
            val zoneId = WeatherMapper.resolveZoneId(data.timezone)
            val nowLocalDate = LocalDate.now(zoneId)
            val todayStr = "${nowLocalDate.monthValue}/${nowLocalDate.dayOfMonth}"
            if (firstDaily.dateText != todayStr) {
                return null
            }
        }
        return data
    }

    fun getLatestCachedWeatherIgnoringTtl(): WeatherDashboardData? {
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            gson.fromJson(json, WeatherDashboardData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun saveWeather(
        data: WeatherDashboardData,
        targetLocation: SavedLocation? = getSelectedCustomLocation(),
        updateActiveSlot: Boolean = true
    ) {
        try {
            val isGps = targetLocation == null || targetLocation.isCurrentGps
            val cacheKey = if (!isGps) {
                customLocationKey(targetLocation!!.latitude, targetLocation.longitude)
            } else {
                val lat = data.latitude
                val lon = data.longitude
                if (lat != null && lon != null) gpsLocationKey(lat, lon) else "gps"
            }

            val now = System.currentTimeMillis()
            val updatedMap = loadLocationCacheMap().toMutableMap()
            updatedMap[cacheKey] = CachedWeatherEntry(timestampMs = now, data = data)
            // Retain the 8 most recently viewed places worldwide for fast swiping
            if (updatedMap.size > MAX_CACHED_LOCATIONS) {
                val oldestKey = updatedMap.entries.minByOrNull { it.value.timestampMs }?.key
                if (oldestKey != null && oldestKey != cacheKey) {
                    updatedMap.remove(oldestKey)
                }
            }

            val editor = prefs.edit()
                .putString(KEY_LOCATION_CACHE_MAP, gson.toJson(updatedMap))

            if (updateActiveSlot) {
                editor
                    .putString(KEY_CACHED_WEATHER, gson.toJson(data))
                    .putLong(KEY_CACHE_TIMESTAMP, now)
                    .putString(KEY_CACHE_LOCATION, cacheKey)
            }
            editor.apply()

            // Dashboard is showing the device's own location: notifications can reuse this forecast.
            if (isGps) saveDeviceWeather(data)
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    /**
     * Forecast for where the device physically is. Kept separate from the dashboard cache so that
     * browsing or swiping to another city never changes what notifications and alerts are about.
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
            .remove(KEY_LOCATION_CACHE_MAP)
            .apply()
    }

    fun currentLocationKey(latitude: Double? = null, longitude: Double? = null): String? {
        val loc = getSelectedCustomLocation()
        return locationKeyFor(loc, latitude, longitude)
    }

    fun locationKeyFor(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null
    ): String? {
        if (location != null && !location.isCurrentGps) {
            return customLocationKey(location.latitude, location.longitude)
        }
        if (gpsLatitude != null && gpsLongitude != null) {
            return gpsLocationKey(gpsLatitude, gpsLongitude)
        }
        return null
    }

    private fun customLocationKey(latitude: Double, longitude: Double): String =
        "%.3f,%.3f".format(Locale.US, latitude, longitude)

    private fun gpsLocationKey(latitude: Double, longitude: Double): String =
        "gps_%.2f,%.2f".format(Locale.US, latitude, longitude)

    private fun loadLocationCacheMap(): Map<String, CachedWeatherEntry> {
        val json = prefs.getString(KEY_LOCATION_CACHE_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, CachedWeatherEntry>>() {}.type
            gson.fromJson<Map<String, CachedWeatherEntry>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun defaultPopularCities(): List<SavedLocation> = listOf(
        SavedLocation("Cape Town", "Western Cape", "South Africa", -33.9249, 18.4241, timezone = "Africa/Johannesburg", countryCode = "ZA"),
        SavedLocation("Johannesburg", "Gauteng", "South Africa", -26.2041, 28.0473, timezone = "Africa/Johannesburg", countryCode = "ZA"),
        SavedLocation("London", "England", "United Kingdom", 51.5074, -0.1278, timezone = "Europe/London", countryCode = "GB"),
        SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503, timezone = "Asia/Tokyo", countryCode = "JP")
    )

    companion object {
        private const val PREFS_NAME = "weather_dashboard_cache"
        private const val KEY_CACHED_WEATHER = "cached_weather_json"
        private const val KEY_CACHE_TIMESTAMP = "cached_weather_timestamp"
        private const val KEY_CACHE_LOCATION = "cached_weather_location_key"
        private const val KEY_LOCATION_CACHE_MAP = "cached_weather_location_map_json"
        private const val KEY_DEVICE_WEATHER = "device_weather_json"
        private const val KEY_DEVICE_WEATHER_TIMESTAMP = "device_weather_timestamp"
        private const val KEY_SAVED_LOCATIONS = "saved_locations_json"
        private const val KEY_ACTIVE_CUSTOM_LOCATION = "active_custom_location_json"
        private const val MAX_CACHED_LOCATIONS = 8
    }
}
